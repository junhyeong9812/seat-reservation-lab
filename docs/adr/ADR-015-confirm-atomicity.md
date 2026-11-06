# ADR-015: 결제 성공 시 확정 — 2중 확인과 전환의 원자성 (Q6)
> 번호 이동(2026-10-12, 5차): 운영 중 매수 방식 전환을 새 ADR-010으로 넣으며 기존 ADR-010~015를 011~016으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-12, 4차): 카운터 어긋남 탐지·격리·복구를 새 ADR-009로 넣으며 기존 ADR-009~014를 010~015로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-11, 3차): counter-upsert-precheck 기본 적용·검증을 새 ADR-008로 넣으며 기존 ADR-008~013을 009~014로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09, 2차): ADR-006 처리량 벽 진단을 새 ADR-007로 넣으며 기존 ADR-007~012를 008~013으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09): counter 1문장 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-15, 6차): 앱 JVM GC·메모리 기준을 새 ADR-010으로 넣으며 기존 ADR-010~016을 011~017로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-16, 7차): 측정 환경 꼬리 지연 원인을 새 ADR-011로 넣으며 기존 ADR-011~017을 012~018로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-18, 8차): 카운터 증분 대조·Kafka/복제본 대조를 새 ADR-012·013으로 넣으며 기존 ADR-012~018을 014~020으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-23, 9차): Kafka·읽기 복제본 대조(옛 ADR-013)를 착수 조건이 생기는 오픈 폭주 뒤로 보내 ADR-020으로 옮기고, 기존 ADR-014~020을 013~019로 당겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 가설 (Hypothesis) — 미측정
- 대응 질문: Q6 "결제는 성공했는데 그 사이 선점이 만료됐다면?"
- 선행: ADR-000, ADR-001, ADR-014(만료 방식에 따라 전환 조건이 달라짐)

## 인터뷰 (2026-11-04 — 사용자 답 · 요구사항 명세는 ADR-014 마감 뒤 작업 폴더에 옮긴다)

- 선택지: **4개 전부** — ① 조건부 UPDATE(전환 문장의 WHERE가 곧 확인 · 영향 행 수로 판정) ② 비관락(좌석·홀드 `FOR UPDATE` 뒤 확인·전환) ③ 낙관락(`@Version`) ④ 결제 중 홀드 연장. 최후 방어선(부분 유니크 인덱스)은 공통.
- 적용 범위: **A(sweep — 현재 기본값) 확정만**. B·C·D는 ADR-014 최소 가드 그대로(ADR-014 재측정에서 D가 기본값이 되면 그때 D를 다시 본다).
- 만료 뒤 결제 성공: **거부**(ADR-014와 같은 규칙) — 결제 취소·환불 같은 보상은 ADR-016.
- 측정: **재현 테스트 + 부하 측정** — 짧은 TTL · 확정 지연 주입으로 이중 결과를 재현해 선택지마다 0건 확인 · .164 확정 경합 부하로 처리량·p99·DB CPU 비교.
- 최후 방어선: `reservation(seat_id) WHERE status='CONFIRMED'` 부분 유니크 인덱스를 **새 마이그레이션 · 기본 위치**(모든 구성에 적용) — 기존 중복 확정 사전 검사·복구 SQL을 운영 절차로(ADR-013 원칙).
- 기본값: **이기면 교체**(교체 조건은 측정 전에 고정 — ADR-014와 같은 틀).
- stakes **높음** · 자율성 **auto**.
- 설계 초안 열린 질문 사용자 답(2026-11-04): Q1 문제 정의 = **재현 스모크 먼저**(우연한 가드 — 홀드 DELETE 0행 → 예외 → 롤백 · 진 쪽 500 — 가 맞으면 §문제를 고치고 선택지 4개 비교는 그대로) · Q4·Q5 ④ = **새 API `POST /api/holds/{id}/payment-start`**(재호출 멱등 · 늦은 연장 409 HOLD_EXPIRED · 확정 문장은 ①) · Q7 V12 = **기본 위치 유지 + 기존 테스트 수정**(기준선 재현은 `flyway.target=11`) · Q10 배치 틱 전체 롤백 = **지표로 재고 NEXT**. 나머지 열린 질문(Q2·Q3·Q6·Q8·Q9·Q11~Q14)은 설계 초안의 권장안으로 둔다(설계 선검증에서 다시 본다).
- 설계 선검증 뒤 사용자 답(2026-11-04): **A 배치 최소 수정을 범위에 넣는다**(앞의 Q10 'NEXT로'를 재합의 — 배치가 지울 때 만료 조건을 다시 확인하는 조건부 DELETE · 좌석은 `status='HELD'`일 때만 되돌림 · 행 수로 판정 · 틱 전체 롤백 개선은 그대로 NEXT) · ④ 재호출 멱등 = **첫 연장만 반영**(재호출은 현재 만료 시각 200 · 연장 표지 열 추가 — 새 마이그레이션 · 추가형) · V12 × 옛 방식 DB = **ADR-013 전환 절차에 outOfOrder 1회**(V9·V10 적용 때만 · 테스트로 고정) · 확정 만료 판정 시각 = **요청 시작 시각(:now · 앱 Clock) 유지** · 측정에서 '전환 시각이 만료 뒤인 성공'을 따로 센다.
- 출발점(코드 확인 2026-11-04): A 확정 `ConfirmReservationService.confirm`은 `findByHoldsId`(잠금 없음)로 좌석을 읽고 앱 Clock으로 `seat.confirm(...)` 확인 뒤 전환 · 예약 저장 — 확인과 전환 사이를 막는 잠금·조건부 문장·DB 유니크 제약이 없다.

## 문제

기준선의 확정은 `홀드 조회 → 내 것·만료 전 비교 → 좌석 HELD 비교 → 전환`이다. **확인과 전환 사이에 상태가 바뀔 수 있다**: 확인 직후 홀드가 만료되고 배치가 좌석을 풀고 다른 사용자가 선점하면, 이미 남의 좌석을 `RESERVED`로 만든다.

## 가설

- TTL을 짧게(수 초) 두고 확정 지연을 주입하면 한 좌석에 두 사용자의 결과(남의 홀드 + 내 예약)가 공존한다.

## 선택지 후보

- **확인과 전환을 원자적으로 묶는다**: 별도 확인 SELECT 없이 전환 문장의 WHERE 조건이 곧 확인. 조건부 문장 1회 + 영향 행 수로 성공 판정.
  - 예: `UPDATE product_seat SET status='RESERVED' WHERE id=? AND status=…` + `DELETE FROM seat_hold WHERE seat_id=? AND user_id=? AND expires_at > now()`, 둘 다 1행이어야 커밋
- 비관락: 좌석·홀드 행 `FOR UPDATE` 후 비교·전환
- 확정 시 홀드 연장(결제 진행 중 만료 방지)

## 함께 검증할 경합

- **예약된 좌석 위에 홀드가 생기는 경합**: 전환이 홀드를 지운 직후 다른 사용자의 선점이 끼어드는 경우. 후보: 선점 시 좌석 행 `FOR SHARE`(공유 락 — 여러 선점 요청은 동시에 통과, 전환의 UPDATE와는 서로 대기).
- **락 순서**: 모든 좌석 경로를 사용자(advisory) → 좌석 행 → 홀드 행 순으로 고정하지 않으면 전환과 선점이 교착한다.
- **최후 방어선**: `reservation(seat_id) WHERE status='CONFIRMED'` 부분 유니크 인덱스 — 앱 로직이 뚫려도 DB가 중복 확정을 막는다.
- **정정(15-01b — 코드와 어긋남)**: ⓐ 첫 항목의 '선점 시 좌석 행 `FOR SHARE`'는 지금 선점 잠금(`FOR UPDATE NOWAIT` — DbLockHoldStrategies.kt:53-60)보다 약해지는 쪽이라 넣지 않는다(§설계-2 둘째 글머리). ⓑ 둘째 항목의 '사용자(advisory) → 좌석 → 홀드'는 기본 조합(쿼터 행 잠금 — advisory 아님 · UserLimitStrategies.kt:320-341)과 A 배치 순서(쿼터 → 홀드 → 좌석 — ExpiryCounters.kt:49-63 뒤 flush)에 맞지 않는다 — 이 ADR의 고정 순서는 **쿼터 행 → 홀드 행 → 좌석 행**이다(§설계-3 공통 규칙 · §3.5).

## 설계 (측정 전 고정 — 초안)

> 초안(task 15-01a — 2026-11-04). 기준 = 작업 디렉토리 그대로(이 폴더는 git 저장소가 아니라 기준 SHA를 적지 못했다 — 구현 착수 때 `main` HEAD에서 아래 `파일:줄`을 다시 확인한다 · 줄 번호는 이 초안을 쓸 때의 것). 정답 기준 = 위 §인터뷰(2026-11-04 사용자 답 — 선택지 4개 · 범위 A 확정만 · 만료 뒤 거부 · 재현 + 부하 · 기본 위치 부분 유니크 인덱스 · 이기면 교체). 실코드로 확인한 사실에는 `파일:줄`, 코드로 확인하지 못한 판단은 **(추정)**, 구현에 닿아야 판정할 수 있는 값은 `[구현 검증]`(§설계-7에 모음)으로 적는다. 측정 고정값 · 성공 기준 · 기본값 교체 조건은 다음 task 몫이다 — 이 절의 측정은 뼈대까지(§설계-8).
>
> 개정(task 15-01b — 2026-11-04): 설계 선검증 지적 11건과 §인터뷰 마지막 사용자 답('설계 선검증 뒤')을 반영했다 — A 배치 최소 수정(새 §3.6 A′ 배치 · 손잡이로만 켬) · ④ 연장 표지 열(V13 — 첫 연장만) · ③ 트리거 번호 V14로 이동 · 새 확정 모드는 V12 없으면 기동 거부 · FM2를 V12 유무로 가름 · 복구 R1을 다시 세기로 · ADR-013 전환 절차의 outOfOrder 1회 · 유니크 위반 매핑은 A만 · 트리거 짝 검사를 마이그레이션 앞으로 · `CONCURRENTLY` 재시도에 `flyway repair` · '만료 뒤 전환된 성공' 지표. 줄 번호는 이 개정 때 다시 읽은 것이다(§설계-9 끝 '다시 읽은 파일').

### 설계-0 용어

| 기호 | 뜻 |
|---|---|
| A | 만료 방식 sweep(`seat.hold.expiry-mode=sweep` — 기본값 · application.yml:39). 좌석에 `HELD`를 저장하고 주기 배치가 만료분을 지운다. 만료 판정은 **앱 Clock**(ADR-014 Q1 결정 — A는 DB `now()`로 옮기지 않았다) |
| 기본 조합 | 좌석 경합 `pessimistic-nowait`(좌석 행 `FOR UPDATE NOWAIT` — 잠겨 있으면 기다리지 않고 진다) + 매수 `counter-upsert-precheck`(잠금 없는 좌석 선조회 + 쿼터 한 문장 upsert)(application.yml:45·47) |
| 확인 · 전환 | 확인 = 내 홀드 · 만료 전 · 좌석 `HELD` 비교. 전환 = 좌석 `RESERVED` + 홀드 행 삭제 + 예약 `CONFIRMED` INSERT(ADR-000 §3) |
| 이중 결과 | 한 좌석에 두 결과가 남는 것: ⓐ 남의 홀드가 살아 있는 좌석이 `RESERVED`(내 예약 + 남의 홀드) ⓑ 같은 좌석 `CONFIRMED` 2건 ⓒ 예약 `CONFIRMED`인데 좌석이 `RESERVED` 아님(전환 없는 예약) · 좌석 `RESERVED`인데 확정 예약 없음 |
| 우연한 가드 | 설계로 둔 장치가 아닌데 결과적으로 경합을 막는 동작. 여기서는 Hibernate가 엔티티 DELETE의 영향 행 수를 1로 기대하는 동작(§설계-1) |
| RC | READ COMMITTED(이 앱의 격리 수준 — 문장마다 그 순간까지 커밋된 것을 본다) |
| EPQ | EvalPlanQual — RC에서 잠그거나 고치려던 행이 그 사이 바뀌었으면 **그 행의 조건만** 최신 버전으로 다시 보는 PostgreSQL 동작(하위 질의는 문장 시작 스냅숏 그대로 — ADR-014 §설계-2.3) |
| FK 검사 잠금 | 예약 INSERT가 참조하는 회차·좌석 행에 거는 `FOR KEY SHARE`(외래 키 검사) — `FOR UPDATE`와는 충돌하고 `FOR NO KEY UPDATE`(키가 아닌 열의 UPDATE)와는 충돌하지 않는다 |
| `:now` | 확정 트랜잭션 시작 때 한 번 읽은 앱 Clock 값(지금 `ConfirmReservationService.kt:43`과 같은 자리). 네 선택지 모두 만료 비교에 이 값을 쓴다 |
| TTL | 홀드 유효 시간(`seat.hold.ttl` — 5분, application.yml:34) |
| A′ 배치 | A 만료 배치의 최소 수정판(§3.6) — 지울 때 만료 조건을 다시 보는 조건부 DELETE · 좌석은 `status='HELD'`일 때만 되돌림 · 행 수로 판정 · 카운터는 실제로 지운 행만큼. `seat.confirm.mode ≠ current`일 때만 돈다(`current`면 지금 JPA 배치 그대로) |
| 대조 배치 · 대조 확정 | 재현 테스트 전용(테스트 소스에만 — 앱 빈 아님): 가드 없이 쓰는 배치 · 확정. '가드가 없으면' 칸을 실제로 만들어 하네스가 이중 결과를 잡을 수 있음을 보인다(§설계-8.1) |
| 표지 열 | `seat_hold.extended_at`(V13 — §3.4) — ④ 첫 연장 시각. NULL = 연장 안 함 |

### 설계-1 지금 코드의 사실 (A 확정 · 기본 조합)

**확정 문장 순서**(`ConfirmReservationService.confirm` — ConfirmReservationService.kt:38-66 · `expiry-mode ≠ sweep`이면 :40에서 ADR-014 B·C·D 확정 `LazyConfirmProcess`로 간다):

