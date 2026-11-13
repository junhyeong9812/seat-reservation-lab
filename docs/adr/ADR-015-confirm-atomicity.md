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
> 번호 이동(2026-11-07, 10차): 억울한 사용자 최소화(결제했는데 좌석을 잃음 — 예방 · 보상 설계)를 새 ADR-016으로 넣으며 기존 ADR-016~020을 017~021로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 결정 (2026-11-06) — 새 기본값 ① `conditional`(고정 규칙 요약기 = 판정 불가 → 사용자 보고 → 사용자 결정 — §결과-3 · §결정) · 기본값 교체 커밋 a68d9518
- 대응 질문: Q6 "결제는 성공했는데 그 사이 선점이 만료됐다면?"
- 선행: ADR-000, ADR-001, ADR-014(만료 방식에 따라 전환 조건이 달라짐)

## 인터뷰 (2026-11-04 — 사용자 답 · 요구사항 명세는 ADR-014 마감 뒤 작업 폴더에 옮긴다)

- 선택지: **4개 전부** — ① 조건부 UPDATE(전환 문장의 WHERE가 곧 확인 · 영향 행 수로 판정) ② 비관락(좌석·홀드 `FOR UPDATE` 뒤 확인·전환) ③ 낙관락(`@Version`) ④ 결제 중 홀드 연장. 최후 방어선(부분 유니크 인덱스)은 공통.
- 적용 범위: **A(sweep — 현재 기본값) 확정만**. B·C·D는 ADR-014 최소 가드 그대로(ADR-014 재측정에서 D가 기본값이 되면 그때 D를 다시 본다).
- 만료 뒤 결제 성공: **거부**(ADR-014와 같은 규칙) — 결제 취소·환불 같은 보상은 ADR-017.
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

