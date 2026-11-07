# 요구사항 명세서 — ADR-016 2단계: ① payment-start · 결제 마감 시각 · 확정 거부 지표 (앱 코드)

> 작성일: 2026-11-07 · 작업 폴더: `docs/plans/2026-11-07/adr-016-payment-start/`
> 설계 정본 = `docs/adr/ADR-016-paid-seat-loss.md` 설계-3 · 4 · 4b · **설계-9(우선)**. 1단계(EOF 하네스) = `docs/plans/2026-11-07/adr-016-paid-seat-loss/`.
> 범위 = **앱 코드 + ADR-015 하네스 키 맞춤**만. `k6/ADR-016/` 하네스 · 모형 스크립트 · 측정은 다음 명세(측정 직전 — 측정 전 고정 규칙과 함께).

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-11-07): "응 구현 명세로 진행해줘" — 설계 리뷰 루프 1 마감 보고 뒤.
- Q/A(2026-11-07):
  - 범위 → **앱 코드 먼저**(하네스 · 모형은 다음 명세).
  - θ · M 기본값 → **θ = 0 · M = 0 · 기동 검사 `M ≤ θ`**(설계-9.3 `M < θ`를 고친다 — 200은 남은 시간 > θ일 때만 나가므로 `M ≤ θ`면 `payBy > :now`가 보장된다. `M < θ`면 기본 0 · 0으로 기동이 안 된다). 값은 측정 뒤 별도 커밋(ADR-015 D-6 선례).
  - stakes → **높음** · 듀얼 리뷰 루프(≤3) + blind 테스트 워커.
  - 자율성 → **auto**.
- 앞서 정해진 것(설계-9 · 사용자 재확인 2026-11-07): 거부 코드 HOLD_EXPIRED 유지 · payment-start 호출은 권고(확정이 검사하지 않음) · 시간 부족 · 만료 구분은 서버 지표로만.

## 1. 목표·대상 (필수)

`main-app`에서 다음을 구현한다(설계-9가 위 절보다 우선):

1. **θ 손잡이 이름 변경**: `seat.confirm.extend-min-remaining` → `seat.confirm.payment-min-remaining`(θ · Duration · 기본 0). ①의 확인 문턱과 ④의 too_late 문턱이 같은 키를 쓴다. **옛 키가 Environment 어느 출처에든 있으면 기동 거부**(키 이름 · 새 키를 메시지에).
2. **새 손잡이** `seat.confirm.pay-by-margin`(M · Duration · 기본 0).
3. **① payment-start**: `POST /api/holds/{holdId}/payment-start`를 `conditional`에도 등록한다. ① 갈래는 E1을 거치지 않는 별도 읽기 전용 함수(`@Transactional(readOnly = true)`) — `SELECT user_id, expires_at FROM seat_hold WHERE id = ?`(잠금 없음). 판정(위에서부터 첫 일치 · `:now` = 앱 Clock · 요청 시작):
   - 없음 → 404 `HOLD_NOT_FOUND` (`result=not_found`)
   - 남의 것 → 403 `HOLD_NOT_OWNED` (`result=not_owned`)
   - `expires_at ≤ :now` → 409 `HOLD_EXPIRED` (`result=expired`)
   - `expires_at − :now ≤ θ` → 409 `HOLD_EXPIRED` (`result=insufficient`)
   - 그 밖 → 200 (`result=ok`)
   - 지표 `seat.confirm.pay_start{result}`. 200은 커밋 뒤 · 거부는 그 자리에서 센다(④ `seat.confirm.extend` 선례).
4. **응답 필드**:
   - payment-start 200 = `{holdId, expiresAt, payBy, payWithinSeconds, extended}`. ①은 `extended = false`. ④는 `extended`(연장함) · `already`(이미 연장)면 true, `not_needed`면 false.
   - 선점 201 = `{holdId, seatId, expiresAt}` + ① · ④ 모드에서만 `payBy, payWithinSeconds`. `current` · ② · ③에서는 두 키가 JSON에 없다.
   - `payBy = expiresAt − M`. `payWithinSeconds = max(0, ⌊(payBy − now) / 1초⌋)`, now = 응답을 만들 때의 앱 Clock(결정 시각보다 늦어 작게 나오는 쪽 — 보수적).
5. **확정 지표**(모든 모드 · 확정 엔드포인트 `POST /api/holds/{holdId}/confirm`만):
   - `seat.confirm.rejected{code}` = 도메인 거부 응답(SeatReservationException → 404 · 403 · 409) 수 · code별 · 커밋/롤백 무관 · 응답 기준.
   - `seat.confirm.failed{status}` = 그 밖의 비2xx 응답(400 · 415 · 5xx · 커밋 실패 등) 수 · status별.
