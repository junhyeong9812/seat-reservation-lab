# 요구사항 명세서 — ADR-018 연석 부분 선점 (1단계: 재현 · 설계 · 구현)

> 작성일: 2026-11-18 · 작업 폴더: `docs/plans/2026-11-18/adr-018-adjacent-seats/` · 브랜치 `feat/adr-018-adjacent-seats`(main 721983c29에서 · 로컬)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-11-18): "PR #17 병합하고 02 payment 진행해줘 진행하면서 좌석 예약은 다음 ADR을 진행하면 되겠네/" → README 순서상 다음 = ADR-018 연석 부분 선점(Q2 "2연석 중 1석만 선점에 성공했다면?").
- Q/A (2026-11-18 · AskUserQuestion 2회 + 확인 1회):
  1. 단계 = **재현 → 구현 → 측정**(ADR-015~017과 같은 흐름). 이 명세는 1단계(재현 스모크 · 설계 · 구현 · 기능 테스트). 측정(k6 하네스 · 측정 전 고정 · 캠페인)은 2단계 명세로 따로.
  2. 연석 계약 = **고른 N석 전부 아니면 전무**: 클라이언트가 고른 좌석 N개(1인 한도 2 이하)를 한 요청으로. 서버는 같은 회차 · 한도만 보고, 인접 여부는 검사하지 않는다(화면 몫).
  3. 결제 단위 = **묶음 = 결제 1건**: 묶음 홀드 하나에 payment-start · 확정 · intent · 환불이 하나씩.
  4. 비교 후보 = **B0 · B1 · M① · M②**(사용자 확인):
     - B0 기준선: 1석 API 두 번(둘째 실패 시 첫 석을 TTL까지 쥠)
     - B1: 1석 두 번 + 실패 시 해제 API로 첫 석을 바로 놓음
     - M① 다석 · 분리형: 좌석 id 오름차순 `FOR UPDATE NOWAIT` → 홀드 INSERT → 상태 UPDATE를 따로
     - M② 다석 · 한 방 쿼리: `WITH u AS (UPDATE product_seat … WHERE id = ANY(?) AND status='AVAILABLE' RETURNING id) INSERT INTO seat_hold … SELECT … FROM u` 한 문장 · 바뀐 행 ≠ N이면 롤백(조건부 일괄 UPDATE 후보를 포함)
     - 사용자 원문: "하나의 api로 두개의 좌석의 홀드를 만들고 두개를 동시에 처리하는게 맞을 꺼 같은데, 우선 1석을 각각 예약 및 홀드를 처리하고, 이것과 다석 api를 만들어서 비교하는게 좋겠네. 쿼리 최적화일꺼고, … 한방쿼리와 각각 분리해서 홀드 생성 후 상태를 바꾸는 지 이것도 보는게 좋겠네."
  5. 묶음 구조 = **묶음 표 추가 · 다석만 묶음**: 새 `hold_group` 표 + `seat_hold.group_id` · 기존 1석 경로(ADR-015~017 측정 경로)는 그대로.
  6. 해제 API = **넣는다**: `DELETE /api/holds/{id}`(좌석 되돌림 · 한도 카운터 −1) · payment-start 뒤(결제 중)는 거부.
  7. 2단계 측정 환경 = **k6를 .9 또는 .158에서**(PC 작업과 분리 · 이전 ADR과 측정 환경이 달라짐을 기록).
  8. 사용자(같은 턴): "캐시화는 나중에 진행하는게 맞는 거 같아" → 좌석 맵 캐시는 ADR-019 몫 · 이번 범위 밖.
  9. (합의 2026-11-18 "응 합의, auto로 진행") 묶음 + 보상 D(auth-capture) · S(substitute) 처리 — 확정: 묶음은 refund · none만 지원하고, D · S 구성에서는 다석 API를 기동 검사로 막는다(D · S는 측정 손잡이일 뿐 기본이 아님 — ADR-017 결정).

---

## 1. 목표·대상 (필수)

seat-reservation-lab `main-app`에 ① 부분 선점 재현 스모크(B0) ② 홀드 해제 API(B1) ③ 다석 묶음 선점 API(N ≤ 한도 · 전부 아니면 전무 · M① · M② 손잡이) ④ 묶음 단위 payment-start · 확정 · intent · 보상(refund) · 만료가 들어가고, ADR-018 설계 절이 채워진 상태. 기본값은 바꾸지 않는다(측정 뒤 결정).

## 2. 경계·불변식 (필수)