| # | 문장 · 동작 | 잠금 | 근거 |
|---|---|---|---|
| A0 | `beforeConfirm()` — 기본 조합은 빈 함수(counter-trigger만 `set_config`) | — | ConfirmReservationService.kt:42 · UserLimitStrategies.kt:51 · :371 |
| A1 | `:now = clock.instant()` | — | ConfirmReservationService.kt:43 |
| A2 | `findByHoldsId(holdId)` — 홀드 id로 좌석 로드. 없으면 404 | 없음 | ConfirmReservationService.kt:44-45 · ProductSeatRepository.kt:16 |
| A3 | 좌석의 홀드 컬렉션 로드(지연 로딩 — A2와 **다른 문장 · 다른 스냅숏**) (추정) | 없음 | ProductSeat.kt:40-42 · :60 `holds.find` |
| A4 | 메모리 안 확인: 내 홀드인가(403) → `isExpired(:now)`(409 HOLD_EXPIRED — 만료 정각은 만료) → `status == HELD`(409 SEAT_NOT_HELD) → `status = RESERVED` · `holds.remove` | 없음 | ProductSeat.kt:59-73 · SeatHold.kt:27 |
| A5 | `reservationRepository.save` — id가 IDENTITY라 **save 시점에 곧바로 INSERT**(flush를 기다리지 않는다) (추정) | 회차·좌석 행 FK 검사 잠금 | ConfirmReservationService.kt:49-58 · Reservation.kt:23-25 · V1__init.sql:33-41 |
| A6 | 커밋 flush: 고아 홀드 `DELETE FROM seat_hold WHERE id = ?`(orphanRemoval) | 홀드 행 | ProductSeat.kt:40 |
| A7 | 커밋 flush: `UPDATE product_seat SET …, status = 'RESERVED' WHERE id = ?` — **상태 조건 없음 · 버전 없음**(V3 `version` 열은 엔티티에 매핑하지 않는다 — V3__add_seat_version.sql:1-2) | 좌석 행(`FOR NO KEY UPDATE` 급) | ProductSeat.kt:36-37 |
| A8 | `changes.changed(회차, 사용자)`(ADR-012 ⓐ 메모리 집합 등록 — method ≠ memory면 아무것도 안 함) · 쿼터 행은 건드리지 않는다(확정 = 홀드 −1 · 예약 +1 → cnt 그대로) | — | ConfirmReservationService.kt:59-60 · UserLimitStrategies.kt:224 주석 |