6. **기동 검사**: θ ≥ 0 · M ≥ 0(모드 무관) · `M ≤ θ`(① · ④) · ④ 규칙 4는 새 키로(`payment-window > 0` · `0 ≤ θ < payment-window`).
7. **기존 테스트 계약 이전**: `Adr015RaceScenarios.kt:228-235`('conditional-extend가 아니면 404')는 ①에서 200이 된다 → ② · ③ · current만 404를 지키고 ①은 ADR-016 테스트로 옮긴다. 옛 키를 쓰는 테스트(`Adr015Support.kt:75` · `Adr015StartupContractTest.kt:33` · `:185-189` · `Adr015DelayAndLatenessTest.kt:74`)는 새 키로 옮긴다.
8. **ADR-015 하네스 키 맞춤**: `k6/ADR-015/scripts/plan15.py:48` · `:167` · `:188` · `:208` · `selftest.py:73` · `compose.confirm.yml:8` · `:33` · `summarize.py:635`(주석) — 옛 키 · `EXTEND_MIN_REMAINING`을 새 키 · `PAYMENT_MIN_REMAINING`으로. `expected_config`에 `payByMargin`이 나오면 함께 둔다([구현 검증] — 설정 노출 형태).

## 2. 경계·불변식 (필수)

- **N16-1** ① payment-start는 어떤 행도 쓰지 않는다(홀드 · 좌석 · 쿼터 · 표지 무변경).
- **N16-2** ① payment-start 200 ⇒ 그 요청 `:now`에 `expires_at − :now > θ`. ④는 지금 E1 계약 그대로(키 이름만 바뀜).
- **N16-3** `payBy = expiresAt − M ≤ expiresAt` · `payWithinSeconds ≥ 0` · 같은 응답의 `expiresAt`과 같은 앱 Clock 기준.
- **N16-4** payment-start 거부(404 · 403 · 409)는 상태를 바꾸지 않는다 — 뒤 확정의 결과는 거부가 없었을 때와 같다.
- **N16-5** `seat.confirm.rejected{code}` 합 = 확정 엔드포인트의 도메인 거부 응답 수(code별) · `failed{status}`는 그 밖의 비2xx만(겹치지 않는다).
- **N16-6**(새) 옛 키 `seat.confirm.extend-min-remaining`이 있으면 어떤 모드에서도 기동하지 않는다(값과 무관 — 무음 후퇴 0).
- **N16-7**(새) `current` · ② · ③의 외부 계약은 그대로: payment-start 404(핸들러 없음) · 선점 201 본문 키 3개 그대로.
- **N16-8**(새) ④의 연장 판정 · 지표(`seat.confirm.extend`) · 진단 순서는 그대로 — 바뀌는 것은 θ 키 이름과 응답 필드 추가뿐.
- 범위 밖: DB 스키마(새 마이그레이션 없음) · 확정 판정(C1 · C2) · 만료 배치 · 선점 판정 · 기존 에러 코드 · 오류 본문 `{code, message}`.

## 3. 기준소스 (필수)

- 설계: `docs/adr/ADR-016-paid-seat-loss.md` 설계-9(우선) · 설계-3.1 · 3.3 · 4 · 4b · 리뷰 ledger(`docs/plans/2026-11-07/adr-016-paid-seat-loss/log.md` RD16-*).
- 코드(main 5ae6c788 + 브랜치 `feat/adr-016-paid-seat-loss` c74f4560 — 앱 코드는 main과 같다): `PaymentStartController.kt` · `PaymentStartService.kt` · `SeatConfirmProperties.kt` · `HoldStrategyStartupCheck.kt:259-300` · `SeatHoldController.kt` · `ReservationController.kt` · `ApiExceptionHandler.kt` · `AtomicConfirmProcess.kt` · `ConfirmReservationService.kt`.
- 테스트: `adr015/Adr015RaceScenarios.kt` · `Adr015Support.kt` · `Adr015StartupContractTest.kt` · `Adr015ApiContractTests.kt` · `adr015fix/*`.
- 선례: ADR-015 ④ 지표 커밋 뒤 세기(R15-16) · 규칙 4 기동 검사 · D-6(앱 기본값은 측정 뒤).

## 4. 금지영역 (필수)

- V1~V14 마이그레이션 · 새 마이그레이션 · 기존 k6 결과 폴더 · 닫힌 ADR 문서(ADR-015 등) 내용.
- 확정 C1 · C2 문장 · 만료 배치 · 선점 경로 동작 · 기존 에러 코드 · 오류 본문 모양.
- 측정 · .164 접속(이 명세에 없음) · .164 사용자 컨테이너 · .158 · .9 · 다른 프로젝트 컨테이너 · `docker system prune` · sudo · PC NIC/전원 설정 · `/home/jun/project/study-note`.
- DB 비밀번호 출력 · credential grep · push · PR(사용자 확인 전).

## 5. 검증 방법 (필수)

