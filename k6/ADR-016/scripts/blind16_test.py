#!/usr/bin/env python3
"""ADR-016 하네스 blind 테스트(구현 미열람 계약 — task 04).

근거 = 측정 명세 `docs/plans/2026-11-08/adr-016-measurement/requirement-spec.md` §1-2~5 · §2 ·
인터페이스 계약(A outcome16 · B model16 · C summarize16) · 설계 ADR-016 설계-2 · 8 · 9 · 앱 PayBy.kt · PaymentStartService.kt.
구현 파일(outcome16 · model16 · summarize16 등)은 읽지 않고 계약에서 사례를 만들었다.

    python3 -I k6/ADR-016/scripts/blind16_test.py [-v]

모듈마다 import를 따로 한다 — 한 모듈의 import 실패는 그 모듈 테스트 클래스의 오류(ERROR)로 드러나고(skip 아님),
다른 모듈 테스트는 그대로 돈다.
"""
import math
import random
import sys
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))  # python3 -I는 스크립트 폴더를 sys.path에 넣지 않는다


def _try_import(name):
    try:
        return __import__(name), None
    except Exception as e:  # noqa: BLE001 — import 실패 원인을 그대로 보고한다
        return None, f"{type(e).__name__}: {e}"


O, O_ERR = _try_import("outcome16")
M, M_ERR = _try_import("model16")
S, S_ERR = _try_import("summarize16")


def _require(mod, err, name):
    if mod is None:
        raise ImportError(f"{name}.py import 실패 — 이 클래스의 사례는 돌지 않았다(skip 아님 · 오류로 센다): {err}")


DEFECT_PREFIXES = ("invalid:", "mode_fail:", "integrity:")


def is_defect(r):
    return isinstance(r, str) and r.startswith(DEFECT_PREFIXES)


# =====================================================================================
# A. outcome16 — classify 결정표 (명세 §1-3 '세션 분류')
# =====================================================================================

def rec(hold=(201, True), pay=None, pay_fail=None, confirm=None, reserved=False):
    """pay = (st, code, err, ok_body) · confirm = (st, code, err) — None이면 그 요청을 안 보냄"""
    r = {"hold": {"st": hold[0], "ok_body": hold[1]}, "pay": None, "pay_fail": pay_fail, "confirm": None, "reserved": reserved}
    if pay is not None:
        r["pay"] = {"st": pay[0], "code": pay[1], "err": pay[2], "ok_body": pay[3]}
    if confirm is not None:
        r["confirm"] = {"st": confirm[0], "code": confirm[1], "err": confirm[2]}
    return r


PAY_OK = (200, None, None, True)
C_OK = (200, None, None)


