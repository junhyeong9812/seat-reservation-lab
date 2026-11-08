#!/usr/bin/env python3
"""ADR-016 세션 결과 — 단일 정의(측정 명세 M16-1 · §1-3 결정표). 모형(model16) · 요약기(summarize16) · selftest16 · k6 시나리오의 마감 계산이 모두 이 파일을 따른다.

- classify(rec)  = 관측 분류: 세션 응답열 + 끝 상태 DB(그 paymentUid의 CONFIRMED 예약 유무) → 4갈래 또는 결함 사유.
- decide(...)    = 정확한 서버 시각을 아는 세션의 예상 분류(모형 몬테카를로가 쓴다).
- predict(...)   = 관측 시각(송신 · 수신 · 시계 오차 상한)으로 본 예상 분류 + 경계 띠 안인가(G-모형 세션 대조).
- deadline(...)  = ⓒ 하네스 결제 마감(DV16-R1) — k6 시나리오도 같은 식을 쓴다.

시각은 모두 서버 시계 기준 초(float). k6 PC 시각은 회차의 clock offset으로 옮긴 뒤 넣는다 · err = 시계 오차 상한(초).
분류(설계-2 · 9.1 · 명세 결정표):
  success    결제 성공 · 확정 200 · 예약 있음
  wronged    억울 — 결제 성공 · 예약 없음(확정 404 HOLD_NOT_FOUND · 409 HOLD_EXPIRED · 409 SEAT_NOT_HELD)
  pre_reject 결제 전 거부 — payment-start 계약 거부(409 HOLD_EXPIRED · 404 HOLD_NOT_FOUND) · 돈이 빠지지 않음
  pay_fail   결제 실패 — D > CAP 또는 ⓒ 마감 초과 · 확정을 보내지 않음 · 돈이 빠지지 않음
그 밖은 'invalid:<사유>'(하네스 무효) · 'mode_fail:<사유>'(방식 실패 — ADR-015 G-F: 시간 초과 · 5xx · 계약 밖) · 'integrity:<사유>'(응답 ↔ 끝 상태 어긋남 — G-정합 실패)."""
import math

SUCCESS, WRONGED, PRE_REJECT, PAY_FAIL = "success", "wronged", "pre_reject", "pay_fail"
CATEGORIES = (SUCCESS, WRONGED, PRE_REJECT, PAY_FAIL)
PAID = "paid"  # predict 전용 — 결제 성공 예상 · 확정 시각 없음(관측 4갈래와 늘 다르다)