- **blind 테스트 워커**(구현 diff 미열람 — 이 명세 · 설계-9 · 기존 테스트 지원 코드만 보고 `adr016/` 테스트 설계 · 작성): N16-1~8 각각 최소 1개. 필수 칸:
  - θ 경계: 남은 시간 = θ → 409 `insufficient` · θ + 1ms → 200 · `expires_at = :now` → 409 `expired`(θ > 0 판과 θ = 0 판).
  - N16-1 · N16-4: 홀드 · 좌석 · 쿼터 행 전후 대조 + 거부 뒤 확정 결과가 거부 없는 판과 같음.
  - N16-3: `payBy = expiresAt − M` · `payWithinSeconds` 값 · ④ `already`에서 payBy가 지났을 때 0.
  - N16-5: 성공 · 404 · 403 · 409 · 400(본문 결함)이 섞인 확정 묶음에서 지표 증분 = 응답 수(code · status별).
  - N16-6: 옛 키를 프로퍼티 · 환경 변수(`SEAT_CONFIRM_EXTENDMINREMAINING`) 두 형태로 줘서 기동 거부.
  - N16-7: current · ② · ③에서 payment-start 404 · 선점 201 본문 키 3개.
  - 기동 검사: `M > θ` 거부 · 음수 거부 · `M = θ` 통과.
- **기존 테스트 전부** `./gradlew test` 통과(이전된 테스트 포함) · 실패가 있으면 출력 그대로 기록.
- **ADR-015 하네스**: `python3 k6/ADR-015/scripts/selftest.py --campaign` 종료 코드 0 · plan15 diff는 키 이름 맞춤뿐.
- **스모크(실제 기동)**: 로컬에서 앱을 ① 모드로 띄워 선점 → payment-start → 확정을 curl로 보내고 응답 필드 · `/actuator/metrics`의 `seat.confirm.pay_start` · `rejected` · `failed`를 확인. ④ 모드 1회 · 옛 키로 기동 거부 1회. 로컬 DB 기동이 안 되면 사유 기록 + Testcontainers 통합 테스트로 대신.
- **load-bearing 가정 조기 실증**(task 01 직후): §8 두 가정.
- diff self-review · 듀얼 리뷰 루프(Opus 워커 ∥ codex · ≤3) · 반증 질문 1회(다른 호출 경로 · 오래된 데이터 · 권한 없는 사용자).

## 6. stakes (필수)

- 판정: **높음** — public API 계약 변경(① payment-start 404 → 200 · 응답 필드 추가 · 설정 키 이름 변경 + 옛 키 기동 거부). 앱 · 테스트 · 하네스는 되돌리기 쉽고 DB는 무변경.

---

## 7. 자율성

- [x] auto (사용자 2026-11-07)
- [ ] lazy

## 8. load-bearing 가정

1. Spring `Binder`로 옛 키를 찾으면 프로퍼티 · 환경 변수(relaxed) 두 형태 모두 잡힌다 → task 01 직후 두 형태 기동 거부 테스트로 실증.
2. `@Transactional(readOnly = true)` 아래 `JdbcTemplate`의 쓰기는 PostgreSQL에서 오류가 된다(JPA 트랜잭션 관리자가 연결을 read-only로 둔다) → task 02 직후 쓰기 시도 테스트로 실증. 성립하지 않으면 멈추고 보고(N16-1 증거는 행 대조로만 남는다).

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | θ 키 이름 변경 · M 키 · 옛 키 기동 거부 · 기동 검사(`M ≤ θ` · 음수) · 기존 테스트 키 이전 · ADR-015 하네스 키 맞춤 | — | 가정 1 실증 · 기존 테스트 통과 · selftest 0 |
| 02 | ① payment-start 읽기 전용 경로 · 등록 조건(① · ④) · `pay_start` 지표 · `Adr015RaceScenarios:228-235` 계약 이전 | 01 | 가정 2 실증 · N16-1 · 2 · 4 · 7 |
| 03 | 응답 필드 `payBy` · `payWithinSeconds` · `extended`(선점 201 ① · ④ · payment-start 200) | 01 | N16-3 · N16-7 · N16-8 |
| 04 | 확정 지표 `rejected{code}` · `failed{status}` | — | N16-5 |
| 05 | blind 테스트 워커 — 명세에서 `adr016/` 테스트 설계 · 작성(구현 diff 미열람) | 명세 합의 | §5 필수 칸 전부 · 구현 뒤 통과(실패 = 구현 또는 테스트 결함 판정 · 기록) |
| 06 | 전체 테스트 · 로컬 기동 스모크 · diff self-review · 듀얼 리뷰 루프 | 02 · 03 · 04 · 05 | 테스트 통과 · 스모크 결과 기록 · 리뷰 미해소 0 |
| 07 | ADR-016 문서 갱신(9.3 `M ≤ θ` · 구현 결과 절) — docs 커밋 분리 | 06 | 코드 커밋에 docs 미포함 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1(2026-11-07 — "합의 · 진행하자")
- [x] 자율성 선택 → MODE=auto 기록