- A6·A7의 순서는 Hibernate flush 규칙(고아 삭제가 UPDATE보다 먼저) (추정 `[구현 검증]` #2). 만료 배치도 같은 JPA 경로라 같은 순서다(아래).
- **경합 창**: W1 = A2·A3(잠금 없는 읽기) ~ A5·A6·A7(쓰기). W2 = A1의 `:now`(트랜잭션 시작에 고정) ~ 커밋. 만료 직전에 시작해 만료 뒤에 커밋하는 확정은 지금도 허용된다(판정 시각 = 트랜잭션 시작 — ADR-014 §설계-2.2와 같은 규칙).

**W1에 끼어들 수 있는 경로**(기본 조합 · 앱 1대):

| 경로 | 문장 순서 | 잠금 순서 | 근거 |
|---|---|---|---|
| 만료 배치(A) | 틱 = 트랜잭션 1 · `findDistinctByHoldsExpiresAtLessThanEqual(앱 now)`(잠금 없음) → `expireHolds` = 만료 홀드 제거 + `HELD`면 `AVAILABLE` → 쿼터 가드 감소(JDBC — 즉시) → 커밋 flush(홀드 DELETE → 좌석 UPDATE) | 쿼터 행(정렬) → 홀드 행 → 좌석 행 (추정 — JDBC 문장은 JPA flush를 일으키지 않는다) | ExpireHoldsService.kt:38-45 · ProductSeatRepository.kt:18 · ProductSeat.kt:76-86 · ExpiryCounters.kt:49-63 |
| 선점(다른 사용자 Y) | 회차 `FOR SHARE` → 좌석 선조회(`AVAILABLE` 아니면 409) → 쿼터 upsert → 좌석 `FOR UPDATE NOWAIT` → `assertHoldable`(잠근 뒤 `AVAILABLE` 확인) → 홀드 INSERT + 좌석 `HELD` | 회차(공유) → 쿼터 행 → 좌석 행(NOWAIT — 기다리지 않음) | SalesCloseGate.kt:50-52 · UserLimitStrategies.kt:320-341 · DbLockHoldStrategies.kt:53-60 · ProductSeatRepository.kt:31-34 · HoldSeatProcess.kt:70-89 |
| 같은 홀드의 두 번째 확정(재시도 · 두 탭) | A0~A8 그대로 | 위와 같음 | — |
| 정리 ①(마감 회차) | 회차 `FOR UPDATE` → 홀드 있으면 아무것도 안 지움 · 없으면 쿼터 행 삭제 | 회차 행(배타) | QuotaCleanup.kt:117-136 |

- **지금 이중 결과를 막는 것**: 잠금 없음 · 조건부 문장 없음(A7은 `WHERE id = ?`뿐) · 버전 없음 · DB 제약 없음(V1__init.sql:1-2 · V2 `idx_reservation_seat_id`는 유니크가 아니다 — V2__add_indexes.sql:12). **남는 것은 우연한 가드 하나**: A6의 DELETE가 0행이면(배치·다른 확정이 그 홀드를 먼저 지우고 커밋) Hibernate가 행 수 불일치 예외를 던져 트랜잭션 전체가 롤백된다 (추정 — Hibernate 6.6의 기본 행 수 기대값 · 이 저장소 테스트로 확인한 적이 없다 · ADR-014 §설계-10 #8과 같은 미확인 · `[구현 검증]` #1).
  - 그 예외는 `SeatReservationException`이 아니라 `ApiExceptionHandler`가 받지 않는다(ApiExceptionHandler.kt:25-33 — 처리기는 이것 하나) → **500**(추정). 진 쪽이 계약 코드(404·409)가 아니라 5xx로 끝난다.
  - 배치 쪽이 지면(확정이 홀드를 먼저 지움) 같은 예외로 **틱 전체**가 롤백된다 — 그 틱의 다른 좌석 만료 · 쿼터 감소도 함께 사라지고 다음 틱(10초 — application.yml:36)으로 밀린다(ExpireHoldsService.kt:46-50 실패 지표 · ExpiryCounters.kt:186-198 skipped 기록).
  - **배치의 카운터 감소는 '지운 행'이 아니라 '고른 행' 기준이다**: 감소(`userLimit.strategy.onHoldsExpired(expired)` — ExpireHoldsService.kt:43 → ExpiryCounters.kt:49-63)는 메모리에서 고른 목록(ProductSeat.kt:76-86)으로 JDBC 문장을 즉시 보내고, 실제 홀드 DELETE는 그 뒤 커밋 flush에서 일어난다. 그래서 DELETE가 0행이 되는 경합에서 감소와 삭제가 맞으려면 지금은 '0행 → 예외 → 틱 롤백(감소도 함께 사라짐)'에 기댈 수밖에 없다 — 우연한 가드가 카운터 정합까지 떠받친다. A′ 배치(§3.6)는 순서를 '지운 뒤 지운 만큼 감소'로 바꾼다.
  - 배치의 좌석 되돌림도 조건이 없다: `status == HELD`는 메모리 판정(ProductSeat.kt:82)이고 flush UPDATE는 `WHERE id = ?`뿐 — 낡은 스냅숏으로 남의 `RESERVED`를 덮을 수 있는 자리다(FM3 '가드가 없으면').
  - ADR-014 캠페인에서 A 정합 위반 0(ADR-014 §결정-1 'ADR-015에 새로 넘길 A 결함은 없다' · MQ9)은 이 가드와 어긋나지 않는다 — TTL 5분 · 확정 지연 주입 없음 조건이라 창이 거의 열리지 않았다(추정).
- **초안 '출발점'(§인터뷰 마지막 줄)과의 차이**: '막는 잠금 · 조건부 문장 · DB 유니크 제약이 없다'는 사실이지만, 결과가 '이중 결과'가 아니라 '500 + 롤백'일 수 있다. 이것이 맞는지는 착수 직후 재현 테스트가 정한다(§설계-8.1 · Q1 — 정해짐: 재현 스모크 먼저).

### 설계-2 재현할 실패 모양 (끼어들기 순서)

각 모양마다 '우연한 가드가 있으면'(지금 코드의 기대 — 추정)과 '가드가 없으면'(대조군 — §설계-8.1)을 적는다. `te` = 내 홀드 만료 시각.

| # | 끼어들기 | 가드가 있으면(지금 추정) | 가드가 없으면 |
|---|---|---|---|
| FM1 남의 홀드 위 내 RESERVED | ① X(나) 확정이 `t0 < te`에 시작 — A1~A4 통과(`isExpired(t0)` 거짓) 뒤 늦어짐 ② `te` 뒤 배치 틱이 내 홀드를 지우고 좌석 `AVAILABLE` · cnt(X) −1 · 커밋 ③ Y가 선점 — 새 홀드 h′ · 좌석 `HELD` · 커밋 ④ X가 A5 예약 INSERT → A6 내 홀드 DELETE | A6 0행 → 예외 → X 롤백 → X에게 500 · Y 홀드 그대로 | A6 0행 무시 → A7이 Y의 `HELD`를 `RESERVED`로 덮음 → **좌석 RESERVED + Y의 활성 홀드 h′ + X 예약**(이중 결과 ⓐ — 판정기 `active_on_reserved_seats` · `v_hold_on_unheld_seat` — LoadtestDataService.kt:172-173 · :231). cnt(X)는 배치가 −1 · 예약 +1 → 실제보다 1 작다(below — `v_counter_mismatch`). Y의 확정은 409 SEAT_NOT_HELD(ADR-016 '돈은 빠졌는데 좌석 없음'이 Y에게 생긴다) |
| FM2 같은 홀드 이중 확정 | ① X1 · X2가 같은 홀드로 동시에 A2~A4 통과 ② 둘 다 A5 예약 INSERT ③ X1 A6 DELETE · A7 · 커밋 ④ X2가 그 뒤 진행 | **V12 없음('V12 없음' 판 — 설계-8.1)**: ②의 두 INSERT는 서로 막지 않는다 → ④ X2 A6 DELETE가 X1을 기다린 뒤 0행 → X2 롤백 → 500. **V12 있음(기본)**: ②에서 늦게 INSERT한 쪽(X2)이 유니크 검사로 X1 커밋을 기다린 뒤 **23505** — A6에 닿기 전에 진다 → 유니크 위반 매핑(N6)으로 409 SEAT_NOT_HELD + `seat.confirm.unique_violation` +1 (추정 `[구현 검증]` #13 — SQL 로그로 '23505가 INSERT 문장에서 났는가 · DELETE 0행 예외가 아닌가'를 본다) | V12 없음: X2도 커밋 → **같은 좌석 CONFIRMED 2건**(이중 결과 ⓑ — `v_duplicate_confirmed_seats` · LoadtestDataService.kt:164) · cnt(X) +0인데 실제 +1(below). V12 있음: 대조 확정의 INSERT가 23505 → 대조군은 매핑이 없어 500 · 이중 결과 0 — **인덱스가 가린다**(그래서 '가드가 없으면' FM2 칸은 `target=11` 판에서만 잰다) |
| FM3 확정과 배치의 같은 틱 | ① 배치 틱이 내 홀드를 만료로 읽음(배치 `now ≥ te`) ② X 확정(`t0 < te` 시작)이 A6·A7을 먼저 보내고 커밋 ③ 배치 flush의 홀드 DELETE가 X를 기다린 뒤 0행 | 배치 **틱 전체 롤백**(정합은 지켜지지만 그 틱의 모든 만료가 다음 틱으로 밀림 · 실패 지표) | 배치 좌석 UPDATE(전체 열 · 낡은 스냅숏 `AVAILABLE`)가 X의 `RESERVED`를 덮음 → **예약 CONFIRMED + 좌석 AVAILABLE**(이중 결과 ⓒ — `v_confirmed_on_unreserved_seat` · LoadtestDataService.kt:168-169) → 그 좌석이 다시 팔린다 · cnt(X) −1(지우지 않은 홀드만큼 below) |
| FM4 응답 계약 | FM1~FM3의 진 쪽 | 5xx(계약 밖 · ADR-016이 '확정 실패'를 404·409로 가를 수 없다) — 단 V12가 있으면 FM2의 진 쪽은 409(매핑) | 진 쪽이 없다(이중 결과) |
| FM5 연장을 잃음(④) | ① 배치가 내 홀드를 만료 후보로 읽음(배치 `:now ≥` 원래 `te`) ② 내 `payment-start`가 연장 커밋(`expires_at` → `:now′ + window` · `extended_at` 기록) ③ 배치가 그 홀드를 지움 | 지금 JPA 배치(id로만 지움): 연장된 홀드가 지워진다 → 좌석 `AVAILABLE` → 내 확정 404(정합은 지키지만 연장이 무효) | 대조 배치도 같다(지워짐 · 잃음 1) |

- **새 모드(①~④ + A′ 배치)의 기대**: FM1 → 404(C1 · P1 · O2가 0행) · FM2 → 404(뒤 쪽이 홀드 행에서 기다린 뒤 0행 — 예약 INSERT 앞이라 V12 유무와 무관) · FM3 → 확정 성공 · A′ 배치는 그 홀드를 0행으로 지나가고(감소 0 · 좌석 되돌림 0행) **틱은 커밋**(다른 좌석 만료는 그대로 진행) · FM5 → A′ 배치의 조건부 DELETE가 EPQ로 연장된 `expires_at`을 다시 보고 지우지 않는다(잃음 0).
- 정리 ①과 확정은 기다림만 있다(정리 ①이 회차 행 `FOR UPDATE`를 쥐는 동안 A5의 FK 검사가 기다린다 — QuotaCleanup.kt:120-123 주석) — 이중 결과 모양이 아니므로 재현 대상에서 뺀다(교착 점검은 §설계-3.5).
- 선점 쪽이 '예약된 좌석 위에 홀드'(§함께 검증할 경합 첫 항목)를 만드는 길은 기본 조합 A에 없다(추정): 선점은 좌석을 `FOR UPDATE NOWAIT`로 잠근 **뒤** `AVAILABLE`을 다시 보고(DbLockHoldStrategies.kt:54 → HoldSeatProcess.kt:74-77), 확정은 좌석을 `AVAILABLE`로 만들지 않으며 홀드 삭제와 좌석 `RESERVED`를 한 트랜잭션에서 한다. 그래서 초안 후보 '선점 시 좌석 행 `FOR SHARE`'는 지금 잠금(`FOR UPDATE NOWAIT`)보다 약한 것으로 바꾸는 셈이라 **넣지 않는다**(재현 테스트에 '확정 커밋 직후 선점' 칸만 둔다 — 기대 409).

### 설계-3 선택지 넷 — 데이터 모델 · 문장 순서 · 잠금 · 배치 · 카운터 · 에러 · 교착 · 되돌리기

**공통 규칙**(네 선택지 모두):

- **범위**: A(sweep) + 기본 조합의 확정 경로 + **A 만료 배치의 최소 수정(A′ — §3.6 · 사용자 재합의 2026-11-04)**. 선점 · 정리 ① · B·C·D 경로는 바꾸지 않는다(③의 트리거와 ④의 새 API만 예외 — 각 절). 손잡이 기본값(`current`)은 지금 JPA 확정 · JPA 배치 그대로다(§설계-5 — 측정의 기준선 칸이 바뀌지 않게 · '기존 동작 보존').
- **시각**: 만료 비교는 `:now`(앱 Clock — 설계-0). DB `now()`를 쓰지 않는다 — A 배치가 앱 Clock으로 만료를 고르고(ExpireHoldsService.kt:39) 기존 테스트가 `MutableClock`으로 만료를 만든다(ConfirmReservationServiceTest.kt:26·66 — ADR-014 §설계-2.1 Q1과 같은 이유). 초안 예시의 `expires_at > now()`(§선택지 후보)는 `expires_at > :now`로 바꾼다.
- **에러 계약 · 순서(지금 A와 같다)**: 404 HOLD_NOT_FOUND → 403 HOLD_NOT_OWNED → 409 HOLD_EXPIRED → 409 SEAT_NOT_HELD(ProductSeat.kt:59-73 · ErrorCode.kt:7-10 · ApiExceptionHandler.kt:46-55). 경합에서 진 확정은 이 넷 중 하나로 끝나야 한다 — 5xx 금지(N4). 문장 영향 행이 0이면 잠금 없는 **진단 문장**으로 코드를 고른다(판정은 영향 행 수가 하고, 진단은 코드만 고른다 — 진단 순간 값이 또 바뀌어도 결과(거부)는 같다).
- **구현 형태(권장)**: 새 선택지는 JDBC 문장으로 쓴다(`LazyConfirmProcess`와 같은 방식 — LazyConfirmProcess.kt:36-71). JPA 애그리거트(`ProductSeat.confirm`)는 메모리 판정 + 전체 열 UPDATE라 조건부 문장을 담을 수 없다. `current` 경로(JPA 확정 · JPA 배치)의 SQL · 판정 · 순서는 건드리지 않는다 — 더하는 것은 유니크 위반 매핑(§설계-4)과 관측 지표 `converted_after_expiry{clock=app}`(아래) 둘뿐이다.
- **카운터 · 대조**: 확정은 쿼터 행을 잡지도 쓰지도 않는다(지금과 같다 — 홀드 −1 · 예약 +1이 같은 트랜잭션이라 cnt = 홀드 행 + 확정이 어느 스냅숏에서도 성립). 커밋 뒤 등록 `changes.changed`는 지금 자리 그대로(ConfirmReservationService.kt:60 · LazyConfirmProcess.kt:69). counter-trigger 등 다른 매수 방식은 기동 거부(§설계-5)라 `beforeConfirm`을 부를 일이 없다. 대조 실제 값 정의(V6__backfill_user_hold_quota.sql:2-3 · LoadtestDataService.kt:190-203)는 바꾸지 않는다.
- **최후 방어선 위반**: 예약 INSERT가 §설계-4의 인덱스에 걸리면(23505 · 인덱스 이름 일치) 409 SEAT_NOT_HELD + ERROR 로그 + 지표 `seat.confirm.unique_violation` — 다른 무결성 오류는 그대로 올린다(DbLockHoldStrategies.kt:104-111과 같은 방식 · 무음 변환 금지). **A 확정만**(`current` 포함 · 새 모드 넷). B·C·D 확정(LazyConfirmProcess.kt:61-67)의 23505는 매핑하지 않고 500으로 둔다 — 무음이 아니고(5xx로 드러남), B·C·D는 K2 좌석 잠금(LazyConfirmProcess.kt:44)이 같은 좌석 확정을 직렬화해 두 번째가 409 SEAT_NOT_HELD로 먼저 끝나므로 23505는 '가드가 뚫렸다'는 뜻일 때만 난다(§인터뷰 범위 'B·C·D는 ADR-014 최소 가드 그대로' — 받아들인 한계).
- **만료 뒤 전환된 성공(관측만 — 판정은 바꾸지 않는다)**: 만료 판정 시각은 `:now`(요청 시작 · 앱 Clock — §인터뷰 사용자 답)를 그대로 둔다. 대신 성공한 확정 중 **전환 시각이 그 홀드의 `expires_at` 뒤**인 것을 따로 센다 — 지표 `seat.confirm.converted_after_expiry{clock}`:
  - `clock=db`: 홀드를 지우는 전환 문장이 `RETURNING …, clock_timestamp() > expires_at AS late`로 같이 돌려준다(① C1 · ④ C1 · ③ O2 · ② P4 — `clock_timestamp()` = 그 문장을 실행한 DB 시각 · 트랜잭션 시작 시각인 `now()`가 아니다). 새 모드만.
  - `clock=app`: 커밋 뒤(`TransactionSynchronization.afterCommit`) 앱 `clock.instant() > expires_at`. 모든 A 모드(`current` 포함 — `current`는 `ProductSeat.confirm` 앞에 찾은 홀드의 `expiresAt`(SeatHold.kt:20)을 들고 있다가 커밋 뒤 비교 · SQL은 바꾸지 않는다).
  - 기대: 0이 아닐 수 있다(만료 직전 시작 · 지연 주입 — 설계상 허용되는 성공). 이것은 '이중 결과'가 아니라 **`:now` 규칙이 허용한 창의 크기**다 — 측정에서 모드별 분포(건수 · `전환 시각 − expires_at` 최대)를 남긴다. 두 시계 차는 `[구현 검증]` #12.
- **잠금 순서(A 전체)**: 기다리는 잠금은 **쿼터 행 → 홀드 행 → 좌석 행** 순(배치가 이 순서 — 설계-1). 확정은 쿼터 행을 잡지 않으므로 **홀드 행 → 좌석 행 → (예약 INSERT FK 검사: 회차 · 좌석 `FOR KEY SHARE`)**. 선점의 좌석 잠금은 NOWAIT라 기다리지 않는다. 초안 §함께 검증할 경합의 '사용자(advisory) → 좌석 → 홀드'는 기본 조합(쿼터 행 — advisory 아님)과 A 배치 flush 순서(홀드 → 좌석)에 맞지 않는다 — 확정이 좌석을 먼저 잡으면 배치와 교착한다(§3.2 · 열린 질문 Q2).

#### 3.1 ① 조건부 문장 — 전환 문장의 WHERE가 곧 확인 (`seat.confirm.mode=conditional`)

- 데이터 모델: 변경 없음.

```sql
-- C1 홀드 소비 = 확인(내 것 · 만료 전) — 조건이 모두 홀드 행 자신의 열이라 EPQ가 전부 다시 본다(하위 질의 없음)
DELETE FROM seat_hold WHERE id = :hold AND user_id = :u AND expires_at > :now
RETURNING seat_id, schedule_id, clock_timestamp() > expires_at AS late;   -- late → converted_after_expiry{clock=db}
--   0행 → C1d 진단(잠금 없음): SELECT user_id, expires_at FROM seat_hold WHERE id = :hold
--         없음 → 404 · 남의 것 → 403 · expires_at <= :now → 409 HOLD_EXPIRED
--         '내 것 · 활성'(설계상 생기지 않는다) → ERROR 로그 + 지표 seat.confirm.unexpected + 409 HOLD_EXPIRED(무음 아님)
-- C2 좌석 전환 = 확인(HELD) — 0행 → 409 SEAT_NOT_HELD(C1 롤백)
UPDATE product_seat SET status = 'RESERVED' WHERE id = :seat AND status = 'HELD';
-- C3 예약(created_at = :now — 지금과 같다) — 23505(§설계-4 인덱스) → 409 SEAT_NOT_HELD + 지표
INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at)
VALUES (:s, :seat, :u, :paymentUid, 'CONFIRMED', :now) RETURNING id;
```

- 잠금 순서: 홀드 행(C1) → 좌석 행(C2) → FK 검사(C3). C1을 C2보다 먼저 두는 이유: ⓐ 배치와 같은 순서(교착 없음) ⓑ 에러 순서 HOLD_EXPIRED → SEAT_NOT_HELD를 문장 순서가 그대로 만든다.
- 왜 충분한가(A 불변식 ② 'HELD ⇔ 홀드 정확히 1개' 위 — ADR-000 §2.1): 기본 조합 A에서 좌석이 다시 선점되려면 먼저 배치가 그 좌석의 홀드(내 것)를 지워야 한다(선점은 `AVAILABLE`만 잡는다). 그 삭제가 C1보다 먼저 커밋됐으면 C1은 0행(FM1 → 404). C1이 먼저면 C1이 홀드 행을 쥐어 배치 DELETE가 기다린다 → 그동안 좌석은 `AVAILABLE`이 될 수 없다. 홀드 id는 BIGSERIAL이라 다시 쓰이지 않아(V1__init.sql:25) Y의 새 홀드가 내 id로 보이는 ABA(값이 A → B → A로 돌아와 변경을 못 알아채는 문제)가 없다. 같은 홀드 이중 확정(FM2)은 뒤 쪽 C1이 앞 쪽 커밋을 기다린 뒤 0행 → 404.
- 배치와(이 모드에서는 A′ 배치 — §3.6): FM3(확정이 먼저)에서 A′의 조건부 DELETE는 홀드 행을 기다린 뒤 0행 → 그 쌍은 감소 0 · 좌석은 되돌림 대상에 들지 않는다 → **틱은 커밋**(정정 — 초안의 '틱 전체 롤백이 남는다'는 A 배치 무변경 전제였다). 배치가 먼저면 C1 0행 → 404(지금 A의 '배치 뒤 확정 = 404'와 같은 응답).
- 문장 수: 성공 3(지금 A2 · A3 · A5 · A6 · A7 = 5 — 추정 `[구현 검증]` #2) · 실패 +1(진단).
- 되돌리기: 손잡이를 `current`로(스키마 없음).

#### 3.2 ② 비관락 — 홀드 · 좌석 `FOR UPDATE` 뒤 확인 · 전환 (`seat.confirm.mode=pessimistic`)

- 데이터 모델: 변경 없음.

```sql
-- P0 홀드 찾기(잠금 없음) — 없음 404 · 남의 것 403(남의 홀드 행을 잠그지 않으려고 잠금 전에 거른다 — LazyConfirmProcess.kt:38-40 K1과 같다)
SELECT seat_id, schedule_id, user_id FROM seat_hold WHERE id = :hold;
-- P1 홀드 행 잠금 + 새 판정 — 행 없음(그 사이 배치 · 다른 확정) → 404 · expires_at <= :now → 409 HOLD_EXPIRED
SELECT expires_at FROM seat_hold WHERE id = :hold FOR UPDATE;
-- P2 좌석 행 잠금 — status <> 'HELD' → 409 SEAT_NOT_HELD
SELECT status FROM product_seat WHERE id = :seat FOR UPDATE;
-- P3~P5 전환 — 두 행을 쥐었으므로 조건 없이 쓴다. P4는 1행이어야 한다(아니면 ERROR + 롤백 — 설계상 생기지 않는다)
UPDATE product_seat SET status = 'RESERVED' WHERE id = :seat;
DELETE FROM seat_hold WHERE id = :hold RETURNING clock_timestamp() > expires_at AS late;
INSERT INTO reservation (…) VALUES (…) RETURNING id;     -- C3과 같다
```

- **잠금 순서 = 홀드 → 좌석**(초안 '좌석 → 홀드'를 바꾼다 — Q2). 좌석을 먼저 잡으면: ① ②가 좌석 S를 잠금 ② 배치 flush가 내 홀드 h를 DELETE(행 잠금 획득) 뒤 좌석 S UPDATE에서 ②를 기다림 ③ ②의 P1이 h에서 배치를 기다림 → **고리(40P01 — 교착 탐지로 한쪽 롤백)**. 배치가 홀드 → 좌석 순서로 쓰기 때문이다(설계-1 — 추정 `[구현 검증]` #2·#3). ADR-014 B·C·D 확정(`LazyConfirmProcess` — 좌석 K2 → 홀드 K3)은 좌석 → 홀드지만, 그쪽 배치는 좌석을 `SKIP LOCKED`로 먼저 잡거나(C) 좌석을 쓰지 않아(D) 고리가 없다(ADR-014 §3.2) — A 배치는 그렇지 않다. 재현 테스트에 '좌석 → 홀드' 대조판을 넣어 40P01이 나는 것을 한 번 보인다(§설계-7 #9).
- ①과의 차이: 확인을 문장 조건이 아니라 잠근 뒤 메모리에서 한다. 잠금 읽기 2문장이 늘고(성공 6), 좌석 잠금을 P2부터 커밋까지 쥔다 — 그동안 같은 좌석 선점은 NOWAIT로 진다(어차피 `HELD`라 선조회 409가 먼저다 — UserLimitStrategies.kt:340).
- 배치와(A′): P1이 먼저면 A′ DELETE가 기다린 뒤 0행 → 그 쌍만 건너뛰고 틱 커밋(①과 같다). 배치가 먼저면 P1 행 없음 → 404.
- 되돌리기: 손잡이.

#### 3.3 ③ 낙관락 — 좌석 버전 비교 (`seat.confirm.mode=optimistic`)

- **지금 버전은 아무도 올리지 않는다**: `product_seat.version`(V3)은 엔티티에 매핑돼 있지 않고(V3__add_seat_version.sql:1-2 — '@Version을 달면 모든 전략에 낙관락 검사가 걸려 기준선이 바뀐다'), 올리는 곳은 ADR-003 낙관락 선점 전략뿐이다(DbLockHoldStrategies.kt:74-93 · ProductSeatRepository.kt:44-51). 기본 조합의 선점 · 배치 · 확정은 버전을 올리지 않는다 → 확정만 버전을 비교하면 FM1(배치 `AVAILABLE` → Y `HELD` — 상태가 `HELD`로 돌아오는 ABA)을 못 본다.
- 버전을 올릴 방법(Q3):

| 안 | 내용 | 판단 |
|---|---|---|
| (가) `ProductSeat`에 `@Version` 매핑 | JPA가 모든 좌석 UPDATE에 `version = version + 1 … WHERE version = ?`를 붙인다 | **기각** — 손잡이와 무관하게 모든 전략 · 경로의 SQL이 바뀐다(V3 주석). ADR-003 낙관락 선점은 버전을 먼저 읽고 flush 뒤 `bumpVersion(expected)`를 하는데(DbLockHoldStrategies.kt:86-91) JPA가 먼저 올리면 늘 0행 → 늘 진다. 범위(A 확정만) 밖 |
| (나) 상태가 바뀔 때 버전을 올리는 DB 트리거 — 새 **선택 위치** `db/migration-confirm-version`의 **V14**(15-01b에서 V13 → V14 — 기본 위치의 V12 · V13 위에 두어, 나중에 이 위치를 더해도 '적용된 버전보다 낮은 미적용'이 되지 않게 · §설계-4 번호): `BEFORE UPDATE OF status ON product_seat FOR EACH ROW WHEN (OLD.status IS DISTINCT FROM NEW.status)` → `NEW.version := OLD.version + 1` | 선점 · 배치 · 확정(JPA · JDBC 모두) 코드를 고치지 않고 덮는다. ③ 셀에만 위치를 더한다 — 트리거 비용이 곧 ③의 비용이다 | **권장** — 기동 검사로 짝을 맞춘다(§설계-5 · V4·V8과 같은 방식 — HoldStrategyStartupCheck.kt:97-104 · :122-132) |
| (다) 선점(HoldSeatProcess) · 배치(ExpireHoldsService)에 버전 증가 문장 추가 | 손잡이로 켬 | 차선 — 범위 밖 코드 두 곳을 고친다 · 앞으로 새 쓰기 경로가 빠뜨릴 수 있다 |

```sql
-- O1 한 문장 = 한 스냅숏(잠금 없음) — A 순서로 판정: 없음 404 · 남의 것 403 · expires_at <= :now 409 HOLD_EXPIRED · status <> 'HELD' 409 SEAT_NOT_HELD
SELECT h.seat_id, h.schedule_id, h.user_id, h.expires_at, s.status, s.version
FROM seat_hold h JOIN product_seat s ON s.id = h.seat_id WHERE h.id = :hold;
-- O2 홀드 삭제 — 0행(그 사이 배치 · 다른 확정) → 404. 순서: 홀드 → 좌석(①과 같은 이유 — 배치와 교착 없음)
DELETE FROM seat_hold WHERE id = :hold RETURNING clock_timestamp() > expires_at AS late;
-- O3 버전 비교 전환(트리거가 version을 올린다) — 0행 → C1d와 같은 진단 뒤 좌석을 다시 읽어 코드 결정(대개 409 SEAT_NOT_HELD)
UPDATE product_seat SET status = 'RESERVED' WHERE id = :seat AND version = :v;
-- O4 예약 = C3
```

- 재시도 없음(진 확정은 거부 — ADR-003 낙관락 선점과 같은 원칙 · DbLockHoldStrategies.kt:67-72 주석).
- **정직한 한계**: A에서는 O2의 행 수 검사만으로 FM1·FM2가 막힌다(홀드 id 재사용 없음 — ①과 같은 논리). O3의 버전은 '홀드 행 정체성 없이 좌석 쪽만으로도 막는다'는 이중 확인이다. 그래서 ③이 ①을 이기는 길은 비용뿐이고, 트리거가 선점 · 배치의 좌석 UPDATE마다 붙어 ③ 셀의 선점 비용도 늘어난다(추정 `[구현 검증]` #7). 사용자 결정(4개 전부 측정)대로 그대로 잰다.
- 되돌리기: 손잡이를 `current`로. V14 트리거는 남아도 기본 조합에 무해하다(버전을 읽는 곳이 없다) — 단 낙관락 선점 전략과는 함께 띄우지 않는다(기동 거부 — §설계-5). **정정**: 되돌릴 때 위치 `classpath:db/migration-confirm-version`을 목록에서 **빼지 않는다** — 이미 적용된 V14가 위치에 없으면 Flyway 검증이 '적용됐는데 위치에 없는 버전'으로 기동을 멈춘다(HoldStrategyStartupCheck.kt:52-54 주석의 Flyway 거부 갈래). 위치는 남기고 모드만 바꾼다 — 그래서 짝 검사는 '위치가 있으면 낙관락 선점 금지'로 둔다(§설계-5).

#### 3.4 ④ 결제 중 홀드 연장 (`seat.confirm.mode=conditional-extend`)

- **'결제 시작'의 정의**: 이 랩에는 결제 단계가 없다(ADR-000 §6 — 확정 API가 결제 결과 `paymentUid`를 받는다 · ReservationController.kt:20-35). 그래서 '결제 시작' = **클라이언트가 사용자를 결제 화면(PG — 결제 대행사)으로 보내기 직전에 부르는 새 API**로 정의한다:
  - `POST /api/holds/{holdId}/payment-start` · 헤더 `X-User-Id` · 본문 없음 → `200 {holdId, expiresAt}` · 실패 `404` HOLD_NOT_FOUND · `403` HOLD_NOT_OWNED · `409` HOLD_EXPIRED(만료 또는 아직 연장 전인데 남은 시간 ≤ 연장 하한 — 새 에러 코드를 두지 않는다 · §인터뷰 Q5). 이 손잡이 값에서만 등록한다(`@Profile("!reconciler")` — 지금 컨트롤러와 같게).
  - **재호출 멱등 = 첫 연장만 반영**(§인터뷰 '설계 선검증 뒤' 사용자 답): 한 홀드는 **많아야 한 번** 연장된다. 재호출은 연장하지 않고 지금 `expires_at`을 200으로 돌려준다.
  - 손잡이: `seat.confirm.payment-window`(연장 뒤 남는 시간 · 기본값은 측정 task가 고정) · `seat.confirm.extend-min-remaining`(연장을 받는 최소 남은 시간 · 초안 5초).
- **데이터 모델(정정 — 초안 '열 추가 없음'을 바꾼다)**: 표지 열 하나를 더한다 — 새 마이그레이션 **V13 · 기본 위치**(추가형 · 모든 구성):

```sql
-- V13__seat_hold_extended_at.sql — classpath:db/migration
-- ADR-015 ④: 첫 연장 시각(NULL = 연장 안 함). 기존 행은 NULL(연장 안 한 홀드 — 뜻이 맞다). 기본값 없음 · NULL 허용이라 PostgreSQL은 표를 다시 쓰지 않는다(카탈로그만 · 짧은 ACCESS EXCLUSIVE)
ALTER TABLE seat_hold ADD COLUMN extended_at TIMESTAMPTZ NULL;
```

  - 엔티티 `SeatHold`(SeatHold.kt:16-24)에는 매핑하지 않는다 — `ddl-auto: validate`(application.yml:12)는 매핑한 열만 보므로 더한 열에 걸리지 않고, JPA 선점 INSERT는 이 열을 쓰지 않아 NULL로 들어간다(추정 `[구현 검증]` #14). 이 열을 쓰는 곳은 E1 하나 · 읽는 곳은 E1 진단 · A′ 배치 B3 `RETURNING` · 대조 배치 · 판정 문장뿐이다.
  - 번호: V12(유니크 인덱스) → **V13(이 열)** → V14(③ 트리거 · 선택 위치). 기본 위치 둘을 선택 위치보다 낮게 둔다(§설계-4 번호).
  - 기본 위치라 생기는 일: ADR-013 전환 · 선택 위치 추가 때의 Flyway 순서 함정이 V12와 같이 생긴다(§설계-4 — 같은 처리).

```sql
-- E1 첫 연장(홀드 행만 · 좌석 · 쿼터 무관) — 내 것 · 활성 · 아직 연장 안 함 · 연장이 실제로 늘리는 경우만
UPDATE seat_hold
   SET expires_at = :now + :window, extended_at = :now
 WHERE id = :hold AND user_id = :u
   AND extended_at IS NULL
   AND expires_at > :now + :minRemaining
   AND expires_at < :now + :window
RETURNING expires_at;
-- 상한: :now < expires_at(원래) − minRemaining 이고 한 번뿐이라 새 expires_at < 원래 expires_at − minRemaining + window ≤ held_at + TTL + window — 따로 계산하지 않는다(초안의 LEAST 식 삭제)
-- 동시 두 첫 호출: 뒤 쪽 UPDATE가 앞 쪽 행 잠금을 기다린 뒤 EPQ가 extended_at IS NULL을 최신 버전으로 다시 봐 0행 → 아래 진단 ③ → 200(첫 연장 값)
--   0행 → E1d 진단(잠금 없음): SELECT user_id, expires_at, extended_at FROM seat_hold WHERE id = :hold — 위에서부터 첫 일치
--     ① 없음 → 404 HOLD_NOT_FOUND
--     ② 남의 것 → 403 HOLD_NOT_OWNED
--     ③ expires_at <= :now → 409 HOLD_EXPIRED(연장했어도 이미 지난 홀드)
--     ④ extended_at IS NOT NULL → 200 {expiresAt = 지금 값}(멱등 재호출 · 지표 seat.confirm.extend{result=already})
--     ⑤ expires_at <= :now + :minRemaining → 409 HOLD_EXPIRED(연장하기엔 늦음 · result=too_late)
--     ⑥ expires_at >= :now + :window → 200 {expiresAt = 지금 값}(남은 시간이 이미 window 이상 — 연장이 늘리지 않으므로 아무것도 쓰지 않고 **표지도 남기지 않는다** · result=not_needed · 뒤의 재호출이 연장을 받을 수 있다 — Q16)
--     ⑦ 그 밖(내 것 · 활성 · 연장 전 · 범위 안 — 설계상 생기지 않는다) → ERROR 로그 + seat.confirm.unexpected + 409 HOLD_EXPIRED(무음 아님)
--   성공 → result=extended
```

- **확정 문장 = ① C1~C3**(§인터뷰 Q4). 연장만으로는 확인과 전환 사이 경합이 없어지지 않는다(늦은 확정 · 이중 확정은 그대로) — ④는 '결제 중 만료' 발생 수를 줄이는 장치이고 원자성은 ①이 맡는다. '지금 확정(current) + 연장'은 연장 효과만 따로 보고 싶을 때의 대조 셀 후보(측정 task 판단 — 그 셀은 `current`라 A′ 배치가 돌지 않으므로 FM5 창이 열린다는 점을 함께 적는다).
- **배치와의 창(연장을 잃는 경우 — FM5)**: 지금 JPA 배치는 고른 홀드를 id로만 지운다(만료 조건을 다시 보지 않는다 — ProductSeat.kt:76-86 · :40 고아 삭제). 이 모드에서는 **A′ 배치**(§3.6)가 돈다 — B3 조건부 DELETE의 `expires_at <= :now`가 홀드 행 자신의 열이라, 배치가 후보로 읽은 뒤 연장이 커밋됐으면 EPQ가 연장된 값을 다시 보고 지우지 않는다(연장 커밋 전이면 E1의 행 잠금을 B3이 기다린 뒤 같은 재확인). 그래서 A′ 아래에서 연장을 잃는 창은 **구조적으로 닫힌다**(추정 — 래치 테스트 `[구현 검증]` #8).
- **`extension_lost`를 보는 법(표지 열이 있어 보인다)**:
  - 서버(배치 쪽): 홀드를 지우는 배치 문장이 `RETURNING extended_at IS NOT NULL AS extended, expires_at > :now AS lost`를 함께 돌려준다 → 지표 `seat.hold.expiry.deleted{extended=true|false}` · `seat.confirm.extension_lost`(= `lost` 참인 지운 행 수). A′에서는 WHERE가 `expires_at <= :now`라 `lost`는 늘 거짓(구조적 0) — 이 값이 뜻을 갖는 곳은 **대조 배치**(id로만 지움 — §설계-8.1)다: 대조에서 ≥ 1 · A′에서 0이어야 '창을 닫았다'가 증명된다. `extended=true`(연장했는데도 만료돼 지워짐)는 잃음이 아니라 'window가 결제 시간보다 짧았다'의 신호다(측정에서 따로 센다).
  - 클라이언트(k6 — §설계-8.2): `payment-start` 200이 준 `expiresAt`보다 앞(여유 1초)에 보낸 확정이 404 · 409 HOLD_EXPIRED면 `ext_then_lost` +1(서버 지표와 교차 확인 · 기대 0).
  - 판정(끝 · DB 직접): `SELECT count(*) FILTER (WHERE extended_at IS NOT NULL), max(expires_at - held_at) FILTER (WHERE extended_at IS NOT NULL) FROM seat_hold` — 살아 있는 연장 홀드 수 · 최대 수명(상한 `TTL + window` 이하여야 한다 — N9).
- 잠금: 홀드 행 하나(E1). 배치가 그 행을 기다릴 수 있으나 연장은 아무것도 기다리지 않는다 → 고리 없음.
- 카운터: 없음(홀드 행 수 그대로). 대가: 연장된 홀드는 매수 한도와 좌석을 더 오래 쥔다 — 지표(홀드 수명 분포 · 만료로 풀린 좌석 수).
- 되돌리기: 손잡이(API가 사라진다 — 클라이언트는 404를 받는다 · 확정은 연장 없이도 동작). V13 열은 남는다(무해 — `current`는 읽지도 쓰지도 않는다).

#### 3.5 잠금 순서 · 교착 점검표 (A · 기본 조합 — 15-01b 다시 점검: 확정 ①~④ · current · A′ 배치 · JPA 배치 · 선점 · 정리 ① · 연장)

| 경로 | 기다리는 잠금(순서) | 기다리지 않는 잠금 |
|---|---|---|
| 선점 | 회차(공유) → 자기 쿼터 행 | 좌석 NOWAIT · 새 홀드 행(자기 INSERT) |
| 만료 배치(JPA — `current`) | 쿼터 행(정렬) → 홀드 행 → 좌석 행 (추정 #2) | — |
| 만료 배치(A′ — 새 모드) | 쿼터 행(정렬 · B2 `FOR UPDATE OF q`) → 홀드 행(B3 DELETE) → 좌석 행(B5 UPDATE) | — (회차 · FK 잠금 없음: 홀드 DELETE는 참조하는 쪽이고 B5는 FK 열을 바꾸지 않는다) |
| 정리 ① | 회차(배타) → (홀드가 있으면 멈춤 · 없으면 쿼터 행 삭제) | — |
| 확정 current | FK 검사(회차 · 좌석 KEY SHARE) → 홀드 행 → 좌석 행 (추정 #2) | — |
| ① · ③ · ④ 확정 | 홀드 행 → 좌석 행 → FK 검사 | — |
| ② 확정 | 홀드 행 → 좌석 행 → FK 검사 | — |
| ④ 연장 | 홀드 행 | — |

| 짝 | 고리가 닫히나 | 이유 |
|---|---|---|
| 확정(①~④) ↔ A′ 배치 | 아니다 | 같은 방향(홀드 → 좌석). 확정은 쿼터 행을 잡지 않으므로 B2가 확정을 기다리게 할 수 없다. 좌석 KEY SHARE(FK)는 B5의 좌석 UPDATE(`FOR NO KEY UPDATE` 급)와 충돌하지 않는다 (추정 #4) |
| 확정 current ↔ JPA 배치 | 아니다 | 지금과 같다(무변경 — `current` 모드에서는 A′가 돌지 않는다) |
| ② '좌석 → 홀드' 판 ↔ 배치(JPA · A′ 모두) | **닫힌다** | §3.2 — 그래서 고르지 않는다(대조 테스트로만) |
| A′ 배치 ↔ 선점 | 아니다 | B2가 Y의 쿼터 행을 기다릴 수 있으나 Y는 쿼터 행 뒤에 NOWAIT(좌석)만 있어 기다리지 않고 끝난다. 선점은 A′가 쥔 홀드 행 · 좌석 행을 기다리지 않는다(NOWAIT) |
| A′ 배치 ↔ 정리 ① | 아니다 | A′는 회차 행을 잡지 않는다. 정리 ①은 A′가 커밋 전이면 지운 홀드가 아직 보여(RC) 쿼터 행을 지우지 않고 끝나고(QuotaCleanup.kt:133-136), 커밋 뒤면 잠금이 없다 (추정 — 동시성 테스트 #9) |
| A′ 배치 ↔ 연장 | 아니다 | 연장은 홀드 행 하나만 쥐고 아무것도 기다리지 않는다. B3이 그 행을 기다린 뒤 EPQ로 재확인(FM5 — 지우지 않음) |
| A′ 배치 ↔ A′ 배치 | 해당 없음 | 만료 틱은 앱 1대 · 한 스레드(ExpiryCounters.kt:42-47 주석) — 여러 대가 되면 B2 정렬 잠금이 같은 순서를 지킨다 |
| 확정 ↔ 선점 | 아니다 | 선점의 좌석 잠금은 NOWAIT(확정이 쥔 좌석이면 즉시 409). 확정이 기다리는 좌석을 선점이 쥐었다면 선점은 그 뒤 기다리는 것이 없다(새 홀드 INSERT의 FK는 회차 · 좌석 — 자기 쥔 행 · 회차 공유) |
| 확정 ↔ 정리 ① | 아니다 | 확정이 홀드 행을 쥔 채 FK 검사로 회차를 기다려도, 정리 ①은 홀드가 보이면(지워졌어도 커밋 전이라 보인다 — RC) 쿼터 행을 지우지 않고 끝난다(QuotaCleanup.kt:133-136) → 정리 ①이 기다리는 행이 없다 (추정 — 동시성 테스트 #9) |
| 확정 ↔ 확정(같은 홀드) | 아니다 | 뒤 쪽이 홀드 행에서 기다린 뒤 0행(`current` + V12면 뒤 쪽이 예약 INSERT의 유니크 검사에서 기다린 뒤 23505 — FM2) |
| 확정 current ↔ 선점 | 아니다(관찰 사항) | 확정의 A5 FK KEY SHARE가 커밋까지 좌석 행에 남아 그동안 같은 좌석 선점의 `FOR UPDATE NOWAIT`가 진다(55P03 → 409 nowait) — Hibernate의 `PESSIMISTIC_WRITE`가 `FOR UPDATE`면 그렇고 `FOR NO KEY UPDATE`면 아니다 (추정 #4) |

#### 3.6 A′ 배치 — A 만료 배치 최소 수정 (`seat.confirm.mode ≠ current`일 때만)

- **범위(사용자 재합의 2026-11-04)**: 배치가 지울 때 만료 조건을 다시 확인하는 조건부 DELETE · 좌석은 `status='HELD'`일 때만 되돌림 · 행 수로 판정 · 카운터 감소 = 실제로 지운 행. **틱 하나 = 트랜잭션 하나는 그대로**(ExpireHoldsService.kt:35-57의 틱 경계 · 지표 · `afterRollback` skipped 기록 그대로 — 바꾸는 것은 :38-45 트랜잭션 몸통뿐). 틱 전체 롤백 개선(쌍 하나의 실패가 틱 전체를 되돌리는 것)은 **NEXT**.
- **어느 모드에 적용하나 — 권장: 확정 손잡이에 묶는다(`current`면 지금 JPA 배치 · 그 밖 네 모드면 A′)**. 까닭:
  1. '기존 동작 보존' — `current` 셀이 측정의 기준선이다. 배치를 모든 모드에 바꾸면 기준선 칸이 '지금 코드'가 아니게 되고, `current`의 FM3 결과(우연한 가드 — 틱 롤백)를 더는 볼 수 없다.
  2. 새 모드의 N5(배치와 겹쳐도 예약 좌석이 `AVAILABLE`이 되지 않음)는 A′가 강제한다 — 새 모드가 JPA 배치와 짝지어지면 다시 우연한 가드에 기댄다. 손잡이 하나에서 둘을 함께 정하면 잘못된 짝이 생길 수 없다(따로 두면 조합표 · 기동 거부가 늘어난다).
  3. 대가: 새 모드 셀의 비용에 배치 차이가 섞인다 — 측정에서 배치 틱 시간 · 문장 수를 셀마다 따로 남겨 가른다(§설계-8.2). 배치만 따로 켜는 손잡이는 두지 않는다(Q15 — 필요해지면 그때).
- 기본 조합 전제: 새 모드는 `counter-upsert-precheck`에서만 뜨므로(§설계-5 규칙 2) A′의 카운터는 counter 계열 감소 하나만 다룬다. `expiry-repair=false`(옛 경로)도 받는다 — `decrementDeleted`가 그 갈래를 이미 가진다(ExpiryCounters.kt:123-127 · MISSING이면 :152 `check`로 틱 실패 — 지금 `legacyDecrement`(:89-98)와 같은 뜻).

```sql
-- B0 :now = clock.instant() — 앱 Clock(A 규칙 · 지금 ExpireHoldsService.kt:39와 같은 자리). DB now()를 쓰지 않는다(C·D 배치와 다른 점 — LazyExpiryBatch.kt:111)
-- B1 후보 쌍(잠금 없음 · idx_seat_hold_expires_at)
SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE expires_at <= :now ORDER BY schedule_id, user_id;
-- B2 쌍 잠금(정렬 · 기다림) — LazyExpiryBatch.lockPairs와 같은 문장(LazyExpiryBatch.kt:182-193) → 잠근 쌍 P
-- B2b MISSING(후보 − P): 지울 id를 읽어 ExpiryCounters.repairMissingBeforeDelete(ExpiryCounters.kt:151-160)로 지우기 앞에서 복구(D3과 같다 — LazyExpiryBatch.kt:165-171 · 시각만 :now)
-- ── 래치 L-B′(테스트 전용 이음새 — '후보 읽기 · 쌍 잠금 뒤 · 첫 DELETE 앞' · §설계-8.1) ──
-- B3 조건부 삭제 = 만료 재확인(홀드 행 자신의 열 — EPQ가 최신 버전으로 다시 본다)
DELETE FROM seat_hold h USING unnest(:s::bigint[], :u::bigint[]) AS p(schedule_id, user_id)
 WHERE h.expires_at <= :now AND h.schedule_id = p.schedule_id AND h.user_id = p.user_id
RETURNING h.seat_id, h.schedule_id, h.user_id, h.extended_at IS NOT NULL AS extended, h.expires_at > :now AS lost;
-- B3b MISSING 쌍: DELETE FROM seat_hold WHERE id = ANY(:missingIds) AND expires_at <= :now RETURNING …(같은 열)
--     지운 수 ≠ 넘긴 수 → 틱 롤백(MissingMismatch — LazyExpiryBatch.kt:211-216과 같다 · PairRepair가 이미 덮은 cnt가 틀리므로 · 남은 틱 롤백 원인 — NEXT)
-- B4 감소 = B3이 돌려준 쌍별 행 수(ExpiryCounters.decrementDeleted — ExpiryCounters.kt:121-143 · n = 0이면 아무것도 안 함 :122 · 같은 트랜잭션)
-- B5 좌석 되돌림 — B3 · B3b가 실제로 지운 홀드의 좌석만 · HELD일 때만 · 다른 활성 홀드가 없을 때만
UPDATE product_seat st SET status = 'AVAILABLE'
 WHERE st.id = ANY(:deletedSeats) AND st.status = 'HELD'
   AND NOT EXISTS (SELECT 1 FROM seat_hold o WHERE o.seat_id = st.id AND o.expires_at > :now)
RETURNING st.id;
--   판정: reverted = 돌려받은 행 수. :deletedSeats − reverted는 잠금 없는 진단 한 문장으로 갈래만 고른다 —
--     status <> 'HELD'(not_held) · HELD인데 다른 활성 홀드(kept_active) → 둘 다 A 불변식 ②(HELD ⇔ 홀드 1개) 위반이라 설계상 0 · 0이 아니면 WARN + 지표(무음 아님) · 틱은 커밋
--   지표 seat.hold.expiry.revert{path=sweep,result=reverted|not_held|kept_active}(revertSeats 지표와 같은 이름 — LazyHoldProcess.kt:197-198 · 시각만 :now라 ExpiryRule.ACTIVE_ON_SEAT(DB now() — ExpiryRule.kt:10)는 쓰지 않는다)
```

- **카운터 = 실제로 지운 행**: 지금은 감소(JDBC 즉시)가 flush DELETE보다 앞이라 '고른 행' 기준이다(§설계-1). A′는 B3의 `RETURNING`을 센 뒤 B4에서 내린다 — FM3(확정이 먼저 홀드를 지움)이면 그 쌍은 0행 · 감소 0이고, 확정이 홀드 −1 · 예약 +1을 했으므로 cnt = 홀드 + 확정이 그대로 맞는다. 잠금 순서는 B2(쿼터) → B3(홀드) → B5(좌석)로 지금 배치와 같다(감소 문장이 B3 뒤로 옮겨 가도 쿼터 행 잠금은 B2에서 먼저 쥔다 — ADR-014 §2.4 규칙 ①과 같은 뜻).
- **FM3 · FM5에서**: B3이 확정 · 연장이 쥔 홀드 행을 기다린 뒤 0행(확정이 지움) · 지우지 않음(연장이 늘림) → 그 쌍 · 좌석만 건너뛰고 **틱 커밋**. 이것으로 'FM3 때문에 틱 전체 롤백'은 새 모드에서 사라진다. 남는 틱 롤백 원인(B3b 불일치 · 40P01 · CHECK)은 지표로 재고 NEXT.
- **구현 형태(권장)**: 새 클래스(예: `SweepConditionalBatch`)가 `LazyExpiryBatch`의 쌍 잠금 · MISSING · 감소 갈래를 그대로 쓰고(공용 함수로 꺼내기 — 사다리 ② 재사용) 시각만 `:now` 인자로 받는다. `ExpireHoldsService.expire`는 :38 트랜잭션 몸통에서 모드로 가른다(`current`면 :39-44 그대로). 측정 주입(`afterExpiryDecrement` — ExpiryCounters.kt:60)은 A′ 경로에 없다(`decrementDeleted`는 부르지 않는다 — :117 주석) → `inject-app.enabled=true` + `mode ≠ current`는 기동 거부(§설계-5 규칙 5).
- 되돌리기: 손잡이를 `current`로(스키마 없음 — A′는 V13 열을 읽기만 한다).

### 설계-4 최후 방어선 — 확정 예약 부분 유니크 인덱스

```sql
-- V12__reservation_confirmed_seat_unique.sql — 기본 위치 classpath:db/migration(모든 구성에 적용 — §인터뷰)
-- 같은 좌석의 CONFIRMED는 1건(CANCELED 이력은 막지 않는다). 기존 중복이 있으면 이 문장이 23505로 실패하고 Flyway가 기동을 멈춘다(조용히 건너뛰지 않는다 — IF NOT EXISTS 쓰지 않음)
CREATE UNIQUE INDEX uq_reservation_confirmed_seat ON reservation (seat_id) WHERE status = 'CONFIRMED';
```

- **번호**: 기존 최대는 V11(선택 위치 `db/migration-seat-map` — V11__seat_map_index.sql) → **V12**(이 인덱스 · 기본 위치) · **V13**(④ 표지 열 `seat_hold.extended_at` · 기본 위치 — §3.4) · **V14**(③ 버전 트리거 · 선택 위치 `db/migration-confirm-version` — §3.3). 기본 위치 파일은 V1·V2·V3·V5·V6·V7(+V12·V13), 선택 위치는 V4(unique) · V8(trigger) · V9(두 판 — migration-updated-at · migration-updated-at-trigger) · V10(incremental) · V11(seat-map)(+V14). 선택 위치 V14를 기본 위치 V12·V13보다 위에 두는 까닭: 나중에 ③을 켜려고 위치를 더해도 '적용된 버전보다 낮은 미적용'(아래 순서 함정)이 되지 않는다.
- **막는 것 · 못 막는 것**: FM2(같은 좌석 CONFIRMED 2건)만 막는다. FM1(내 예약 1건 + 남의 홀드)과 FM3(예약 + 좌석 `AVAILABLE`)은 예약이 1건이라 이 인덱스로 보이지 않는다 — 원자성은 §설계-3이 맡고, 이 인덱스는 앱 로직이 뚫린 중복 확정만 받는다.
- **위반 응답**: 409 SEAT_NOT_HELD + ERROR 로그 + 지표 `seat.confirm.unique_violation`(기대 0 — 0이 아니면 앱 가드가 뚫린 것 · 측정에서 실패로 센다). 인덱스 이름과 SQLSTATE 23505가 모두 맞을 때만 바꾸고, 다른 무결성 오류는 그대로 올린다(DbLockHoldStrategies.kt:104-111과 같은 방식). **적용 = A 확정만, `current` 포함**(Q6 권장안 채택 — JPA 경로는 IDENTITY INSERT라 save 자리(ConfirmReservationService.kt:49-58)에서 예외가 난다 (추정 #13)). `current`에서는 FM2(같은 홀드 이중 확정)가 V12 아래에서 이 갈래로 끝나므로 `current` 셀의 `unique_violation`은 0이 아닐 수 있다 — 측정에서 '실패'로 세는 것은 새 모드 넷의 값뿐이다(§설계-8). **B·C·D 확정의 23505는 500 그대로**(매핑하지 않음 · 무음 아님 — §설계-3 공통 규칙 · 받아들인 한계).
- **적용 락(ADR-013 §7.3 원칙 '락을 쥐는 시간은 트랜잭션 경계가 정한다')**: 트랜잭션 안 `CREATE UNIQUE INDEX`는 `reservation`에 SHARE 락을 끝까지 쥔다 → 그동안 확정(예약 INSERT)이 멈춘다(선점 · 배치는 `reservation`을 쓰지 않는다). 운영 크기 사본에서 시간을 먼저 재고 `[구현 검증]` #5, 짧으면 Flyway 트랜잭션 안 그대로 · 길면 `CONCURRENTLY` 판(`V12__….sql.conf`의 `executeInTransaction=false` · 기동 검사가 `pg_index.indisvalid`를 본다 — ADR-013 §4.4 INVALID 규칙과 같은 뜻). **`CONCURRENTLY` 실패 뒤 재시도 절차**(운영 · 한 단계씩 확인): ① 앱을 띄우지 않은 채 `DROP INDEX CONCURRENTLY IF EXISTS uq_reservation_confirmed_seat`(INVALID 인덱스 제거 — 트랜잭션 밖) ② **`flyway repair`**(같은 위치 · 같은 SHA — 실패로 남은 V12 이력 행을 지운다. 지우지 않으면 다음 migrate가 '실패한 마이그레이션이 있다'로 멈춘다 — Flyway 11.7.2가 PostgreSQL의 비트랜잭션 실패를 이력에 남기는지 `[구현 검증]` #5) ③ R0 다시(실패 원인이 23505 중복이면 R1부터) ④ 앱 기동 → V12 다시 적용 → `indisvalid` 확인. ②를 건너뛰고 이력 표를 손으로 고치지 않는다.
- **기존 중복 사전 검사(운영 절차 · 배포 전 — ADR-013 원칙: 기존 데이터 사본에서 먼저)**:

```sql
-- R0 사전 검사 — 0행이어야 배포한다
SELECT seat_id, count(*) AS n, array_agg(id ORDER BY created_at, id) AS reservation_ids
FROM reservation WHERE status = 'CONFIRMED' GROUP BY seat_id HAVING count(*) > 1;
```

- **0행이 아니면 배포를 멈춘다 — 자동 복구하지 않는다**: 중복 확정 1건 = 결제한 사용자 둘이다(어느 쪽을 남길지는 사업 결정 · 남지 않는 쪽은 ADR-016 보상 대상). 사람이 고른 뒤 쓰는 복구 틀(한 트랜잭션 · 쿼터 행을 먼저 잠근다 — ADR-014 §2.4 규칙 ①과 같은 뜻):

```sql
-- R1 복구 틀(운영자가 :keep(남길 예약 id)과 :drop(취소할 예약 id 목록)을 정한 뒤) — 한 트랜잭션 · 차감(cnt − n)이 아니라 **쌍마다 V6 정의로 다시 센다**
-- ① 쌍 잠금(정렬 · 기다림) — 취소할 예약의 (회차, 사용자) 쌍
SELECT q.schedule_id, q.user_id FROM user_hold_quota q
 WHERE (q.schedule_id, q.user_id) IN (SELECT schedule_id, user_id FROM reservation WHERE id = ANY(:drop))
 ORDER BY q.schedule_id, q.user_id FOR UPDATE OF q;
-- ② 취소 — 영향 행 수 = cardinality(:drop)이어야 한다(아니면 ROLLBACK — 그 사이 누가 바꿨다)
UPDATE reservation SET status = 'CANCELED' WHERE id = ANY(:drop) AND status = 'CONFIRMED';
-- ③ 다시 세기(V6__backfill_user_hold_quota.sql:2-3 · :15-24와 같은 정의: cnt = 그 쌍의 seat_hold 행 전체 + reservation CONFIRMED) — ②를 본 같은 트랜잭션의 새 문장
UPDATE user_hold_quota q
   SET cnt = (SELECT count(*) FROM seat_hold h WHERE h.schedule_id = q.schedule_id AND h.user_id = q.user_id)
           + (SELECT count(*) FROM reservation r WHERE r.schedule_id = q.schedule_id AND r.user_id = q.user_id AND r.status = 'CONFIRMED')
 WHERE (q.schedule_id, q.user_id) IN (SELECT schedule_id, user_id FROM reservation WHERE id = ANY(:drop))
RETURNING q.schedule_id, q.user_id, q.cnt;                                                           -- 증분 방식이 stmt면 `, updated_at = now()`를 더한다(trigger면 V9 트리거가 쓴다)
--   쿼터 행이 없는 쌍(정리 ①이 지운 마감 회차 등)은 ③이 건드리지 않는다 — 카운터를 쓰지 않는 쌍이다(행을 새로 만들지 않는다)
-- R2 확인(하나라도 어기면 배포하지 않는다): R0 = 0행 · v_counter_mismatch = 0 · v_reserved_without_confirmed = 0 · v_confirmed_on_unreserved_seat = 0
--    (판정기 문장을 DB에서 직접 — LoadtestDataService.kt:140-210)
```

  - **다시 세기가 맞는 까닭(정정 — 초안의 `cnt − n` 차감은 그 쌍의 cnt가 이미 어긋나 있으면 어긋남을 그대로 옮긴다)**: 그 쌍의 실제 값을 바꾸는 경로는 모두 쿼터 행을 먼저 잠그거나(선점 upsert · 배치 감소 — 지금 배치는 감소가 flush보다 앞이라 R1이 잠금을 쥐면 배치는 감소에서 기다리고, 그때 그 배치의 홀드는 아직 지워지지 않아 ③이 센다 · A′는 B2), 한 트랜잭션에서 홀드 −1 · 예약 +1을 함께 한다(확정 — 한 문장 스냅숏에는 둘 다 보이거나 둘 다 안 보인다). 그래서 ① 잠금 아래 ③의 한 문장 값이 그 쌍의 정의 값이다. 상한(max-per-user)을 넘는 값도 그대로 둔다(V6:4와 같다).
  - 좌석은 `RESERVED` 그대로(확정 1건이 남는다). 취소한 예약은 행으로 남긴다(지우지 않는다 — 결제 uid 추적 · ADR-016 입력). 다시 센 값은 0 이상이라 V5 CHECK(V5__user_hold_quota.sql:6)에 걸릴 일이 없다.
  - R0과 V12 사이에 새 중복이 생겨도 V12가 23505로 실패하고 앱이 뜨지 않는다(무음 아님) → R0부터 다시.
- **기본 위치에 두어서 생기는 일(사용자 결정의 대가 — 코드 사실)**:
  - 기존 테스트 2건이 정면으로 어긋난다: `LoadtestEndpointsTest` '같은 좌석의 CONFIRMED 2건 → 중복 확정'(LoadtestEndpointsTest.kt:108-114 — 같은 좌석에 CONFIRMED 2건을 INSERT) · `IndexMigrationTest` '인덱스는 중복을 막지 않는다 — … 같은 좌석 CONFIRMED 2건이 모두 들어간다'(IndexMigrationTest.kt:21-). 둘 다 ADR-000 §1 '문제를 가리지 않을 것'(기준선 스키마에 유니크 없음 — V1__init.sql:1-2)을 지키는 테스트다. 기준선 재현은 `spring.flyway.target=11`로(같은 파일 :37의 `target=1` 방식) — Q7.
  - **Flyway 순서 함정**: 기본 위치의 V12가 먼저 적용된 DB에 나중에 선택 위치(V4 · V8 · V9 · V10 · V11)를 더하면 그 버전이 '적용된 버전보다 낮은 미적용'이 되어 검증이 실패한다(outOfOrder 기본 false · 추정 `[구현 검증]` #6 — ADR-014 §설계-7이 V11에서 본 것과 같은 성질). 계단식으로 마이그레이션하는 기존 테스트가 걸린다: FirstWindowPageTest.kt:51→56 · :87→95(기본 위치만 → V9 추가) · Adr013FirstWindowContractTest.kt:76(기본 위치만 → 앱이 V9·V10) · Adr013InvalidIndexContractTest.kt:29(기본 + V9 → 앱이 V10). 마이그레이션 앞 기동 관문은 '미적용 버전' 오류를 거부로 보지 않으므로(HoldStrategyStartupCheck.kt:54 · :64 `PENDING`) 거부는 `flyway.migrate()`의 검증 실패로 난다 — 스키마 변경 전에 멈추는지 `[구현 검증]` #6. 운영: ADR-014 결정상 V11은 기본값 교체 때 기본 목록에 들어갈 예정이라(ADR-014 §설계-7) 그때 이 함정에 닿는다 — 같은 처리(그 한 번만 outOfOrder). V13(표지 열)도 기본 위치라 같은 함정을 함께 만든다.
  - **V12 · V13 × 옛 방식 DB — ADR-013 전환 절차에 outOfOrder 1회(§인터뷰 '설계 선검증 뒤' 사용자 답 · V9 · V10을 적용할 때만)**:
    - 어떤 DB가 걸리나: 옛 방식(ADR-009 C — K2)으로 띄운 업무 앱 DB. K2는 `spring.flyway.locations=classpath:db/migration`만 준다(application.yml:13-14 · :52-53) → 이 SHA로 K2를 띄우면 V1~V7 위에 **V12 · V13이 먼저 적용**되고, 뒤에 ADR-013 전환(V9 운영 중 적용 → V10)을 하면 V9 · V10이 '적용된 V13보다 낮은 미적용'이 된다. 측정 오버레이 `k6/ADR-014/compose.expiry.yml:34`의 위치 기본값도 `classpath:db/migration`뿐이다(같은 파일 :10 주석의 '업무 앱 기본 위치 + seat-map'과 다르다 — 값은 하네스 plan이 넘긴다) → 변수 없이 렌더한 DB에 나중에 V9~V11을 더하면 같은 함정. ADR-015 하네스(k6/ADR-015)는 새 DB의 **첫 기동부터 전체 위치 목록**을 넘기고 회차마다 새 DB를 쓴다(계단식 추가 없음 — 측정 task가 plan에 고정).
    - 절차 자리(ops/online-v9/apply.sh — ADR-013 §4.4 운영 중 적용판): **단계 0**(apply.sh:96-113 사전 확인)에 `max_applied = (SELECT max(version::numeric) FROM flyway_schema_history WHERE success AND version IS NOT NULL)`를 더해 `--out`의 state0에 남긴다. **단계 4 기록 ①**(apply.sh:229 Flyway CLI 인자 `migrate -target=9 -skipExecutingMigrations=true -locations=…`)에 `max_applied > 9`일 때만 **`-outOfOrder=true`**를 붙인다(그 한 번의 CLI 호출에만 · knobs에 기록). 기록 ② 직접(apply.sh:248-259)은 버전 순서를 보지 않고 `installed_rank` 다음 번호로 넣으므로 바꾸지 않는다. **이어지는 앱 첫 기동**(전체 위치 — V10 적용 · ADR-013 §4.4 다음 단계)은 `max_applied > 10`이면 그 한 번만 `spring.flyway.out-of-order=true`(환경 `SPRING_FLYWAY_OUT_OF_ORDER=true`)로 띄우고, V10 이력 확인 뒤 다음 기동부터 뺀다. 기동 관문(HoldStrategyStartupCheck.kt:54 · :64 `PENDING`)은 이 키를 Flyway 설정으로 그대로 읽어 검증한다(바꾸지 않는다).
    - 고정하는 테스트: `ops/online-v9/test-local.sh`에 경우 하나를 더한다 — 대상 DB = Flyway V1~V7 + V12 · V13(새 SHA를 K2로 띄운 옛 앱 상태) → apply.sh `--record cli`(Flyway jar가 있을 때) · `--record direct` 두 판 → ⓐ exit 0 · state0.max_applied = 13 · 기록 ① 인자에 `-outOfOrder=true` ⓑ 이어서 전체 위치 Flyway를 **outOfOrder 없이** 돌리면 검증 실패 · V10 객체 없음(스키마 무변경) ⓒ outOfOrder 1회로 돌리면 **V10 한 개만** 적용 ⓓ 그다음 outOfOrder 없이 다시 돌려도 검증 통과(순서 밖으로 적용된 V9 · V10 이력을 받아들이는가 — `[구현 검증]` #6). 같은 내용을 앱 쪽 계약 테스트(Adr013FirstWindowContractTest.kt:76 방식 — Testcontainers)로 한 번 더.

### 설계-5 손잡이 · 기동 검사 · 적용 범위

- **손잡이(제안 — Q9)**: `seat.confirm.mode` = `current`(**기본** — 지금 JPA 경로 그대로 · 키를 주지 않으면 지금과 같다) · `conditional`(①) · `pessimistic`(②) · `optimistic`(③) · `conditional-extend`(④). 새 `@ConfigurationProperties("seat.confirm")`(값: `mode` · `payment-window` · `extend-min-remaining` · `loadtest.delay` · `unique-index-optional`). **`mode`는 만료 배치도 고른다**: `current` = 지금 JPA 배치 · 그 밖 = A′ 배치(§3.6 — 배치만 따로 켜는 키는 두지 않는다 · Q15). 기본값 교체는 측정 뒤 '이기면 교체'(§인터뷰 — 교체 조건은 측정 task에서 측정 전에 고정).
- **분기 자리**: 확정 = `ConfirmReservationService.confirm` 첫머리(지금 :40의 만료 방식 분기 뒤 · `current`면 :42 이하 그대로). 배치 = `ExpireHoldsService.expire`의 트랜잭션 몸통(:38 — `current`면 :39-44 그대로 · 틱 경계 :35-57은 공용).
- **기동 거부(설정 검사 — 마이그레이션 앞 · `HoldStrategyStartupCheck.checkConfig` — HoldStrategyStartupCheck.kt:168-175에 규칙 추가)**: 조용히 `current`나 B·C·D 가드로 돌지 않는다.
  1. `mode ≠ current`이면 `expiry-mode = sweep`이어야 한다 — 아니면 거부(지금 :40은 `expiry-mode ≠ sweep`이면 손잡이와 무관하게 `LazyConfirmProcess`로 가므로, 거부하지 않으면 손잡이가 조용히 무시된다). B·C·D는 ADR-014 최소 가드 그대로(§인터뷰 — D가 기본값이 되면 그때 다시 본다).
  2. `mode ≠ current`이면 `strategy = pessimistic-nowait` · `limit-strategy = counter-upsert-precheck`(기본 조합) — §3.1의 '왜 충분한가'가 A 불변식 ②(3b가 지킨다)에 기대고, counter-trigger는 `beforeConfirm`의 트리거 시각 설정(UserLimitStrategies.kt:371)이 필요하며, 낙관락 선점은 ③ 트리거와 부딪친다(ADR-014 :190-194와 같은 문구 방식).
  3. `loadtest.delay > 0`이면 loadtest 프로필이어야 한다(측정 장치 — `inject-app`과 같은 원칙).
  4. `conditional-extend`면 `payment-window > 0` · `0 ≤ extend-min-remaining < payment-window`.
  5. `mode ≠ current`이면 `seat.hold.loadtest.inject-app.enabled=false`여야 한다 — 주입 이음새 `afterExpiryDecrement`(ExpiryCounters.kt:60)는 JPA 배치의 가드 감소에만 있고 A′(`decrementDeleted` — ExpiryCounters.kt:117 주석)에는 없어, 함께 켜면 주입이 조용히 빠진다(ADR-014 checkExpiryModeConfig와 같은 원칙 — HoldStrategyStartupCheck.kt:195-198).
  6. **③ 트리거(V14) 짝 — 설정으로 판정(마이그레이션 앞)**: 위치 목록(`spring.flyway.locations` — 목록 전체를 덮어쓰는 값 · HoldStrategyStartupCheck.kt:362-363)에 `classpath:db/migration-confirm-version`이 있고 `target`이 14에 닿으면 '트리거가 생긴다'로 본다(Flyway 관문의 `reaches` 판정 — HoldStrategyStartupCheck.kt:51과 같은 규칙). ⓐ `mode = optimistic`이면 그 위치가 있어야 한다 ⓑ 그 위치가 있으면 `strategy ≠ optimistic`(ADR-003 낙관락 선점 — §3.3 (가)의 충돌). 이미 트리거가 적용된 DB는 관문의 '내다본 스키마'(DB 사실 읽기 + 위치 — HoldStrategyStartupCheck.kt:55-58)에서 같은 규칙을 본다. 둘 다 `flyway.migrate()`(:60) **앞**이라 거부된 기동은 스키마를 바꾸지 않는다(HoldStrategyStartupCheck.kt:31-35 원칙 — 초안의 '`run`에서 검사'는 V14가 이미 적용된 뒤라 이 원칙을 어긴다 · 정정).
  7. **새 모드 + V12 없음 = 기동 거부(WARN 아님)**: `mode ≠ current`인데 `spring.flyway.target`이 12 미만으로 주어졌으면 거부. 예외는 **`seat.confirm.unique-index-optional=true`**(기본 false) 하나 — 이 키는 `target`이 12 미만으로 **명시**됐을 때만 받는다(아니면 그 자체로 거부 — 일반 배포에서 인덱스 없음을 가리는 데 쓸 수 없다). 쓰는 곳은 재현 테스트의 'V12 없음' 판(§설계-8.1)뿐이다(.164 측정은 V12 있음 — 키를 주지 않는다).
- **DB 상태 검사(`run` — 마이그레이션 뒤 · Flyway가 꺼진 구성에서도 빠지지 않게 — HoldStrategyStartupCheck.kt:40)**: ③ 트리거(V14) — 규칙 6을 DB 사실로 다시(있으면 `strategy ≠ optimistic` · `mode = optimistic`이면 있어야 함 · 그 밖의 모드에서 남는 것은 무해 — §3.3 되돌리기). V12 인덱스 — **INVALID면 모든 모드 거부** · 없으면 `mode = current`는 WARN(기준선 재현 `target=11` — ADR-001·003 조건) · `mode ≠ current`는 `unique-index-optional=true`가 아니면 **거부**(규칙 7과 같은 뜻 · Q14 정정). V13 열 — `mode = conditional-extend`면 있어야 한다(없으면 거부 · `target` 12 이하로 띄운 경우).
- 적용 범위: 업무 앱만. reconciler 프로필(F — 대조 전용 프로세스)은 컨트롤러가 없다(ReservationController.kt:15).

### 설계-6 새 동작 불변식 (설계 선검증 대상 — 코드베이스에 없던 것만)

| # | 불변식 | 지금 강제 위치 | 새 강제 위치 | 검증 |
|---|---|---|---|---|
| N1 | **확정 성공 ⇒ 같은 트랜잭션에서 내 활성 홀드 1행 삭제 + 좌석 `HELD → RESERVED` 1행 + 예약 1건** — 하나라도 0행이면 전부 롤백 | 없음(우연한 가드 A6 — Hibernate 행 수 검사 · 추정) | ① · ④ C1·C2 · ② P1·P2·P4 · ③ O2·O3 영향 행 수 | FM1~FM3 재현 테스트 · 판정기 `v_reserved_without_confirmed` · `v_confirmed_on_unreserved_seat` 0 |
| N2 | **한 홀드는 많아야 한 번 확정된다**(두 번째는 404) | 없음(우연한 가드 · `current` + V12면 유니크 인덱스가 먼저 — FM2) | 홀드 행 삭제의 행 수(예약 INSERT 앞) | FM2 동시 확정 테스트(V12 있음 · 없음 두 판) · `v_duplicate_confirmed_seats` 0 |
| N3 | **확정은 남의 활성 홀드가 있는 좌석을 `RESERVED`로 만들지 않는다** | 없음 | C1(내 홀드 행이 살아 있어야 함) + A 불변식 ② | FM1 · `active_on_reserved_seats` · `v_hold_on_unheld_seat` 0 |
| N4 | **경합에서 진 확정은 계약 코드(404 · 403 · 409 HOLD_EXPIRED · 409 SEAT_NOT_HELD)로 끝난다 — 5xx 0**(새 모드 넷) | 없음(지금은 500 — 추정 · V12 있는 FM2만 409) | 영향 행 수 + 진단 문장 · 유니크 위반 매핑 | 재현 테스트 응답 · 부하 5xx 수 |
| N5 | **배치와 확정이 겹쳐도 예약이 있는 좌석이 `AVAILABLE`이 되지 않고, 배치의 카운터 감소 = 그 트랜잭션이 실제로 지운 홀드 행 수 → cnt = 홀드 행 + 확정 유지**(새 모드) | 없음(우연한 가드 — 0행이면 틱 롤백이 감소까지 되돌림 · §설계-1) | **A′ 배치**: B3 조건부 DELETE(`expires_at <= :now` — EPQ 재확인) 행 수 → B4 `decrementDeleted`(같은 트랜잭션 — ExpiryCounters.kt:121) · B5 `status='HELD'` 조건 UPDATE 행 수(§3.6) — 잠금 순서 쿼터 → 홀드 → 좌석 | FM3(L-B′ 래치) · 대조 배치에서 이중 결과 ⓒ ≥ 1 · A′에서 0 · `v_counter_mismatch` 0 · FM3 틱 커밋(`seat.hold.expiry.tick{outcome=ok}`) |
| N6 | **같은 좌석 `CONFIRMED` ≤ 1을 DB가 강제하고, A 확정의 위반은 409 + 지표로 드러난다(무음 아님)** — B·C·D는 500(받아들인 한계) | 없음(V1 · V2 — 의도) | V12 + A 확정 매핑(`current` 포함) | 직접 INSERT 테스트(23505) · `current` FM2(V12) 409 · 지표 |
| N7 | **`mode ≠ current`인데 조합 밖이면(규칙 1~7) 기동을 거부하고 스키마를 바꾸지 않는다** | 기존 '거부된 기동은 스키마를 바꾸지 않는다'(HoldStrategyStartupCheck.kt:31-35) 안 | `checkConfig` 새 규칙 + 관문 내다본 스키마(V14 짝) — 모두 `flyway.migrate()`(:60) 앞 | 기동 테스트(조합표 · 거부 뒤 `flyway_schema_history` · V14 트리거 · V12 무변경) |
| N8 | **확정 · A′ 배치 경로의 기다리는 잠금은 쿼터 행 → 홀드 행 → 좌석 행 순서다**(확정은 쿼터 없이 홀드 → 좌석) — 40P01 0 | 없음 | 문장 순서 | 교착 테스트(② 좌석 → 홀드 대조판 40P01 · 권장판 0 · §3.5 짝 전부) |
| N9 | (④) **연장은 내 활성 홀드에만, 한 홀드에 많아야 한 번(`extended_at IS NULL`), 좌석 · 쿼터를 바꾸지 않는다 · 재호출은 지금 `expires_at` 200 · 수명 ≤ TTL + window** | 없음(API 없음) | E1 조건 + V13 표지 열 | blind 테스트(첫 호출 · 재호출 · 동시 두 첫 호출 · 남은 시간 ≥ window · 늦음) · 끝 판정 '최대 수명' |
| N10 | (④ × A′) **연장이 커밋된 홀드는 그 만료 전에 배치가 지우지 않는다**(`extension_lost` 0) | 없음(지금 JPA 배치는 id로만 지움) | A′ B3 `expires_at <= :now`(EPQ) | FM5(L-E + L-B′ 래치) · 대조 배치 잃음 ≥ 1 · A′ 0 · k6 `ext_then_lost` 0 |
| N11 | **새 모드는 V12(유효)가 있을 때만 돈다**(예외: 테스트 준비가 V12 인덱스를 지운 DB + 테스트·loadtest 프로필의 `unique-index-optional=true` — 설계-8.1 정정) | 없음(초안은 WARN) | 규칙 7 + `run` 인덱스 검사 | 기동 테스트(`target=11` 키 없음 → 거부 · 키 있음 → 기동 · 키만 있고 `target` 없음 → 거부 · INVALID → 거부) |

- 코드베이스에 이미 있는 불변식(다시 세우지 않는다): A 에러 순서(ProductSeat.kt:59-73 · ConfirmReservationServiceTest.kt:22-89 — 새 모드도 같은 테스트를 통과해야 한다) · 만료 정각 = 만료(SeatHold.kt:27 — A′ B3의 `<=`도 같다) · 카운터 실제 값 정의(V6 · 판정기) · ADR-014 B·C·D 확정 가드(LazyConfirmProcess.kt — 무변경) · 쌍 잠금 → 지운 행만큼 감소(ADR-014 §2.4 규칙 ①~③ — LazyExpiryBatch.kt:182-229 · A′가 재사용) · 손잡이를 주지 않으면 이전과 같다(`current` = JPA 확정 + JPA 배치).
- 관측 지표(불변식 아님 — 기대값 없음): `seat.confirm.converted_after_expiry{clock=db|app}`(§설계-3 공통 규칙 — `:now` 규칙이 허용한 창의 크기).

### 설계-7 `[구현 검증]` 목록

1. **Hibernate 6.6 고아 DELETE의 행 수 검사**(A6) — 0행이면 어떤 예외(`StaleObjectStateException` → `ObjectOptimisticLockingFailureException` 추정)로 롤백되고 응답이 500인가. **착수 직후 스모크 1순위** — 결과가 §설계-1 · §설계-2의 '가드가 있으면' 칸과 문제 문장(Q1)을 정한다.
2. 지금 A 확정 · A 배치의 실제 문장 순서(SQL 로그): A2 · A3 분리 · A5 IDENTITY 즉시 INSERT · flush의 '고아 삭제 → 좌석 UPDATE' 순서.
3. 배치 flush 순서가 홀드 → 좌석이라 ② '좌석 → 홀드' 판이 40P01을 내는지(대조 테스트).
4. Hibernate `PESSIMISTIC_WRITE`가 PostgreSQL에서 `FOR UPDATE`인지 `FOR NO KEY UPDATE`인지 — 확정 중 같은 좌석 선점 NOWAIT 패배 여부(§3.5 마지막 행).
5. V12 생성 시간 · `reservation` 쓰기 차단 길이(운영 크기 사본 · .164 시드의 예약 행 수) → 트랜잭션 안 그대로 대 `CONCURRENTLY`. `CONCURRENTLY` 판이면: Flyway 11.7.2가 PostgreSQL의 비트랜잭션 실패를 이력에 실패 행으로 남기는지 · `flyway repair`가 그 행만 지우는지 · 재시도 절차(§설계-4) 끝에서 `indisvalid`.
6. Flyway 순서 함정: §설계-4의 계단식 테스트 4곳 · 선택 위치를 V12 · V13 뒤에 더한 DB의 거부 시점(관문 `PENDING`과 같은 오류 코드인지 · 스키마 무변경인지) · ADR-013 전환의 outOfOrder 1회(apply.sh 기록 ① `-outOfOrder=true` · 앱 첫 기동 `spring.flyway.out-of-order=true`) 뒤 **플래그 없이** 다시 기동해도 검증이 순서 밖 적용 이력을 받아들이는가(test-local.sh 새 경우 ⓓ).
7. ③ 트리거 비용 — 선점 · 배치 · 확정의 좌석 UPDATE마다(`UPDATE OF status`가 Hibernate 전체 열 UPDATE에서도 발화하고 `WHEN`이 같은 값은 거르는지).
8. ④ 연장을 잃는 창(FM5 — 배치 후보 읽기 → 연장 커밋 → 배치 DELETE) — L-E + L-B′ 래치: A′에서 지우지 않음(EPQ 재확인) · 대조 배치에서 지움(잃음 1) · `extend-min-remaining` 기본값.
9. 교착 테스트 — 40P01 0: 확정(①~④) ↔ 배치 · 확정 ↔ 정리 ①(마감 회차 확정 중 정리) · ② 대조판.
10. 확정이 이긴 경합(FM3)에서 `current`(JPA 배치)의 틱 전체 롤백 빈도 · 밀린 만료 수(`seat.hold.expiry.tick{outcome=failed}` — ExpireHoldsService.kt:48) 대 새 모드(A′)의 0. A′의 남은 틱 롤백 원인(B3b 불일치 · 40P01 · CHECK) 빈도 — NEXT 판단 입력.
11. 지연 주입 자리의 모드별 의미(§설계-8.2) — 주입이 잠금을 쥔 채 도는지(②) 아닌지(①).
12. .164 앱 Clock과 DB 시계 차(모든 모드가 앱 Clock — 판정기 `active_on_reserved_seats`는 DB `now()` 기준이라 경계 표본이 갈릴 수 있다 — LoadtestDataService.kt:224).
13. `current` 경로의 유니크 위반이 save 자리에서 `DataIntegrityViolationException`으로 오는지(IDENTITY 즉시 INSERT) · **FM2의 V12 유무별 결과**(SQL 로그로 확인): V12 있음 = 늦은 쪽 INSERT가 유니크 검사에서 앞 쪽 커밋을 기다린 뒤 23505(A6 앞 — 409 매핑) · V12 없음 = 두 INSERT 통과 뒤 늦은 쪽 A6 DELETE 0행 예외(500). 어느 문장이 던졌는지를 로그의 문장 · SQLSTATE로 가른다.
14. V13 열이 JPA에 무해한가 — `ddl-auto: validate`가 매핑 안 된 `extended_at`을 무시하는지 · JPA 선점 INSERT가 NULL로 넣는지 · `ALTER TABLE … ADD COLUMN … NULL`이 .164 크기 `seat_hold`에서 표를 다시 쓰지 않는지(잠금 길이).
15. A′ 배치 문장 수 · 틱 시간 대 JPA 배치(같은 후보 수) — 새 모드 셀 비용에 섞이는 몫(§3.6 까닭 3).
16. A′ B5의 `NOT EXISTS` 하위 질의는 문장 시작 스냅숏이다(EPQ는 좌석 행 조건만 다시 본다 — 설계-0) — A에서 `HELD` 좌석 위에 새 홀드가 생기지 않으므로(선점은 `AVAILABLE`만) 결과가 갈리지 않는다는 가정을 래치 테스트로 한 번(B5 앞 래치 · 그 사이 같은 좌석 선점 시도 → 409).
17. `converted_after_expiry{clock=db}`의 `clock_timestamp()` 값이 C1 · O2 · P4 문장 실행 시각인지(트랜잭션 시작 `now()`가 아님)와 `clock=app`(커밋 뒤)과의 차 — #12와 함께.

### 설계-8 측정 설계 — 뼈대 (고정값 · 성공 기준 · 교체 조건은 측정 task)

#### 8.1 재현 테스트 (Testcontainers · 이중 결과 재현 → 선택지마다 0건)

- 조건: TTL 짧게(예: 3초) · 만료 스케줄러 끔(`seat.hold.expiry-scheduler-enabled=false` — HoldExpiryScheduler.kt:8-10 · 배치는 `ExpireHoldsUseCase.expire()`를 테스트가 부른다) · `MutableClock`으로 만료를 넘긴다(기존 관례).
- **끼어들기는 잠자기가 아니라 래치**(테스트 전용 훅 빈 — 이름 붙은 자리에서 멈췄다 놓는다) — 순서를 결정적으로 만든다. 부하용 지연(§8.2)과 따로 둔다(Q12). 래치 자리:

| 래치 | 멈추는 쪽 · 자리 | 이음새 |
|---|---|---|
| L-C | 확정 '확인 뒤 · 첫 쓰기 앞': `current` = `seat.confirm` 뒤 save 앞(ConfirmReservationService.kt:47과 :49 사이) · ① · ④ = `:now` 뒤 C1 앞 · ② = P2 뒤 P3 앞(잠금을 쥔 채) · ③ = O1 뒤 O2 앞 · 대조 확정 = 확인 뒤 쓰기 앞 | 새 테스트 전용 훅(확정 모드 공용) |
| L-B(JPA) | `current`의 JPA 배치 '후보 읽기(ExpireHoldsService.kt:40) · 가드 감소 뒤 · flush(홀드 DELETE) 앞' | **기존 이음새** `AppPathInjectHook.afterExpiryDecrement`(ExpiryCounters.kt:60 · 인터페이스 AppPathInjectHook.kt:11-12) — 테스트가 자기 `AppPathInjectHook` 빈을 둔다(`ObjectProvider` — ExpiryCounters.kt:30 · loadtest의 `AppPathInjector`(AppPathInjector.kt:56)는 띄우지 않는다). **조건**: `expiry-repair=true`(application.yml:57)이고 그 쌍의 가드 감소가 1행일 때만 불린다(:56-60 — 복구 갈래 :57 · 옛 경로 :89-98에서는 안 불린다) → FM3 테스트는 쿼터 행이 맞는 상태에서 돈다 |
| L-B′ | A′ 배치 '후보 읽기 · 쌍 잠금(B1 · B2 · B2b) 뒤 · B3 DELETE 앞' | 새 테스트 전용 이음새(A′ 안 — 감소는 B3 뒤라 '감소 뒤'가 아니다) |
| L-B(대조) | 대조 배치 '후보 읽기 · 후보 수만큼 감소 뒤 · 무조건 DELETE 앞' | 대조 배치 자신(테스트 소스) |
| L-E | ④ 연장 '`:now` 뒤 · E1 앞' | 새 테스트 전용 훅 |

| FM | 순서(래치) | 가드 있음 칸 | 가드 없음 칸(대조) |
|---|---|---|---|
| FM1 | X 확정 L-C(`:now` < te) → 시계 te 뒤로 → 배치 끝까지(커밋) → Y 선점(커밋) → L-C 풂 | 모드별 확정 + 모드에 맞는 배치 | 대조 확정 + JPA 배치 |
| FM2 | X1 · X2 L-C → X1 풂 · 커밋 → X2 풂 | 모드별 확정 × V12 있음 · 없음 | 대조 확정('V12 없음' 판만 — 인덱스를 지운 DB · V12 있으면 인덱스가 가린다) |
| FM3 | X 확정 L-C(`:now` < te) → 시계 te 뒤로 → 배치 L-B(JPA · A′면 L-B′) → L-C 풂 · 확정 커밋 → 배치 풂 | `current` = L-B(JPA) · 새 모드 = L-B′ | 대조 확정 + 대조 배치 L-B(대조) → 이중 결과 ⓒ(예약 + 좌석 `AVAILABLE`) |
| FM5 | 연장 L-E(`:now′` < te − minRemaining) → 시계 te 뒤로 → 배치 L-B′ → L-E 풂 · 연장 커밋 → 배치 풂 → 확정 | `conditional-extend` + A′ → 지우지 않음 · 확정 200 | 대조 배치 → 지움 · `extension_lost` 1 · 확정 404 |

- **대조 배치**(테스트 소스에만): 후보 = `expires_at <= :now` 홀드 → 후보 수만큼 쌍별 감소 → L-B(대조) → `DELETE FROM seat_hold WHERE id = ANY(:ids)`(만료 재확인 없음 · 행 수 안 봄) → `UPDATE product_seat SET status = 'AVAILABLE' WHERE id = ANY(:seats)`(상태 조건 없음) · RETURNING은 A′와 같은 열(잃음 셈). 이것이 FM3 · FM5의 '가드 없음' 칸을 실제로 만든다 — **대조에서 이중 결과 · 잃음이 0이면 하네스가 무력하다**(그린 위장 점검 — 그 경우 테스트 실패로 둔다).
- 칸: FM1 · FM2 · FM3 · '확정 커밋 직후 같은 좌석 선점'(기대 409) · 교착 칸(#9) × 모드 `current` · ① · ② · ③ · ④ + **대조군**(테스트 전용 확정 — 지금과 같은 확인 → 무조건 JDBC 쓰기 · 행 수를 보지 않음): 하네스가 이중 결과를 실제로 만들 수 있음을 보인다(이것이 0이면 재현 테스트가 무력하다 — 그린 위장 점검).
- V12 유무 두 판(정정 2026-11-04 — post-fix 재점검: `target=11` 판에는 V13 `extended_at`이 없어 A′ 배치 B3이 열 없음으로 죽고, ③은 V14 트리거가 없어 기동 거부된다): **'V12 없음' 판 = 모든 마이그레이션(V12 · V13 · 필요하면 V14 위치)을 적용한 뒤 테스트 준비 단계가 `DROP INDEX` 로 V12 인덱스만 지운 DB** + `seat.confirm.unique-index-optional=true`(테스트·loadtest 프로필 한정 키 — 앱 로직만 보이게 · 새 모드는 이 키 없이는 인덱스 없는 DB에서 뜨지 않는다 — 규칙 7·N11) · 기본 판(V12 유효). 그래서 ①~④ 모두 두 판이 성립한다. `current`의 기준선 재현(V11까지만 있는 옛 스키마)은 `target=11`을 그대로 쓴다(새 모드는 그 판에서 돌지 않는다). 규칙 7·N11의 예외 조건도 '`target` < 12 명시' 대신 '인덱스 없음 + 테스트 키'로 읽는다(아래 N11 행과 같은 뜻).
- 기대: 대조군 이중 결과 ≥ 1(FM1 · FM2('V12 없음' 판) · FM3) · 대조 배치 잃음 ≥ 1(FM5) · `current` = #1 결과대로(추정: 이중 결과 0 · FM1 · FM3 진 쪽 500 · FM2는 V12 없음 500 / 있음 409 + `unique_violation` · FM3 틱 롤백) · ①~④(A′) 이중 결과 0 · 진 쪽 계약 코드 · 5xx 0 · 40P01 0 · FM3 틱 커밋 · `extension_lost` 0 · `v_counter_mismatch` 0 · `unique_violation` 0.

#### 8.2 .164 부하 측정 (확정 경합)

- 시나리오 `confirm-contention`(k6 — 새 디렉토리 `k6/ADR-015`): 짧은 TTL(초 단위) · 확정 요청을 만료 경계 부근에 몰리게 보내는 흐름(선점 → 대기 ≈ TTL → 확정) · 만료 직후 같은 좌석을 노리는 재선점 VU · 같은 홀드 이중 확정 비율 · ④ 셀은 확정 전에 `payment-start`. 확정 지연 주입 `seat.confirm.loadtest.delay`(loadtest 프로필 전용 · 자리 = 모드별 '확인 뒤 · 첫 쓰기 앞': current = ProductSeat.confirm 뒤 save 앞(ConfirmReservationService.kt:47과 :49 사이) · ① = `:now` 뒤 C1 앞 · ② = P2 뒤 P3 앞(잠금을 쥔 채) · ③ = O1 뒤 O2 앞).
- 지표: 확정 처리량 · 확정 p99(상위 1% 응답 시간) · 선점 p99 · DB CPU · 응답 코드 분포(404 · 409 HOLD_EXPIRED · 409 SEAT_NOT_HELD · 5xx) · 40P01 수 · 배치 틱 실패 수 · 배치 틱 시간 · 문장 수(JPA 대 A′ — #15) · `seat.hold.expiry.revert{result}` · `seat.confirm.unique_violation`(새 모드 기대 0 · `current`는 FM2 몫이 있을 수 있다) · `seat.confirm.unexpected` · **`seat.confirm.converted_after_expiry{clock=db|app}`**(건수 · `전환 시각 − expires_at` 최대 — 기대값 없음 · 모드별 분포) · (④) `seat.confirm.extend{result}` · `seat.confirm.extension_lost` · `seat.hold.expiry.deleted{extended}` · k6 `ext_then_lost` · 연장 수 · 홀드 수명.
- **이중 결과 수 = 0**(끝 판정기 — LoadtestDataService.kt:140-234): `v_duplicate_confirmed_seats` · `v_reserved_without_confirmed` · `v_confirmed_on_unreserved_seat` · `v_hold_on_unheld_seat` · `active_on_reserved_seats` · `double_sold_seats` · `v_counter_mismatch` — 모두 기대 0. .164에는 V12가 있으므로 앱 가드가 뚫린 중복 확정은 `unique_violation`으로 보인다 → 그것도 0이어야 한다.
- 하네스: ADR-014 §결정-3의 keep-alive 수정(긴 생각 시간 앞 `Connection: close`)을 먼저 넣는다 — 같은 EOF가 5xx 지표를 흐리지 않게.
- 셀 수 · 반복 · 길이 · 문턱(G — 관문) · '이기면 교체' 조건은 측정 task(ADR-014 측정-8과 같은 틀).

### 설계-9 열린 질문 (사용자 · 메인이 정할 것 — 질문마다 권장안 1개)

**정해진 것(15-01b에서 표에서 뺐다 — 출처)**: Q1 재현 스모크 먼저(§인터뷰 2026-11-04) · Q4 ④ 확정 문장 = ①(§인터뷰) · Q5 `payment-start` API(§인터뷰) + 재호출 = 첫 연장만 · 표지 열(§인터뷰 '설계 선검증 뒤' → §3.4) · Q6 유니크 위반 매핑 = A 확정(`current` 포함) · B·C·D 500(지적 8 → §설계-3 · §설계-4) · Q7 V12 기본 위치 + 기존 테스트 수정 · 기준선 `target=11`(§인터뷰) · Q8 순서 함정 = ADR-013 전환 절차에 outOfOrder 1회(§인터뷰 '설계 선검증 뒤' → §설계-4) — 계단식 테스트 4곳은 첫 단계에 `target` 고정(권장안 그대로) · Q10 A 배치 = 최소 수정을 범위에(§인터뷰 재합의 → §3.6) · 틱 전체 롤백 개선은 NEXT · Q14 V12 검사 = INVALID 거부 · 새 모드는 없으면 거부(지적 4 → §설계-5 규칙 7).

| # | 질문 | 권장안 | 누가 |
|---|---|---|---|
| Q2 | ②의 잠금 순서 | **홀드 행 → 좌석 행**(초안 '좌석 → 홀드' 대신 — A 배치(JPA · A′ 모두) 순서와 같게) · '좌석 → 홀드'는 대조 테스트로만 | 사용자(§인터뷰: 권장안으로 둠) |
| Q3 | ③의 버전 올리기 | **(나) 상태 변경 트리거 — 번호 V14 · 선택 위치 `db/migration-confirm-version`**(③ 셀만 · 짝 검사는 마이그레이션 앞 — §설계-5 규칙 6) — `@Version` 매핑은 기각 | 사용자(권장안으로 둠) |
| Q9 | 손잡이 이름 · 값 | **`seat.confirm.mode` = current · conditional · pessimistic · optimistic · conditional-extend**(기본 current — 배치도 함께 고른다) · `payment-window` · `extend-min-remaining` · `loadtest.delay` · `unique-index-optional` | 메인 |
| Q11 | 같은 홀드 이중 확정의 두 번째 응답 | **404 HOLD_NOT_FOUND 그대로**(새 모드) — `current` + V12는 409 SEAT_NOT_HELD(매핑)로 갈린다는 점을 기록만. 같은 `paymentUid` 재시도의 멱등 응답은 ADR-016 · NEXT 몫 | 사용자(권장안으로 둠) |
| Q12 | 지연 주입 방식 | **재현 테스트 = 래치(L-C · L-B · L-B′ · L-E — §설계-8.1) · 부하 = `seat.confirm.loadtest.delay`(loadtest 프로필 전용 · 기본 0 · 자리 '확인 뒤 · 첫 쓰기 앞')** | 메인 |
| Q13 | 적용 조합 | **기본 조합만**(3b + counter-upsert-precheck · `expiry-mode = sweep`) — 그 밖은 기동 거부(§설계-5) | 사용자(권장안으로 둠) |
| Q15 (새) | A′ 배치를 확정 손잡이에 묶나, 따로 켜나 | **묶는다**(`current` = JPA 배치 · 그 밖 = A′) — 기준선 칸 무변경 · 잘못된 짝 없음. 셀 비용에 섞이는 배치 몫은 틱 시간 · 문장 수로 따로 남긴다(#15). 'A′만 켠 current' 셀이 필요해지면 그때 키를 더한다 | 메인(사용자 확인 권장 — 측정 셀 구성에 닿는다) |
| Q16 (새) | 남은 시간이 이미 window 이상일 때 `payment-start` | **200 + 지금 `expires_at` · 표지를 남기지 않는다**(연장이 늘리지 않으므로 '첫 연장'을 쓰지 않는다 — 뒤의 재호출이 연장을 받을 수 있다). 대안 '표지만 남김(그 홀드는 더 연장 못 함)'은 결제를 늦게 시작한 사용자에게 불리하다 | 사용자 |
| Q17 (새) | ③ 트리거 번호를 V13 → V14로 옮기는 것 | **옮긴다** — V13은 ④ 표지 열(기본 위치)이 쓰고, 선택 위치를 기본 위치보다 위 번호로 두어 나중에 ③을 켤 때 순서 함정을 피한다 | 메인 |

- **다시 읽은 파일(15-01b · 줄 번호 근거)**: ExpireHoldsService.kt(1-63) · ProductSeat.kt(1-87) · ExpiryCounters.kt(1-211) · ConfirmReservationService.kt(1-67) · LazyConfirmProcess.kt(1-76) · LazyExpiryBatch.kt(104-241) · LazyHoldProcess.kt(183-199 revertSeats) · ExpiryRule.kt(10-13) · AppPathInjectHook.kt(1-13) · QuotaCleanup.kt(110-140) · HoldStrategyStartupCheck.kt(1-200 · 349-363) · ApiExceptionHandler.kt(1-67) · SeatHold.kt(16-24) · application.yml(1-68) · ops/online-v9/apply.sh(1-39 · 96-113 · 225-240 · 248-259) · ops/online-v9/test-local.sh(1-30) · k6/ADR-014/compose.expiry.yml(1-36) · db/migration*/ 목록 · V1__init.sql(15-41) · V6__backfill_user_hold_quota.sql(1-31).

## 실험 결과

미측정.

## 결정

미정.