- G1 전부 아니면 전무: 묶음 선점이 끝나면 요청한 N석이 모두 그 사용자 HELD이거나, 하나도 바뀌지 않는다(좌석 상태 · seat_hold · 한도 카운터 · hold_group 모두).
- G2 한도: 카운터는 +N 한 번(부분 증가 없음) · 한도 초과면 전체 거부 · 기존 에러 우선순위(마감 → 좌석 불가 → 한도 초과) 유지.
- G3 교착 없음: 겹치는 좌석 집합을 다른 순서로 요청해도 교착 대신 409(M① 좌석 id 오름차순 잠금 · NOWAIT).
- G4 묶음 결제 1건: 묶음당 payment-start 1회(모든 좌석 만료 연장) · 확정은 N석 모두 RESERVED이거나 전부 거부 · intent 1개 · 보상 CLOSE 1건(금액 = N석 합).
- G5 해제: payment-start 전 홀드만 해제 · 좌석 AVAILABLE · 카운터 −1(묶음이면 −N) · 남의 홀드 · 없는 홀드는 4xx · 해제와 만료 sweep · 확정이 겹쳐도 카운터 이중 감소 없음.
- G6 기존 1석 경로의 동작 · 응답 · 측정 계약은 그대로(기존 테스트 단언 무변경).
  - 예외(2026-11-19 사용자 합의 "7개 모두 고침"): V16 스키마 모습 단언 7곳만 조정 — Flyway 적용 버전 목록(Adr009DefaultsTest · Adr014ImplStartupTest · FirstWindowPageTest · Adr015OutOfOrderContractTest 2곳) · 인덱스 집합(IndexMigrationTest) · reservation 유니크 열(Adr017PaymentStartIntentTest:101). 동작 단언은 그대로.

## 3. 기준소스 (필수)

- `docs/adr/ADR-018-adjacent-seats.md`(가설 · 선택지) · README Q2 · Load Profile(1석 60% / 2연석 40% · 1인 2매)
- 코드 사실(탐색 2026-11-18): `HoldSeatProcess.kt` · `DbLockHoldStrategies.kt`(pessimistic-nowait 기본) · `UserLimitStrategies.kt`(counter-upsert-precheck `+1` 고정) · `SweepConditionalBatch.kt` · `PaymentStartService.kt` · `AtomicConfirmProcess.kt` · `PaymentConfirmService.kt` · V1 · V5 · V12 · V13 · V15(`payment_intent_hold_uk` · `reservation_intent_uk`)
- ADR-015 · 016 · 017 결정(확정 conditional-extend · payBy · 보상 refund)

## 4. 금지영역 (필수)

- 닫힌 ADR(000~017) · V1~V15 마이그레이션 수정 금지(변경은 V16+).
- 기본값 변경 금지(확정 · 보상 · 홀드 전략) — 측정 뒤 결정 커밋에서.
- 좌석 맵 캐시(ADR-019) · 대기열(ADR-020) · 인접 검증 · 자동 배정 넣지 않음.
- 묶음 + 보상 D · S 지원 넣지 않음(Q/A 9 — 합의로 확정).
- k6 하네스 · 측정(2단계 명세).
- push · PR · 원격 브랜치는 사용자 확인 뒤 · code · docs 커밋 분리 · 검증 출처를 커밋 · 주석에 쓰지 않음.

## 5. 검증 방법 (필수)

- 재현 스모크 먼저(load-bearing 가정 1): 로컬에서 같은 2석을 두 사용자가 반대 순서로 1석 API 두 번 → 부분 선점(1석만 쥔 사용자)이 실제로 생기는지 · 그 좌석이 TTL까지 묶이는지.
- 설계 선검증(새 동작 불변식 G1~G5 — codex 교차 검증 1회).
- blind 테스트 워커: 명세 + ADR-018 설계 절 + API 계약만 보고 G1~G6 테스트(구현 미열람) — 동시성 테스트 포함(겹치는 2석 반대 순서 · 교착 0 · 부분 상태 0).
- 전체 `./gradlew cleanTest test`(main-app · mock-pg · reconciler) 실패 0 · 기존 단언 무변경.
- 로컬 스모크: 다석 선점 → payment-start → 확정(N 예약) · 지연 확정 → 환불 1건(합계 금액) · 해제 → 카운터 · 좌석 record 대조 · 판정기(consistency) 0.
- 듀얼 리뷰 루프 ≤ 3 · 머지 전 안전선.

## 6. stakes (필수)

- **높음** — 스키마 변경(V16) · 결제 경로(묶음 intent · 환불 금액) · 동시성(다석 잠금 · 교착) · public API 신설.

---

## 7. 자율성

- [x] auto (권장)
- [ ] lazy

## 8. load-bearing 가정

1. 지금 1석 API 두 번으로 부분 선점이 실제로 생긴다(경합 2석 · 반대 순서) — 착수 직후 로컬 재현 스모크.
2. 묶음 결제를 `payment_intent`에 묶음 키로 얹고 예약 유니크를 (intent, 좌석)으로 바꾸면 1석 경로의 ADR-017 동작은 그대로다 — V16 적용 뒤 기존 전체 테스트로 실증.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 재현 스모크(B0) | 합의 | 부분 선점 관측 · 수치 기록 |
| 02 | ADR-018 설계 절(묶음 표 · V16 · API · 잠금 순서 · M① · M② · 해제 · 묶음 결제 · 만료 · 보상) + 설계 선검증 | 01 | 선검증 반영 |
| 03 | blind 테스트(G1~G6 · 구현 미열람) | 02 | 테스트 · 모호점 |
| 04 | 구현: 해제 API · V16 · 다석 API(M① · M②) · 묶음 payment-start · 확정 · intent · 보상 · 만료 | 02 | 컴파일 · 구현 테스트 |
| 05 | 전체 테스트 · 로컬 스모크 · 듀얼 리뷰 ≤ 3 | 03 · 04 | green · ledger 마감 |
| 06 | 마감(code · docs 커밋 · NEXT · 측정로그) → 2단계 명세 | 05 | SHA · 보고 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-11-18 "응 합의, auto로 진행하고 …")
- [x] 자율성 선택 — auto