- ~~A6·A7의 순서는 Hibernate flush 규칙(고아 삭제가 UPDATE보다 먼저)~~ **정정(15-02 실측 — Hibernate 6.6.26 · PG 서버 실행 로그 · Adr015ReproSmokeTest)**: flush는 **좌석 UPDATE(A7) → 고아 DELETE(A6)** 순이다. 만료 배치도 같다(가드 감소 JDBC → 좌석 UPDATE → 홀드 DELETE). 그래서 0행 DELETE 예외는 좌석 UPDATE가 이미 실행된 뒤에 나고, 롤백이 그 UPDATE를 되돌린다 — 지금 정합은 롤백 하나가 지킨다. 결과(FM1 · FM3 이중 결과 0 · 진 확정 HTTP 500 · 예외 `ObjectOptimisticLockingFailureException` ← `StaleObjectStateException` · FM3 틱 전체 롤백)는 §설계-1 추정과 같다. JPA 경로의 잠금 순서는 확정 = FK 검사 → 좌석 → 홀드 · 배치 = 쿼터 → 좌석 → 홀드(둘이 같은 방향이라 `current` 짝에 고리는 없다). Hibernate `PESSIMISTIC_WRITE` = `FOR NO KEY UPDATE`(#4 답).
- **경합 창**: W1 = A2·A3(잠금 없는 읽기) ~ A5·A6·A7(쓰기). W2 = A1의 `:now`(트랜잭션 시작에 고정) ~ 커밋. 만료 직전에 시작해 만료 뒤에 커밋하는 확정은 지금도 허용된다(판정 시각 = 트랜잭션 시작 — ADR-014 §설계-2.2와 같은 규칙).

**W1에 끼어들 수 있는 경로**(기본 조합 · 앱 1대):

| 경로 | 문장 순서 | 잠금 순서 | 근거 |
|---|---|---|---|
| 만료 배치(A) | 틱 = 트랜잭션 1 · `findDistinctByHoldsExpiresAtLessThanEqual(앱 now)`(잠금 없음) → `expireHolds` = 만료 홀드 제거 + `HELD`면 `AVAILABLE` → 쿼터 가드 감소(JDBC — 즉시) → 커밋 flush(좌석 UPDATE → 홀드 DELETE — 15-02 실측) | 쿼터 행(정렬) → 좌석 행 → 홀드 행 (15-02 실측 — 감소 JDBC는 flush 앞에서 즉시) | ExpireHoldsService.kt:38-45 · ProductSeatRepository.kt:18 · ProductSeat.kt:76-86 · ExpiryCounters.kt:49-63 |
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
| FM1 남의 홀드 위 내 RESERVED | ① X(나) 확정이 `t0 < te`에 시작 — A1~A4 통과(`isExpired(t0)` 거짓) 뒤 늦어짐 ② `te` 뒤 배치 틱이 내 홀드를 지우고 좌석 `AVAILABLE` · cnt(X) −1 · 커밋 ③ Y가 선점 — 새 홀드 h′ · 좌석 `HELD` · 커밋 ④ X가 A5 예약 INSERT → A6 내 홀드 DELETE | A6 0행 → 예외 → X 롤백 → X에게 500 · Y 홀드 그대로 | A6 0행 무시 → A7이 Y의 `HELD`를 `RESERVED`로 덮음 → **좌석 RESERVED + Y의 활성 홀드 h′ + X 예약**(이중 결과 ⓐ — 판정기 `active_on_reserved_seats` · `v_hold_on_unheld_seat` — LoadtestDataService.kt:172-173 · :231). cnt(X)는 배치가 −1 · 예약 +1 → 실제보다 1 작다(below — `v_counter_mismatch`). Y의 확정은 409 SEAT_NOT_HELD(ADR-017 '돈은 빠졌는데 좌석 없음'이 Y에게 생긴다) |
| FM2 같은 홀드 이중 확정 | ① X1 · X2가 같은 홀드로 동시에 A2~A4 통과 ② 둘 다 A5 예약 INSERT ③ X1 A6 DELETE · A7 · 커밋 ④ X2가 그 뒤 진행 | **V12 없음('V12 없음' 판 — 설계-8.1)**: ②의 두 INSERT는 서로 막지 않는다 → ④ X2 A6 DELETE가 X1을 기다린 뒤 0행 → X2 롤백 → 500. **V12 있음(기본)**: ②에서 늦게 INSERT한 쪽(X2)이 유니크 검사로 X1 커밋을 기다린 뒤 **23505** — A6에 닿기 전에 진다 → 유니크 위반 매핑(N6)으로 409 SEAT_NOT_HELD + `seat.confirm.unique_violation` +1 (추정 `[구현 검증]` #13 — SQL 로그로 '23505가 INSERT 문장에서 났는가 · DELETE 0행 예외가 아닌가'를 본다) | V12 없음: X2도 커밋 → **같은 좌석 CONFIRMED 2건**(이중 결과 ⓑ — `v_duplicate_confirmed_seats` · LoadtestDataService.kt:164) · cnt(X) +0인데 실제 +1(below). V12 있음: 대조 확정의 INSERT가 23505 → 대조군은 매핑이 없어 500 · 이중 결과 0 — **인덱스가 가린다**(그래서 '가드가 없으면' FM2 칸은 `target=11` 판에서만 잰다) |
| FM3 확정과 배치의 같은 틱 | ① 배치 틱이 내 홀드를 만료로 읽음(배치 `now ≥ te`) ② X 확정(`t0 < te` 시작)이 A6·A7을 먼저 보내고 커밋 ③ 배치 flush의 홀드 DELETE가 X를 기다린 뒤 0행 | 배치 **틱 전체 롤백**(정합은 지켜지지만 그 틱의 모든 만료가 다음 틱으로 밀림 · 실패 지표) | 배치 좌석 UPDATE(전체 열 · 낡은 스냅숏 `AVAILABLE`)가 X의 `RESERVED`를 덮음 → **예약 CONFIRMED + 좌석 AVAILABLE**(이중 결과 ⓒ — `v_confirmed_on_unreserved_seat` · LoadtestDataService.kt:168-169) → 그 좌석이 다시 팔린다 · cnt(X) −1(지우지 않은 홀드만큼 below) |
| FM4 응답 계약 | FM1~FM3의 진 쪽 | 5xx(계약 밖 · ADR-017이 '확정 실패'를 404·409로 가를 수 없다) — 단 V12가 있으면 FM2의 진 쪽은 409(매핑) | 진 쪽이 없다(이중 결과) |
| FM5 연장을 잃음(④) | ① 배치가 내 홀드를 만료 후보로 읽음(배치 `:now ≥` 원래 `te`) ② 내 `payment-start`가 연장 커밋(`expires_at` → `:now′ + window` · `extended_at` 기록) ③ 배치가 그 홀드를 지움 | 지금 JPA 배치(id로만 지움): 연장된 홀드가 지워진다 → 좌석 `AVAILABLE` → 내 확정 404(정합은 지키지만 연장이 무효) | 대조 배치도 같다(지워짐 · 잃음 1) |

- **15-02 실측(`current` — Adr015ReproSmokeTest)**: FM1 = X 확정 `ObjectOptimisticLockingFailureException` ← `StaleObjectStateException` → 롤백(예약 INSERT · 좌석 UPDATE 함께 사라짐) → **HTTP 500**(Spring 기본 오류 JSON — `ApiExceptionHandler` 밖) · 끝 상태 좌석 HELD · Y 홀드 1 · 예약 0 · 판정기 전부 0. FM3 = 배치가 같은 예외로 **틱 전체 롤백**(가드 감소도 사라짐 · `seat.hold.expiry.tick{outcome=failed}` +1) · X 확정 성공 · 판정기 전부 0. 정정: FM3의 '배치 DELETE가 X를 기다린 뒤 0행'은 이번 순서(X가 L-B 대기 중 커밋)에선 기다림 없이 0행이었고, flush가 좌석 UPDATE를 먼저 보내므로 X 커밋 전이면 배치가 기다리는 곳은 좌석 행이다(추정 — 미실측). 진 배치는 낡은 `AVAILABLE` 좌석 UPDATE를 **실제로 실행한 뒤** 롤백된다 — '가드가 없으면' 칸의 덮어쓰기 모양과 같다.
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
- **잠금 순서(A 전체)**: 기다리는 잠금은 **쿼터 행 → 홀드 행 → 좌석 행** 순(새 모드의 배치 A′가 이 순서 — §3.6. 정정 15-02: `current`의 JPA 배치는 쿼터 → 좌석 → 홀드지만 Q15로 새 확정 모드와 짝지어지지 않는다). 확정은 쿼터 행을 잡지 않으므로 **홀드 행 → 좌석 행 → (예약 INSERT FK 검사: 회차 · 좌석 `FOR KEY SHARE`)**. 선점의 좌석 잠금은 NOWAIT라 기다리지 않는다. 초안 §함께 검증할 경합의 '사용자(advisory) → 좌석 → 홀드'는 기본 조합(쿼터 행 — advisory 아님)과 A′ 배치 순서(홀드 → 좌석)에 맞지 않는다 — 새 모드 확정이 좌석을 먼저 잡으면 A′ 배치와 교착한다(§3.2 · 열린 질문 Q2).

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

- **잠금 순서 = 홀드 → 좌석**(초안 '좌석 → 홀드'를 바꾼다 — Q2). 좌석을 먼저 잡으면: ① ②가 좌석 S를 잠금 ② A′ 배치 B3이 내 홀드 h를 DELETE(행 잠금 획득) 뒤 B5 좌석 S UPDATE에서 ②를 기다림 ③ ②의 P1이 h에서 배치를 기다림 → **고리(40P01 — 교착 탐지로 한쪽 롤백)**. A′가 홀드 → 좌석 순서로 쓰기 때문이다(§3.6 · `[구현 검증]` #3). 정정 15-02: JPA 배치(`current`)는 좌석 → 홀드 순이라 '좌석 → 홀드' 판과 고리가 닫히지 않는다 — 대조판은 A′ 배치와 짝지어 보인다. ADR-014 B·C·D 확정(`LazyConfirmProcess` — 좌석 K2 → 홀드 K3)은 좌석 → 홀드지만, 그쪽 배치는 좌석을 `SKIP LOCKED`로 먼저 잡거나(C) 좌석을 쓰지 않아(D) 고리가 없다(ADR-014 §3.2) — A 배치는 그렇지 않다. 재현 테스트에 '좌석 → 홀드' 대조판을 넣어 40P01이 나는 것을 한 번 보인다(§설계-7 #9).
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
| 만료 배치(JPA — `current`) | 쿼터 행(정렬) → 좌석 행 → 홀드 행 (15-02 실측) | — |
| 만료 배치(A′ — 새 모드) | 쿼터 행(정렬 · B2 `FOR UPDATE OF q`) → 홀드 행(B3 DELETE) → 좌석 행(B5 UPDATE) | — (회차 · FK 잠금 없음: 홀드 DELETE는 참조하는 쪽이고 B5는 FK 열을 바꾸지 않는다) |
| 정리 ① | 회차(배타) → (홀드가 있으면 멈춤 · 없으면 쿼터 행 삭제) | — |
| 확정 current | FK 검사(회차 · 좌석 KEY SHARE) → 좌석 행 → 홀드 행 (15-02 실측) | — |
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

- **모드 혼합 금지(15-08 리뷰 OQ15-1 · 받아들인 한계)**: 위 표는 모든 인스턴스가 **같은 `seat.confirm.mode`** 로 도는 것을 전제한다. `current`(JPA 확정 · JPA 배치 — 좌석 → 홀드 flush · 낡은 스냅숏 좌석 덮어쓰기)와 새 모드(홀드 → 좌석 · A′)가 한 DB에 동시에 붙으면 교착 짝(§3.5)과 FM3 덮어쓰기가 다시 열린다. 그래서 모드 전환 · 되돌리기는 **모든 앱 인스턴스를 멈춘 뒤 새 값으로 함께 띄운다**(롤링 전환 금지). 기동 검사는 다른 인스턴스의 모드를 볼 수 없으므로 이것은 운영 절차 규칙이다.

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

- **0행이 아니면 배포를 멈춘다 — 자동 복구하지 않는다**: 중복 확정 1건 = 결제한 사용자 둘이다(어느 쪽을 남길지는 사업 결정 · 남지 않는 쪽은 ADR-017 보상 대상). 사람이 고른 뒤 쓰는 복구 틀(한 트랜잭션 · 쿼터 행을 먼저 잠근다 — ADR-014 §2.4 규칙 ①과 같은 뜻):

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
  - 좌석은 `RESERVED` 그대로(확정 1건이 남는다). 취소한 예약은 행으로 남긴다(지우지 않는다 — 결제 uid 추적 · ADR-017 입력). 다시 센 값은 0 이상이라 V5 CHECK(V5__user_hold_quota.sql:6)에 걸릴 일이 없다.
  - R0과 V12 사이에 새 중복이 생겨도 V12가 23505로 실패하고 앱이 뜨지 않는다(무음 아님) → R0부터 다시.
- **기본 위치에 두어서 생기는 일(사용자 결정의 대가 — 코드 사실)**:
  - 기존 테스트 2건이 정면으로 어긋난다: `LoadtestEndpointsTest` '같은 좌석의 CONFIRMED 2건 → 중복 확정'(LoadtestEndpointsTest.kt:108-114 — 같은 좌석에 CONFIRMED 2건을 INSERT) · `IndexMigrationTest` '인덱스는 중복을 막지 않는다 — … 같은 좌석 CONFIRMED 2건이 모두 들어간다'(IndexMigrationTest.kt:21-). 둘 다 ADR-000 §1 '문제를 가리지 않을 것'(기준선 스키마에 유니크 없음 — V1__init.sql:1-2)을 지키는 테스트다. 기준선 재현은 `spring.flyway.target=7`로(같은 파일 :37의 `target=1` 방식 — 11은 기본 위치에 없어 T12) — Q7.
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
- **DB 상태 검사(`run` — 마이그레이션 뒤 · Flyway가 꺼진 구성에서도 빠지지 않게 — HoldStrategyStartupCheck.kt:40)**: ③ 트리거(V14) — 규칙 6을 DB 사실로 다시(있으면 `strategy ≠ optimistic` · `mode = optimistic`이면 있어야 함 · 그 밖의 모드에서 남는 것은 무해 — §3.3 되돌리기). V12 인덱스 — **INVALID면 모든 모드 거부** · 없으면 `mode = current`는 WARN(기준선 재현 `target=7`(T12) — ADR-001·003 조건) · `mode ≠ current`는 `unique-index-optional=true`가 아니면 **거부**(규칙 7과 같은 뜻 · Q14 정정). V13 열 — `mode = conditional-extend`면 있어야 한다(없으면 거부 · `target` 12 이하로 띄운 경우).
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
3. ② '좌석 → 홀드' 판이 **A′ 배치**(홀드 → 좌석)와 40P01을 내는지(대조 테스트). 정정 15-02: JPA 배치는 좌석 → 홀드(실측)라 이 대조의 짝이 아니다.
4. Hibernate `PESSIMISTIC_WRITE`가 PostgreSQL에서 `FOR UPDATE`인지 `FOR NO KEY UPDATE`인지 — 확정 중 같은 좌석 선점 NOWAIT 패배 여부(§3.5 마지막 행).
5. V12 생성 시간 · `reservation` 쓰기 차단 길이(운영 크기 사본 · .164 시드의 예약 행 수) → 트랜잭션 안 그대로 대 `CONCURRENTLY`. `CONCURRENTLY` 판이면: Flyway 11.7.2가 PostgreSQL의 비트랜잭션 실패를 이력에 실패 행으로 남기는지 · `flyway repair`가 그 행만 지우는지 · 재시도 절차(§설계-4) 끝에서 `indisvalid`.
6. Flyway 순서 함정: §설계-4의 계단식 테스트 4곳 · 선택 위치를 V12 · V13 뒤에 더한 DB의 거부 시점(관문 `PENDING`과 같은 오류 코드인지 · 스키마 무변경인지) · ADR-013 전환의 outOfOrder 1회(apply.sh 기록 ① `-outOfOrder=true` · 앱 첫 기동 `spring.flyway.out-of-order=true`) 뒤 **플래그 없이** 다시 기동해도 검증이 순서 밖 적용 이력을 받아들이는가(test-local.sh 새 경우 ⓓ). **실측(15-07 · 11-05 · PG 16.15 · Flyway 11.7.2)**: 관문 `validateWithResult`는 순서 밖 미적용을 `RESOLVED_VERSIONED_MIGRATION_NOT_APPLIED`(PENDING 묶음과 같은 코드)로 봐 거부로 분류하지 않고, 거부는 `flyway.migrate()` 검증(FlywayValidateException)에서 난다 — **스키마 변경 전**(V10 객체 0 · 이력 · 스키마 지문 무변경). 기록 ①은 플래그가 있어야 한다(없으면 V9에서 같은 검증 실패 · 이력 무변경). outOfOrder 1회 뒤 **플래그 없이 다시 기동해도 통과**(적용 0) — test-local.sh ⓐ~ⓓ(direct · cli) · Adr015OutOfOrderContractTest. 부수: 트랜잭션 안 V12가 23505로 실패하면 이력 행이 남지 않아 repair 없이 R1 · R2 뒤 재기동하면 적용된다(같은 실행의 앞 버전은 각자 커밋되어 남는다).
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
- V12 유무 두 판(정정 2026-11-04 — post-fix 재점검: `target=11` 판에는 V13 `extended_at`이 없어 A′ 배치 B3이 열 없음으로 죽고, ③은 V14 트리거가 없어 기동 거부된다): **'V12 없음' 판 = 모든 마이그레이션(V12 · V13 · 필요하면 V14 위치)을 적용한 뒤 테스트 준비 단계가 `DROP INDEX` 로 V12 인덱스만 지운 DB** + `seat.confirm.unique-index-optional=true`(테스트·loadtest 프로필 한정 키 — 앱 로직만 보이게 · 새 모드는 이 키 없이는 인덱스 없는 DB에서 뜨지 않는다 — 규칙 7·N11) · 기본 판(V12 유효). 그래서 ①~④ 모두 두 판이 성립한다. `current`의 기준선 재현(V12 앞 옛 스키마)은 `target=7`(기본 위치 — T12)을 쓴다(새 모드는 그 판에서 돌지 않는다). 규칙 7·N11의 예외 조건도 '`target` < 12 명시' 대신 '인덱스 없음 + 테스트 키'로 읽는다(아래 N11 행과 같은 뜻).
- 기대: 대조군 이중 결과 ≥ 1(FM1 · FM2('V12 없음' 판) · FM3) · 대조 배치 잃음 ≥ 1(FM5) · `current` = #1 결과대로(추정: 이중 결과 0 · FM1 · FM3 진 쪽 500 · FM2는 V12 없음 500 / 있음 409 + `unique_violation` · FM3 틱 롤백) · ①~④(A′) 이중 결과 0 · 진 쪽 계약 코드 · 5xx 0 · 40P01 0 · FM3 틱 커밋 · `extension_lost` 0 · `v_counter_mismatch` 0 · `unique_violation` 0.

#### 8.2 .164 부하 측정 (확정 경합)

- 시나리오 `confirm-contention`(k6 — 새 디렉토리 `k6/ADR-015`): 짧은 TTL(초 단위) · 확정 요청을 만료 경계 부근에 몰리게 보내는 흐름(선점 → 대기 ≈ TTL → 확정) · 만료 직후 같은 좌석을 노리는 재선점 VU · 같은 홀드 이중 확정 비율 · ④ 셀은 확정 전에 `payment-start`. 확정 지연 주입 `seat.confirm.loadtest.delay`(loadtest 프로필 전용 · 자리 = 모드별 '확인 뒤 · 첫 쓰기 앞': current = ProductSeat.confirm 뒤 save 앞(ConfirmReservationService.kt:47과 :49 사이) · ① = `:now` 뒤 C1 앞 · ② = P2 뒤 P3 앞(잠금을 쥔 채) · ③ = O1 뒤 O2 앞).
- 지표: 확정 처리량 · 확정 p99(상위 1% 응답 시간) · 선점 p99 · DB CPU · 응답 코드 분포(404 · 409 HOLD_EXPIRED · 409 SEAT_NOT_HELD · 5xx) · 40P01 수 · 배치 틱 실패 수 · 배치 틱 시간 · 문장 수(JPA 대 A′ — #15) · `seat.hold.expiry.revert{result}` · `seat.confirm.unique_violation`(새 모드 기대 0 · `current`는 FM2 몫이 있을 수 있다) · `seat.confirm.unexpected` · **`seat.confirm.converted_after_expiry{clock=db|app}`**(건수 · `전환 시각 − expires_at` 최대 — 기대값 없음 · 모드별 분포) · (④) `seat.confirm.extend{result}` · `seat.confirm.extension_lost` · `seat.hold.expiry.deleted{extended}` · k6 `ext_then_lost` · 연장 수 · 홀드 수명.
- **이중 결과 수 = 0**(끝 판정기 — LoadtestDataService.kt:140-234): `v_duplicate_confirmed_seats` · `v_reserved_without_confirmed` · `v_confirmed_on_unreserved_seat` · `v_hold_on_unheld_seat` · `active_on_reserved_seats` · `double_sold_seats` · `v_counter_mismatch` — 모두 기대 0. .164에는 V12가 있으므로 앱 가드가 뚫린 중복 확정은 `unique_violation`으로 보인다 → 그것도 0이어야 한다.
- 하네스: ADR-014 §결정-3의 keep-alive 수정(긴 생각 시간 앞 `Connection: close`)을 먼저 넣는다 — 같은 EOF가 5xx 지표를 흐리지 않게.
- 셀 수 · 반복 · 길이 · 문턱(G — 관문) · '이기면 교체' 조건은 측정 task(ADR-014 측정-8과 같은 틀).

### 설계-9 열린 질문 (사용자 · 메인이 정할 것 — 질문마다 권장안 1개)

**정해진 것(15-01b에서 표에서 뺐다 — 출처)**: Q1 재현 스모크 먼저(§인터뷰 2026-11-04) · Q4 ④ 확정 문장 = ①(§인터뷰) · Q5 `payment-start` API(§인터뷰) + 재호출 = 첫 연장만 · 표지 열(§인터뷰 '설계 선검증 뒤' → §3.4) · Q6 유니크 위반 매핑 = A 확정(`current` 포함) · B·C·D 500(지적 8 → §설계-3 · §설계-4) · Q7 V12 기본 위치 + 기존 테스트 수정 · 기준선 `target=11`(§인터뷰 — 15-04에서 `target=7`로 정정 · T12) · Q8 순서 함정 = ADR-013 전환 절차에 outOfOrder 1회(§인터뷰 '설계 선검증 뒤' → §설계-4) — 계단식 테스트 4곳은 첫 단계에 `target` 고정(권장안 그대로) · Q10 A 배치 = 최소 수정을 범위에(§인터뷰 재합의 → §3.6) · 틱 전체 롤백 개선은 NEXT · Q14 V12 검사 = INVALID 거부 · 새 모드는 없으면 거부(지적 4 → §설계-5 규칙 7).

| # | 질문 | 권장안 | 누가 |
|---|---|---|---|
| Q2 | ②의 잠금 순서 | **홀드 행 → 좌석 행**(초안 '좌석 → 홀드' 대신 — 새 모드의 짝인 A′ 배치 순서와 같게 · 정정 15-02: JPA 배치는 좌석 → 홀드지만 Q15로 새 모드와 짝지어지지 않는다) · '좌석 → 홀드'는 대조 테스트로만 | 사용자(§인터뷰: 권장안으로 둠) |
| Q3 | ③의 버전 올리기 | **(나) 상태 변경 트리거 — 번호 V14 · 선택 위치 `db/migration-confirm-version`**(③ 셀만 · 짝 검사는 마이그레이션 앞 — §설계-5 규칙 6) — `@Version` 매핑은 기각 | 사용자(권장안으로 둠) |
| Q9 | 손잡이 이름 · 값 | **`seat.confirm.mode` = current · conditional · pessimistic · optimistic · conditional-extend**(기본 current — 배치도 함께 고른다 · **§결정(2026-11-06)으로 기본 conditional** — 범위 밖 구성은 current 명시) · `payment-window` · `extend-min-remaining` · `loadtest.delay` · `unique-index-optional` | 메인 |
| Q11 | 같은 홀드 이중 확정의 두 번째 응답 | **404 HOLD_NOT_FOUND 그대로**(새 모드) — `current` + V12는 409 SEAT_NOT_HELD(매핑)로 갈린다는 점을 기록만. 같은 `paymentUid` 재시도의 멱등 응답은 ADR-017 · NEXT 몫 | 사용자(권장안으로 둠) |
| Q12 | 지연 주입 방식 | **재현 테스트 = 래치(L-C · L-B · L-B′ · L-E — §설계-8.1) · 부하 = `seat.confirm.loadtest.delay`(loadtest 프로필 전용 · 기본 0 · 자리 '확인 뒤 · 첫 쓰기 앞')** | 메인 |
| Q13 | 적용 조합 | **기본 조합만**(3b + counter-upsert-precheck · `expiry-mode = sweep`) — 그 밖은 기동 거부(§설계-5) | 사용자(권장안으로 둠) |
| Q15 (새) | A′ 배치를 확정 손잡이에 묶나, 따로 켜나 | **묶는다**(`current` = JPA 배치 · 그 밖 = A′) — 기준선 칸 무변경 · 잘못된 짝 없음. 셀 비용에 섞이는 배치 몫은 틱 시간 · 문장 수로 따로 남긴다(#15). 'A′만 켠 current' 셀이 필요해지면 그때 키를 더한다 | 메인(사용자 확인 권장 — 측정 셀 구성에 닿는다) |
| Q16 (새) | 남은 시간이 이미 window 이상일 때 `payment-start` | **200 + 지금 `expires_at` · 표지를 남기지 않는다**(연장이 늘리지 않으므로 '첫 연장'을 쓰지 않는다 — 뒤의 재호출이 연장을 받을 수 있다). 대안 '표지만 남김(그 홀드는 더 연장 못 함)'은 결제를 늦게 시작한 사용자에게 불리하다 | 사용자 |
| Q17 (새) | ③ 트리거 번호를 V13 → V14로 옮기는 것 | **옮긴다** — V13은 ④ 표지 열(기본 위치)이 쓰고, 선택 위치를 기본 위치보다 위 번호로 두어 나중에 ③을 켤 때 순서 함정을 피한다 | 메인 |

- **다시 읽은 파일(15-01b · 줄 번호 근거)**: ExpireHoldsService.kt(1-63) · ProductSeat.kt(1-87) · ExpiryCounters.kt(1-211) · ConfirmReservationService.kt(1-67) · LazyConfirmProcess.kt(1-76) · LazyExpiryBatch.kt(104-241) · LazyHoldProcess.kt(183-199 revertSeats) · ExpiryRule.kt(10-13) · AppPathInjectHook.kt(1-13) · QuotaCleanup.kt(110-140) · HoldStrategyStartupCheck.kt(1-200 · 349-363) · ApiExceptionHandler.kt(1-67) · SeatHold.kt(16-24) · application.yml(1-68) · ops/online-v9/apply.sh(1-39 · 96-113 · 225-240 · 248-259) · ops/online-v9/test-local.sh(1-30) · k6/ADR-014/compose.expiry.yml(1-36) · db/migration*/ 목록 · V1__init.sql(15-41) · V6__backfill_user_hold_quota.sql(1-31).

### 설계-10 blind 테스트가 드러낸 해석 — 메인 결정(15-03 · 2026-11-05)

> blind 테스트 워커(구현 미열람)가 문서만으로 기대값을 정하지 못한 12곳. 아래가 구현 · 테스트의 기준이다(설계 §5 · §8.1보다 이 절이 우선).

| # | 모호점 | 결정 |
|---|---|---|
| T1 | 규칙 7 · N11 — '키만 있고 `target` 없음 → 거부'가 §8.1 정정('인덱스 없음 + 테스트 키')과 충돌 | **키 `unique-index-optional=true`는 test · loadtest 프로필에서만 받는다(그 밖은 기동 거부)**. 인덱스가 있으면 키는 아무 일도 하지 않는다(기동). 인덱스가 없을 때 새 모드를 띄우는 길은 이 키 하나 · `target` 값으로 판정하지 않는다 |
| T2 | N11 '`target=11` + 키 → 기동' | **새 모드는 V13 열이 있어야 한다**(A′ B3 `RETURNING extended_at` — 모든 새 모드) → 규칙 7을 '`mode ≠ current`이면 `spring.flyway.target`이 주어졌을 때 13 이상'으로, `run` 검사의 V13 없음 거부를 ④만이 아니라 **새 모드 전부**로 넓힌다. `target=11`은 `current` 기준선 재현 전용 |
| T3 | 거부 메시지 | `IllegalStateException` · 메시지에 위반한 키 이름(`seat.confirm.*` · `seat.hold.*`)을 넣는다 — 테스트는 원인 사슬의 ISE + 'confirm' 포함으로 본다 |
| T4 | 기대 0 지표의 '등록 안 됨' | 없는 지표 = 0으로 읽는 것을 허용(늘어나야 하는 칸은 없으면 실패). `unique_violation`은 태그 없이 하나 |
| T5 | ② L-C 자리 | **P1(`… FOR UPDATE`) 앞**으로 옮긴다(P2 뒤면 배치가 홀드 행에서 기다려 FM1을 만들 수 없다). '잠금을 쥔 채' 칸은 교착 점검 래치(L-S)가 맡는다. 부하 지연 주입 자리(§8.2 ② = P2 뒤 P3 앞)는 그대로 — 측정의 뜻(잠금 보유 비용)이 다르다 |
| T6 | `payment-window` · `extend-min-remaining` 기본값 | 테스트는 2m · 5s 명시. 앱 기본값은 측정 task가 고정 — 그 전까지 ④ 모드는 두 키를 명시해야 기동(규칙 4가 `payment-window > 0`을 요구 · 기본 0) |
| T7 | `payment-start` 200 본문 | `{holdId, expiresAt}` · `expiresAt` = ISO-8601(지금 홀드 응답과 같은 Jackson 기본) |
| T8 | 규칙 3 범위 | 모드 무관(`current` + delay도 loadtest 프로필이 아니면 거부) |
| T9 | FM5의 ①②③ · 'current + 연장' 셀 | 재현 테스트에서 제외(API가 ④에만 있다) — 측정 task 판단으로 남긴다 |
| T10 | 판정기 제외 항목 | `v_stale_held_seats` · `held_no_active` · `expired_hold_rows`는 시간 · 유예에 따라 기대값이 갈려 재현 판정에서 뺀다(부하 끝 판정에서는 기존 규칙대로) |
| T11 | 확정 직후 선점 응답 | 409 SEAT_NOT_AVAILABLE(지금 계약 — ApiExceptionHandler.kt:49) |
| T12 | (관찰) 지금 코드에서 '기본 위치 + target=11/12' 기동이 entityManagerFactory 생성에서 실패 | **원인(15-04 실측)**: Flyway `failOnMissingTarget`(기본 참) — `No migration with a target version 11 could be found`. V11은 선택 위치(seat-map)에만 있어 기본 위치 구성에서 `target=11`은 앱과 무관하게 항상 실패한다. **결정(메인 · 11-05)**: 기준선 재현 = `spring.flyway.target` = **그 구성에서 V12 앞 마지막 버전**(기본 위치만 = `7` — 스키마는 V11까지와 같다). `failOnMissingTarget=false`는 쓰지 않는다(오타 target이 조용히 통과 — 무음 실패). 이 문서의 다른 곳에 남은 '기준선 재현 `target=11`'은 모두 이 뜻으로 읽는다 |

- **T1의 받아들인 한계(15-08 OQ15-5)**: 테스트 클래스패스 `config/application.properties`의 `spring.profiles.include=test`는 `bootTestRun`(로컬 실행)에도 붙는다 — 로컬에서는 `unique-index-optional=true` + 인덱스 없는 DB로 새 모드가 뜰 수 있다. 운영 jar에는 이 파일이 없다(영향 없음). 운영에서 `test` 프로필을 켜면 T1이 우회되는 것도 같은 한계(운영자 설정).

- 기존 테스트 충돌(15-04 몫 · 15-03 목록): LoadtestEndpointsTest.kt:108-114 · IndexMigrationTest.kt:15-18 · :21- · Adr014ImplStartupTest.kt:173(최대 버전 11 → 13) · Adr009DefaultsTest.kt:254 · StartupCheckOrderTest.kt:93 · 계단식 4곳(§설계-4).

### 설계-11 구현 리뷰(15-08 루프 1)가 바꾼 것 — 위 절보다 우선

> 출처: 작업 폴더 log 리뷰 ledger R15-1~10 · 코드 커밋 b9ee9c65. 위 절의 해당 문장은 이 절로 읽는다.

- **지연 주입(§설계-8.2)**: `seat.confirm.loadtest.delay`는 이제 실제로 잔다 — current = `seat.confirm` 뒤 · 예약 save 앞 / ①④ = `:now` 뒤 · C1 앞 / ② = P2 뒤 · P3 앞 / ③ = O1 뒤 · O2 앞. 0(기본)이면 호출이 없다(current 문장 · 순서 무변경). loadtest 프로필 전용(규칙 3).
- **만료 뒤 전환 지표(§설계-3)**: 전환 문장 RETURNING은 `clock_timestamp() > expires_at AS late` 대신 `(EXTRACT(EPOCH FROM clock_timestamp() - expires_at) * 1000000)::bigint AS late_us`(지연량 µs · `late` = `late_us > 0`). `converted_after_expiry{clock=db|app}`는 **둘 다 커밋 뒤**(afterCommit) 기록한다 · 새 지표 **`seat.confirm.converted_after_expiry.lateness{clock}`**(Timer — 건수 · max — §설계-3의 '전환 시각 − expires_at 최대'). current는 `clock=app`만.
- **V12 판정(§설계-5)**: DB 상태 검사의 V12 판정은 `run`만이 아니라 **마이그레이션 앞 관문**에서도 돈다(이번 기동이 V12를 만들지 않을 때 — 이력에 이미 있거나 target · 위치 밖). 판정 = `indisvalid` + `indisunique` + 표 `reservation` + 열 `seat_id` 하나 + 부분 조건 `status='CONFIRMED'`. 모양이 다르면 INVALID와 같이 **모든 모드 거부**(사유를 메시지에) · 없으면 새 모드는 `unique-index-optional` 없이 거부 · current는 WARN. 관문과 run이 같은 판정 함수를 쓴다.
- **유니크 위반 매핑(§설계-4 · N6)**: '인덱스 이름 일치' = PostgreSQL 서버 오류의 `constraint` 필드가 `uq_reservation_confirmed_seat`(SQLSTATE 23505와 함께). 빌드: postgresql 드라이버 `runtimeOnly` → `implementation`(산출 jar 동일).
- **ADR-013 전환 outOfOrder(§설계-4)**: apply.sh 단계 0이 outOfOrder 기록 ①을 부를 판이면 위치의 '9 이하 미적용'(`state0.pending_le9`)이 정확히 {9}인지 먼저 본다(아니면 fail_step 0 · 아무것도 쓰지 않음). 기록 ① 뒤 이력 차이(`record.cli.history_diff`)가 V9 한 행인지 다시 본다(아니면 class `history-diff` · exit 1 · 쓴 행은 사람이 확인). test-local.sh 실패 주입 2건(V4 위치 오입력 · V9 폴더에 V4 사본).
- **기존 하네스(§설계-4 '기본 위치에 두어서 생기는 일')**: ADR-015 이전 k6 하네스는 flyway target을 'V12 앞 마지막 버전'으로 고정했다 — ADR-001 · 002(on) · 003 · 005 · 006 · 007 · 008 = 7 · ADR-009 = run.sh가 위치 집합으로 계산(7 · 8 · 9 · 10) · ADR-014 = 11(plan14 위치 — seat-map 포함). ADR-013 하네스는 슬롯마다 옛/새 SHA가 섞여 공통 target을 둘 수 없어 **`--sha-new`에 V12가 있으면 거부**(exit 2)로 대신했다(슬롯별 target은 NEXT). 결과 파일은 바꾸지 않았다.
- **모드 혼합 금지**: §3.5 표 아래 글머리(전 인스턴스 정지 후 전환).
- **마감 회차 홀드 연장(§3.4 · 리뷰 OQ15-12 · 사용자 결정 2026-11-05 '받아들이고 기록')**: E1은 회차 마감(판매 마감 · 정리 ①)을 보지 않는다 — 마감 회차의 홀드도 결제 시작이면 window까지 연장된다. 결제 중인 사용자의 확정을 지키는 쪽이고, 정리 ①은 활성 홀드가 있으면 건너뛰었다가(Adr015CleanupDeadlockTest 관측) 연장이 끝난 뒤 처리한다 — 그만큼 정리가 늦어진다(상한 = window).
- **V13 열 판정(§설계-5 · R15-11)**: V12와 같이, 이번 기동이 V13을 적용하지 않을 때(이력에 이미 있음 · target · 위치 밖) 새 모드는 `seat_hold.extended_at` 열을 **마이그레이션 앞 관문**에서 확인한다(없으면 거부 · V14 트리거 등 스키마 무변경). run 검사와 같은 판정 함수.
- **교착 짝 실측(§3.5 · [구현 검증] #9 · R15-12)**: 정리 ①(`cleanup.mode=schedule`)을 켠 컨텍스트에서 확정(current · ①~④) ↔ 정리 ① · A′ ↔ 정리 ① · A′ ↔ 선점 짝을 래치로 돌렸다(Adr015CleanupDeadlockTest 22칸) — 40P01 0(`pg_stat_database.deadlocks` 증가분 0 포함). §3.5 표의 이 짝들의 '(추정 — 동시성 테스트 #9)'는 이 실측으로 읽는다. 관측: current에서는 정리 ①이 확정의 FK KEY SHARE를 기다린다(기다림 — 고리 아님) · 새 모드에서는 정리 ①이 활성 홀드를 보고 건너뛴다.
- **history-diff 실패 복구(R15-15)**: 위 'ADR-013 전환 outOfOrder'의 '쓴 행은 사람이 확인'은 `ops/online-v9/README.md` 절차 + `ops/online-v9/06-history-diff-recover.sql`(미리보기 기본 · 이력 맨 끝 연속 행만 삭제 · 수 불일치 시 실패)로 한다 — 권장 재개 = V9까지 남은 행을 지우고 고친 `--locations`로 apply.sh 다시(test-local.sh에서 실행 확인).
- **ADR-013 §4.4 절차와의 관계(15-08 R15-13)**: 닫힌 ADR-013은 고치지 않고 여기와 `ops/online-v9/README.md`에 둔다. 옛 방식 DB에 V12 · V13이 이미 있으면(`apply.sh` 단계 0 `state0.max_applied > 9`) 적용판 기록 ①이 `-outOfOrder=true`를 한 번 붙인다(자동). 이어지는 ADR-013 ④ · ⑤의 T1 새 앱(또는 그림자 앱) **첫 기동은 `apply.sh` 출력 `next.app_first_boot_out_of_order`가 참일 때(`max_applied > 10`) 그 한 번만 `SPRING_FLYWAY_OUT_OF_ORDER=true`** 로 띄우고, V10 이력(success) 확인 뒤 다음 기동부터 뺀다. 플래그 없이 띄우면 FlywayValidateException으로 기동이 멈춘다(스키마 무변경 — [구현 검증] #6). ①~③(앱이 V9 · V10을 적용)도 `max_applied > 10`이면 같은 1회 플래그가 필요하다.

## 측정 전 고정 규칙

> **고정(task 15-09 · 2026-11-05)** — 이 절은 첫 캠페인 회차 전에 커밋한다(이 커밋 = 측정 전 고정). 측정 뒤에는 바꾸지 않는다 — 바꿔야 하면 그 캠페인은 superseded로 두고 새 고정 커밋부터 다시 잰다(ADR-014 측정 전 고정 규칙과 같은 원칙). 하네스 = `k6/ADR-015/`(고정 시점 커밋 86ffbcd1). 리뷰 = 작업 폴더 log 리뷰 ledger M15-1~35(듀얼 리뷰 3루프 + 사용자 override 1회 · 타깃 재점검 — PF-m1: V12 판정 불가여도 모드 결론을 판정 불가로 바꾸지 않는다 — V12는 모든 모드(current 포함)의 기본 위치라 모드 선택과 독립이고, 결론에 '운영 적용 전 V12 판정 필요'를 강제한다). 아래 머리말의 '초안' · '메인 결정 필요' 표현은 리뷰 이력이며, 사용자 결정(D-1~D-11 · 2026-11-05) · 메인 결정(D-12~D-15)은 확정이다.

> 이 초안은 ADR-015 본문에 들어갈 '## 측정 전 고정 규칙' 절의 후보다. 메인이 리뷰한 뒤 ADR에 옮기고 커밋한다(그 커밋 = 측정 전 고정). 캠페인 첫 회차를 시작한 뒤에는 고치지 않는다. 측정 뒤 다르게 읽어야 하면 원문은 그대로 두고 '측정 뒤 재해석' 행을 따로 단다(ADR-014 측정 머리말 :505와 같은 규칙).
> 기준 SHA = `b104fc4a`(`feat/adr-015-confirm-atomicity`). 정답 기준 = 명세 §0(이기면 교체) · §5(부하 측정) · §9 15-09/15-10 · ADR-015 §설계-8.2 · §설계-11(지연 주입 자리 · 지표 이름 — 이 절이 우선) · §설계-7 측정 몫(#5 · #10 · #11 · #12 · #15 · #17).
> 틀 = ADR-014 '측정 전 고정 규칙'(docs/adr/ADR-014-hold-expiry.md:503~). 값 = 같은 (모드, 셀) 유효 3회의 [최소–최대]. **범위 비겹침으로 낮다** = 한쪽 최대 < 다른 쪽 최소(같은 값이면 겹침 — ADR-014 :507).
> 근거가 없는 숫자는 지어내지 않고 **메인 결정 D-n**(후보 2개 + 권장)으로 둔다. 하네스(`k6/ADR-015/`)는 권장값을 기본으로 넣었다 — 바꾸면 `scripts/plan15.py` 한 곳만 고친다. **D-1~D-11은 사용자가 권장안으로 고정했다(2026-11-05 — 끝 '사용자 결정' 표).**
> **리뷰 루프 1 수정(15-09-fix1 · M15-1~M15-15)**: 결론 경로(판정 불가 갈래) · 양성 대조(모드별 실제 충돌 표지) · ④ 경계 흐름 · 재선점 조밀 구간 · FM1 겹침 · V12 완전성 · #15 배치 문장 묶음을 고쳤다. 새 고정값은 측정-4 표 끝 'fix1' 행 · 새 메인 결정은 D-12~D-15.
> **리뷰 루프 2 수정(15-09-fix2 · M15-17~M15-28)**: 새 모드 양성 대조를 서버 논리 증거로 바꿨다(M15-26 — ①③④ = 응답 끝 실측 시각 · 시계 차 오차 보정으로 서버 `:now < e`를 세운 404 HOLD_NOT_FOUND · ② = B3 대기 표본) · 'A도 같은 문턱'(M15-17) · FM1 겹침 대상(M15-18) · 잘못된 거부(M15-19) · cost 셀 재선점 끔(M15-20) · (상태, code) 계약 쌍(M15-21) · V12 모양(M15-22) · ④ 본문 · 확정 수(M15-23) · Hikari 풀(M15-24) · PC 메모리 하한(M15-25) · D-12~D-15 확정(M15-27) · 결론 갈래 정리(M15-28). 새 고정값은 측정-4 끝 'fix2' 행.
> **리뷰 루프 3 수정(15-09-fix3 · M15-29~M15-35 + Opus OQ2 · 사용자 결정 2026-11-05 — 측정 전 마지막 수정)**: current 양성 대조 · G2(b)(c)를 **app.log 원인 귀속 값**으로(M15-29 — 0행 DELETE 갈래만) · V12 단계를 캠페인 완료 관문에(M15-30 · M15-31) · ④ 잘못된 거부 기준 = 서버가 준 가장 늦은 expiresAt(M15-32) · **race 비용 지표를 3단 · G3에서 뺌(M15-33 — 사용자 결정 · 설명 열)** · 'current 유지' 사유 갈래(M15-34) · 인용 정정(M15-35) · 'A도 같은 문턱' 시간 초과 묶음(OQ2). 새 고정값은 측정-4 끝 'fix3' 행.
> 이 절의 번호: 지표 **M1~M12** · 관문 **G-H(하네스) · G-E(환경) · G-F(모드 실패) · G1~G4(교체)** · 메인 결정 **D-1~D-11**. §설계의 FM · N · Q와 섞이지 않는다.

### 측정-0 용어

| 기호 | 뜻 |
|---|---|
| 모드 | `seat.confirm.mode` 값 다섯(`current` · `conditional` ① · `pessimistic` ② · `optimistic` ③ · `conditional-extend` ④ — SeatConfirmProperties.kt:12-13 · ConfirmMode). `current` = 기준(후보 아님) · 나머지 넷 = 후보 |
| race 셀 · cost 셀 | race = 확정 지연 주입으로 '확인 뒤 · 첫 쓰기 앞' 창을 열어 만료 경계 경합을 만드는 셀(판정 지표 셀) · cost = 지연 없음 · 높은 도착률(비용 지표 셀) |
| 세션 · sid | k6 main 시나리오 반복 하나 = 사용자 하나가 자기 좌석 하나를 선점 → (④면 payment-start) → 확정. sid = `exec.scenario.iterationInTest`(공통 난수 씨앗 · 좌석 · 사용자 번호를 정한다) |
| e · el · c | e = 그 확정의 기준 만료(선점 201이 준 서버 expiresAt · ④ 경계 흐름은 payment-start 200의 expiresAt = e′ — CSV `conf{e}`) · **el = 그 확정 앞에 서버가 준 가장 늦은 expiresAt**(④는 기준 흐름도 payment-start 200의 e′ · 다른 모드는 e와 같다 — CSV `conf{el}` · 잘못된 거부의 기준 · M15-32) · c = 확정 송신 **계획** 오프셋(서버 시각 e + c에 보내려 한다) ~ U[−(delay + 300ms), +300ms] |
| ts · te · ε | ts · te = 확정 송신 · 응답 끝의 **실측** 시각(k6 PC 시각 + 준 시계 차 — CSV `conf{ts,te}`) · ε = 시계 차 오차 상한 = max(\|준 값 − 회차 앞 표본\|, \|준 값 − 뒤 표본\|) + 왕복/2 최대 + 1ms(ADR-009 `clock_offset` · ADR-014 측정-4 :672 규칙 — 요약기 `clock_err_ms`). 참 서버 시각 ∈ [태그 − ε, 태그 + ε] |
| 오프셋 묶음 pre · win · post(계획 — M1 분포 · FM1 겹침 후보 · 설명) | pre: c < −delay(지연을 다 자도 만료 전에 쓴다 — 경합 창 밖) · win: −delay ≤ c < 0(만료 전 확인 · 만료 뒤 쓰기 — 경합 창) · post: c ≥ 0(만료 뒤 송신 — 409 HOLD_EXPIRED · 404가 계약, ④는 연장돼 200) |
| 짝(dup) | 같은 홀드로 확정 두 개를 동시에 보낸 세션(FM2 — http.batch) |
| 재선점 VU | **race 셀만**(cost 셀 비율 0 — M15-20) 같은 sid의 좌석을 다른 사용자가 선점 시도(201이면 멈추고 이탈) — ① **조밀 구간** e − 20ms부터 20ms 간격으로 e + delay + 100ms까지(race만 · 경합 창 '만료 직후 ~ 확정 쓰기' 안 — M15-6) ② **성긴 구간** e + 0.5초부터 1초 간격으로 e + 13초까지. ④ 경계 흐름 세션은 기준이 e′ |
| ④ 경계 흐름(xb) | race 셀 ④ 세션의 절반(EXT_BOUNDARY_F 0.5)은 payment-start 200이 준 연장 만료 e′ 기준 **e′ + c**에 확정한다(M15-5 — 연장 뒤에도 ④에서 경합 창이 열린다). 나머지 ④ 세션은 e + c(연장이 지켜 주는가 — ext_then_lost). CSV `conf{fl=x}` |
| 경합 증거(양성 대조 — M15-3 · **M15-26 서버 논리 증거**) | 그 회차에서 **실제 충돌**이 있었다는 표지(회차 전체 · 단독 확정만 — 짝 진 쪽은 N2 정상 결과): **current = 회차 app.log(run.sh가 `docker logs`로 거둔 app.log.gz)에서 0행 DELETE 갈래로 귀속된 확정 5xx(FM1) + 배치 틱 실패(FM3)**(M15-29 — 측정-4 fix3 행: 맨 위 예외 `ObjectOptimisticLockingFailureException` ← `StaleObjectStateException`(SeatHold) · `AbstractDeleteCoordinator` 프레임 · 확정 = `ReservationController.confirm` · 틱 = `ExpireHoldsService.expire` 프레임 · k6 첫 요청 − 2초 뒤 사건만). 다른 예외 5xx · 틱 실패는 증거가 아니라 설명 열(`evidence_desc.unattributed` · 예외 종류 표) · 로그를 못 읽으면 None(판정 불가). 루프 2의 '계획 win 단독 상태 ≥ 500 + 틱 실패 지표'는 원인을 가리지 않아(codex 루프 3 F1) 설명 열(`win_single_5xx_k6`)로 · **①③ = 기준 흐름 · ④ = 경계 흐름(fl=x) 단독 확정 중 `404 HOLD_NOT_FOUND` ∧ te + ε − delay + 여유(20ms) < e ∧ te − ts ≥ delay**(서버 `:now < e`인데 C1 · O2 0행 = 지연 중 배치가 지움 — 논리는 측정-4 fix2 행) · **② = B3 대기 표본에서 'A′ B3가 Lock 대기 ∧ 막은 세션 = idle in transaction · 마지막 문장 ② P2' 에피소드 ≥ 1**(scripts/b3wait-sampler.sh · race 20ms). 루프 1 근사(계획 오프셋 win 404 · pgss B3 max)는 **판정에서 빼고 설명 열**(`evidence_desc` · `pess_b3`)로만 |
| FM1 겹침 | **계획 win ∧ 단독(dup=0) 확정(④는 경계 흐름 fl=x만 — M15-18)**의 [송신, 응답 끝] 안에 같은 좌석 재선점 201(응답 끝 서버 시각)이 든 건수(post · 짝 · ④ 기준 흐름을 넣은 값은 설명 열 `fm1_overlap_all`) — '배치가 푼 좌석을 남이 잡은 뒤 그 확정이 쓴다' 순서(FM1-Y)가 부하에서 생겼다(M15-6 · CSV `conf{ts,te}` · `rehold_ok{t}`) |
| API 수준 이중 결과 | 같은 좌석에 확정 200과 재선점 201이 둘 다 나옴(어느 순서든 계약 위반 — 모든 모드 기대 0) |
| ≫(실질적으로 높다) | X ≫ Y ⇔ X가 Y보다 범위 비겹침으로 높다 **그리고** X 중앙값 > Y 중앙값 × 1.10(ADR-014 측정-0 :522 그대로) |
| 판정 창 | [t0, t0 + D_arr) · t0 = k6 setup_data.startedAt(첫 요청 — ADR-014 측정-0 :515와 같은 정의). 지연 · 응답 분포(M1)는 완료 초가 창 안인 요청만 · 실패(G-F — 계약 밖 확정 코드 포함 · M15-10)와 경합 증거는 회차 전체(창 밖 · 수렴 포함)로 넓게 센다 |

### 측정-1 측정 조건 (모든 셀 공통 · 고정)

- **서버 .164 하나 · 앱 1대 · lb 없음**(k6 PC → 앱 8101 직접) — ADR-014 측정-1 :530과 같다. 모드 혼합이 구조적으로 생기지 않는다(측정-11).
- **자원**: L4 · DB tmpfs 12g(DB 메모리 14g) · 앱 2g 세대형 ZGC(ADR-014 측정-1 :531 그대로 — run.sh RES_ENV14).
- **기본 조합 고정**: `expiry-mode = sweep`(새 모드 기동 규칙 1) · `strategy = pessimistic-nowait` · 매수 = 업무 앱 기본 `counter-upsert-precheck`(규칙 2 · application.yml:49-51). 카운터 구성 키는 넣지 않는다(업무 앱 기본값 — configprops로 대조).
- **TTL 30초 · 배치 주기 10초**: TTL = ADR-014 t30 셀 선례(plan14 MAIN · 측정-2 :546) · 주기 = application.yml:40 기본값(ADR-014 측정-1 :534 '모든 셀 고정'과 같다).
- **Flyway = 업무 앱 기본 위치(application.yml:16 — migration · updated-at-trigger · incremental) · target 없음(최신 — V12 · V13 적용)** · `optimistic`만 `classpath:db/migration-confirm-version`(V14 트리거 — 규칙 6) 추가. 회차마다 V12 인덱스 유효(indisvalid · indisunique · 부분 조건) · V13 열 · V14 트리거 유무(③만)를 시드 확인에서 대조(`schema-mismatch`). `unique-index-optional`은 주지 않는다(.164는 V12 있음 — §설계-5 규칙 7).
- **주입**: `seat.confirm.loadtest.delay`(§설계-11 자리 — current = `seat.confirm` 뒤 save 앞 · ①④ = `:now` 뒤 C1 앞 · ② = P2 뒤 P3 앞 · ③ = O1 뒤 O2 앞)만. race 200ms · cost 0(D-1). `inject-app`은 끈다(규칙 5).
- **연결 상한 · nofile**: Tomcat `max-connections` 20,000 · 앱 nofile 65,536(ADR-014 측정-1 재해석 :540 그대로 — 동시 VU가 keep-alive 연결을 쥔다).
- **keep-alive 처방(ADR-014 §결정-3 :1090 · lib14.js `closeBeforeLongThink`)**: 생각 시간 ≥ 50초인 요청 앞은 `Connection: close`. 이 측정의 생각 시간은 약 TTL(30초)이라 지금 값에서는 붙지 않지만 모든 '잠들기 앞' 요청(선점 · payment-start)에 건다 — TTL을 늘려도 같은 EOF가 5xx 지표를 흐리지 않게. 그래도 시간 초과가 아닌 코드 0이 1건이라도 나오면 하네스 무효(`k6-code0` — G-H).
- **이미지 고정 · k6 바이너리 고정 · 측정기(`--probes`) 켬 · 마감 없음** — ADR-014 측정-1 :537-539 그대로(run.sh가 ADR-014 lib14 · ADR-009 lib를 동결 사본에서 쓴다).
- **pg_stat_statements(D-4 — 사용자 결정 켬)**: 다섯 모드 모두 같게(`shared_preload_libraries` — compose.confirm.yml `PG_PRELOAD` · plan15 `PGSS_DEFAULT = 1`). #15가 읽는다(② B3 max는 fix2부터 설명 열 — 판정은 아래 B3 대기 표본). `pgss=0`이면 dry-run이 막는다(fix1 — 그대로 둔다: #15 결측).
- **B3 대기 표본기(fix2 · M15-26)**: race 셀 **다섯 모드 모두** 20ms(plan15 `B3WAIT_PERIOD_MS` — 표본 질의 부하가 ②의 race 비용 지표에만 섞이지 않게) · cost 셀 끔. 서버에서 psql 세션 하나(PGAPPNAME `seatlab-harness-b3`)로 `pg_stat_activity` · `pg_blocking_pids`를 읽는다(ADR-014 psql-sampler.sh와 같은 방식 — 그 파일은 고치지 않고 ADR-015 `scripts/b3wait-sampler.sh`) → `timeline-b3wait.jsonl`(회차 끝 서버 사본으로 복구 — lib14 `recover_copy`).

### 측정-2 셀 설계 — 셀 수 · 반복 · 길이 · 시간

| 셀 | 모드 | 도착률 | D_arr | 지연 | gracefulStop | 수렴 | 시드 | 셀 수 |
|---|---|---|---|---|---|---|---|---|
| race | 5 | 20 세션/s | 600초(D-8) | 200ms(D-1) | 50초 | 52초(TTL + 22 — ADR-014 plan14 CONVERGE_EXTRA_S) | 1회차 × 5만 석 | 5 |
| cost | 5 | 300 세션/s(D-2) | 300초(D-8) | 0 | 50초 | 52초 | 3회차 × 5만 석 | 5 |

- **반복 3회**: ADR-010~014와 같다 — '범위 비겹침'의 최소 단위(ADR-014 측정-2 :549). **합계 = 10셀 × 3회 = 30회**.
- **부하 축은 셀 둘뿐이다**: §설계-8.2의 흐름(만료 경계 확정 · 재선점 VU · 이중 확정 비율 · ④ payment-start)은 축이 아니라 **한 흐름 안의 고정 비율**이다(측정-3). 축을 늘리면(예: 지연 0 · 100 · 200ms × 이중 비율 2단) 셀이 4배가 된다 — 이번에는 판정에 필요한 둘(경합이 있는 셀 · 비용 셀)만 둔다.
- **gracefulStop 50초** = TTL 30 + 재선점 끝 13 + 여유 7(창 끝에 시작한 세션이 끝날 시간). 
- **④ race만 gracefulStop 160초**(= 50 + 창 120 − 리드 10 — 경계 흐름 세션이 e′ ≈ e + 110초까지 산다 · fix1) → 회차 약 16분 · 최대 VU main 2,850 · rehold 1,500(dry-run).
- **회차 시간 = k6(D_arr + gracefulStop) + 200초**: 200초 = ADR-014 TTL 30초 셀 실측의 'k6 밖' 몫(아래) — race 약 14.2분 · cost 약 9.2분.
  - 근거(읽기만 — `k6/ADR-014/results/20261103-adr014-fd494aea/{a20-t30,a50-t30}/*/rep*/meta.json` 24회): a20-t30 회차 432~444초 − k6 약 242~252초 = 약 190초 · a50-t30 616~624초 − k6 약 423~433초 = 약 190초(수렴 52초 · 시작 대기 약 87초 포함) + 다음 회차까지 간격 약 9초(예: a20-t30 B rep1 끝 10:39:00 → C rep1 시작 10:39:09).
- **총 시간 추정**: 부하 30회 **약 5.9시간**(`plan15.py --dry-run` — fix1의 ④ race +1.8분 × 3) + #5 V12 단계 약 0.5시간(측정-10 · 크기 2 × 3회 × 배포 · 기동 · 시드 · 생성 약 4분 — 추정) + 재측정(ADR-014 실측: 계획 21.5시간 대 실제 약 23.3시간 = +8% · 같은 비율이면 약 0.5시간) + 스모크(시작 조건 ① 7회 약 1.4시간) ≈ **약 8시간**.
- **순서**: 회차 우선(회차 1의 10자리 → 2 → 3) · 회차 안 묶음 race → cost · 묶음 g · 회차 r의 모드 = 다섯 모드를 (r + g) mod 5칸 회전(ADR-014 측정-2 :577 '균형 회전'과 같은 뜻 — 6묶음에서 모드마다 서로 다른 자리 4곳 이상 · selftest 확인).
- **회차마다 DB 볼륨 · 컨테이너를 새로 띄운다**(ADR-014 측정-2 :578 그대로) — 이것이 곧 모드 전환 절차다(측정-11).

### 측정-3 부하 모델

#### 3.1 main 흐름 (세션 sid)

1. 사용자 = 1,000,000 + sid · 좌석 = 세션마다 하나(회차 k = 1 + ⌊sid ÷ 50,000⌋ · 좌석 id = (k − 1) × 50,000 + sid mod 50,000 + 1 — LoadtestDataService.kt:36 좌석 id 규칙). 좌석이 겹치지 않아 첫 선점은 늘 201이어야 한다(아니면 `hold-fail` — G-H).
2. 선점(201 → e). 생각 시간 = 약 TTL + c.
3. (④만) 서버 시각 e − 10초에 `POST /api/holds/{id}/payment-start`(PaymentStartController.kt:28-34) → 200이면 연장된 만료 e′(= 그 시각 + 2분). **흐름 구성상 기대값**(PaymentStartService.kt:38-51 · :75-81): 남은 시간 10초 > extend-min-remaining 5초 · < 창 2분 · 홀드당 한 번 → 모든 payment-start가 200 `extended` · `already` = `not_needed` = `too_late` = 0(M15-4).
4. 서버 시각 e + c에 확정 — **④ 경계 흐름 세션(race · 비율 0.5)은 e′ + c**(e′ = payment-start 응답의 expiresAt · payment-start가 200이 아니면 확정하지 않는다 — pay 분포가 G-F로 드러낸다). 짝 세션(비율 0.1 — D-3)은 같은 홀드로 확정 두 개를 동시에(http.batch).
5. 응답 기록: `conf{cb, dup, st, code, seat, fl, ts, te, e}`(code = **상태 무관 본문 code** — lib15 `confirmCodeOf` · M15-21 · fl = b 기준 | x 경계 · ts · te = 송신 · 응답 끝 서버 시각 ms · e = 기준 만료) · `sess{fl, n}`(확정할 세션 — 계획 확정 수 · M15-23) · `conf_skip{fl, n}` · `pay_body_bad{fl}`(④ 경계 세션이 연장을 못 받아 확정을 건너뜀 · 200 본문 expiresAt 못 읽음) · `pay{st, code, fl}` · (④) 연장 뒤 e′ − 1초 앞에 보낸 확정이 404 · 409 HOLD_EXPIRED면 `ext_then_lost`(짝 중 하나라도 200이면 세지 않는다 — 짝 진 쪽 404는 N2의 정상 결과).

#### 3.2 rehold 흐름 (재선점 VU)

- 같은 도착률 · 같은 시작 시각 · 같은 sid. 공통 난수로 rehold 속성(비율 race 0.5 — D-3 · **cost 0 — M15-20**: cost 셀은 경합 판정 몫이 없고 ④만 연장 때문에 재선점이 13초 동안 409를 거듭 받아 요청 수가 구조적으로 많다 → G3가 모드가 아니라 부하 구성을 잰다)인 세션만. 난수를 뽑는 순서는 그대로라 다른 속성(c · 짝 · xb)은 같다. 기준 = 반복 시작 + TTL(④ 경계 흐름 세션은 + 창 − 리드 = + 110초 ≈ e′). **조밀 구간**(race만): 기준 − 20ms부터 20ms 간격으로 기준 + delay + 100ms까지 17번 → **성긴 구간**: 기준 + 0.5초부터 1초 간격으로 기준 + 13초(배치 한 주기 + 3초)까지(사용자 4,000,000 + sid) — 201이면 멈추고 이탈. 서버가 준 e는 main만 알므로 'e ≈ 반복 시작 + TTL'로 맞춘다(같은 executor · 같은 도착률 — 차이는 선점 응답 지연만큼).
- **왜 조밀 구간인가(M15-6)**: 15-09 설계의 재선점 시작(e + 0.5초)은 경합 창(e ~ e + delay ≤ 200ms) 뒤라 FM1-Y 순서가 부하에서 생길 수 없었다. 조밀 구간이 창을 덮는다(plan15 dry-run이 '조밀 구간 ⊇ [0, delay]'를 점검). 비용: race 재선점 요청 약 20 × 0.5 × 17 ≈ 170/s 추가(다섯 모드 같다) · cost 셀은 창이 없어(지연 0) 끈다.
- ④ 기준 흐름 세션은 홀드가 연장돼 재선점이 모두 409다(설계상 — 측정-8 D-7). 경계 흐름 세션의 재선점은 e′ 둘레를 노린다.

#### 3.3 왜 이 흐름이 경합을 만드나 (추정 — 스모크로 확인)

- win 묶음 비율 = 200 ÷ (200 + 600) = 25% → race 5 세션/s가 '만료 전 확인 · 만료 뒤 쓰기'다. 그 창(평균 약 100ms) 안에 배치 틱(10초 주기)이 들면 FM1(current = 고아 DELETE 0행 → 500 · 새 모드 = C1 0행 → 404) 또는 FM3(current = 틱 롤백)이다. 확률 ≈ 0.1 ÷ 10 = 1% → **race 회차당 약 30건**(추정 — 20/s × 600초 × 25% × 1%).
- **fix1 로컬 스모크(측정-15 — PG 16 · 앱 jar 1대 · TTL 8초 · 지연 200ms · 20/s · .164 값 아님)**: 배치 **10초** · 120초 창에서 current 경합 증거 6(win 단독 500 0 · 틱 실패 6/14) · ① 6(win 단독 404) · ② 1(B3 max 119ms) · FM1 겹침 current 0 · ① 7 · ② 0. 배치 2초 · 30초 창에서 current 4(500 1 · 틱 실패 3) · ① 3 · ② 1(B3 max 143ms ≥ 50) · ③ 7 · ④ 6(경계 흐름 404) · FM1 겹침 current 1 · ① 7 · ② 0 · ③ 3 · ④ 5. 선형으로 늘리면(600초 ÷ 120초 = 5배) .164 race 회차당 current 약 30 · ① 약 30 · ① FM1 겹침 약 35(추정 — 측정-14).

- **fix2 로컬 스모크(새 표지 — PG 16 tmpfs · 앱 jar 1대 · TTL 8초 · 배치 2초 · 지연 200ms · 20/s · 30초 · ε = 1ms(같은 PC — 시계 차 0) · .164 값 아님)**: ① 서버 논리 증거 **8**(단독 404 HOLD_NOT_FOUND 45건 중 — 나머지 37건은 늦은 도착) · ③ **5**(계획 win 404 7건 중 2건은 지연을 안 잔 O1 404 — te − ts < delay라 뺐다) · ④ 경계 흐름 **2** · ② B3 대기 에피소드 **3**(B3 Lock 대기 표본 15 = 모두 막은 쪽이 ② P2) · ①③④ B3 대기 0 · current 경합 증거 5(틱 실패 5). 다섯 모드 모두 잘못된 거부 0(단독 te + ε < e 약 200건) · 계약 밖 쌍 0 · 계획 확정 수 = 실제(④ b 343 · x 307) · 판정기 0 · API 이중 결과 0. FM1 겹침(win 단독) ① 2 · ③ 4 · ④ 1 · current · ② 0.

#### 3.4 공통 난수

- 세션 속성(c · 짝 · rehold)은 (회차 번호, sid)를 씨앗으로 한 결정적 난수(lib15 `sessionAttrs15` — lib14 FNV-1a + mulberry32 재사용)다. 같은 회차 번호의 다섯 모드가 같은 속성 열을 받는다. main · rehold가 같은 sid로 같은 속성을 다시 뽑으므로 `dropped_iterations` > 0이면 두 흐름이 어긋난다 → 무효(G-H). ADR-014의 crn 해시 대조(측정-3.5)는 쓰지 않는다 — 속성은 씨앗에서 결정되고 dropped 0이 같은 집합을 보장한다(crn 행은 기록만).

### 측정-4 고정값과 근거 (측정 뒤 바꾸지 않는다)

| 값 | 쓰는 곳 | 근거 | 근거 종류 |
|---|---|---|---|
| 회차 3 | 측정-2 | ADR-014 측정-2 :549(ADR-010~013과 같다 — 범위 비겹침 최소 단위) | 선례 |
| TTL 30초 | 측정-1 | ADR-014 t30 셀(:546 · plan14 MAIN) · §설계-8.2 '짧은 TTL(초 단위)' | 선례 · 설계 |
| 배치 주기 10초 | 측정-1 | application.yml:40 · ADR-014 측정-1 :534 | 기본값 · 선례 |
| L4 · tmpfs 12g · 2g ZGC · Tomcat 20,000 · nofile 65,536 | 측정-1 | ADR-014 측정-1 :531 · :540 | 선례 |
| keep-alive 처방(≥ 50초 앞 `Connection: close`) | 측정-1 | ADR-014 §결정-3 :1090 · lib14.js `KEEPALIVE_SAFE_MS` | 선례 |
| race 지연 200ms | 측정-2 | 지연은 트랜잭션 안에서 잔다(ConfirmReservationService.kt:55 `@Transactional` · :121-124) — 풀 점유 ≈ 20 × 1.1 × 0.2 = 4.4 < 풀 10의 50%(`plan15 --dry-run` 점검) · 500ms면 11 > 10(풀 고갈 — 지연이 비용 지표가 아니라 대기열이 된다) | 설계 · 값은 **D-1** |
| race 도착률 20/s | 측정-2 | 위 풀 점유 상한 + 경합 건수 추정(측정-3.3 약 30건/회차) | 설계(추정) |
| cost 도착률 300/s | 측정-2 | ADR-014 맹목 300세션/s(:584 · :654) · VU 약 1.9만(미리 만든 VU 18,750 — ADR-014 경험 15,300) | 선례 · **D-2** |
| D_arr race 600 · cost 300초 | 측정-2 | 근거 없음 — race는 경합 건수(약 30) · cost는 p99 표본 수(약 9만 선점) | **D-8** |
| 오프셋 c ~ U[−(delay + 300ms), +300ms] | 측정-3.1 | win 묶음 = 지연 폭 · pre · post 각 300ms(시계 오차 허용 20ms — ADR-014 측정-4 :672의 15배 · 앞뒤 대조) | 설계 |
| 짝 0.1 · 재선점 race 0.5 · **cost 0** | 측정-3 | 근거 없음(D-3) · cost 0 = 루프 2 리뷰(M15-20 — 측정-3.2) | **D-3** · M15-20 |
| 재선점 e + 0.5 ~ e + 13초 · 1초 간격 | 측정-3.2 | 배치 한 주기(10초) + 3초 — 풀린 좌석을 놓치지 않는다 | 설계 |
| ④ payment-window 2m · extend-min-remaining 5s · payment-start = e − 10초 | 측정-3.1 | §설계-10 T6(테스트 값) · e − 10초 > min 5초 · < window | 선례(테스트) · **D-6** |
| 허용선 +10%(≫ 둘째 조건) | 측정-8 G3 · 3단 | ADR-014 측정-4 :665 ← ADR-009 사용자 결정 '선점 p99·처리량 대조군 대비 10% 이내'(docs/adr/ADR-009-counter-drift.md:259) · DB CPU에 쓰는 +10%는 선례 없음(ADR-014와 같은 보수적 선택) | 선례(p99) · **보수적 선택**(CPU) |
| 단순성 순서 ① > ② > ③ > ④ | 측정-8 4단 | 더하는 부품 수: ① 0 · ② 잠금 문장(P1 `FOR UPDATE`) 1 · ③ 선택 위치 V14 트리거 + 기동 짝 검사(규칙 6) 2 · ④ 새 API + 키 2 + 클라이언트 호출 3 | 설계 · **D-9** |
| 시작 대기 87 ± 5초 · 시계 오차 · env-stall · DB CPU 결측 규칙 | 측정-6 | ADR-014 summarize.py 상수 그대로(CLOCK_RTT_HALF_MAX_S · PRE_K6_TARGET_S · STALL_MIN_S · DBCPU_*) — 그 파일을 import해서 쓴다 | 선례 |
| #5 문턱 2,000ms | 측정-10 | ADR-013 적용판 `SET LOCAL lock_timeout = '2s'`(docs/adr/ADR-013-counter-config-cutover.md:186 — 줄 서는 시간을 시도당 2초로 묶는다) | 선례 · **D-5** |
| (fix1) ④ 경계 흐름 비율 race 0.5 · cost 0 | 측정-3.1 | race: ④ 경계 win 단독 ≈ 20 × 600 × 0.5 × 0.25 × 0.9 ≈ 1,350세션 → 경합 약 13건/회차(추정) + 나머지 절반이 ext_then_lost를 잰다 · cost: 경계 세션이 약 110초 더 살아 VU가 약 2만 늘어난다(300/s × 0.5 × 110) → 비용 셀에 넣지 않는다 | 설계(추정) · **D-12** |
| (fix1) ④ race gracefulStop 160초 | 측정-2 | 50 + 창 120 − 리드 10(경계 세션 끝 ≈ e′ + 재선점 13초) | 설계 |
| (fix1) 재선점 조밀 구간 e − 20ms ~ e + delay + 100ms · 20ms | 측정-3.2 | 경합 창 ⊂ [e, e + delay) · 확정 응답 끝 여유 100ms · 간격 20ms = 창 평균(100ms)의 1/5(로컬: 조밀 201이 시도 4~17번째에 났다) | 설계 · **D-13** |
| (fix1 → fix2 대체) 진 쪽 계약 코드 = 404 HOLD_NOT_FOUND만(HOLD_EXPIRED 제외) | 측정-0 경합 증거 | 배치가 먼저 지운 홀드 → C1 · O2 0행 → 진단 '없음' 404(AtomicConfirmProcess.kt:71-78 · :90-99 · :154-157) · SEAT_NOT_HELD는 홀드를 먼저 쥔 뒤라 경합 표지가 아니다. fix2: 계획 win 묶음 대신 아래 서버 시각 조건을 붙인다 | 코드 |
| (fix1 → fix2 설명 열) ② pgss B3 max_exec_time ≥ delay × 0.25 | 설명(`pess_b3`) | 문장 **전체** 실행 시간이라 삭제 작업 · CPU · I/O도 든다(codex 루프 2 F2 · 감사) — 판정에서 뺐다. 기록만(로컬 fix2: ② 142ms) | 실측(로컬) |
| (fix1 → fix2 대체) k6 404 · 403 = `HTTP404` · `HTTP403` | — | fix2: k6가 상태와 무관하게 본문 code를 읽고(lib15 `confirmCodeOf`) 요약기가 (상태, code) 쌍을 본다(아래 fix2 행). ADR-009 common.js는 고치지 않는다 | — |
| **(fix2) ①③④ 서버 논리 증거: 단독 `404 HOLD_NOT_FOUND` ∧ te + ε − delay + 여유 < e ∧ te − ts ≥ delay** | 측정-0 · G1 | ①④: `:now`(AtomicConfirmProcess.kt:41) 바로 뒤에 지연을 잔다(:69 → ConfirmReservationService.kt:122-124 `Thread.sleep` ≥ delay) → 참 `:now` ≤ 참 te − delay ≤ te + ε − delay < e − 여유. 그러면 C1(:73 `id · user_id · expires_at > :now`)의 조건은 그 행이 있으면 참이다(④ 연장은 expires_at을 늘리기만 한다) → 0행 = 행이 사라졌다 → 진단(:91-94)도 '없음' 404(만료면 HOLD_EXPIRED). 단독이라 같은 홀드의 다른 확정이 없고, 배치는 앱 Clock으로 expires_at ≤ 배치 now인 행만 지운다(§설계-3 '시각' · ExpireHoldsService.kt:47 `now = clock.instant()` → A′ B1 SweepConditionalBatch.kt:38 · B3 :61 · B3b :72가 모두 `expires_at <= :now` — M15-35 정정). **sweep에서 seat_hold를 지우는 자리 전수**: A′ = B3 `DELETE … USING unnest`(SweepConditionalBatch.kt:60-62) · B3b `DELETE … WHERE id = ANY`(:72) 둘뿐 · current = JPA 고아 DELETE(ExpireHoldsService.kt:51-52 → ProductSeat.kt:79-84 `isExpired(now)` 홀드 `removeAll` · orphanRemoval :40 — 틱 커밋 flush) → **지연 중 배치가 지웠다(FM1 자리)**. ③: O1(:141-147)은 지연 **앞**이라 404가 지연 없이도 난다 → te − ts ≥ delay(지연을 잤다)를 함께 요구(로컬 fix2: 계획 win 404 7건 중 2건이 지연 안 잔 O1 404). **송신 기준식(ts + ε < e − 여유 — 메인 루프 2 방향)은 송신 → `:now` 사이(앞단 대기 · 풀 대기 — 트랜잭션 시작 뒤 :now)가 묶이지 않아 증명이 아니다** → 응답 끝 기준으로 바꾸고 송신 기준 값은 설명 열(`evidence_desc.send_based`) | 코드 · 실측(로컬) |
| (fix2) 여유 20ms | 위 식 | ADR-014 측정-4 :672 시계 오차 허용(왕복/2 ≤ 20ms)과 같은 값 · Thread.sleep ms 해상도 · 컨테이너 시계 흔들림 몫(엄밀 증명에는 0이어도 되나 보수적으로 둔다) | 선례 · 보수적 선택 |
| (fix2) ε = max(\|준 값 − 앞\|, \|준 값 − 뒤\|) + 왕복/2 최대 + 1ms | 측정-0 · 위 식 · 잘못된 거부 | 참 시계 차가 회차 앞 · 뒤 표본 사이에 있다고 본다(drift 단조 — ADR-014 `clock-offset-bad`가 drift > 100ms · 왕복/2 > 20ms면 회차를 무효로 한다) · 1ms = 정수 ms 반올림 · Date.now 해상도 | 선례(ADR-014 측정-4) |
| **(fix2) ② B3 대기 표본 20ms(race 다섯 모드)** | 측정-0 · G1 | 계산(추정): ② win 단독 확정은 만료 뒤 L = delay + c ~ U(0, 200ms] 동안 잠금을 쥔다 → 동시에 '만료 뒤 잠긴 홀드' 기대 = 20/s × 0.25 × 0.9 × 0.1초 = 0.45 → 틱(10초 · 600초에 60번)이 그런 행을 만날 확률 1 − e^−0.45 ≈ 0.36 → 대기 틱 약 22. 틱이 만난 순간의 남은 지연 w는 길이 편향 잔여 분포 f(w) = (1 − w/200)/100(평균 약 67ms)라 주기 p 표본이 한 대기를 잡을 확률 = E[min(1, w ÷ p)]: **p = 20ms → 약 0.90** · ADR-009 대기 표본(0.5초) → E[w] ÷ 500 ≈ **0.13**. 회차당 잡힌 에피소드 기대 20ms ≈ 20 · 0.5초 ≈ 2.9(회차당 0일 확률 약 5.5% · 3회 중 하나라도 0 약 16% — ②가 근거 없이 판정 불가로 갈 수 있다) → 20ms를 고른다. 막은 세션 조건(idle in transaction ∧ 마지막 문장 P2 `SELECT status FROM product_seat WHERE id = $1 FOR UPDATE` — :118, 그 바로 뒤가 지연 :121)으로 ②의 지연 중 확정만 센다(다른 대기 · 다른 모드 제외 — 로컬 fix2: ①③④ 0). 관측 불가(표본 없음 · 깨짐) = `b3wait-missing`(G-H — ②만) · 에피소드 0 = 판정 불가(D-15) | 설계(추정) · 실측(로컬) |
| (fix2 → fix3 기준 el) 잘못된 거부: 단독 ∧ te + ε < **el** ∧ 상태 ≠ 200 | 측정-6 G-F | 확정의 모든 문장이 el 전에 끝났다(fix3 M15-32: el = 그 확정 앞에 서버가 준 가장 늦은 expiresAt — ④ 기준 흐름은 payment-start 200의 e′. 연장 전 e로 재면 e < te < e′ 사이 거부를 놓친다 · ④ 확정에 el이 없으면 `k6-csv-missing`) — 배치는 expires_at ≤ 배치 now(≥ e)인 행만 지우고 좌석은 HELD라 재선점(NOWAIT)도 못 잡는다 → 200 말고는 잘못된 거부(시간 초과 · 5xx 포함). 계획 c(pre 묶음 · '− 여유') 대신 실측 te를 쓴다(k6가 늦게 깨면 pre 세션도 실제로는 창 안일 수 있다) | 코드 |
| (fix2) 계약 = (상태, code) 쌍 {200 OK · 404 HOLD_NOT_FOUND · 403 HOLD_NOT_OWNED · 409 HOLD_EXPIRED · 409 SEAT_NOT_HELD} | M1 · G-F | §설계-3 공통 에러 계약 · N4 · ApiExceptionHandler.kt:46-55. 본문 없음(NOCODE) · 엉뚱한 쌍(409 HOLD_NOT_FOUND 등)은 계약 밖 | 설계 · 코드 |
| (fix2) V12 모양 = 앱 기동 관문과 같은 기준 | 측정-6 `schema-mismatch` | HoldStrategyStartupCheck.kt:335-361(`confirmedSeatIndexState` · `normalizePredicate`): 유효 · 유니크 · 표 reservation · 열 seat_id 하나 · 식 열 없음 · 부분 조건(괄호 · 공백 · `::text` · `::character varying` 제거)이 `status='CONFIRMED'`(로컬 fix2: 정확 1 · 다른 열 · 열 둘 · `<>` · 다른 값 · 조건 없음 · 비유니크 · 식 열 · 다른 표 모두 0) | 코드 · 실측(로컬) |
| **(fix3) current 원인 귀속 = app.log 0행 DELETE 갈래(M15-29)** | 측정-0 경합 증거 · G2(b)(c) · G4 | 15-02 실측(ADR-015 :124 · log 11-04 23:47): current FM1 = 확정 `ObjectOptimisticLockingFailureException` ← `StaleObjectStateException` → 롤백 → HTTP 500 · FM3 = 같은 예외로 틱 롤백. **.164 시험 1(int164 current race · 하네스 8388e198) app.log.gz 실물 형식**: 확정 5xx = `[dispatcherServlet] … Request processing failed: org.springframework.orm.ObjectOptimisticLockingFailureException` + `Caused by`/다음 줄 `org.hibernate.StaleObjectStateException … [com.jun.labs.seatreservation.domain.SeatHold#…]` + `AbstractDeleteCoordinator` · `ReservationController.confirm(ReservationController.kt:27)` 프레임 · 틱 실패 = `o.s.s.s.TaskUtils$LoggingErrorHandler : Unexpected error occurred in scheduled task` + 다음 줄 같은 예외 + `ExpireHoldsService.expire(ExpireHoldsService.kt:46)` 프레임(그 회차: 5xx 28건 · 틱 4건 모두 이 꼴 · 다른 예외 0 — 유니크 위반 723건은 409 갈래라 5xx 아님). 귀속 = 맨 위 예외 ∈ {OOLFE · StaleObjectState} ∧ SeatHold의 StaleObjectState ∧ DELETE 행 수 검사 프레임. 확정 짝(FM2)의 진 쪽은 유니크 인덱스에서 INSERT가 먼저 막혀(→ 409) DELETE까지 가지 않는다 → 0행 DELETE를 만드는 쪽은 배치뿐(그 회차의 짝 둘 다 500인 2쌍도 배치가 지운 것). G2(b)(c)의 current 값 = 이 귀속 수 · 새 모드 값 = 전체(보수적 — 귀속 안 된 새 모드 5xx는 G-F 그대로). 귀속 안 된 current 5xx(k6 5xx − 귀속) · 틱 실패(지표 − 귀속)는 설명 열에 예외 종류와 함께. 시각 창 = k6 첫 요청(서버 시각) − 2초 뒤(예열 · 시드 단계 사건 제외) | 코드 · 실측(.164) |
| **(fix3) #5 V12 = 캠페인 완료 관문(M15-30 · M15-31)** | 측정-8 결론 · 측정-10 | campaign.sh가 `v12-stage.json`(skipped · rc)을 남기고 요약기가 결론 옆에 적는다: 단계 누락(`--skip-v12` · 기록 없음 · 폴더 없음) · 실패(rc ≠ 0) · 필수 값 결측(자리마다 트랜잭션 판 `build_ms` · `probe_blocked_ms` · `probe_started_inside` · CONCURRENTLY 판 `build_ms` · `probe_ms` · `probe_started_inside` · 두 판 `valid` · 시드 rc · 시드 행 수) → **'V12 판정 불가(사유)'**. 교체 결론은 그대로 두되 **'— 운영 적용 전 V12 판정 필요'**를 붙인다(`decision.v12_required_before_apply` — 교체 커밋 · 운영 적용 앞에 #5를 다시 잰다). V12 판정이 서면 결론 옆에 `#5 V12: <판정>` | 설계(codex 루프 3 F2 · F3) |
| **(fix3) 3단 · G3 = cost 셀 지표 셋만(M15-33 — 사용자 결정 2026-11-05)** | 측정-8 | race 셀 확정 p99 · DB CPU에는 ④ 재선점 구조(연장 때문에 409를 거듭 받는다) · ② 잠금 보유 지연 · 경계 흐름 VU가 섞여 모드가 아니라 부하 구성을 잰다(Opus 루프 3 F2) → 설명 열(`desc_ranges`)로만. 결과 문서용으로 모드별 재선점 요청 수 `req.rehold`(race · cost)를 함께 낸다 | 사용자 결정 |
| **(fix3) 'A도 같은 문턱'의 시간 초과는 요청 이름과 무관하게 묶는다(Opus 루프 3 OQ2)** | 측정-7 | 환경 원인 시간 초과는 그때 떠 있던 요청에 아무렇게나 걸린다 — current에 그 셀 시간 초과가 하나라도 있으면 X의 어느 요청 이름 시간 초과든(④ `pay_start` 포함 — current에는 그 요청이 없다) 판정 불가. 다른 ○ 항목(선점 · 재선점 5xx · 잘못된 거부 · 방식 정지)은 그대로 (항목, 셀) 단위 | 설계 |

### 측정-5 지표 · 원자료 · 계산식

| # | 지표 | 원자료 | 계산 |
|---|---|---|---|
| M1 | 확정 응답 분포 | k6 `conf{cb, dup, st, code}` | (계획 묶음 × 단독/짝 × (상태, 본문 code)) 건수 — 창 안 |
| M2 | 확정 5xx | k6 `http_req_duration{name=confirm}` **상태 ≥ 500만**(G2(b) · 2단의 current 값은 app.log 0행 DELETE 귀속 수 — M15-29 · 측정-4 fix3 행)(시간 초과 error_code 1050은 `confirm_timeout`으로 따로 — G-F `timeout` · M15-3a) | 회차 전체 합 · 서버 교차 `http.server.requests{outcome=SERVER_ERROR}`(actsnap15 `server_5xx`) |
| M3 | 배치 틱 실패 | `seat.hold.expiry.tick{outcome=failed}`(ExpireHoldsService.kt:59) | k6 직전 → 회차 끝 증분(G2(c) · 2단의 current 값은 app.log 0행 DELETE 귀속 수 — M15-29) |
| M4 | 확정 처리량 | M1의 OK | 창 안 OK ÷ D_arr(초당) — 열린 모델이라 '능력'이 아니라 성공 비율의 표지 |
| M5 | 확정 p99 · 선점 p99 | k6 `confirm:200` · `hold:201`(창 안) | 99번째 백분위(ADR-014 summarize `p99`) |
| M6 | DB CPU | docker stats 10초(`timeline-server.jsonl` seatlab-db-1) | 창 안 평균 · 결측 규칙 ADR-014 측정-4 :670 |
| M7 | 40P01 | `pg_stat_database.deadlocks`(table-before → table-end) | 증분 |
| M8 | 유니크 위반 · 예상 밖 | `seat.confirm.unique_violation` · `seat.confirm.unexpected` | 증분(current는 짝 FM2 몫이 있다 — 기대 > 0 · §설계-4 :344) |
| M9 | 만료 뒤 전환 | `seat.confirm.converted_after_expiry{clock=db\|app}` · `.lateness{clock}` | 건수 증분 · **lateness 최대 = 판정 창 끝 스냅숏 MAX('감쇠 최대' — Micrometer Timer MAX는 약 2분 창 감쇠라 회차 끝 값은 줄어 있다 · M15-15)** · 평균 = TOTAL_TIME ÷ COUNT 증분(기대값 없음 — 설명 · 경합 증거가 아니다) |
| M10 | ④ 연장 | k6 `pay{st,code,fl}`(보낸 수 · 200 수 · 비200 분포) · main `hold:201` 수(예정) · `seat.confirm.extend{result}` · `seat.confirm.extension_lost` · k6 `ext_then_lost` · `seat.hold.expiry.deleted{path=sweep,extended}` | 증분 · 기대: 보낸 수 = 예정 · 200 = 보낸 수 · extended = 200 수 · already = not_needed = too_late = 0 · 잃음 0(M15-4) |
| M11 | 판정기(이중 결과) | `/internal/consistency?graceSeconds=20`(LoadtestDataService.kt:140-234) — 수렴 뒤 | `v_duplicate_confirmed_seats` · `v_reserved_without_confirmed` · `v_confirmed_on_unreserved_seat` · `v_hold_on_unheld_seat` · `active_on_reserved_seats` · `double_sold_seats` · `v_counter_mismatch` · `v_over_limit_users` · `multi_active_seats` · `v_duplicate_hold_seats` · `v_held_without_hold` · `expired_hold_rows` · `v_stale_held_seats` — 모두 기대 0(열이 없거나 숫자가 아니면 `end-check-failed`) |
| M12 | API 수준 이중 결과 | k6 `conf` OK 좌석 ∩ `rehold_ok` 좌석 | 교집합 크기 — 기대 0 |
| M13 | FM1 겹침(FM1 양성 대조 — M15-6 · M15-18) | `rehold_ok{t}` · 같은 좌석 `conf{ts,te}`(계획 win ∧ 단독 · ④ fl=x) | 재선점 201 시각 ∈ [확정 송신, 응답 끝] 건수(회차 전체) · 설명 `fm1_overlap_all`(모든 확정) |
| M14 | 경합 증거(양성 대조 — M15-3 · M15-26 · M15-29) | 측정-0 정의(모드별) · `conf{st,code,ts,te,e}` · clock-offset.json · k6-window.json · `timeline-b3wait.jsonl`(②) · **app.log.gz(current)** | 회차 전체 건수(② = B3 대기 에피소드 수 · current = 0행 DELETE 귀속 확정 5xx + 틱 실패) · ε · 로그를 못 세우면 None |
| M15 | 잘못된 거부(M15-19 · M15-32) | `conf{dup,st,te,el}` · ε | 단독 ∧ te + ε < el ∧ 상태 ≠ 200 건수(회차 전체 · (상태, code)별) · ε 못 세움 = None |
| M16 | 계획 대 실제 확정 수(M15-23) | `sess{fl,n}` · `conf{fl}` · `conf_skip{fl,n}` | 흐름별 sess n 합 = conf 수 + conf_skip n 합(다르면 `confirm-count-mismatch`) |
| 설명 | 재선점 성공 · 배치 행 연산 · 배치 문장 · **race 확정 p99 · race DB CPU · 재선점 요청 수(fix3 — M15-33)** | `rehold_ok` 수(조밀 · 성긴) · k6 `req.rehold`(race · cost) · M5 · M6의 race 값 · `pg_stat_user_tables`(seat_hold n_tup_del · product_seat n_tup_upd · reservation n_tup_ins) · actsnap15(`missing_pairs_sweep` · `revert_sweep`) · pg_stat_statements 배치 문장 묶음(M15-9 — 측정-12) | 측정-12 |

### 측정-6 관문 G — 회차 유효 · 모드 실패

**G-H 하네스 무효(→ 재측정 — 자리마다 2번)** · **G-E 환경 무효(→ 재측정 — 따로 2번)**: 하나라도면 status = `invalid:<사유>`(판정에 쓰지 않는다).

| 사유 | 조건 | 종류 |
|---|---|---|
| `k6-exit-N` · `k6-csv-missing` · `k6-summary-missing` · `k6-window-missing` | k6 rc ≠ 0 · 원자료 없음 | G-H |
| `dropped-iterations` | 시나리오 어느 쪽이든 dropped > 0(main · rehold의 sid가 어긋난다) | G-H |
| `k6-code0` | 시간 초과(error_code 1050)가 아닌 코드 0 ≥ 1(하네스 EOF 류 — ADR-014 06c) | G-H |
| `hold-fail` | main 첫 선점 **4xx · 본문 없음** ≥ 1(자기 좌석 — 대역 · 시드 결함). 첫 선점 5xx · 시간 초과는 여기가 아니라 G-F(5xx · timeout — 모드 결함을 무효로 숨기지 않는다 · M15-12) | G-H |
| `pay-shortfall` · `pay-unexpected` | ④: payment-start 보낸 수 < main 선점 201 수(반복이 끊겼다) · ④ 밖 모드에서 payment-start가 나감(M15-4) | G-H |
| `pgss-missing` | pgss 켬인데 `pgss-end.json` 없음 · 깨짐 | G-H |
| `confirm-mode-mismatch` · `cond-config-mismatch` | configprops `seat.confirm.mode` · delay · ④ 키 · TTL · 주기 · 전략 · 매수 · Flyway 위치 · Tomcat · nofile · **Hikari 풀 실측(`hikaricp.connections.max` = [10] — M15-24 · ADR-014 plan14.py:310 선례)**이 계획과 다름(plan15 `--config-check`) | G-H |
| `schema-mismatch` · `seed-mismatch` | V12 모양(앱 기동 관문과 같은 기준 — 측정-4 fix2 행 · M15-22) ≠ 1 · V13 열 ≠ 1 · V14 트리거 유무가 모드와 안 맞음 · 시드 행 수 | G-H |
| `confirm-count-mismatch` | 흐름별 계획 확정 수(sess n 합) ≠ 실제 conf 수 + 건너뜀(conf_skip n 합) — 반복이 확정 전에 끊겼다(M15-23 — 확정 p99 · 처리량 모집단이 조용히 줄지 않게) | G-H |
| `b3wait-missing` | ② race: B3 대기 표본(`timeline-b3wait.jsonl`) 없음 · 깨진 줄 · 못 읽는 줄(M15-26 — ② 양성 대조 입력) | G-H |
| `app-instances-not-one` | seatlab app 컨테이너 ≠ 1(모드 혼합 금지 — 측정-11) | G-H |
| `warmup-failed` · `pre-k6-mismatch` · `clock-offset-bad` · `db-cpu-missing` · `path-gap` · `waits-gap` · `probe-missing` · `gc-log-missing` · `container-log-missing` · `app-metric-missing` · `end-check-failed` · `table-stats-missing` · `pc-link-missing` | ADR-014 측정-9 사유와 같은 정의(summarize14 상수 · Inputs 결측 규칙 — 0으로 채우지 않는다) | G-H |
| `pc-mem-missing` | k6 PC MemAvailable 표본(`timeline-client.jsonl`)이 판정 창을 덮지 못함(파일 없음 · 깨짐 · 공백 > 30초 — ADR-014 R5 ② 이식 · M15-25) | G-H |
| `env-stall` · `pc-link-down` · `pc-mem-low` | ADR-014 측정-9 env-stall(MR-6 — 측정기 5초 무응답 ∧ GC < 100ms ∧ DB 쪽 원인 없음) · PC 링크 끊김 · 판정 창 MemAvailable 최소 < 4GiB(ADR-014 plan14 `PC_MEM_RUN_MIN_KB` — M15-25) | G-E |

**G-F 모드 실패(회차는 유효 · 그 모드 탈락 자료)** — 1건이라도면 그 모드는 성공이 아니다(문턱 · 순위에 쓰지 않는다). 'A' 열 = 측정-7 **'A도 같은 문턱'** 대상(○)인가.

| 항목 | 적용 | 근거 | A |
|---|---|---|---|
| 판정기 M11 열 ≠ 0 | **모든 모드** — current의 위반은 'A 결함'(G2(a) 입력 · ADR-014 측정-7 :767 'A는 기준'과 같은 처리) | §설계-8.2 '이중 결과 수 = 0' | × |
| API 수준 이중 결과 M12 ≠ 0 | 모든 모드(current = A 결함) | 측정-0 | × |
| 40P01 M7 ≠ 0 | 모든 모드(current = A 결함 — §3.5 'current 짝 고리 없음') | N8 | × |
| 선점 · 재선점 5xx ≠ 0(요청 이름별) | 새 모드 넷(current도 기록 — `5xx_non_confirm`) | N4 | ○ |
| 확정 5xx ≠ 0 | 새 모드 넷(current는 기대 > 0 — FM1) | N4 | × |
| payment-start 5xx ≠ 0 | ④(current는 그 경로가 없다) | N4 | × |
| 계약 밖 확정 (상태, code) 쌍 ≠ 0(**회차 전체** — M15-10 · M15-21) | 새 모드 넷 | N4 · §설계-3 | × |
| 시간 초과(1050) ≠ 0(요청 이름별) | 모든 모드 기록(새 모드는 실패 · current는 경합 증거에 넣지 않는다 — M15-3a) · 'A도 같은 문턱'은 요청 이름 무관 묶음(OQ2 — 측정-7) | N4 | ○ |
| **잘못된 거부 M15 ≠ 0(M15-19)** | 모든 모드(current는 기록 — 'A도 같은 문턱' 비교 자료) | N4 · 측정-4 fix2 행 | ○ |
| payment-start 비200 ≠ 0 · 서버 `extend` 갈래 ≠ 기대(extended = 200 수 · already = not_needed = too_late = 0) · **200 본문 expiresAt 못 읽음(`pay_body_bad` — M15-23: 본문은 앱이 만든다 · k6 파서 결함이면 모든 ④ 회차에 나서 시작 조건 ① 스모크가 먼저 잡는다)** | ④(M15-4) | §3.4 '200 {holdId, expiresAt}' · 흐름 구성(측정-3.1 ③) | × |
| `unique_violation` ≠ 0 · `unexpected` ≠ 0 | 새 모드 넷 | §설계-4 :344 · §3.1 | × |
| `revert{path=sweep,result=not_held\|kept_active}` ≠ 0 | 새 모드 넷 | §3.6 B5 'A 불변식 ② 위반이라 설계상 0' | × |
| `extension_lost` ≠ 0 · `ext_then_lost` ≠ 0 | ④(다른 모드는 구조적 0) | N10 | × |
| 방식 정지(정지 덩어리 중 env가 아닌 것) | 모든 모드(기록 · 새 모드는 실패) | ADR-014 측정-9 | ○ |

### 측정-7 성공 조건 (G1)

- 새 모드 X의 상태는 셋 중 하나다(M15-1 · M15-14):
  - **실패** = 유효 회차에 G-F가 1건이라도 — 'A도 같은 문턱' 대상(○) 항목은 아래 규칙에서 '실패'로 남은 것만(유효 반복이 모자라도 실패가 먼저다).
  - **판정 불가** = 실패가 아닌데 ⓞ 'A도 같은 문턱'으로 판정 불가가 된 항목이 있다(아래) ⓐ 두 셀 중 하나라도 유효 < 3(재측정 한도 뒤 — 사유 무관) ⓑ **X 자신의 race 경합 증거**(측정-0 — 모드별 실제 충돌 표지)가 어느 회차든 None 또는 0 ⓒ ①③④: race 3회 **FM1 겹침 합 = 0**(M12 = 0의 뜻이 서지 않는다 — M15-6 · ②는 구조상 0이라 이 조건이 없다 — 측정-14) ⓓ 2 · 3단 지표 중 하나라도 범위를 낼 수 없음(값 None).
  - **성공** = 위 둘이 아님. 판정 불가는 성공도 실패도 아니다 — 결론에서 측정-8 '판정 불가 결론'으로 간다(ADR-014 측정-8 :800 ⓑ와 같은 처리).
- **A도 같은 문턱(M15-17 — ADR-014 측정-7 :765 · 재해석 :766 · 측정-8 :800 ⓐ와 같은 꼴)**: G-F 표의 ○ 항목(선점 · 재선점 5xx · 시간 초과 · 잘못된 거부 · 방식 정지)은 current에도 같은 식 · 같은 창으로 계산한다(요청 이름별 · 셀별). 새 모드 X가 어긴 (항목, 셀)을 **current도 같은 셀의 유효 회차에서 어겼으면** 그 항목은 '하네스 · 환경 · 조건 원인 → 판정 불가'로 분류하고 X의 실패로 세지 않는다. current의 그 셀이 유효 < 3이라 'current도 어겼나'를 세울 수 없을 때도 실패가 아니라 판정 불가다(ADR-014 :766 CR14-30과 같은 보수적 처리). **시간 초과는 요청 이름과 무관하게 묶는다(fix3 · Opus 루프 3 OQ2)**: current에 그 셀 시간 초과가 하나라도 있으면 X의 모든 요청 이름 시간 초과(④ `pay_start` 포함)가 판정 불가다 — 환경 원인 시간 초과가 ④에만 있는 요청에 걸려 hard 실패가 되지 않게. current가 어기지 않은 항목을 X가 어기면 그대로 X의 실패다. × 항목은 이 규칙 밖이다 — 정합 · API 이중 결과 · 40P01(current의 위반은 A 결함 — G2(a))과 current의 기대가 0이 아니거나 그 경로가 없는 항목(확정 5xx · payment-start · 연장 · unexpected · 되돌림 · 계약 밖 쌍)은 X의 실패다. 왜: 환경 원인 시간 초과 · 정지가 다섯 모드 모두에 나면 새 모드가 모두 '실패' → 'current 유지 ①'로 **측정하지 못한 후보가 진 것으로** 결론 난다(opus 루프 2 F1).
- current는 후보가 아니라 기준이다(ADR-014 측정-7 :767과 같다) — 성공을 따지지 않는다.

### 측정-8 고르는 규칙 · '이기면 교체' 조건

**후보 줄 세우기**(성공한 새 모드만 — 명세 '정합 → 사용자 결과 → 비용 → 단순성'):

| 단 | 지표 | 빠지는 규칙 |
|---|---|---|
| 1 정합 | G-F | 문턱만(이미 성공 조건) |
| 2 사용자 결과 | race 확정 5xx(M2) · race 틱 실패(M3) — 원인 귀속 값(current = app.log 0행 DELETE 귀속 · 새 모드 = 전체 — M15-29) | Y가 어느 지표에서 X보다 범위 비겹침으로 낮고 어느 지표에서도 범위 비겹침으로 높지 않으면 X가 빠진다(ADR-014 측정-8 2단 :777과 같은 꼴) |
| 3 비용 | cost 확정 p99 · cost 선점 p99 · cost DB CPU(**race 확정 p99 · race DB CPU는 빼고 설명 열 — M15-33 사용자 결정**) | Y가 어느 지표에서 X보다 실질적으로 낮고(X ≫ Y) 어느 지표에서도 Y ≫ X가 아니면 X가 빠진다(ADR-014 3단 :778) |
| 4 단순성 | 고정 순서 ① > ② > ③ > ④(D-9) | 남은 후보 중 앞선 것 |

- 동률 · 순환 · 순위 = ADR-014 측정-8 :781-783 그대로(늦게 빠진 후보가 앞 · 순환은 순환에 든 후보만 남긴다).

**교체 관문**(순위 차례로 G1~G4를 대고, 처음 모두 통과한 후보가 새 기본값):

| 관문 | 조건 |
|---|---|
| G1 | 성공(측정-7) |
| G2 이긴다 | 다음 중 하나: (a) current에 A 결함(G-F의 판정기 · API 이중 결과 · **40P01** — M15-13)이 있고 X에는 없다 · (b) race 확정 5xx(상태 ≥ 500 — M2)가 current보다 범위 비겹침으로 낮다(계약 — N4) · (c) race 틱 실패가 current보다 범위 비겹침으로 낮다(N5 — FM3 틱 롤백). **(b)(c)의 current 값 = app.log 0행 DELETE 갈래로 귀속된 수 · X 값 = 전체**(M15-29 — 원인 없는 current 5xx · 틱 실패로 X가 이기지 않는다 · current 로그를 못 읽어 값이 None이면 판정 불가). **(b)(c)를 '이긴다'로 보는 것은 사용자 결정(D-10 · 2026-11-05) — 단 G3 비용 조건은 그대로다**. 셋 다 아니고 범위를 낼 수 없는 값이 있으면 '못 넘음'이 아니라 판정 불가 |
| G3 비용 | 3단의 세 지표(cost 셀 — M15-33) 어디서도 X ≫ current가 없다. X ≫ current인 지표가 있으면 판정 불가 지표가 함께 있어도 '걸림'(ADR-014 :801 끝) · 걸린 지표 없이 범위를 낼 수 없는 지표가 있으면 판정 불가 |
| G4 캠페인 유효 | current 두 셀 유효 3회 · **current race 양성 대조**: 회차마다 경합 증거(**app.log 0행 DELETE 귀속 확정 5xx + 틱 실패** — 측정-0 · M15-29 · 시간 초과 제외 · M15-3a) ≥ 1 · 로그를 못 읽은 회차(None)가 있으면 G4 실패(판정 불가). 유니크 위반(FM2)은 짝 설계로 거의 늘 생겨 이 증거에 넣지 않는다(로컬 스모크: 짝 6쌍 = unique_violation 6 · 5xx 0). current FM1 겹침은 기록만(구조상 드물다 — 측정-14) |

- **교체가 성립하면**: `seat.confirm.mode` 기본값 = X를 **별도 커밋**(ADR-012 §3 '기본값 교체는 판정 뒤 별도 커밋' — ADR-014 측정-8 :798과 같은 선례). ③이면 V14 위치를 기본 위치 목록에 더함 · ④면 payment-window · extend-min-remaining 앱 기본값(D-6)과 클라이언트의 payment-start 호출이 함께 필요하다. 기존 운영 전환은 측정-11 절차. `current`는 손잡이로 되돌릴 수 있게 둔다.
- **교체 후보가 여럿 이기면**: 위 순위(2단 → 3단 → 4단)의 앞 후보부터 관문을 대고 **처음 통과한 하나**만 교체한다. 그보다 앞 순위 후보의 G2 · G3가 판정 불가(확정적으로 걸리지 않음)면 그 후보가 이겼을 수 있으므로 **교체 선정을 멈추고** 결론은 '판정 불가 → 사용자 보고'(잠정 결과를 함께 적는다 — ADR-014 측정-8 :801 ⓒ · :802 ⓓ · M15-2).
- **판정 불가 결론**(기본값 `current` 그대로 두되 'current가 이겼다'로 적지 않는다 — 사용자 보고 뒤 사용자가 정한다): ⓐ G4 실패 ⓑ 위 '앞 순위 관문 판정 불가' ⓒ 측정-7 판정 불가 후보('A도 같은 문턱' · 유효 반복 부족 · 양성 대조 없음 · 범위 없음 — 어느 사유든)가 있고 그 후보가 관문에서 확정적으로 걸리지 않았다(G2 확정 실패 · G3 걸림이 아니면 그 후보가 이겼을 수 있다 — M15-1 · M15-14 · M15-17). 판정 불가 후보가 확정적으로 걸렸으면 나머지 규칙대로 간다.
  - **재측정 상한 소진은 사유와 무관하게 측정-7 ⓐ(판정 불가)로 간다(M15-28)** — ADR-014는 환경 상한 소진만 판정 불가(:800 ⓑ)이고 하네스 상한 소진은 'A 유지 ⑤ superseded'였다. 이 측정은 둘을 가르지 않는다: 어느 쪽이든 그 후보는 **측정되지 않았고** 기본값은 current 그대로라 효과는 같지만, 'current 유지'로 적으면 측정하지 못한 후보가 진 것으로 남는다(D-15와 같은 이유). 그래서 15-09의 'current 유지 ④(superseded)' 갈래는 없앴다(ⓐ에 흡수 — 요약기에도 없다).
- **current 유지**(기본값 `current` 그대로 · 위 판정 불가가 아닐 때만 — 판정 불가 후보는 여기 올 때 모두 관문에서 확정적으로 걸렸다 · 사유는 사실대로 — M15-34): ① **G1 성공 후보 없음**(실패 후보 목록 · 판정 불가였으나 관문에서 확정적으로 걸린 후보와 그 관문을 함께 적는다) ② **성공 후보가 모두 관문에서 확정적으로 걸림**(후보마다 G2 False 또는 G3 hit 지표를 적는다 — 섞여도 ②). 결과 문서는 모드별 실패 항목 · 원인 후보를 적는다(무음 금지).
- **#5 V12 관문(M15-30 — 측정-10)**: 결론 옆에 늘 V12 상태를 적는다 — 판정이 서면 `#5 V12: <판정>` · 단계 누락(`--skip-v12` 포함) · 실패 · 필수 값 결측이면 **'V12 판정 불가(사유)'** · 교체 결론이면 여기에 **'— 운영 적용 전 V12 판정 필요'**(교체 자체는 그대로 · 교체 커밋 · 운영 적용 앞에 #5를 다시 잰다).
- **주의(측정 전에 밝혀 둔다 — ADR-014 측정-8 :803과 같은 꼴)**: 15-02 실측으로 current의 정합은 '우연한 가드(0행 DELETE → 예외 → 롤백)'가 지킨다. 그래서 G2(a)는 A 결함이 새로 드러날 때만 서고, 교체 여부는 사실상 **G2(b)(c) — 진 쪽이 5xx 대신 계약 코드로 끝나는가 · 배치 틱이 롤백되지 않는가 — 와 G3(비용)** 이 정한다. **사용자 결정(D-10 · 2026-11-05)**: 맞다 — (b)(c)를 '이긴다'로 본다 · G3 비용 조건은 유지.

### 측정-9 시작 조건 · 재측정 · 멈춤

**캠페인 시작 조건**(campaign.sh `cond_lines` — 각 log 1행):

0. 하네스 동결(`--sha` git archive · 해시) · 이미지 ID 고정(`run.sh --prepare-image` — ADR-014 run.sh에 넘긴다).
1. .164 스모크: 다섯 모드 × race 1회 + current · conditional × cost 1회 — 모두 ok(env-stall만으로 무효면 막지 않음 — ADR-014 :810과 같다) · **current race 경합 증거 ≥ 1** · 흐름별 VU 최대(maxVUs = 미리 만든 VU × 1.5가 넘지 않는지). 스모크는 판정에 쓰지 않는다.
2. 캠페인 스모크(두 자리 이상 — 루프 stdin 교훈 · ADR-013 §6.1).
3. dry-run(`plan15.py --dry-run` exit 0 — 좌석 · 풀 점유 · 오프셋 창).
4. k6 PC 점검(`pccheck14.sh` 그대로 + 사람 기록).
5. [구현 검증] 측정 몫: #12 앱−DB 시계 차(스모크의 `app-db-clock-*.json`) · #15 pg_stat_statements 결정(D-4) · VU 최대.
6. knobs(`pgss` · `cost_rate` · `v12_rows`) — plan.json에 고정 · 이어서 실행 때 다르면 멈춤.
7. 측정 SHA에서 전체 테스트 green(15-08 끝 기준).

**재측정**: invalid만 · 자리마다 환경 2번 · 하네스 2번 따로 · 원래 순서대로(ADR-014 campaign.sh와 같은 루프). ok(모드 실패 포함)는 다시 재지 않는다.
**멈춤**: 무효 연속 5회 → exit 3 · **회차 1 race 묶음 끝에 race/current/rep1이 ok인데 경합 증거 0 → exit 4**(부하가 경합을 만들지 못했다 — 사용자 보고 · D-1/D-8 재검토). 그 자리가 무효 · 없음이면 멈추지 않고 재측정 경로로 넘긴다(끝 G4가 판정 — M15-11 · summarize `--early-check` rc 0 · 1 · 2) · 미실행 자리 → exit 6 · 하네스 해시 불일치 → exit 5.

### 측정-10 #5 V12 운영 크기 생성 시간 · 쓰기 차단 길이 (이 캠페인 안 — 부하 회차 뒤 한 번)

- **크기**: 예약 N행(CONFIRMED · 좌석마다 1행 — 인덱스 크기 최대 쪽 · 보수적 선택) · N ∈ {1,000,000 · 5,000,000}(D-5 — ADR-002 규모 축 100만 · S5 상단 500만 — docs/adr/ADR-002-db-baseline.md:769) · 크기마다 3회.
- **절차**(`scripts/v12-build.sh`): 새 DB(V1~V13 — 앱 current로 한 번 띄워 Flyway 적용) → 앱 정지(다른 쓰기 없음) → 인덱스 DROP → 시드(좌석 N + 1,000 · 예약 N · ANALYZE) → ① 세션 A: `BEGIN; CREATE UNIQUE INDEX uq_reservation_confirmed_seat ON reservation (seat_id) WHERE status = 'CONFIRMED'; COMMIT;`(V12 파일과 같은 문장 — Flyway 기본 = 스크립트마다 트랜잭션 하나) · 50ms 뒤 세션 B: 예약 1행 INSERT(쓰기 하나 — 시작 · 끝 `clock_timestamp()`) → ② DROP 뒤 `CREATE UNIQUE INDEX CONCURRENTLY` + 같은 탐침 B → 끝에 `indisvalid AND indisunique` 확인(둘 다).
- **측정 SQL의 값**: 생성 시간 = A의 두 `clock_timestamp()` 차 · **쓰기 차단 길이 = max(트랜잭션 안 생성 시간, 탐침이 구간 안에서 떴으면 탐침 대기)** — SHARE 락은 문장 끝 · 커밋까지 쥐므로 그 순간 도착한 쓰기는 생성 시간만큼 기다린다(로컬 시험: 30만 행 생성 190ms < 탐침 지연 300ms라 탐침이 구간 밖에서 떴다 → 탐침만으로는 못 잰다 · 지연을 50ms로 줄이고 생성 시간을 하한 없이 쓴다).
- **판정**(`summarize.py --v12`): **계획한**(v12-build.sh가 남긴 `v12-plan.json` — 크기 목록 · 반복) 가장 큰 N의 3회 쓰기 차단 최대 ≤ 2,000ms(D-5 — ADR-013 :186) → **트랜잭션 안 판 유지** · > 2,000ms → **CONCURRENTLY 판 후속**(V12 `.conf` `executeInTransaction=false` + `indisvalid` 기동 검사 · §설계-4 재시도 절차 — 새 task). **계획한 모든 (크기, 반복) 자리**마다 파일 · 시드 종료 코드 0 · 시드 실측 행 수(예약 = N · 좌석 = N + 1,000) · 값 · indisvalid가 맞아야 한다 — 하나라도 빠지면 판정 불가(관측 파일 중 가장 큰 N으로 판정하지 않는다 · M15-7 · M15-8). **필수 값(fix3 M15-31)**: 트랜잭션 판 `build_ms` · `probe_blocked_ms` · `probe_started_inside` · **CONCURRENTLY 판 `build_ms` · `probe_ms` · `probe_started_inside`** · 두 판 `valid` — 하나라도 없으면(측정 오류 `error` 포함) 판정 불가. **이 판정은 캠페인 완료 관문이다(M15-30 — 측정-8 끝 'V12 관문')**: 단계 누락 · 실패 · 판정 불가면 결론 옆 'V12 판정 불가' · 교체면 '운영 적용 전 V12 판정 필요'. CONCURRENTLY 쪽 탐침 대기(쓰기를 막지 않아야 한다)와 생성 시간도 함께 적는다(Flyway 비트랜잭션 실패 이력 · `flyway repair`는 #5의 나머지 몫 — CONCURRENTLY 판을 고를 때 그 task가 잰다).
- **로컬 신호(.164 값 아님 · 판정에 쓰지 않는다)**: 이 PC 로컬 PG 16(tmpfs 아님)에서 100만 행 3회 생성 582~670ms · 탐침 대기 532~637ms · CONCURRENTLY 768~874ms(탐침 7~9ms). 선형이면 500만 행 약 3초 → 2초 문턱을 넘을 수 있다 — 결과가 'CONCURRENTLY 판 후속'으로 갈 가능성을 메인이 미리 알아 둔다.

### 측정-11 모드 전환 절차 (셀 사이 — §3.5 '모드 혼합 금지')

1. 회차마다 run.sh가 `compose down -v --remove-orphans`(seatlab 모든 컨테이너 · DB 볼륨) → seatlab 컨테이너 0 · 볼륨 없음을 확인(lib14 `compose_down14` — 3번까지 · 실패면 `compose-down-failed`).
2. 새 모드 값으로 `compose up`(앱 1대 · `CONFIRM_MODE` · delay · ④ 키 · Flyway 위치) → health UP.
3. seatlab app 컨테이너 = 1개 확인(`app-instances.txt` — 아니면 `app-instances-not-one`).
4. configprops `seat.confirm.mode`가 계획과 같은지 대조(`confirm-mode-mismatch`).
- 이 절차는 **롤링 전환 없음 · 전 인스턴스 정지 후 함께 기동**을 회차마다 강제한다. 운영의 모드 전환(교체 커밋 뒤)도 같은 순서다(기동 검사는 다른 인스턴스의 모드를 볼 수 없다 — 운영 절차 규칙).

### 측정-12 측정 기록 항목 (설명 — 판정 아님)

- **#15 A′ 대 JPA 배치(같은 후보 수)**:
  - A′ B2b 쌍당 SELECT = `seat.hold.expiry.missing_pairs{path=sweep}` 증분(MISSING 쌍마다 B2b `SELECT id FROM seat_hold WHERE schedule_id = ? AND user_id = ? AND expires_at <= ?` 1문장 — SweepConditionalBatch.kt:45-53). 기본 조합에서는 쿼터 행이 있어 0이 기대(추정).
  - 틱당 문장 수 · 시간(M15-9 — 실측): 요약기 `batch_statements`가 pgss 모든 행(상위 N행 아님)에서 배치 문장을 **정규화 문장 앞머리**로 골라(summarize.py `BATCH_STMTS` — fix1 로컬 PG 16 실측 문장에서 옮김) 묶음별 calls · total_ms · max_ms를 내고, **틱당 = 배치 전용 묶음 합 ÷ 틱 수**(같은 구간 — k6 직전 pgss 0 → 회차 끝 · 틱 = act15 tick ok + failed 증분)를 낸다. A′ = B1 · B2 · B2b · B3 · B3b · B4(감소) · B5 · B5d(전부 배치 전용). JPA = 후보 조회(J1 — `select distinct … left join seat_hold`) · 감소(J4) 만 배치 전용이고, 홀드 컬렉션 로딩 · 좌석 UPDATE · 홀드 DELETE(J2 · J3 · J5)는 **확정 · 선점과 같은 문장**이라 글자로 배치 몫을 가를 수 없다 → `shared`로 따로 적고 틱당 값에서 뺀다(JPA 틱당 값은 하한 — 측정-14). 틱 시간은 DB 쪽 하한(앱 쪽 틱 타이머 없음 — ExpireHoldsService.kt:59 · :63). 로컬 신호(배치 10초 · 20/s): JPA 틱당 전용 문장 약 153 · 15.7ms 대 A′ 약 82 · 13.7ms(.164 값 아님).
  - 지운 홀드 수: DB `pg_stat_user_tables seat_hold n_tup_del` 증분으로 견준다(아래 태그 차이 때문).
- **`seat.hold.expiry.deleted` 태그 키 차이(경로별 메모)**: JPA 배치(current)는 이 지표를 **내지 않는다** · A′는 `{path=sweep, extended=true|false}`(SweepConditionalBatch.kt:95-96) · ADR-014 B·C·D 경로는 `{path=reclaim}`(LazyHoldProcess.kt:143) · `{path=batch}`(LazyExpiryBatch.kt:100) · 정리 ①은 다른 이름(`seat.hold.counter.cleanup.deleted{mode}` — QuotaCleanup.kt:80 · :232). ADR-014 actsnap14는 `path=reclaim|batch`만 읽으므로 이 측정에서는 늘 0이다 — actsnap15가 `path=sweep` 두 태그를 따로 읽는다.
- **#11 지연 주입 자리의 모드별 의미**: ②는 P2 뒤(좌석 · 홀드 잠금을 쥔 채) 잔다 — race 셀에서 ②만 잠금 보유 비용이 섞인다(같은 좌석 재선점은 NOWAIT라 기다리지 않고 409 — 비용은 확정 p99 · DB CPU로만 보인다). ①④③은 잠금 없이 잔다.
- **#12 앱−DB 시계 차**: 회차 앞뒤 `app-db-clock-{before,after}.json`(lib14 `app_db_clock`) — `converted_after_expiry{clock=db}` 대 `{clock=app}` 차와 함께 적는다(#17).
- **#10 틱 롤백 빈도**: current race 틱 실패(M3 — 지표 · 0행 DELETE 귀속 수 · 귀속 안 된 수) 대 새 모드 0 — G2(c) 자료(귀속 수).
- **M9 분포**: 모드별 `converted_after_expiry` 건수 · lateness 감쇠 최대(창 끝 스냅숏) · 평균(win 묶음 크기의 표지 — 경합 증거가 아니다: 새 모드의 성공 수는 실제 충돌이 아니라 '창이 열렸다'일 뿐이다 · M15-3b).
- **M13 FM1 겹침**: 모드별 race 회차 값(①③④는 G1 조건 · current · ②는 기록만).

### 측정-13 [구현 검증] 측정 몫 대응표

| # | 이 캠페인에서 | 자리 |
|---|---|---|
| #5 | V12 생성 시간 · 쓰기 차단(트랜잭션 안 대 CONCURRENTLY) | 측정-10 · v12-build.sh |
| #10 | current 틱 실패 빈도 대 A′ | M3 · G2(c) |
| #11 | 지연 자리의 모드별 의미 | 측정-12 |
| #12 · #17 | 앱−DB 시계 차 · clock=db 대 app | 측정-12 · 시작 조건 ⑤ |
| #15 | A′ 문장 수 · 틱 시간 대 JPA | 측정-12(D-4) |
| #14 | V13 열이 .164 크기에서 표를 다시 쓰지 않는지 | 이번 측정의 시드는 빈 표에 V13을 적용한다 — **잴 수 없다**(받아들이는 한계 — 측정-14) |

### 측정-14 받아들이는 한계 (측정 전)

- 앱 1대라 '두 대 배치 겹침'(A′ ↔ A′)은 재지 않는다(§3.5 '해당 없음' 그대로).
- 경합은 지연 주입(200ms)으로 키운 창에서 생긴다 — 운영의 자연 창(수 ms)보다 크다. race 셀의 5xx · 틱 실패 수는 '창이 열렸을 때 무엇으로 끝나는가'의 비교이지 운영 빈도가 아니다.
- ④의 post 묶음 성공 · 재선점 0은 흐름 구성상(연장 2분 > 재선점 13초) 정해진다 — 측정한 성질이 아니다(D-7).
- #14(V13 ALTER의 잠금 길이)는 빈 표 적용이라 재지 않는다.
- env-stall 규칙 · PC 메모리 하한(`pc-mem-low` · `pc-mem-missing` — ADR-014 R5 ②)은 ADR-014 것을 그대로 쓴다(fix2에서 요약기에 이식 — M15-25).
- **(fix2) ③ 서버 논리 증거의 잔여 한계**: ③은 O1(지연 앞)에서도 404가 난다 — te − ts ≥ delay로 '지연을 잤다'를 요구하지만, 앞단 대기(Tomcat · 풀 — 트랜잭션 시작 앞)가 delay 이상인 O1 404는 가를 수 없다(그 확정은 송신이 e − 여유보다 앞이고 서버가 송신 뒤 delay 이상 늦게 :now를 잡았으며, 그 사이 배치 틱이 들어야 한다 — 드물다(추정)). ①④는 지연이 `:now` 바로 뒤라 이 한계가 없다.
- **(fix2) 시계 차 오차 ε는 회차 앞 · 뒤 두 표본 사이를 단조로 본다** — 회차 중 시계가 튀면(NTP 계단) 증거 · 잘못된 거부의 시각 조건이 틀릴 수 있다. ADR-014 `clock-offset-bad`(앞뒤 차 > 100ms)가 큰 계단은 회차 무효로 막는다.
- 열린 모델이라 '확정 처리량'(M4)은 도착률과 성공 비율의 곱이다 — 처리 능력 비교는 cost 셀 p99 · DB CPU로 대신한다.
- **(fix1) FM1-Y(배치가 푼 좌석을 남이 잡은 뒤 그 확정이 쓴다)는 current와 ②에서 부하가 재지 않는다(재현 테스트 몫 — Adr015ReproSmokeTest)**: current는 JPA 배치가 겹친 확정 때문에 틱 롤백(FM3)으로 지는 쪽이 잦아 좌석이 풀리지 않는다(로컬 배치 10초 · 2분: 틱 실패 6/14 · FM1 겹침 0) · ②는 확정이 홀드 · 좌석 잠금을 쥐어 재선점이 NOWAIT 409로 끝난다(로컬 FM1 겹침 0). 그래서 M12(API 수준 이중 결과)는 두 모드에서 G-F 문턱으로만 쓰고 'FM1-Y에 안전하다'의 근거로 쓰지 않는다. ①③④는 FM1 겹침 합 ≥ 1을 G1 조건으로 둔다(로컬 ① 7건/2분).
- **(fix3) current 원인 귀속은 앱 로그 형식에 기댄다(M15-29)**: Spring 기본 오류 기록(`dispatcherServlet` · `TaskUtils$LoggingErrorHandler`)과 Hibernate 6.6 예외 문구 · 스택 프레임으로 가른다(앱 코드 무변경 조건 — 원인 표지 지표를 새로 내지 않는다). 로그 형식이 바뀌면 귀속 0 · 머리 줄 0이면 None → G4 판정 불가로 드러난다(무음 성공 아님). 귀속은 0행 DELETE가 '누가 먼저 지웠나'까지는 말하지 않는다 — current 기간에 seat_hold를 지우는 쪽이 확정 · 배치뿐이고 짝 진 쪽은 유니크 인덱스에서 먼저 막힌다(측정-4 fix3 행)는 코드 사실에 기댄다.
- **(fix2) ② 경합 표지는 표본이다** — B3 대기 표본(20ms)이 잡은 에피소드 수라 실제 대기 수의 하한이다(잡을 확률 약 0.9 — 측정-4 fix2 행). 진 쪽 응답이 없는 설계라 응답 · 앱 지표로는 잴 수 없다(앱 코드 무변경 조건). 표본기 부하는 race 다섯 모드에 같게 든다.
- **(fix1) ④ 경계 흐름은 race 셀에만 있다** — cost 셀 ④는 기준 흐름(e − 10초 payment-start + e + c 확정)만이라 연장 뒤 경계 경합 비용은 cost 지표에 없다.
- **cost 셀 ④는 payment-start 요청을 포함한 채 다른 모드와 비교한다(사용자 결정 2026-11-05)** — ④의 cost 선점 · 확정 p99 · DB CPU에는 payment-start 한 번(세션마다 홀드 행 UPDATE 1)의 부하가 섞인다(그 대가는 ④가 치르는 실제 비용으로 읽는다).
- **(fix1) 현재 ④ 연장 기대값(not_needed = too_late = 0)은 흐름 구성이 정한다** — 결제 시간 분포가 다른 운영에서는 두 갈래가 생긴다(측정하지 않음 · D-6).

### 사용자 결정 (D-1~D-11 — 2026-11-05 · 모두 권장안으로 고정)

| # | 결정 | 근거(원래 권장 이유) |
|---|---|---|
| D-1 | race 지연 **200ms** | 풀 점유 4.4 < 5(풀 50%) · 500ms는 풀 고갈(11 > 10) · 100ms면 경합 추정 약 15건/회차 |
| D-2 | cost 도착률 **300/s** | ADR-014 맹목 300/s 선례 · VU 약 1.9만(스모크 ①에서 VU · dropped 확인) |
| D-3 | 짝 · 재선점 비율 **(0.1, 0.5)** — **루프 2 리뷰로 cost 셀 재선점만 0(race는 0.5 그대로 · M15-20 — 사용자 보고 필요)** | 짝은 FM2를 매 회차 수십 건 만드는 최소 · 재선점은 FM1 쪽 요청 수를 확정 수 이하로. cost만 0인 사유: cost 셀은 경합 판정 몫이 없고(race가 맡는다 · 조밀 구간도 이미 끔) ④만 연장 때문에 재선점이 13초 동안 409를 거듭 받아 요청 수가 구조적으로 많다 → G3(비용)가 모드가 아니라 부하 구성을 잰다(opus 루프 2 F4) |
| D-4 | pg_stat_statements **켬**(다섯 모드 같게) | #15 문장 수 · 틱 시간이 읽는다 — `PGSS_DEFAULT = 1`(fix2: ② 경합 표지는 B3 대기 표본으로 옮겼다 — pgss B3 max는 설명 열) |
| D-5 | #5 크기 **{100만, 500만} · 판정 500만 · 2초** | ADR-002 규모 축 선례 · 로컬 신호상 500만이 문턱 근처 |
| D-6 | ④ **측정 2m · 5s** · 앱 기본값 0 유지(④가 이기면 교체 커밋에서 2m · 5s) | §설계-10 T6 · 결제 시간 분포 자료 없음 |
| D-7 | ④의 post 묶음 성공 · 재선점 손실은 **설명만** | 흐름 구성상 정해지는 값(측정-14) |
| D-8 | D_arr **race 600 · cost 300** | 경합 추정 약 30건/회차 · 총 시간 |
| D-9 | 단순성 **① > ② > ③ > ④** | 더하는 부품 수(측정-4) |
| D-10 | '계약 코드로 진다(5xx 0)' · '틱 롤백 없음'을 **이긴다로 본다(G2 b · c) — 단 G3 비용 조건 유지** | §문제 · Q1(15-02 실측 — current 정합은 우연한 가드) |
| D-11 | 시작 조건 ① 스모크 **7회**(다섯 모드 × race + current · conditional × cost) | cost는 VU · 처리 상한 확인이 목적 |

- 측정 규모 약 8시간 그대로(사용자) · cost 셀 ④는 payment-start 포함 그대로 비교(사용자 — 대가는 측정-14).

### 메인 결정(확정 — fix1이 새로 둔 값 D-12~D-15 · 사용자 결정 범위 밖 · 루프 2 M15-27로 '필요' → '확정')

메인이 권장안으로 확정했다(하네스 기본값과 같다). D-14는 루프 2(M15-26)에서 판정에서 빠졌다 — 표는 기록으로 둔다.

| # | 질문 | 후보 | 확정 | 근거 |
|---|---|---|---|---|
| D-12 | ④ 경계 흐름 비율 · 셀 | race 0.5 · cost 0 / race 1.0 · cost 0 | **race 0.5 · cost 0** | 절반은 e′ 경계 경합(로컬 경계 win 단독 404 6건/30초) · 절반은 연장 유지(ext_then_lost) · cost에 넣으면 VU 약 2만 증가(300/s × 0.5 × 110초). 대가: ④ race 회차 +1.8분(gracefulStop 160초) · 최대 VU main 2,850 |
| D-13 | 재선점 조밀 구간 | e − 20ms ~ e + delay + 100ms · 20ms(17번) / 10ms(33번) | **20ms** | 창 평균 100ms의 1/5 · race 추가 요청 약 170/s(10ms면 약 330/s) · 로컬 FM1 겹침 ① 7건/2분(20ms로 충분) |
| D-14 | ② 경합 표지 문턱 | B3 max ≥ delay × 0.25(50ms) / × 0.5(100ms) | **0.25 — fix2에서 설명 열 기준으로만**(판정 = B3 대기 표본 · 측정-4 fix2 행) | 로컬 ② B3 max 143ms 대 ① 0.4ms · ④ 0.3ms(경합 없는 B3는 1ms 아래) — 기다린 시간은 확정의 남은 잠 시간이라 0~200ms 고르게(0.5면 절반쯤 놓친다 — 추정) |
| D-15 | 새 모드 경합 증거 0(그 모드에서 충돌이 안 생김) · FM1 겹침 0을 '실패'로 보나 '판정 불가'로 보나 | 판정 불가 / 실패(성공 아님 → current 유지 ① 가능) | **판정 불가** | 충돌이 없었으면 그 모드의 충돌 처리를 재지 못한 것 — '측정하지 못한 후보가 진 것으로 결론'(codex F1)과 같은 꼴. 15-09 하네스는 실패(→ current 유지 ①)로 처리했다 |

<details><summary>15-09 원래 표(D-1~D-11 후보 · 권장 — 기록)</summary>

| # | 질문 | 후보 | 권장 | 근거 |
|---|---|---|---|---|
| D-1 | race 지연 값 | 100ms · 200ms | **200ms** | 풀 점유 4.4 < 5(풀 50%) · 500ms는 풀 고갈(11 > 10) · 100ms면 경합 추정 약 15건/회차로 범위 비겹침이 약해진다(추정) |
| D-2 | cost 도착률 | 100/s · 300/s | **300/s** | ADR-014 맹목 300/s 선례 · VU 약 1.9만(ADR-014 경험 15,300 — 조금 위 · 스모크 ①에서 VU · dropped 확인) |
| D-3 | 짝 비율 · 재선점 비율 | (0.1, 0.5) · (0.2, 1.0) | **(0.1, 0.5)** | 근거 없음 — 짝은 FM2를 매 회차 수십 건 만드는 최소 · 재선점은 FM1 쪽 요청 수를 확정 수 이하로 둔다(cost 셀 요청 약 1,350/s 추정) |
| D-4 | pg_stat_statements(#15) | 켬(다섯 모드 같게) · 끔(문장 수는 코드 읽기 + missing_pairs만) | **켬** | #15는 '문장 수 · 틱 시간'을 요구하는데 앱에 틱 타이머가 없다 · 대가: ADR-014 이전 DB 조건과 갈린다(이 ADR 안 비교는 같다) |
| D-5 | #5 운영 크기 · 문턱 | 크기 {100만, 500만} · {100만}만 / 문턱 2초(ADR-013 :186) · race 확정 p99 기준 | **{100만, 500만} · 판정은 500만 · 2초** | ADR-002 규모 축 선례 · 로컬 신호상 500만이 문턱 근처 |
| D-6 | ④ payment-window · extend-min-remaining(측정값 · 앱 기본값) | 2m · 5s(T6 테스트 값) · 측정 근거로 새로 | **측정 2m · 5s · 앱 기본값은 0 유지 — ④가 이기면 교체 커밋에서 2m · 5s** | §설계-10 T6 '앱 기본값은 측정 task가 고정' — 결제 시간 분포 자료가 없다 |
| D-7 | ④의 post 묶음 성공 · 재선점 손실을 G2 · G3에 넣나 | 넣는다 · 설명만 | **설명만** | 흐름 구성상 정해지는 값(측정-14) — 넣으면 ④는 G2(d)로 늘 이기고 G3 재선점으로 늘 진다 |
| D-8 | D_arr | race 600 · cost 300 / race 300 · cost 300 | **race 600 · cost 300** | 경합 추정 약 30건/회차(600초) 대 약 15건(300초) · 총 시간 5.8 대 4.6시간 |
| D-9 | 단순성 순서 | ① > ② > ③ > ④ · ① > ③ > ② > ④ | **① > ② > ③ > ④** | 더하는 부품 수(측정-4) |
| D-10 | '계약 코드로 진다(5xx 0)'와 '틱 롤백 없음'을 '이긴다'로 보나 | 그렇다(G2 b · c) · 정합(a)만 | **그렇다 — 사용자 확인** | §문제 · Q1(15-02 실측: current 정합은 우연한 가드가 지킨다 — 진 쪽 500 · 틱 롤백이 남은 차이) · ADR-014 MQ3과 같은 성격 |
| D-11 | 시작 조건 ① 스모크 구성 | 다섯 모드 × race + current · conditional × cost(7회 약 1.4시간) · 다섯 × 두 셀(10회 약 2시간) | **7회** | cost는 VU · 처리 상한 확인이 목적 — 모드 둘이면 충분(추정) |

</details>

### 측정-15 하네스(15-09 — `k6/ADR-015/`)와 로컬 점검

- **fix3(루프 3) 하네스 변경**: `summarize.py`(app.log 원인 귀속 `applog_attrib` · current 경합 증거 · `g2` 값 · 2단 · G2 원인 귀속 · G3 = cost 셋 · 설명 열 `desc_ranges` · V12 관문 `v12_gate` · V12 필수 값 · 'current 유지' 사유 · 시간 초과 묶음 · 잘못된 거부 `el`) · `campaign.sh`(`v12-stage.json` · 끝 줄에 V12 상태) · `confirm-contention.js`(`conf{el}`) · `selftest.py` · `scripts/fixtures/applog-164-current.log`(새 — .164 시험 1 app.log.gz 실물 사건 6개 그대로: 원본 67 · 4478 · 82~85 · 36910행).
- **fix3 로컬 점검**: selftest 160항목 · `--campaign` 167항목 EXIT 0(M15-29 픽스처 귀속 · 다른 예외 · 첫 요청 앞 · 로그 없음 · 머리 줄 0 · 새 모드 G-F · G2 귀속 값 · G2 None · M15-30/31 V12 7갈래 · M15-32 ④ e′ · el 없음 · M15-33 · M15-34 ①② · OQ2 3갈래) · `k6 inspect` 다섯 모드 × 두 셀 EXIT 0 · `bash -n` · `py_compile` · `plan15 --dry-run` · `campaign.sh --dry-run --worktree` · `docker compose config`(current · optimistic · conditional-extend) EXIT 0.
- **fix3 .164 원자료 재요약(새 요약기 — 원자료 사본에서 · 판정에 쓰지 않는다)**: 시험 1 current race(하네스 8388e198) — 경합 증거 **32 = 0행 DELETE 귀속 확정 5xx 28 + 틱 실패 4** · 귀속 안 됨 5xx 0 · 틱 0(k6 확정 5xx 28 · 틱 지표 4 · 계획 win 단독 5xx 23은 설명 열) · status `invalid:confirm-count-mismatch`(그 하네스 k6에 `sess` · `e` 태그가 없던 옛 형식 — fix2 이전 원자료라 기대된 결과) · 시험 2 pessimistic race(하네스 5c1b96e5) — status **ok** · 경합 증거 26(B3 대기 에피소드) · app.log 예외 0 · 잘못된 거부 0(단독 te + ε < el 3,532건).

- **fix2(루프 2) 하네스 변경**: `scripts/b3wait-sampler.sh`(새 — 서버 쪽 B3 대기 표본) · `lib15.sh`(V12 모양 SQL · `b3wait_sampler`) · `run.sh`(race 셀 B3 표본기 기동 · 정지 · 사본 복구) · `plan15.py`(cost ρ = 0 · `B3WAIT_PERIOD_MS` · `EVIDENCE_MARGIN_MS` · Hikari 대조 · dry-run 점검) · `scenarios/lib15.js`(`confirmCodeOf`) · `confirm-contention.js`(`conf{e}` · 본문 code · `sess` · `conf_skip` · `pay_body_bad`) · `summarize.py`(서버 논리 증거 · B3 에피소드 · 계약 쌍 · 잘못된 거부 · 확정 수 · FM1 대상 · PC 메모리 하한 · 'A도 같은 문턱') · `selftest.py`.
- **fix2 로컬 점검**: selftest 143항목(`--campaign` 포함 — M15-17 11갈래 + 결론 2 · M15-19 · M15-21 · M15-23 · M15-24 · M15-25 · M15-26 · M15-18) EXIT 0 · `k6 inspect` 다섯 모드 × 두 셀 EXIT 0 · `bash -n` · `py_compile` · `plan15 --dry-run` · `campaign.sh --dry-run --worktree` · `run.sh --dry-run` · `docker compose config`(current · optimistic · conditional-extend) EXIT 0. 로컬 스모크 값은 측정-3.3 fix2 행 · V12 모양 SQL 음성 8갈래는 측정-4 fix2 행.

- 파일: `compose.confirm.yml`(ADR-009 compose + tmpfs 위 오버레이 — 앱 JSON · 이미지 고정 · nofile · db.command + `PG_PRELOAD`) · `scenarios/{lib15.js, confirm-contention.js, warmup15.js}`(lib14.js 재수출 + payment-start · 세션 속성) · `scripts/{plan15.py, run.sh, lib15.sh, actsnap15.py, summarize.py, campaign.sh, selftest.py, hhash.sh, v12-build.sh}`. ADR-014 · ADR-009 하네스는 고치지 않고 동결 사본에서 source · import(lib14.sh · actsnap14.py · summarize.py · pccheck14.sh · pclink14.sh · run.sh --prepare-image).
- **fix1 로컬 점검**: selftest 109항목(`--campaign` 포함 · M15-1~M15-15 시뮬레이션 · 합성 회차) EXIT 0 · `k6 inspect` 열 렌더 EXIT 0 · `bash -n` · `py_compile` · `plan15 --dry-run` · `campaign.sh --dry-run --worktree` · `docker compose config`(current · optimistic · conditional-extend) EXIT 0. 로컬 스모크(PG 16 tmpfs + pg_stat_statements · 앱 jar 1대 · TTL 8초 · 지연 200ms · 20/s · ④ 창 20초 · 리드 6초): 다섯 모드 모두 판정기 0 · API 이중 결과 0 · 40P01 0 · 새 양성 대조 성립(값은 측정-3.3) · ④ payment-start 600/600 200 · extended 600 · not_needed = too_late = 0 · ext_then_lost 0.
- 15-09 로컬 점검(이 PC · .164 아님): selftest 62항목 통과(`--campaign` 30자리 시뮬레이션 · 재측정 상한 · `--check` 포함) · `k6 inspect` 다섯 렌더 EXIT 0 · `docker compose config` 세 모드 EXIT 0 · `plan15.py --dry-run` EXIT 0.
- 로컬 스모크(PG 16 컨테이너 + 앱 jar 1대 · TTL 8초 · 배치 2초(·1초) · k6 약 20초): 다섯 모드 모두 판정기 0 · API 이중 결과 0 · 하네스 사유 0. current 짝 → 409 SEAT_NOT_HELD + unique_violation(FM2) · 배치 1초 판에서 current win 묶음 확정 500 4건(서버 5xx 4 — FM1) 대 conditional 같은 조건 500 0 · win 404 4 · converted_after_expiry{db} 29. ④ 연장 96/96 · 잃음 0(스모크가 잡은 하네스 결함 1건 — 짝 진 쪽 404를 `ext_then_lost`로 세던 것 — 고침).

## 실험 결과

### 결과-1 캠페인 개요

- **캠페인**: `k6/ADR-015/results/20261105-adr015-29e09aef/`(요약기 `RESULT.md` · `result.json`) · 하네스 29e09aef(동결본 해시 a50d3ca5…52bd) · 이미지 `images-29e09aef.json` · .164 서버 · k6 PC = 이 PC · 2026-11-05 17:14 ~ 11-06 07:23(사용자가 .164를 쓰려고 18:4x ~ 01:32 중지 · `--id`로 이어서 실행).
- **시작 조건**(측정-9 ⓪~⑦ 모두 통과): .164 스모크 7회(86ffbcd1) 중 2회(④ race · current cost)가 `dropped-iterations`(rehold 흐름 반복 누락)라 **하네스 VU 크기를 고친 뒤**(29e09aef — 미리 만든 VU = 평균 동시 세션 × 1.3 · 최소 도착률 × 1초 · 측정 규칙 · 판정식 무변경) 두 셀을 다시 재 ok · 경합 증거 current 29 · ① 19 · ② 14 · ③ 16 · ④ 8 · 캠페인 스모크 두 자리 ok · ⑦ = 1110/0/0/20 green(8bd5f387) + 8bd5f387..29e09aef 앱 · 빌드 파일 diff 0.
- **회차**: 30자리 중 유효 29 · 무효 6(모두 `invalid:k6-code0` — 회차마다 선점 · 재선점 요청 1~2건이 연결 끊김 EOF(k6 error_code 1000) · 확정 요청 0 · 시간 초과 0): race ② rep2 ×2(3번째 ok) · race ④ rep2 ×1(2번째 ok) · **race ④ rep3 ×3 → 재측정 상한 소진**. 사용자 override로 상한 넘어 1회 더(attempt4)도 같은 EOF 무효 → 재측정이 **승자**를 바꾸지 못함(결과-3 — 유효 rep3가 나오면 요약기 출력 '판정 불가'는 바뀔 수 있다)을 확인하고 사용자 결정으로 중단(attempt5는 진행 중 중단 — `rep3.attempt5-aborted`).
- **G4 캠페인 유효**: ok — current race 양성 대조(app.log 0행 DELETE 귀속 확정 5xx + 틱 실패) rep1~3 25 · 27 · 25 · current 두 셀 유효 3회.

### 결과-2 지표 범위 (유효 3회 [최소, 최대])

| 모드 | race 확정 5xx | race 틱 실패 | cost 확정 p99(ms) | cost 선점 p99(ms) | cost DB CPU(%) | 상태 |
|---|---|---|---|---|---|---|
| current | [18, 26] | [1, 7] | [1.84, 1.99] | [2.10, 2.34] | [27.1, 28.3] | 대조군 |
| ① conditional | [0, 0] | [0, 0] | [1.32, 1.38] | [2.24, 2.44] | [24.6, 26.0] | 성공 |
| ② pessimistic | [0, 0] | [0, 0] | [1.52, 1.64] | [2.12, 2.46] | [25.1, 27.2] | 성공 |
| ③ optimistic | [0, 0] | [0, 0] | [1.38, 1.49] | [2.10, 2.40] | [25.7, 26.2] | 성공 |
| ④ conditional-extend | 유효 2회(값 있음 — 범위 없음) | 〃 | [1.25, 1.31] | [2.10, 2.17] | [28.0, 29.0] | 판정 불가(race 유효 2/3) |

- 모드 실패(G-F) 0 · 'A도 같은 문턱' 0 · current A 결함(판정기 · API 이중 결과 · 40P01) 없음 — 이번 측정에서도 current의 정합은 '우연한 가드'(0행 DELETE → 예외 → 롤백)가 지켰고, 그 대가가 race 확정 5xx와 틱 실패다.
- 새 모드 경합 증거(양성 대조 — 측정-0 · rep1~3 순): ① 9 · 14 · 18 · ② 16 · 21 · 23(B3 대기 에피소드) · ③ 21 · 14 · 26 · ④ 6 · 7(rep1 · rep2). FM1 겹침(win 단독 확정): current 7 · 10 · 10 · ① 9 · 7 · 15 · ② 0 · 0 · 0(구조상 — 측정-14) · ③ 18 · 10 · 18 · ④ 4 · 3.
- 설명 열(판정 아님): race 확정 p99는 다섯 모드 모두 약 204ms(주입 지연 200ms 지배) · race DB CPU 22~27%.
- **#5 V12 생성 시간**(측정-10): 트랜잭션 안 판 유지 — 100만 행 쓰기 차단 최대 139ms · 500만 행 712ms(≤ 2000ms) · CONCURRENTLY 판 500만 행 1080~1108ms(쓰기 차단 약 1ms).

### 결과-3 판정 — 요약기 '판정 불가'와 수동 판정

- **요약기 결론**: '판정 불가 → 사용자 보고(후보 판정 불가 — ④ 유효 반복 부족 race 2/3) · 잠정 ① · 순위 ① > ③ > ②'(②는 3단에서 빠짐). 측정-8 판정 불가 ⓒ — 판정 불가 후보 ④가 관문에서 확정적으로 걸리지 않았으므로 '이겼을 수 있다'로 막았다(summarize.py `undet_block`).
- **고정 규칙의 결론은 지금도 '판정 불가'다**(측정-8 ⓒ를 글자 그대로 — ④는 G2 None · G3 True라 관문에서 확정적으로 걸리지 않았다 · `result.json` new_default = null).
- **ⓒ의 근거('④가 이겼을 수 있다') 반증**(사용자 질문 '재측정이 의미가 있나?' — 2026-11-06): 같은 고정 규칙을 ④가 race 3회 유효였다고 가정하고 대어 보면 ④는 어떤 값이 나와도 ①을 빼지 못한다(④를 넣고 eliminate를 돌리면 3단에서 빠지는 쪽 {② · ④} · 남는 쪽 {① · ③} · 순환 없음 — 결과 문서 리뷰 재계산).
  - 2단(race 확정 5xx · 틱 실패 — 범위 비겹침으로 낮아야 이긴다): ①은 두 지표 모두 [0, 0] → 0보다 낮을 수 없다.
  - 3단(cost 세 지표 — X ≫ Y = 범위 비겹침 ∧ 중앙값 비 > 1.10 · ④ cost 3회는 모두 유효라 재측정과 무관): cost DB CPU ④ 중앙값 28.94 대 ① 25.86 = **1.119 · 범위 비겹침 → ④ ≫ ①** → 3단 규칙상 ④는 ①을 뺄 수 없고 오히려 ①이 ④를 뺀다. ① ≫ ④인 지표는 없다(확정 p99 · 선점 p99 중앙값 비 ①/④ = 1.068 < 1.10).
  - 따라서 ④가 성공 · 실패 · 판정 불가 어느 쪽이든 순위 첫자리는 ①이고(④가 성공이라 가정하면 순위 ① · ③ · ② · ④ · 지금 실제 순위는 ④ 없이 ① · ③ · ②), ①은 G1 성공 · G2 이긴다((b) 5xx [0, 0] < current [18, 26] · (c) 틱 [0, 0] < [1, 7]) · G3 걸림 없음(cost 세 지표 어디서도 ① ≫ current 아님 — 확정 p99는 current보다 낮다).
- **사용자 결정**(측정-8 '판정 불가 → 사용자 보고 뒤 사용자가 정한다' 경로 — log 11-06 12:14 '멈추고 그렇게 마무리'): 위 반증을 근거로 ①을 새 기본값으로 정했다. **고정 규칙의 자동 통과가 아니다** — 명세 '측정 전에 고정한 규칙으로 기본값을 정한다'에 대해, 규칙이 멈춘 자리를 사용자가 같은 규칙의 계산으로 메운 것이다.
- **받아들인 한계**: 입력 = `result.json` · rep-summary 원자료(계산은 log 11-06 행 · 결과 문서 리뷰가 재계산으로 확인). ④는 race 판정 불가로 남는다(측정되지 않은 후보 — 'current 유지'처럼 '④가 졌다'로 적지 않는다 · 결정에는 영향 없음). 3단 DB CPU 비 1.119는 문턱 1.10에 가깝다 — ④ race 재측정과 무관한 cost 값이라 결론은 이 데이터로 고정된다.

### 결과-4 하네스 결함 (측정에서 드러남 — 결과 판정과 분리)

- **rehold 반복 누락**(스모크 · 고침 29e09aef): k6 constant-arrival-rate는 빈 VU가 없으면 반복을 버린다. 계획 회차 rep1~3의 결정적 세션 속성으로 센 최대 동시 세션(④ race rehold 1038 · 1039 · 1014)이 옛 미리 만든 VU 1000을 넘었다.
- **선점 · 재선점 연결 끊김 EOF**(캠페인 · 고치지 않음 — NEXT): 가설(미검증) = 쉬던 VU가 서버 keep-alive 만료 순간에 같은 연결을 재사용(ADR-014 06c와 같은 류). ④ race에서 잦다(시도 7 중 무효 5 — 장수명 세션으로 VU가 오래 쉰다) · 29e09aef로 미리 만든 VU가 늘어 쉬는 시간이 길어졌을 수 있다.

## 결정

- **새 기본값 = ① `conditional`**(조건부 문장 확정 + A′ 배치) — **사용자 결정**(2026-11-06): 요약기 결론은 판정 불가(측정-8 ⓒ — ④ race 유효 2/3)였고, ④가 어떤 값이어도 승자가 ①임을 같은 규칙으로 보인 뒤 사용자가 정했다(결과-3). ① 자신은 G1 성공 · G2 이긴다 · G3 걸림 없음 · G4 ok. current는 손잡이로 되돌릴 수 있게 남긴다.
- **V12 = 트랜잭션 안 판 유지**(500만 행 쓰기 차단 최대 712ms ≤ 2초). CONCURRENTLY 판은 후속(NEXT).
- **② · ③ · ④는 기본값이 아니다**: ②는 3단에서 빠짐(cost 확정 p99 ② ≫ ① — 중앙값 비 1.166 · 범위 비겹침) · ③은 남았으나 단순성 순서(①>②>③>④ — D-9)에서 뒤 · ④는 race 판정 불가(결정에 영향 없음 — 결과-3).
- **기본값 교체 = 별도 커밋 a68d9518**(측정-8 · ADR-012 §3 선례 — 2026-11-06): `seat.confirm.mode` 기본 current → conditional. **범위 밖 구성은 `seat.confirm.mode=current` 명시 강제 — 자동 후퇴 없음**(사용자 결정 2026-11-06 — '우연한 가드로 해결되면 안 된다 · 결제 뒤 500은 문제 · 404로 명확히'): B · C · D 만료 방식 · 기본 밖 좌석 · 매수 전략 · 앱 경로 주입 · Flyway target 12 이하는 키가 없으면 기동 검사 규칙 1 · 2 · 5 · 7이 거부한다(계약 `Adr015DefaultModeContractTest`). 기존 테스트 35파일 · 옛 측정 하네스 compose 11개(ADR-001~014) · 운영 절차(ops/expiry-mode-cutover · ops/confirm-unique-v12)에 current 명시 · 안내를 더했다 · 전체 테스트 1116/0/0/20 + reconciler 6/0.