# payment-start 결제 전 거부로 받는 계약 쌍(① check · ④ too_late · 만료 · 없음). 403 HOLD_NOT_OWNED는 세션 자신의 홀드라 생기지 않는다 → 결함
PAY_START_REJECT = frozenset({(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")})
# 확정 거부 허용 목록(억울 쪽 — 설계 문제 절 · 9.1). 403은 결함
CONFIRM_REJECT = frozenset({(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")})
K6_TIMEOUT_ERROR = 1050  # k6 error_code: request timeout — ADR-015 규칙상 방식 실패(G-F) · 그 밖 status 0은 하네스 무효

MODE_COND, MODE_EXT = "conditional", "conditional-extend"


def _status0(stage, err):
    return f"mode_fail:{stage}-timeout" if err == K6_TIMEOUT_ERROR else f"invalid:{stage}-code0"


def classify(rec):
    """rec: dict — hold{st, ok_body} · pay{st, code, err, ok_body} 또는 None(호출 안 함) · pay_fail('cap' | 'deadline' | None) ·
    confirm{st, code, err} 또는 None · reserved(bool — 끝 상태에 그 paymentUid의 CONFIRMED 예약이 있나)."""
    hold = rec.get("hold") or {}
    if hold.get("st") != 201 or not hold.get("ok_body", True):
        return "invalid:hold-fail"
    reserved = bool(rec.get("reserved"))
    pay = rec.get("pay")
    if pay is not None:
        st, code = pay.get("st"), pay.get("code")
        if st == 0:
            return _status0("pay", pay.get("err"))
        if st >= 500:
            return "mode_fail:pay-5xx"
        if st == 200:
            if not pay.get("ok_body", True):
                return "mode_fail:pay-body-bad"
        elif (st, code) in PAY_START_REJECT:
            if rec.get("confirm") is not None:
                return "invalid:confirm-after-pre-reject"
            return "integrity:pre-reject-with-reservation" if reserved else PRE_REJECT
        else:
            return f"mode_fail:pay-noncontract-{st}-{code}"
    if rec.get("pay_fail"):
        if rec.get("confirm") is not None:
            return "invalid:confirm-after-pay-fail"
        return "integrity:pay-fail-with-reservation" if reserved else PAY_FAIL
    c = rec.get("confirm")
    if c is None:
        return "invalid:confirm-missing"
    st, code = c.get("st"), c.get("code")
    if st == 0:
        return _status0("confirm", c.get("err"))
    if st >= 500:
        return "mode_fail:confirm-5xx"
    if st == 200:
        return SUCCESS if reserved else "integrity:200-without-reservation"
    if (st, code) in CONFIRM_REJECT:
        return "integrity:reject-with-reservation" if reserved else WRONGED
    return f"mode_fail:confirm-noncontract-{st}-{code}"


def pay_within_seconds(pay_by, t_resp):
    """서버 계약(PayBy.kt): max(0, ⌊(payBy − 응답 시각)/1초⌋)."""
    return max(0, math.floor(pay_by - t_resp))


def deadline(src_recv, pws, pay_by, err):
    """ⓒ 하네스 결제 마감(DV16-R1): min(마감을 준 응답의 수신 시각 + payWithinSeconds, 절대 payBy − 시계 오차 상한).
    수신 + 상대값만 쓰면 전송 지연이 내림으로 버린 소수 초보다 클 때 서버 payBy를 넘는다."""
    return min(src_recv + pws, pay_by - err)


def _pay_start_branch(mode, remaining, theta, window):
    """payment-start 판정(남은 시간 = 만료 − 서버 판정 시각). 반환: 'reject' | 'ok'(①) | 'not_needed' | 'extend'(④)."""
    if remaining <= 0 or remaining <= theta:
        return "reject"
    if mode == MODE_COND:
        return "ok"
    return "not_needed" if remaining >= window else "extend"


def decide(*, mode, b, c, theta, window, margin, cap, ttl, think, pay, delta, err=0.0, net=0.0):
    """정확한 서버 시각을 아는 세션(모형 몬테카를로). 선점 = 서버 시각 0 · 만료 e0 = ttl.
    think = 선점 → 결제 시작(T) · pay = 결제 시간(D) · delta = 확정 송신 → 서버 :now(δ) · net = 응답 전송 지연(ⓒ 마감 계산) · err = 시계 오차 상한.
    mode = conditional(b: payment-start를 부르나) | conditional-extend(늘 부른다 — ⓐ + ⓑ). c = ⓒ 켬. 반환: 4갈래 하나."""
    e = ttl
    t_p = think
    called = b or mode == MODE_EXT
    src_resp = 0.0  # 마감을 준 응답의 서버 생성 시각(① ⓑ 없음 = 선점 201)
    if called:
        br = _pay_start_branch(mode, e - t_p, theta, window)
        if br == "reject":
            return PRE_REJECT
        if br == "extend":
            e = t_p + window
        src_resp = t_p
    if pay > cap:
        return PAY_FAIL
    if c:
        pay_by = e - margin
        dl = deadline(src_resp + net, pay_within_seconds(pay_by, src_resp), pay_by, err)
        if t_p + pay > dl:
            return PAY_FAIL
    now = t_p + pay + delta
    return SUCCESS if e > now else WRONGED


def predict(*, mode, b, c, theta, window, margin, cap, e0, hold_recv, think_end, ps_send=None, ps_recv=None,
            e_after=None, pay, c_send=None, c_recv=None, err, pws=None, pay_by=None, src_recv=None):
    """관측 시각으로 본 예상 분류(G-모형 세션 대조 · 명세 δ̂ 절). 반환 (분류, 띠 안인가).
    서버 판정 시각은 [송신, 수신] 어딘가이고 시계 오차 ±err가 붙는다 — 그 구간이 문턱을 품으면 '띠 안'(불일치 허용).
    - payment-start θ 띠: 남은 시간 ∈ [e0 − ps_recv − err, e0 − ps_send + err]가 θ(또는 ④ window)를 품나.
    - 확정 띠: 만료 e ∈ [c_send − err, c_recv + err]인가.
    e_after = payment-start 200 응답의 expiresAt — **예측 입력으로 쓰지 않는다**(리뷰 L3-1 — 응답 값을 예측에 쓰면 순환 검증). ④ 연장 만료는 정책식 구간
      [ps_send + W, ps_recv + W](± err)로 예측하고, 응답 값이 그 구간 밖인지는 요약기가 따로 본다(extend_value_mismatch). 확정 띠는 그 구간의 끝점까지 넓힌다.
    pws · pay_by · src_recv = ⓒ 마감을 준 응답의 값(하네스가 기록) — 없으면(예: 서버가 결제 전 거부해 마감 응답이 없다) 마감 단계를 건너뛰고 PAID(리뷰 L3-2)."""
    band = False
    e_lo = e_hi = e0
    t_p = think_end
    called = b or mode == MODE_EXT
    if called:
        lo, hi = e0 - ps_recv - err, e0 - ps_send + err
        mid = e0 - (ps_send + ps_recv) / 2
        if lo <= theta <= hi or lo <= 0 <= hi or (mode == MODE_EXT and lo <= window <= hi):
            band = True
        br = _pay_start_branch(mode, mid, theta, window)
        if br == "reject":
            return PRE_REJECT, band
        if br == "extend":   # 서버 :now ∈ [송신, 수신] ± err → 연장 만료 ∈ [ps_send + W − err, ps_recv + W + err]
            e_lo, e_hi = ps_send + window - err, ps_recv + window + err
        t_p = ps_recv
    if pay > cap:
        return PAY_FAIL, band
    if c:
        if src_recv is None or pws is None or pay_by is None:
            return PAID, band   # 마감 입력이 없다 — 하네스가 확정을 보냈을 리 없다(관측과 늘 어긋나 θ 띠만 허용)
        dl = deadline(src_recv, pws, pay_by, err)
        if t_p + pay > dl:
            return PAY_FAIL, band
    if c_send is None:
        # 결제 성공을 예상했는데 관측 세션은 확정을 보내지 않았다(결제 전 거부 · 결제 실패로 끝남) — 4갈래 밖 'paid'를 돌려
        # 관측과 반드시 어긋나게 한다(앞의 θ 띠만 허용). '띠 안'으로 돌리면 잘못된 결제 전 거부 · 마감 오판을 G-모형이 넘긴다
        return PAID, band
    if e_hi >= c_send - err and e_lo <= c_recv + err:   # 만료 구간과 서버 확정 판정 구간이 겹치면 띠 안
        band = True
    now = (c_send + c_recv) / 2
    return (SUCCESS if (e_lo + e_hi) / 2 > now else WRONGED), band