class TestOutcomeConstants(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome16")

    def test_categories(self):
        self.assertEqual((O.SUCCESS, O.WRONGED, O.PRE_REJECT, O.PAY_FAIL), ("success", "wronged", "pre_reject", "pay_fail"))
        self.assertEqual(set(O.CATEGORIES), {"success", "wronged", "pre_reject", "pay_fail"})

    def test_contract_pairs(self):
        self.assertEqual(set(O.PAY_START_REJECT), {(409, "HOLD_EXPIRED"), (404, "HOLD_NOT_FOUND")})
        self.assertEqual(set(O.CONFIRM_REJECT), {(404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")})
        self.assertEqual(O.K6_TIMEOUT_ERROR, 1050)
        self.assertEqual((O.MODE_COND, O.MODE_EXT), ("conditional", "conditional-extend"))


class TestClassify(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome16")

    def c(self, **kw):
        return O.classify(rec(**kw))

    # ---- 선점 ----
    def test_hold_non201_invalid(self):
        for st in (409, 500, 0):
            r = self.c(hold=(st, False), pay=PAY_OK, confirm=C_OK, reserved=True)
            self.assertTrue(r.startswith("invalid:"), f"선점 {st} → invalid:(hold-fail) 기대 · 실제 {r}")

    def test_hold_body_defect_invalid(self):
        r = self.c(hold=(201, False), confirm=C_OK, reserved=True)
        self.assertTrue(r.startswith("invalid:"), f"선점 201 본문 결함 → invalid: 기대 · 실제 {r}")

    # ---- payment-start ----
    def test_pay_200_then_confirm_success(self):
        self.assertEqual(self.c(pay=PAY_OK, confirm=C_OK, reserved=True), "success")

    def test_pay_409_hold_expired_pre_reject(self):
        self.assertEqual(self.c(pay=(409, "HOLD_EXPIRED", None, False)), "pre_reject")

    def test_pay_404_not_found_pre_reject(self):
        self.assertEqual(self.c(pay=(404, "HOLD_NOT_FOUND", None, False)), "pre_reject")

    def test_pay_403_out_of_contract(self):
        r = self.c(pay=(403, "HOLD_NOT_OWNED", None, False))
        self.assertTrue(is_defect(r), f"payment-start 403(계약 쌍 밖) → 4갈래 밖(결함 사유) 기대 · 실제 {r}")

    def test_pay_409_other_code_out_of_contract(self):
        r = self.c(pay=(409, "SEAT_NOT_HELD", None, False))
        self.assertTrue(is_defect(r), f"payment-start 409 SEAT_NOT_HELD(계약 쌍 밖) → 결함 사유 기대 · 실제 {r}")

    def test_pay_5xx_mode_fail(self):
        for st in (500, 503):
            r = self.c(pay=(st, None, None, False))
            self.assertTrue(r.startswith("mode_fail:"), f"payment-start {st} → mode_fail:(G-F) 기대 · 실제 {r}")

    def test_pay_status0_timeout_mode_fail(self):
        r = self.c(pay=(0, None, 1050, False))
        self.assertTrue(r.startswith("mode_fail:"), f"payment-start status 0 · err 1050(시간 초과) → mode_fail: 기대 · 실제 {r}")

    def test_pay_status0_other_invalid(self):
        for err in (1000, 1211, None):
            r = self.c(pay=(0, None, err, False))
            self.assertTrue(r.startswith("invalid:"), f"payment-start status 0 · err {err}(시간 초과 아님) → invalid:(하네스) 기대 · 실제 {r}")

    def test_pay_200_body_defect(self):
        r = self.c(pay=(200, None, None, False), confirm=C_OK, reserved=True)
        self.assertTrue(is_defect(r), f"payment-start 200 본문 결함 → 결함 사유 기대 · 실제 {r}")

    # ---- 결제 실패 ----
    def test_pay_fail_cap_and_deadline(self):
        for pf in ("cap", "deadline"):
            self.assertEqual(self.c(pay_fail=pf), "pay_fail", f"ⓑ 없음 · pay_fail={pf}")
            self.assertEqual(self.c(pay=PAY_OK, pay_fail=pf), "pay_fail", f"payment-start 200 뒤 pay_fail={pf}")

    def test_pay_fail_but_confirm_sent(self):
        for reserved in (False, True):
            for conf in (C_OK, (409, "HOLD_EXPIRED", None)):
                r = self.c(pay=PAY_OK, pay_fail="deadline", confirm=conf, reserved=reserved)
                self.assertTrue(is_defect(r), f"결제 실패인데 확정 {conf} · 예약 {reserved} → 결함 사유 기대 · 실제 {r}")

    # ---- 확정 · 끝 상태 ----
    def test_confirm_200_reserved_success(self):
        self.assertEqual(self.c(confirm=C_OK, reserved=True), "success")

    def test_confirm_200_not_reserved_integrity(self):
        r = self.c(confirm=C_OK, reserved=False)
        self.assertTrue(r.startswith("integrity:"), f"확정 200인데 예약 없음 → integrity: 기대 · 실제 {r}")

    def test_confirm_reject_not_reserved_wronged(self):
        for st, code in ((404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED"), (409, "SEAT_NOT_HELD")):
            self.assertEqual(self.c(confirm=(st, code, None), reserved=False), "wronged", f"확정 {st} {code} · 예약 없음")
            self.assertEqual(self.c(pay=PAY_OK, confirm=(st, code, None), reserved=False), "wronged", f"ⓑ 200 뒤 확정 {st} {code}")

    def test_confirm_reject_but_reserved_integrity(self):
        for st, code in ((404, "HOLD_NOT_FOUND"), (409, "HOLD_EXPIRED")):
            r = self.c(confirm=(st, code, None), reserved=True)
            self.assertTrue(r.startswith("integrity:"), f"확정 {st} {code}인데 예약 있음 → integrity: 기대 · 실제 {r}")

    def test_confirm_403_out_of_contract(self):
        r = self.c(confirm=(403, "HOLD_NOT_OWNED", None), reserved=False)
        self.assertTrue(is_defect(r), f"확정 403 → 결함 사유 기대 · 실제 {r}")

    def test_confirm_409_other_code_out_of_contract(self):
        r = self.c(confirm=(409, "SEAT_NOT_AVAILABLE", None), reserved=False)
        self.assertTrue(is_defect(r), f"확정 409 SEAT_NOT_AVAILABLE(허용 목록 밖) → 결함 사유 기대 · 실제 {r}")

    def test_confirm_5xx_mode_fail(self):
        r = self.c(confirm=(500, None, None), reserved=False)
        self.assertTrue(r.startswith("mode_fail:"), f"확정 500 → mode_fail: 기대 · 실제 {r}")

    def test_confirm_status0_timeout_mode_fail(self):
        r = self.c(confirm=(0, None, 1050), reserved=False)
        self.assertTrue(r.startswith("mode_fail:"), f"확정 status 0 · 1050 → mode_fail: 기대 · 실제 {r}")

    def test_confirm_status0_other_invalid(self):
        r = self.c(confirm=(0, None, 1000), reserved=False)
        self.assertTrue(r.startswith("invalid:"), f"확정 status 0 · 1000 → invalid: 기대 · 실제 {r}")

    def test_confirm_missing(self):
        for pay in (None, PAY_OK):
            r = self.c(pay=pay, confirm=None, reserved=False)
            self.assertTrue(is_defect(r), f"결제 성공(pay {pay}) · 결제 실패 아님 · 확정 누락 → 결함 사유 기대 · 실제 {r}")

    def test_result_always_known_shape(self):
        """모든 조합의 반환은 4갈래 또는 세 접두사 중 하나(조용한 제3의 값 없음)"""
        holds = [(201, True), (201, False), (409, False)]
        pays = [None, PAY_OK, (200, None, None, False), (409, "HOLD_EXPIRED", None, False), (404, "HOLD_NOT_FOUND", None, False),
                (403, "HOLD_NOT_OWNED", None, False), (500, None, None, False), (0, None, 1050, False), (0, None, 1000, False)]
        pfs = [None, "cap", "deadline"]
        confs = [None, C_OK, (404, "HOLD_NOT_FOUND", None), (409, "HOLD_EXPIRED", None), (409, "SEAT_NOT_HELD", None),
                 (403, "HOLD_NOT_OWNED", None), (500, None, None), (0, None, 1050), (0, None, 1000)]
        for h in holds:
            for p in pays:
                for pf in pfs:
                    for cf in confs:
                        for rs in (False, True):
                            r = self.c(hold=h, pay=p, pay_fail=pf, confirm=cf, reserved=rs)
                            self.assertTrue(r in O.CATEGORIES or is_defect(r), f"{h} {p} {pf} {cf} {rs} → {r!r}")


# =====================================================================================
# A. outcome16 — pay_within_seconds · deadline
# =====================================================================================

class TestPayWithinAndDeadline(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome16")

    def test_floor_integer(self):
        self.assertEqual(O.pay_within_seconds(110.0, 100.0), 10)
        self.assertIsInstance(O.pay_within_seconds(110.0, 100.0), int)

    def test_floor_fraction(self):
        self.assertEqual(O.pay_within_seconds(110.999, 100.0), 10)
        self.assertEqual(O.pay_within_seconds(100.999, 100.0), 0, "0.999초 남음 → 내림 0")

    def test_negative_clamped(self):
        self.assertEqual(O.pay_within_seconds(99.0, 100.0), 0)
        self.assertEqual(O.pay_within_seconds(50.5, 100.0), 0)

    def test_deadline_absolute_wins_when_delay_exceeds_fraction(self):
        # 서버 :now 100 · payBy 110.9 → pws 10(0.9 버림) · 수신 101.5(지연 1.5 > 0.9) → 수신 + pws 111.5 > payBy
        pws = O.pay_within_seconds(110.9, 100.0)
        self.assertEqual(pws, 10)
        self.assertAlmostEqual(O.deadline(101.5, pws, 110.9, 0.0), 110.9, places=9)
        self.assertAlmostEqual(O.deadline(101.5, pws, 110.9, 0.3), 110.6, places=9, msg="err만큼 앞당김")

    def test_deadline_relative_wins_when_delay_small(self):
        pws = O.pay_within_seconds(110.9, 100.0)
        self.assertAlmostEqual(O.deadline(100.2, pws, 110.9, 0.0), 110.2, places=9)
        self.assertAlmostEqual(O.deadline(100.2, pws, 110.9, 0.5), 110.2, places=9)


# =====================================================================================
# A. outcome16 — decide (정확한 서버 시각 · 선점 0 · 만료 ttl)
# =====================================================================================

def dec(**kw):
    base = dict(mode="conditional", b=False, c=False, theta=0.0, window=0.0, margin=0.0, cap=600.0, ttl=300.0,
                think=100.0, pay=10.0, delta=0.0)
    base.update(kw)
    return O.decide(**base)


class TestDecide(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome16")

    # ① θ 경계
    def test_theta_equal_pre_reject(self):
        self.assertEqual(dec(b=True, theta=60.0, think=240.0), "pre_reject", "남은 시간 = θ → 결제 전 거부")

    def test_theta_plus_eps_passes(self):
        self.assertEqual(dec(b=True, theta=60.0, think=239.999, pay=10.0), "success", "남은 시간 θ + ε → 통과")

    def test_no_b_no_theta_check(self):
        self.assertEqual(dec(b=False, theta=60.0, think=240.0, pay=10.0), "success", "ⓑ 없는 ①은 θ를 쓰지 않음")

    def test_remaining_nonpositive_pre_reject(self):
        self.assertEqual(dec(b=True, theta=0.0, think=300.0), "pre_reject", "남은 시간 0 → 거부")
        self.assertEqual(dec(mode="conditional-extend", theta=0.0, window=600.0, think=301.0), "pre_reject")

    # 확정 서버 시각 = 결제 시작 + D + δ · 만료 > 그 시각이면 성공(엄격)
    def test_expiry_strict(self):
        self.assertEqual(dec(think=100.0, pay=190.0, delta=10.0), "wronged", "확정 시각 = 만료 → 억울")
        self.assertEqual(dec(think=100.0, pay=190.0, delta=9.999), "success")

    def test_delta_crosses_expiry_wronged(self):
        self.assertEqual(dec(think=200.0, pay=99.0, delta=2.0), "wronged", "δ가 만료를 넘김")
        self.assertEqual(dec(think=200.0, pay=99.0, delta=0.5), "success")

    def test_cap(self):
        self.assertEqual(dec(think=30.0, pay=601.0, cap=600.0), "pay_fail", "D > CAP")
        self.assertEqual(dec(mode="conditional-extend", theta=5.0, window=700.0, think=30.0, pay=600.0, cap=600.0), "success", "D = CAP은 실패 아님")

    # ④ extend vs not_needed
    def test_ext_extends(self):
        self.assertEqual(dec(mode="conditional-extend", theta=5.0, window=600.0, think=100.0, pay=550.0, delta=1.0), "success",
                         "④ 만료 = 결제 시작 + W(700) > 651")
        self.assertEqual(dec(think=100.0, pay=550.0, delta=1.0), "wronged", "같은 세션 ①은 억울")

    def test_ext_not_needed_keeps_expiry(self):
        # TTL 900 · 결제 시작 100 → 남은 800 ≥ W 600 → 만료 그대로 900(연장했다면 700)
        self.assertEqual(dec(mode="conditional-extend", theta=5.0, window=600.0, ttl=900.0, cap=1000.0, think=100.0, pay=750.0), "success",
                         "not_needed — 만료 900 유지")
        self.assertEqual(dec(mode="conditional-extend", theta=5.0, window=600.0, ttl=900.0, cap=1000.0, think=100.0, pay=850.0), "wronged")

    def test_ext_theta_boundary(self):
        self.assertEqual(dec(mode="conditional-extend", theta=30.0, window=600.0, think=270.0), "pre_reject")
        self.assertEqual(dec(mode="conditional-extend", theta=30.0, window=600.0, think=269.9, pay=10.0), "success")

    def test_ext_b_flag_ignored(self):
        """④는 늘 payment-start — b 값과 무관"""
        for think in (100.0, 270.0):
            a = dec(mode="conditional-extend", b=False, theta=30.0, window=600.0, think=think, pay=400.0)
            b = dec(mode="conditional-extend", b=True, theta=30.0, window=600.0, think=think, pay=400.0)
            self.assertEqual(a, b, f"think {think}")

    def test_w_ge_cap_plus_delta_no_wronged(self):
        cap, delta = 600.0, 5.0
        w = cap + delta + 11.0
        for think in list(range(30, 241, 7)) + [240]:
            for pay in (1.0, 50.0, 200.0, 450.0, 599.9, 600.0):
                r = dec(mode="conditional-extend", theta=5.0, window=w, think=float(think), pay=pay, delta=delta, cap=cap)
                self.assertEqual(r, "success", f"W {w} ≥ CAP + δ · T {think} · D {pay} → {r}")

    # ⓒ 마감
    def test_c_deadline_exceeded_pay_fail_no_b(self):
        # ① ⓑ 없음: 마감 = 선점 201(서버 0) 기준 · payBy = 300 − 10 = 290 · pws 290 → 마감 290
        self.assertEqual(dec(c=True, margin=10.0, think=100.0, pay=190.001), "pay_fail")
        self.assertEqual(dec(c=True, margin=10.0, think=100.0, pay=189.9), "success")
        self.assertEqual(dec(c=False, margin=10.0, think=100.0, pay=190.001), "success", "ⓒ 끔이면 마감 없음")

    def test_c_deadline_source_payment_start(self):
        # ⓑ: payment-start(서버 100.5)가 마감을 준다 · pws = ⌊290 − 100.5⌋ = 189 → 마감 = min(289.5, 290) = 289.5
        self.assertEqual(dec(b=True, c=True, margin=10.0, think=100.5, pay=189.2), "pay_fail", "ⓑ 응답 기준 마감 289.5 < 289.7")
        self.assertEqual(dec(b=False, c=True, margin=10.0, think=100.5, pay=189.2), "success", "선점 201 기준 마감 290 > 289.7")

    def test_c_deadline_ext(self):
        # ④: 만료 = 100 + 600 = 700 · payBy 690 · pws 590 → 마감 690
        self.assertEqual(dec(mode="conditional-extend", c=True, theta=10.0, margin=10.0, window=600.0, think=100.0, pay=590.5), "pay_fail")
        self.assertEqual(dec(mode="conditional-extend", c=True, theta=10.0, margin=10.0, window=600.0, think=100.0, pay=589.5), "success")

    def test_results_in_categories(self):
        rnd = random.Random(7)
        for _ in range(2000):
            r = dec(mode=rnd.choice(["conditional", "conditional-extend"]), b=rnd.random() < 0.5, c=rnd.random() < 0.5,
                    theta=rnd.choice([0.0, 5.0, 60.0, 90.0]), window=rnd.choice([0.0, 300.0, 616.0]), margin=rnd.choice([0.0, 5.0]),
                    think=rnd.uniform(30, 240), pay=45 * math.exp(0.94 * rnd.gauss(0, 1)), delta=rnd.uniform(0, 2))
            self.assertIn(r, O.CATEGORIES)


class TestModelPropertyViaDecide(unittest.TestCase):
    """모형 성질 — θ < 60초(T ≤ 240 · TTL 300)면 ①+ⓑ = ①(ⓑ가 아무도 거부하지 않음)"""

    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome16")

    def test_b_equals_no_b_when_theta_below_60(self):
        for theta in (0.0, 30.0, 59.9):
            for think in list(range(30, 241, 10)) + [240]:
                for pay in (5.0, 45.0, 150.0, 265.0, 700.0):
                    for c in (False, True):
                        a = dec(b=True, c=c, margin=5.0 if c else 0.0, theta=theta, think=float(think), pay=pay, delta=1.0)
                        b = dec(b=False, c=c, margin=5.0 if c else 0.0, theta=theta, think=float(think), pay=pay, delta=1.0)
                        if not c:
                            self.assertEqual(a, b, f"θ {theta} T {think} D {pay}")
                        self.assertNotEqual(a, "pre_reject", f"θ {theta} < 60 · T {think} ≤ 240 → 거부 없음")


# =====================================================================================
# A. outcome16 — predict (관측 시각 · 경계 띠)
# =====================================================================================

def pred(**kw):
    base = dict(mode="conditional", b=False, c=False, theta=0.0, window=0.0, margin=0.0, cap=600.0, e0=300.0, hold_recv=0.05,
                think_end=100.0, pay=50.0, err=0.05)
    base.update(kw)
    return O.predict(**base)


class TestPredict(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(O, O_ERR, "outcome16")

    def test_theta_band_in(self):
        # 남은 시간 구간 [300 − 240.1 − 0.05, 300 − 239.9 + 0.05] = [59.85, 60.15] ∋ θ 60
        cat, band = pred(b=True, theta=60.0, think_end=239.9, ps_send=239.9, ps_recv=240.1, pay=10.0, c_send=250.2, c_recv=250.3)
        self.assertTrue(band, f"θ 근처 → 띠 안 · 실제 ({cat}, {band})")
        self.assertIn(cat, O.CATEGORIES)

    def test_theta_band_out_pass(self):
        cat, band = pred(b=True, theta=60.0, think_end=200.0, ps_send=200.0, ps_recv=200.1, pay=50.0, c_send=250.2, c_recv=250.3)
        self.assertEqual((cat, band), ("success", False))

    def test_theta_band_out_reject(self):
        cat, band = pred(b=True, theta=60.0, think_end=250.0, ps_send=250.0, ps_recv=250.1)
        self.assertEqual((cat, band), ("pre_reject", False))

    def test_theta_band_contains_zero(self):
        cat, band = pred(b=True, theta=0.0, think_end=299.98, ps_send=299.98, ps_recv=300.02, pay=1.0, c_send=301.1, c_recv=301.2)
        self.assertTrue(band, f"남은 시간 구간이 0을 품음 → 띠 안 · 실제 ({cat}, {band})")

    def test_window_band_ext(self):
        # ④ · e0 900 · 남은 시간 [599.9, 600.1] ∋ W 600
        cat, band = pred(mode="conditional-extend", theta=5.0, window=600.0, cap=1000.0, e0=900.0, think_end=299.95, ps_send=299.95,
                         ps_recv=300.05, e_after=900.0, pay=10.0, c_send=310.1, c_recv=310.2)
        self.assertTrue(band, f"④ 남은 시간 ≈ W → 띠 안 · 실제 ({cat}, {band})")

    def test_ext_out_of_band_success(self):
        cat, band = pred(mode="conditional-extend", theta=5.0, window=600.0, think_end=100.0, ps_send=100.0, ps_recv=100.1,
                         e_after=700.05, pay=300.0, c_send=400.2, c_recv=400.3)
        self.assertEqual((cat, band), ("success", False))

    def test_ext_extended_expiry_wronged(self):
        cat, band = pred(mode="conditional-extend", theta=5.0, window=600.0, think_end=100.0, ps_send=100.0, ps_recv=100.1,
                         e_after=700.05, pay=590.0, c_send=710.0, c_recv=710.1)
        self.assertEqual((cat, band), ("wronged", False))

    def test_confirm_band_in(self):
        cat, band = pred(think_end=100.0, pay=199.95, c_send=299.95, c_recv=300.05, err=0.01)
        self.assertTrue(band, f"확정 띠 [299.94, 300.06] ∋ 만료 300 → 띠 안 · 실제 ({cat}, {band})")
        self.assertIn(cat, ("success", "wronged"))

    def test_confirm_band_out_wronged(self):
        self.assertEqual(pred(think_end=100.0, pay=210.0, c_send=310.0, c_recv=310.1), ("wronged", False))

    def test_confirm_band_out_success(self):
        self.assertEqual(pred(think_end=100.0, pay=100.0, c_send=200.0, c_recv=200.1), ("success", False))

    def test_cap_pay_fail(self):
        cat, band = pred(think_end=30.0, pay=601.0, cap=600.0)
        self.assertEqual((cat, band), ("pay_fail", False))

    def test_c_deadline_pay_fail(self):
        # ① ⓑ 없음 ⓒ: payBy 290 · pws 289 · 수신 0.5 → 마감 min(289.5, 289.99) = 289.5 · 결제 끝 ≈ 295
        cat, band = pred(c=True, margin=10.0, hold_recv=0.5, think_end=100.0, pay=195.0, err=0.01, pws=289, pay_by=290.0, src_recv=0.5)
        self.assertEqual((cat, band), ("pay_fail", False))


# =====================================================================================
# B. model16 — choose
# =====================================================================================

def _phi_sf(z):
    return 0.5 * math.erfc(z / math.sqrt(2))


def base_rate_reject_fail(theta, ttl=300.0, t_lo=30.0, t_hi=240.0, m=45.0, sigma=0.94, cap=600.0):
    """기준 분포(T ~ U[30,240] · D ~ 로그정규 m 45 σ 0.94 · CAP 10분) ①+ⓑ(θ)의 결제 전 거부 + 결제 실패 비율(해석식)"""
    t_cut = ttl - theta  # T ≥ t_cut면 남은 시간 ≤ θ → 거부
    p_pre = min(1.0, max(0.0, (t_hi - t_cut) / (t_hi - t_lo)))
    p_fail = _phi_sf(math.log(cap / m) / sigma)
    return p_pre + (1 - p_pre) * p_fail


class TestModelChoose(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(M, M_ERR, "model16")
        cls.deltas = [i * 2.5 / 999 for i in range(1000)]  # p99.9 ≈ 2.5초
        cls.err = 0.2
        cls.slack = 5.0
        cls.r = M.choose(deltas=cls.deltas, err=cls.err, margin_slack=cls.slack, n=20000, seed=3)

    def test_keys(self):
        for k in ("M", "W", "theta_ext", "theta_cond"):
            self.assertIn(k, self.r)

    def test_m_covers_p999_rounded_up(self):
        mval = self.r["M"]
        self.assertIsNotNone(mval)
        self.assertEqual(float(mval), math.ceil(mval), f"M은 초 단위 올림 · 실제 {mval}")
        self.assertEqual(mval, 3, f"δ̂ p99.9 ≈ 2.5 + err 0.2 = 2.7 → 3초 기대 · 실제 {mval}")

    def test_w_formula(self):
        self.assertAlmostEqual(self.r["W"], 600 + self.r["M"] + 10 + self.slack, places=9, msg=f"W = 600 + M + 10 + slack · 실제 {self.r}")

    def test_theta_ext_is_m(self):
        self.assertEqual(self.r["theta_ext"], self.r["M"])

    def test_startup_constraint(self):
        mval, w = self.r["M"], self.r["W"]
        for th in (self.r["theta_ext"], self.r["theta_cond"]):
            self.assertTrue(mval <= th < min(300, w), f"M ≤ θ < min(TTL, W) — M {mval} θ {th} W {w}")

    def test_theta_cond_within_5pct(self):
        th = self.r["theta_cond"]
        rate = base_rate_reject_fail(th)
        self.assertLessEqual(rate, 0.05 + 0.004, f"θ_cond {th} → 거부 + 실패 {rate:.4f}(해석식 · 표본 오차 0.4%p 허용)")

    def test_no_deltas_m_none(self):
        for d in (None, []):
            r = M.choose(deltas=d, err=self.err, margin_slack=self.slack, n=2000, seed=3)
            self.assertIsNone(r["M"], f"δ 없음({d!r}) → M None(0으로 채우지 않음) · 실제 {r.get('M')}")

    def test_deterministic_seed(self):
        r2 = M.choose(deltas=self.deltas, err=self.err, margin_slack=self.slack, n=20000, seed=3)
        self.assertEqual({k: r2[k] for k in ("M", "W", "theta_ext", "theta_cond")},
                         {k: self.r[k] for k in ("M", "W", "theta_ext", "theta_cond")})


# =====================================================================================
# C. summarize16 — 순수 함수
# =====================================================================================

def _binom_cdf(k, n, p):
    if p <= 0:
        return 1.0
    if p >= 1:
        return 0.0 if k < n else 1.0
    s = 0.0
    for i in range(k + 1):
        s += math.exp(math.lgamma(n + 1) - math.lgamma(i + 1) - math.lgamma(n - i + 1) + i * math.log(p) + (n - i) * math.log1p(-p))
    return s


def ref_cp_upper(k, n, conf=0.95):
    if k >= n:
        return 1.0
    lo, hi = 0.0, 1.0
    for _ in range(200):
        mid = (lo + hi) / 2
        if _binom_cdf(k, n, mid) > 1 - conf:
            lo = mid
        else:
            hi = mid
    return (lo + hi) / 2


class TestCpUpper(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_zero_3000(self):
        self.assertAlmostEqual(S.cp_upper(0, 3000), 1 - 0.05 ** (1 / 3000), places=9)
        self.assertAlmostEqual(S.cp_upper(0, 3000), 0.000998, places=6)

    def test_zero_3300_below(self):
        self.assertLess(S.cp_upper(0, 3300), 0.001)

    def test_one_3300_above(self):
        self.assertGreater(S.cp_upper(1, 3300), 0.001)

    def test_reference_values(self):
        for k, n in ((1, 100), (3, 50), (1, 3300), (5, 1000)):
            self.assertAlmostEqual(S.cp_upper(k, n), ref_cp_upper(k, n), delta=1e-5, msg=f"({k}, {n})")

    def test_conf_param(self):
        self.assertAlmostEqual(S.cp_upper(0, 100, conf=0.99), 1 - 0.01 ** (1 / 100), places=9)

    def test_n_nonpositive_none(self):
        self.assertIsNone(S.cp_upper(0, 0))
        self.assertIsNone(S.cp_upper(0, -1))

    def test_monotone_in_k(self):
        vals = [S.cp_upper(k, 200) for k in range(0, 10)]
        self.assertEqual(vals, sorted(vals))


class TestGTarget(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_wronged_boundary(self):
        self.assertTrue(S.g_target(0, 3300, 0, 0))
        self.assertTrue(S.g_target(0, 3000, 0, 0), "상한 0.000998 ≤ 0.001")
        self.assertFalse(S.g_target(0, 2900, 0, 0), "상한 ≈ 0.00103 > 0.001")
        self.assertFalse(S.g_target(1, 3300, 0, 0))

    def test_reject_fail_boundary(self):
        self.assertTrue(S.g_target(0, 3300, 165, 0), "165/3300 = 5% 정확히 → 통과(≤)")
        self.assertTrue(S.g_target(0, 3300, 100, 65))
        self.assertFalse(S.g_target(0, 3300, 166, 0))
        self.assertFalse(S.g_target(0, 3300, 100, 66), "거부 + 실패 합으로 본다")

    def test_n_zero_false(self):
        self.assertFalse(S.g_target(0, 0, 0, 0))


class TestGModel(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_all_match(self):
        ok, lst = S.g_model([("success", False, "success"), ("wronged", True, "wronged"), ("pre_reject", False, "pre_reject"),
                             ("pay_fail", False, "pay_fail")])
        self.assertTrue(ok, lst)
        self.assertIsInstance(lst, list)

    def test_out_of_band_mismatch_fails(self):
        ok, lst = S.g_model([("success", False, "success"), ("success", False, "wronged")])
        self.assertFalse(ok)
        self.assertTrue(lst, "불일치가 목록에 있어야 함")

    def test_in_band_mismatch_allowed(self):
        ok, lst = S.g_model([("success", True, "wronged"), ("pre_reject", True, "success"), ("success", False, "success")])
        self.assertTrue(ok, lst)
        self.assertTrue(any("허용" in str(x) for x in lst), f"띠 안 불일치는 '허용' 표기 · 실제 {lst}")

    def test_non_category_observed(self):
        for obs in ("invalid:hold-fail", "mode_fail:pay-5xx", "integrity:confirm-200-no-reservation"):
            ok, lst = S.g_model([("success", False, "success"), ("success", True, obs)])
            self.assertFalse(ok, f"관측 {obs}(4갈래 밖) → False")
            self.assertTrue(lst, f"관측 {obs} — 무시하지 말고 사유로 돌려줘야 함")


class TestN16_5(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_bounds(self):
        k6 = {"rejected:HOLD_EXPIRED": 10}
        self.assertTrue(S.n16_5(k6, 2, {"rejected:HOLD_EXPIRED": 10})[0], "서버 = k6(하한)")
        self.assertTrue(S.n16_5(k6, 2, {"rejected:HOLD_EXPIRED": 12})[0], "서버 = k6 + status0(상한)")
        self.assertFalse(S.n16_5(k6, 2, {"rejected:HOLD_EXPIRED": 13})[0], "상한 초과")
        self.assertFalse(S.n16_5(k6, 2, {"rejected:HOLD_EXPIRED": 9})[0], "하한 미달")

    def test_per_key(self):
        k6 = {"rejected:HOLD_EXPIRED": 5, "failed:500": 1}
        self.assertTrue(S.n16_5(k6, 0, {"rejected:HOLD_EXPIRED": 5, "failed:500": 1})[0])
        self.assertFalse(S.n16_5(k6, 0, {"rejected:HOLD_EXPIRED": 6, "failed:500": 0})[0], "합은 같아도 키별로 어긋남")

    def test_key_only_server(self):
        self.assertFalse(S.n16_5({}, 0, {"failed:500": 1})[0], "서버에만 1 · k6 0 + status0 0")
        self.assertTrue(S.n16_5({}, 1, {"failed:500": 1})[0], "0 ≤ 1 ≤ 0 + 1")

    def test_key_only_k6(self):
        self.assertFalse(S.n16_5({"rejected:HOLD_NOT_FOUND": 1}, 0, {})[0], "k6에만 1 · 서버 0 < 1")
        self.assertFalse(S.n16_5({"rejected:HOLD_NOT_FOUND": 1}, 5, {})[0])

    def test_server_none(self):
        ok, lst = S.n16_5({"rejected:HOLD_EXPIRED": 1}, 0, {"rejected:HOLD_EXPIRED": None})
        self.assertFalse(ok, "서버 값 None = 결측 → False")
        self.assertIsInstance(lst, list)


class TestBoundaryOk(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_pass(self):
        ok, cnt = S.boundary_ok([(True, "success")] * 5 + [(False, "pay_fail")] * 3)
        self.assertTrue(ok)
        self.assertIsInstance(cnt, dict)

    def test_wronged_before_deadline_fails(self):
        ok, _ = S.boundary_ok([(True, "success")] * 50 + [(True, "wronged")])
        self.assertFalse(ok)

    def test_wronged_after_deadline_is_control_only(self):
        ok, _ = S.boundary_ok([(True, "success")] * 5 + [(False, "wronged")] * 2)
        self.assertTrue(ok, "마감 뒤 송신의 억울은 대조용 계수 — 판정 밖")

    def test_no_before_deadline_no_power(self):
        self.assertFalse(S.boundary_ok([(False, "pay_fail")] * 10)[0], "마감 전 송신 0건 → 검출력 없음 → False")
        self.assertFalse(S.boundary_ok([])[0])


class TestFlowCount(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_equal(self):
        ok, _ = S.flow_count_ok({"A": 3, "B": 2}, {"A": {"success": 2, "wronged": 1}, "B": {"pre_reject": 1, "pay_fail": 1}})
        self.assertTrue(ok)

    def test_defect_reasons_count(self):
        ok, _ = S.flow_count_ok({"A": 3}, {"A": {"success": 2, "invalid:hold-fail": 1}})
        self.assertTrue(ok, "결함 사유도 분류된 세션 수에 들어간다")

    def test_shortfall(self):
        ok, lst = S.flow_count_ok({"A": 3, "B": 2}, {"A": {"success": 2}, "B": {"success": 2}})
        self.assertFalse(ok)
        self.assertTrue(lst)

    def test_excess(self):
        self.assertFalse(S.flow_count_ok({"A": 3}, {"A": {"success": 4}})[0])

    def test_missing_or_extra_flow(self):
        self.assertFalse(S.flow_count_ok({"A": 3, "B": 2}, {"A": {"success": 3}})[0], "B 흐름 분류 0")
        self.assertFalse(S.flow_count_ok({"A": 3}, {"A": {"success": 3}, "Z": {"success": 1}})[0], "계획에 없는 흐름")


class TestEarlyReject(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_counts(self):
        rows = [
            (300.0, 199.9, 200.0, 0.1, 409, "HOLD_EXPIRED"),   # 남은 99.9 > 60 · 409 → 1
            (300.0, 199.9, 200.0, 0.1, 200, None),             # 200 → 0
            (300.0, 199.9, 200.0, 0.1, 404, "HOLD_NOT_FOUND"),  # 404는 대상 아님 → 0
            (300.0, 239.4, 239.5, 0.5, 409, "HOLD_EXPIRED"),   # 남은 60.0 = θ(> 아님) → 0
            (300.0, 245.0, 245.1, 0.1, 409, "HOLD_EXPIRED"),   # 남은 54.8 < θ → 0
            (300.0, 100.0, 100.2, 0.0, 409, "HOLD_EXPIRED"),   # → 1
        ]
        self.assertEqual(S.early_reject(rows, 60.0), 2)

    def test_uses_ps_recv_upper_bound(self):
        # 송신 기준이면 300 − 239 − 0 = 61 > 60이지만 수신 기준 300 − 240.5 = 59.5 → 잘못된 거부 아님
        self.assertEqual(S.early_reject([(300.0, 239.0, 240.5, 0.0, 409, "HOLD_EXPIRED")], 60.0), 0)

    def test_empty(self):
        self.assertEqual(S.early_reject([], 60.0), 0)


def cand(gc=True, gm=True, gt=True, cost=None, bok=None):
    d = {"g_consistency": gc, "g_model": gm, "g_target": gt, "cost_report": list(cost or [])}
    if bok is not None:
        d["boundary_ok"] = bok
    return d


def all_pass():
    return {"cond": cand(gt=False), "cond+c": cand(bok=True), "cond+b+c": cand(bok=True), "cond+b": cand(),
            "ext+c": cand(bok=True), "ext": cand()}


class TestDecideCampaign(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        _require(S, S_ERR, "summarize16")

    def test_order_constant(self):
        self.assertEqual(list(S.ORDER), ["cond+c", "cond+b+c", "cond+b", "ext+c", "ext"])

    def test_first_pass_picked(self):
        r = S.decide_campaign(all_pass())
        self.assertEqual((r["pick"], r["status"]), ("cond+c", "pick"))

    def test_c_needs_boundary_ok(self):
        cs = all_pass()
        cs["cond+c"]["boundary_ok"] = False
        r = S.decide_campaign(cs)
        self.assertEqual((r["pick"], r["status"]), ("cond+b+c", "pick"))

    def test_c_needs_pair_g_model(self):
        cs = all_pass()
        cs["cond"]["g_model"] = False  # cond+c의 짝(ⓒ 끔)
        r = S.decide_campaign(cs)
        self.assertEqual((r["pick"], r["status"]), ("cond+b+c", "pick"), "짝 cond의 G-모형 실패 → cond+c 탈락")
        cs = all_pass()
        cs["cond"]["g_model"] = False
        cs["cond+b"]["g_model"] = False  # cond+b+c의 짝 · cond+b 자신도 실패
        r = S.decide_campaign(cs)
        self.assertEqual((r["pick"], r["status"]), ("ext+c", "pick"))

    def test_c_own_gate_fail(self):
        cs = all_pass()
        cs["cond+c"]["g_target"] = False
        cs["cond+b+c"]["g_consistency"] = False
        self.assertEqual(S.decide_campaign(cs)["pick"], "cond+b")

    def test_front_undetermined(self):
        for gate in ("g_consistency", "g_model", "g_target"):
            cs = all_pass()
            cs["cond+c"][gate] = None
            r = S.decide_campaign(cs)
            self.assertEqual(r["status"], "undetermined", f"앞 후보 {gate} None → undetermined · 실제 {r}")

    def test_undetermined_after_pick_ignored(self):
        cs = all_pass()
        cs["cond+c"]["g_target"] = False
        cs["cond+b+c"]["g_target"] = False
        cs["ext"]["g_target"] = None
        r = S.decide_campaign(cs)
        self.assertEqual((r["pick"], r["status"]), ("cond+b", "pick"))

    def test_none(self):
        cs = {k: cand(gt=False, bok=True if k.endswith("+c") else None) for k in ["cond", "cond+c", "cond+b+c", "cond+b", "ext+c", "ext"]}
        r = S.decide_campaign(cs)
        self.assertEqual((r["pick"], r["status"]), (None, "none"))

    def test_no_pass_with_undetermined(self):
        cs = {k: cand(gt=False, bok=True if k.endswith("+c") else None) for k in ["cond", "cond+c", "cond+b+c", "cond+b", "ext+c", "ext"]}
        cs["ext"]["g_target"] = None
        r = S.decide_campaign(cs)
        self.assertEqual(r["status"], "undetermined")
        self.assertIsNone(r["pick"])

    def test_cost_report_carried(self):
        cs = all_pass()
        cs["cond+c"]["cost_report"] = ["db_cpu", "hold_rows"]
        r = S.decide_campaign(cs)
        self.assertEqual(r["pick"], "cond+c")
        self.assertTrue("db_cpu" in str(r["report"]) and "hold_rows" in str(r["report"]), f"cost_report → report · 실제 {r['report']}")


if __name__ == "__main__":
    unittest.main(verbosity=2)
