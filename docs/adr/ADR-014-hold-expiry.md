# ADR-014: 선점 후 이탈 — 홀드 만료와 좌석 상태의 원천 (Q3)
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

- 상태: 설계 중 — §설계(task 01 · 2026-10-26 · 설계 선검증 듀얼 + 재점검 마감) · §측정 전 고정 규칙(task 02 · 듀얼 1패스 + 재점검 마감 · 캠페인 60회 약 21.2시간 추정 — 사용자 재확인 2026-10-26) · 미측정
- 명세: `docs/plans/2026-10-26/adr-014-hold-expiry/requirement-spec.md` · 기록: 같은 폴더 `log.md`
- 대응 질문: Q3 "좌석을 선점해놓고 결제 없이 떠났다면?"
- 선행: ADR-000, ADR-001

## 문제

기준선은 좌석에 `HELD`를 저장하고 `@Scheduled` 배치가 만료분을 `AVAILABLE`로 되돌린다(sweep). 만료 시각과 배치 실행 사이에 좌석은 팔 수 있는데 팔리지 않는다. 배치가 늦거나 실패하면 "만료됐는데 HELD" 좌석이 남는다.

## 가설

- 이탈률 20% / 50% 시나리오에서 만료 좌석이 배치 주기만큼 묶이고, 잔여석 구간(20~30분) 매진 시간이 늘어난다.
- 만료됐지만 배치 전인 홀드가 1인 매수(ADR-005)에 계속 집계되어, 사용자가 자기 좌석을 다시 못 잡는다.
- 배치와 확정·재선점이 겹치면 배치가 방금 재선점된 좌석을 `AVAILABLE`로 되돌리는 경합이 생긴다.

## 선택지 후보

| 선택지 | "좌석이 비었다"의 판정 | 예상 장단점 |
|--------|----------------------|------------|
| A. `HELD` 저장 + sweep (기준선) | `status = AVAILABLE` | 조회 단순 / 배치 주기만큼 실시간성 손실 |
| B. `HELD` 저장 + 조회 시 만료 판정 (lazy) | `AVAILABLE` 또는 (`HELD` 이고 만료) | 실시간 / 저장된 `HELD`가 거짓일 수 있음 |
| C. 혼합 (판정 lazy + 정리 sweep) | B와 같음 | B + 정리 / 같은 규칙이 두 곳 |
| D. 좌석엔 영속 사실만, 홀드는 유도 | `AVAILABLE` 이고 활성 홀드(`expires_at > now()`) 없음 | 실시간, 거짓 상태 없음 / 조회 시 홀드 조인, 경합 지점이 홀드 행으로 이동 |

- D를 택하면 좌석당 홀드 1행 + 만료 행 덮어쓰기(upsert) 모델이 후보가 된다 — "활성 홀드 1개"는 시간 조건이라 부분 유니크 인덱스로 표현할 수 없기 때문.
- 시각의 단일 출처(앱 `Clock` vs DB `now()`)도 함께 결정한다.

## 설계 (측정 전 고정 — 초안)

> 초안(task 01 — 2026-10-26 · **설계 선검증 지적 반영** — 반영한 곳마다 `(선검증 DS14-n)`). 기준 = 브랜치 `feat/adr-013-config-cutover` HEAD `9dc2b7e4` — 구현은 명세 §3대로 #11·#12 병합 뒤 `main`에서 하고, 그때 아래 `파일:줄` 사실을 다시 확인한다(줄 번호는 이 초안을 쓸 때의 것). 정답 기준 = 명세 §0 Q/A · §2 불변식 · §8 가정. 실코드로 확인한 사실에는 `파일:줄`을 붙이고, 코드로 확인하지 못한 판단은 **(추정)**, 구현에 닿아야 판정할 수 있는 값은 `[구현 검증]`(§설계-10에 모음)으로 적는다. 측정 고정값·성공 기준·기본값 교체 조건은 task 02 몫이다 — 이 절의 측정은 뼈대와 후보까지(§설계-8).

### 설계-0 용어

| 기호 | 뜻 |
|---|---|
| 활성 홀드 | `expires_at > now()`인 홀드 행. `now()` = DB `now()`(그 트랜잭션의 시작 시각) |
| 남은 만료 홀드 | 만료됐지만(`expires_at <= now()`) 아직 지워지지 않은 홀드 행 |
| 비어 있음 | 선점할 수 있는 좌석. A: `status = 'AVAILABLE'` · B·C·D: `status <> 'RESERVED'` 이고 활성 홀드 없음(§설계-2.1) |
| 회수 | 선점 트랜잭션이 그 (회차, 사용자)의 남은 만료 홀드를 지우고, 같은 트랜잭션에서 지운 수만큼 cnt를 내리는 것(명세 §0 · §2) |
| 쌍 잠금 | (회차, 사용자) 쿼터 행 `user_hold_quota`를 `FOR UPDATE`로 잠그는 것. 그 쌍의 '홀드 행 삭제 + cnt 변경'을 한 번에 한 트랜잭션으로 묶는 자물쇠다 |
| 기본 조합 | 좌석 경합 방식 `pessimistic-nowait`(좌석 행 `FOR UPDATE NOWAIT` — 잠겨 있으면 기다리지 않고 진다 · ADR-003 3b) + 매수 방식 `counter-upsert-precheck`(잠금 없는 좌석 선조회 + 쿼터 한 문장 upsert · ADR-008) |
| 판정기 | `/internal/consistency`(`LoadtestDataService.consistency` — ADR-001의 끝 시점 정합 검사) |
| RC | READ COMMITTED. 이 앱의 격리 수준이고, 문장마다 새 스냅숏(그 순간까지 커밋된 것)을 본다 |
| EPQ | EvalPlanQual. RC에서 잠그거나 고치려던 행이 그 사이 다른 트랜잭션에 바뀌었으면, 그 행의 최신 버전으로 **그 행의 조건만** 다시 보는 PostgreSQL 동작. 다른 테이블을 읽는 하위 질의는 문장 시작 스냅숏 그대로다 |
| TTL | 홀드 유효 시간(`seat.hold.ttl` — 지금 5분, application.yml:34) |

### 설계-1 지금 코드의 사실 (A — 기준선 · 기본 조합)

| 경로 | 문장 순서 | 잠금 | 시각 출처 |
|---|---|---|---|
| 선점 | ① 마감 판정 `SELECT starts_at FROM product_schedule … FOR SHARE`(SalesCloseGate.kt:50-52) ② 잠금 없는 좌석 선조회 `SELECT status FROM product_seat` — `AVAILABLE`이 아니면 409(UserLimitStrategies.kt:335-341) ③ 쿼터 upsert `cnt + 1 <= max`(:283-287 — 0행이면 ②를 다시 보고 매수 초과 :322-325) ④ 좌석 `FOR UPDATE NOWAIT`(ProductSeatRepository.kt:31-34 · 55P03이면 409 — DbLockHoldStrategies.kt:57) ⑤ `assertHoldable` = `status != AVAILABLE`이면 409(ProductSeat.kt:44-48) ⑥ `seat.hold` → flush = 홀드 INSERT + 좌석 `status='HELD'` UPDATE(HoldSeatProcess.kt:86-89) | 회차(공유) → 쿼터 행(배타) → 좌석 행(배타 · NOWAIT)(HoldSeatProcess.kt:47 주석) | `held_at`·`expires_at` = 앱 Clock + TTL(HoldSeatProcess.kt:87 · ProductSeat.kt:53) |
| 확정 | `findByHoldsId`(잠금 없음 — ProductSeatRepository.kt:16) → 내 것인가 · `isExpired(앱 now)` · `status == HELD`(ProductSeat.kt:59-73) → 좌석 RESERVED · 홀드 삭제(orphanRemoval — ProductSeat.kt:40) · 예약 INSERT가 한 트랜잭션(ConfirmReservationService.kt:32-58) | 없음(확인과 전환 사이의 경합은 ADR-015) | 앱 Clock(ConfirmReservationService.kt:36) |
| 좌석 조회 | **API 없음**(컨트롤러는 선점 · 확정 · `/internal`뿐). `product_seat(schedule_id)` 인덱스도 없다(V1·V2 — 좌석 테이블에는 PK 인덱스뿐) | — | — |
| 만료 배치 | `@Scheduled(fixedDelay = expiry-interval)` 10초(HoldExpiryScheduler.kt:15 · application.yml:36). **틱 하나 = 트랜잭션 하나**(ExpireHoldsService.kt:31-38): `findDistinctByHoldsExpiresAtLessThanEqual(앱 now)`로 좌석·홀드를 잠금 없이 읽고(ProductSeatRepository.kt:18) → `expireHolds` = 만료 홀드 제거 + `status == HELD`면 `AVAILABLE`(ProductSeat.kt:76-86 — **남은 활성 홀드가 있는지는 보지 않는다**. 좌석당 홀드가 0..1인 A에서만 맞다) → `onHoldsExpired` = 쌍 정렬 가드 감소 `cnt >= n` · 0행이면 PairRepair 복구(ExpiryCounters.kt:49-63) | 쿼터 행(정렬 · JDBC 즉시) → 커밋 때 flush(홀드 DELETE · 좌석 UPDATE) (추정 — JdbcTemplate 문장은 JPA flush를 일으키지 않으므로 쿼터 UPDATE가 먼저 나간다) | 앱 Clock(ExpireHoldsService.kt:32) |
| 카운터 회수 | 배치에서만. 선점은 남은 만료 홀드를 회수하지 않는다 — 가설 2의 원인 | — | — |

- **카운터·대조가 세는 '실제 값' = 홀드 행 전부(만료 여부 무관) + CONFIRMED 예약**: `PairRepair.actualOf`(PairRepair.kt:120-130) · 대조 one-shot 문장(ChunkDetector.kt:77-90) · split·group(ChunkDetector.kt:204 주석 '홀드는 expires_at으로 거르지 않는다') · V6 백필 정의(V6__backfill_user_hold_quota.sql 머리 주석) · 판정기 `v_counter_mismatch`. 그래서 지금은 남은 만료 홀드가 cnt와 실제 값 양쪽에 똑같이 들어가 어긋남이 아니다.
- **판정기 항목 중 만료와 닿는 것**(LoadtestDataService.kt:140-183): `v_duplicate_hold_seats`(좌석당 홀드 **행** > 1) · `v_held_without_hold` · `v_hold_on_unheld_seat` · `v_stale_held_seats`(DB `now()` − 유예) · `v_over_limit_users`(홀드 행 + 확정 > 한도). 모두 'HELD 저장 + 좌석당 홀드 0..1'(A)을 전제로 정의돼 있다.
- **A가 가설 3을 막는 방식**: A에서는 저장된 `HELD`가 남아 있는 동안 아무도 그 좌석을 다시 잡지 못한다(②·⑤). 배치 두 틱(앱 2대 등)이 같은 홀드를 지우면 늦은 쪽 틱이 롤백된다 — ADR-013 §4.2의 기술('한쪽 틱이 롤백된다 — 이중 감소는 없다') (추정 — 근거는 Hibernate의 삭제 행 수 검사이고 이 저장소 테스트로는 확인하지 않았다 `[구현 검증]`).

### 설계-2 B·C·D 공통 규칙

#### 2.1 만료 판정 단일 규칙과 시각 출처

- 활성 = `expires_at > now()` · 만료 = `expires_at <= now()`(지금 `SeatHold.isExpired`의 '만료 정각에는 이미 만료'와 같은 경계 — SeatHold.kt:27). 쓰기도 DB 시각이다: 홀드 INSERT 문장 안에서 `held_at = now()` · `expires_at = now() + TTL`, 응답 `expiresAt`은 `RETURNING expires_at`.
- **비어 있음(B·C·D 공통) = `status <> 'RESERVED'` 이고 활성 홀드 없음.** 선택지 표의 B·C 판정('`AVAILABLE` 또는 (`HELD` 이고 만료)')을 이 꼴로 바꾼다 — 저장된 `HELD`·`AVAILABLE`을 판정에 쓰지 않는다. 그러면 배치가 상태를 잘못 되돌려도 이중 판매로 이어지지 않고(§2.3), B·C·D 세 방식이 같은 판정을 써서 서로 섞여도 판정이 갈리지 않는다(§설계-7). B·C의 저장 `HELD`는 '활성 홀드가 있을 수 있다'는 힌트(좌석 맵 빠른 길 — §3.3)일 뿐이다. D는 `HELD`를 쓰지 않는다.
- **앱 Clock으로 만료를 판정·기록하던 곳과 바꿀 방법**(main-app 전수 grep `clock`·`Instant.now`·`now()` — 실코드):

| # | 위치 | 지금 | B·C·D에서 |
|---|---|---|---|
| 1 | HoldSeatProcess.kt:87 → ProductSeat.kt:50-56 | `held_at`·`expires_at` = 앱 now + TTL | 홀드 INSERT 문장의 `now()` · `now() + TTL`(§3.1 H9) |
| 2 | 선점 응답 `expiresAt`(HoldSeatProcess.kt:91 → SeatHoldController.kt:29) | 앱이 계산한 값 | `RETURNING expires_at` |
| 3 | ConfirmReservationService.kt:36 → ProductSeat.kt:65 `isExpired(now)` | 앱 now로 만료 판정 | 좌석을 잠근 뒤 새 문장 `h.expires_at > now()`(§3.2 K3) |
| 4 | ExpireHoldsService.kt:32 → ProductSeatRepository.kt:18 · ProductSeat.kt:76-86 | 앱 now로 만료 대상을 고르고 지움 | C·D 배치 문장의 `expires_at <= now()`(§3.4) · B는 주기 배치 없음 |
| 5 | ProductSeat.kt:44-48 `assertHoldable` · UserLimitStrategies.kt:335-341 `assertSeatAvailable` | 시각 없이 `status == AVAILABLE` — A의 '비어 있음' | 비어 있음 규칙(위) — 선조회 H1 · 잠근 뒤 H8 |
| 6 | (회수) | 없음 | 선점 H2~H5의 `expires_at <= now()` |
| — | **만료가 아닌 앱 Clock — 바꾸지 않는다** | 판매 마감 SalesCloseGate.kt:29·54·71 · `ensureQuotaRow`(UserLimitStrategies.kt:151 — counter 전용) · 정리 QuotaCleanup.kt:72·124·181 · 예약 `created_at`(ConfirmReservationService.kt:49) · 대조의 열린 회차 판정(IncrementalReconciler.kt:147 · PairRepairRunner.kt:51 등) · 기록 시각(PairRepair.kt:148) · counter-trigger `setCounterNow`(UserLimitStrategies.kt:367-375 — 범위 밖 조합) | 그대로(마감은 ADR-009 규칙이고 명세 §2의 '만료 판정'이 아니다) |

- **A는 이 표를 적용하지 않는다**(초안 판단 — 열린 질문 Q1): 명세 §2의 '기존 동작 보존(A 동작 · 기존 테스트 결과 그대로)'과 '앱 Clock으로 만료를 판정하지 않는다'가 A에서 부딪친다. 기존 통합 테스트는 `MutableClock`(고정 시각 2026-09-27T10:00Z — IntegrationTest.kt:121-128)으로 만료를 만든다(시계를 옮기는 호출 51곳 · 20개 파일 — grep). A를 DB `now()`로 바꾸면 이 테스트들이 만드는 홀드는 실제 DB 시각으로 이미 만료다. 권장안: A는 지금 코드 그대로(앱 Clock) 두고, 단일 규칙은 B·C·D 경로에 적용하며, 명세 §2에 예외 1줄을 재합의한다.
- .164에서 앱과 DB는 같은 호스트의 컨테이너라 두 시계의 차는 작을 것이다(추정). 그래도 A 대 B·C·D 비교에는 시계 출처 차이가 섞인다 — 측정 때 `now()`와 앱 Clock의 차를 한 번 기록한다 `[구현 검증]`.

#### 2.2 `now()`(트랜잭션 시작에 고정)와 `clock_timestamp()`(호출 순간)의 차이가 의미 있는 곳 — 명세 §8 가정 1

두 값의 차 = 그 트랜잭션이 판정 문장 전에 쓴 시간(잠금 대기 · 앞 문장 · 임계 구역 지연 장치).

| 자리 | 차이가 생기는 원인 | 결과 | 설계 |
|---|---|---|---|
| 선점의 회수·비어 있음 판정 | 같은 사용자 동시 요청이 쿼터 행에서 줄을 선다(upsert · H3 — UserLimitStrategies.kt:256 주석) · 정리 ①의 회차 `FOR UPDATE`와 마감 `FOR SHARE`가 서로 기다린다(QuotaCleanup.kt:117-122) · `critical-section-delay`(기본 0 — HoldSeatProcess.kt:84) | 벽시계로는 막 만료된 홀드를 아직 활성으로 본다 → 회수 안 됨(매수 초과 409) · 좌석 409. **보수 쪽**이다(이중 판매 쪽이 아니다). 새 홀드의 `expires_at`도 그만큼 짧아진다 | `now()` 유지. 경계 부근의 '자기 좌석 재선점 실패'에 섞이는 몫은 지표로 남긴다(§설계-8) |
| 확정 | 좌석 `FOR UPDATE` 대기(확정은 기다린다 — K2) | 만료 직전에 시작한 확정이 대기 뒤, 벽시계로는 만료 뒤에 성공한다 — 오차 상한 = 대기 시간 | 허용(판정 시각 = 트랜잭션 시작). 단 그 사이 다른 사용자가 같은 좌석을 잡았으면 거부한다(K3의 '다른 활성 홀드 없음' — 아래 '시각 역전') |
| 배치 틱 | 긴 틱 | 틱 시작 뒤 만료된 홀드는 다음 틱 몫 — 무해 | C·D 배치는 청크마다 트랜잭션(§3.4) |
| **시각 역전** | 나중에 시작한 트랜잭션 Y(`now()`가 큼)가 먼저 시작한 X(`now()`가 작음)보다 먼저 커밋한다 | X는 Y가 '만료'로 보고 처리한 홀드를 '활성'으로 본다. 예: 확정 X(만료 직전 시작)가 재선점 Y(만료 뒤 시작 · 먼저 커밋)의 뒤에서 '내 홀드는 활성'만 보고 통과하면 **이중 판매**(남의 활성 홀드 + 내 확정) | 시각 비교가 아니라 행으로 막는다 — 잠근 뒤 새 문장에서 '다른 활성 홀드 없음'을 함께 본다(K3 · H8) · 만료를 근거로 지우는 문장은 `DELETE … WHERE expires_at <= now() RETURNING`(지운 행만큼만 감소 — §2.4) |
| 홀드 쓰기 시각 | 앱 인스턴스마다 시계가 다르면(앱 2대 · 다른 서버) 시계가 빠른 인스턴스의 배치가 갓 만든 홀드를 만료로 볼 수 있다 | — | DB 한 시계로 쓰면 TTL > 0인 한 '갓 만든 홀드를 만료로 보는 트랜잭션'이 없다(그보다 먼저 시작한 트랜잭션의 `now()`는 더 작다) — DB로 쓰는 이유 |

- `clock_timestamp()`는 판정에 쓰지 않는다 — 관측 열에만 쓴다(ADR-012 `counter_app_inject.at`과 같은 쓰임 — V10 주석). 착수 직후 스모크: 부하 중 선점·확정 판정 문장에서 `clock_timestamp() − now()` 분포를 잰다 `[구현 검증]`.

#### 2.3 잠근 뒤의 판정은 새 문장으로

RC에서 `SELECT … FOR UPDATE [NOWAIT]` 한 문장 안의 `EXISTS (SELECT … FROM seat_hold …)`는 문장 시작 스냅숏으로 평가된다. 잠긴 좌석 행이 바뀌었으면 EPQ가 좌석 행의 조건만 최신으로 다시 보고, 하위 질의는 다시 보지 않는다(외부 근거: PostgreSQL 문서 'Read Committed Isolation Level' — 이 저장소에서 재현은 `[구현 검증]`). 특히 **D는 선점이 좌석 행을 잠그기만 하고 쓰지 않으므로** 앞 선점의 커밋이 좌석 행 버전에 남지 않는다 — 잠금과 판정을 한 문장으로 하면 막 커밋된 남의 활성 홀드를 못 보고 두 번째 활성 홀드를 만든다. 그래서:

- 판정은 잠금을 얻은 **뒤의 새 문장**으로 한다(RC 새 스냅숏 = 잠금을 쥐었던 트랜잭션의 커밋이 들어 있다).
- 같은 이유로 C 배치의 좌석 되돌리기를 `UPDATE … WHERE status = 'HELD' AND NOT EXISTS (활성 홀드)` 한 문장(먼저 잠그지 않음)으로 하지 않는다 — 좌석 행 잠금을 기다린 뒤 EPQ가 `status`만 다시 보고, 막 커밋된 재선점 홀드는 못 봐 `AVAILABLE`로 되돌릴 수 있다(가설 3의 B·C판 — §설계-5).
- 잠금 없는 선조회(H1)는 걸러내기일 뿐이고, 최종 판정은 H8이다.

#### 2.4 쌍 잠금과 '지운 만큼만 내린다' — 회수와 배치의 이중 감소 방지

- **규칙 ①**: (회차, 사용자)의 홀드 행을 **지우는** 경로(회수 · C·D 배치)는 먼저 그 쌍의 쿼터 행을 `FOR UPDATE`로 잠근다. 여러 쌍이면 `(schedule_id, user_id)` 순(ExpiryCounters.kt:52-53과 같은 순서). 홀드를 **만드는** 선점은 이미 upsert로 같은 행을 잠그고, 대조 복구도 같은 행을 잠근다(PairRepair.kt:110-112). → 그 쌍의 '행 삭제 + cnt 변경'은 한 번에 한 트랜잭션이다.
- **규칙 ②**: 감소량 = 그 트랜잭션의 `DELETE … RETURNING`이 실제로 돌려준 행 수. 같은 행을 두 트랜잭션이 지우려 하면 뒤 쪽은 앞 쪽 커밋을 기다렸다가 행이 없어 0행을 받고 0만큼 내린다. 그래서 회수와 배치가 같은 홀드를 두 번 빼지 않는다(규칙 ①이 있으면 그 기다림도 쿼터 행에서 먼저 일어난다).
- **규칙 ③**: 삭제와 감소는 같은 트랜잭션이다. 선점이 뒤 단계(NOWAIT 패배 · 매수 초과 · 좌석 불가)에서 실패하면 회수도 함께 롤백된다(부분 반영 0 — 명세 §2). 대가: 실패한 선점의 회수는 남지 않는다(다음 선점 · 배치 몫).
- 가드 감소 `cnt >= n`이 0행이면(cnt가 이미 어긋남) A와 같은 방식으로 그 자리에서 `PairRepair.repairInCurrentTx(pendingDecrement = n)`을 부른다 — 행을 이미 지웠으므로 `excludeHoldIds`는 비운다(오탐 판정식 `locked == actual + pendingDecrement` — PairRepair.kt:101 — 이 그대로 성립. 매개변수 뜻은 PairRepair.kt:66-70 주석) (선검증 DS14-7). 기록 출처 이름 후보: 회수 `hold-reclaim` · C·D 배치 `expiry-repair`(A와 같은 이름). `expiry-repair=false`면 A의 옛 경로처럼 실패한다(트랜잭션 롤백 — ExpiryCounters.kt:89-98과 같은 뜻). 회수(H3)와 배치(C·D)에서 쿼터 행이 **없는** 쌍(MISSING — 어긋남 모양 '행 없음')은 지운 뒤가 아니라 지우기 **앞에서** 부른다(§3.1 H3 · §3.4 C3b — A와 같은 순서 · 회수 쪽은 메인 종합 DS14-8). 회수 쪽 MISSING 복구와 배치 MISSING 복구가 같은 쌍에서 만나면 한쪽이 40P01로 롤백될 수 있다(무음 아님 — 선점은 5xx가 아니라 재시도 대상인지 [구현 검증]).
- **감소 문장 · 등록은 A 경로를 재사용한다(선검증 DS14-6)**: 회수 H5와 배치 C5의 가드 감소는 `ExpiryCounters`의 증분 방식별 문장을 그대로 쓴다 — `method = stmt`(ADR-012 ⓒ — 앱 문장이 표시를 남기는 방식, `IncrementalMethod` — ReconcileProperties.kt:39)면 `UPDATE … SET cnt = cnt - ?, updated_at = now() …`(GUARD_STMT_SQL), 아니면 `SET cnt = cnt - ?`(GUARD_SQL) — 고르는 곳 ExpiryCounters.kt:33-35 · 문장 ExpiryCounters.kt:142-143. 감소가 1행이면 같은 경로로 `changes.changed(회차, 사용자)`(ADR-012 ⓐ 메모리 집합 등록 — 커밋 뒤에만 넣는다)를 부른다(ExpiryCounters.kt:59). 복구 갈래는 PairRepair가 등록한다(PairRepair.kt:100 — ExpiryCounters.kt:59 주석과 같음). 문장을 새로 적지 않는 이유: 방식별 표시(ⓐ 등록 · ⓒ `updated_at`)를 빠뜨리면 증분 대조가 그 쌍의 변경을 못 본다. 같은 줄의 측정 주입 `afterExpiryDecrement`(ExpiryCounters.kt:60)는 부르지 않는다(B·C·D와 주입은 기동 거부 — Q13).

#### 2.5 ADR-009·012 대조와 '남은 만료 홀드' — 오탐 0 조건

- **대조의 실제 값 정의를 바꾸지 않는다**: 홀드 행 전부(만료 무관) + 확정(§설계-1). B·C·D에서도 cnt는 '행이 있는 동안' 그 홀드를 센다. 행 삭제와 cnt 감소가 규칙 ①~③으로 한 트랜잭션이므로 어느 스냅숏에서도 cnt = 홀드 행 수 + 확정이다 → 오탐 0 · 끝 불일치 0(명세 §2 '카운터 정합'). reconcile-core는 무변경이다(대조 기본 경로 문장은 '한 글자도 바꾸지 않는다' — ChunkDetector.kt:77 주석).
- 실제 값을 '활성 홀드만'으로 바꾸면 틀린다: 남은 만료 홀드마다 cnt > 실제(above)로 탐지되고, 복구가 cnt를 활성 수로 덮은 뒤 회수·배치가 그 행을 지우며 한 번 더 내린다 → 이중 감소(below).
- **명세 §2 '카운터 = 활성 홀드 수'의 해석**(열린 질문 Q2): 대조 기준 = 홀드 행 수(만료 무관) + 확정. **매수 판정** = 회수 뒤의 cnt — 그 순간 그 사용자의 홀드 행은 이 트랜잭션 `now()` 기준으로 모두 활성이므로 '활성 홀드 수 + 확정'과 같다. 둘은 회수 직후에 같아진다.
- 증분(ADR-012 ⓑ 트리거 방식): 회수·배치의 cnt 감소는 쿼터 행 UPDATE라 V9 트리거가 `updated_at`을 남긴다(V9__quota_updated_at_trigger.sql) → 증분이 본다. 홀드 행 삭제 자체에는 표시가 없지만(같은 파일 주석 '행 삭제는 표시가 없다') 같은 트랜잭션의 쿼터 UPDATE가 표시를 남긴다. ⓒ(stmt — 앱 문장 `updated_at = now()`)·ⓐ(memory — 메모리 집합 등록)도 A와 같은 문장·등록 경로를 쓰므로 같게 성립한다(§2.4 마지막 항목 — 선검증 DS14-6). 안전망 최근 창은 홀드·예약 **id 범위**로 새 행을 본다(SafetyNetReconciler.kt:772 `HOLD_PAIRS_SQL`) — B·C·D가 홀드를 늘 새 행(새 id)으로 만들면 그대로 성립한다(D 후보 (나) '덮어쓰기'는 여기서 어긋난다 — §설계-4).

### 설계-3 선택지별 데이터 모델과 다섯 경로 (기본 조합에서만)

| | A | B | C | D |
|---|---|---|---|---|
| `product_seat.status` | AVAILABLE·HELD·RESERVED — 원천 | 같은 값 · `HELD`는 힌트(거짓일 수 있다) | B와 같음 · 배치·회수가 고친다 | AVAILABLE·RESERVED만 쓴다(`HELD`를 쓰지 않음 — 옛 데이터의 `HELD`는 '예약 아님'으로 읽는다) |
| `seat_hold` | 좌석당 0..1행(활성 또는 배치 전 만료) | 좌석당 활성 ≤ 1 + 남은 만료 행 0..n(남의 것 포함 — 열린 질문 Q4) | B와 같음(배치가 줄인다) | B와 같음(§설계-4 후보 (가)) |
| 비어 있음 | `status = AVAILABLE` | `status <> RESERVED` ∧ 활성 없음 | 같음 | 같음 |
| 만료 시각 | 앱 Clock | DB `now()` | DB `now()` | DB `now()` |
| 주기 배치 | 있음(10초 · 틱 = 트랜잭션 1) | **없음**(마감 회차 정리 ①만 — §3.4) | 있음(청크마다 트랜잭션 · 행 + 카운터 + 좌석 상태) | 있음(행 + 카운터만 · 좌석 행을 쓰지 않음) |
| 스키마 | 무변경 | 열·표 추가 없음 · V11(좌석 맵 인덱스 — 선택 위치 · 기본 위치에 넣지 않고 측정 때 모든 방식 셀에 같게 켠다 §설계-7 · 선검증 DS14-OQ1) | 같음 | 같음 |

#### 3.1 선점 (B·C·D)

```sql
-- H0 마감 판정(지금 그대로 — SalesCloseGate.kt:50-52 · 앱 Clock · 마감은 만료 규칙이 아니다)
SELECT starts_at FROM product_schedule WHERE id = :s FOR SHARE;
-- H1 잠금 없는 선조회(precheck를 비어 있음 규칙으로) — 없으면 404, 비어 있지 않으면 409 SEAT_NOT_AVAILABLE
SELECT st.status,
       EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now()) AS active
FROM product_seat st WHERE st.id = :seat AND st.schedule_id = :s;
-- H2 회수 탐침(잠금 없음) — false면 H3~H5를 건너뛴다(대부분의 요청)
SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = :s AND user_id = :u AND expires_at <= now());
-- H3 쌍 잠금(규칙 ①) — 행이 없으면(MISSING 어긋남) H4 **앞에서** PairRepair(pendingDecrement = 0)로 행을 만들고 잠근 뒤 진행한다(배치 C3b와 같은 순서 · 메인 종합 DS14-8 — 행 없이 H4·H5로 가면 규칙 ①이 서지 않아, 그 사이 같은 사용자의 다른 선점이 H6 upsert로 만든 cnt 1 행을 H5가 내려 무음 below가 생긴다)
SELECT cnt FROM user_hold_quota WHERE schedule_id = :s AND user_id = :u FOR UPDATE;
-- H4 회수 — 지운 행만 센다(규칙 ②)
DELETE FROM seat_hold WHERE schedule_id = :s AND user_id = :u AND expires_at <= now() RETURNING id, seat_id;
-- H4b (C만 · 열린 질문 Q6) 회수한 홀드의 다른 좌석(대상 좌석 제외) 상태 고치기 — 기다리지 않는다
SELECT id FROM product_seat WHERE id = ANY(:reclaimedSeats) AND status = 'HELD' FOR UPDATE SKIP LOCKED;
UPDATE product_seat st SET status = 'AVAILABLE'
 WHERE st.id = ANY(:locked) AND st.status = 'HELD'
   AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now());  -- 잠근 뒤 새 문장(§2.3)
-- H5 감소(n = H4 행 수 > 0일 때) — 0행이면 PairRepair(pendingDecrement = n · §2.4)
--    문장은 ExpiryCounters의 방식별 가드 문장 재사용(stmt면 `, updated_at = now()`가 붙는다) · 1행이면 changes.changed 등록(§2.4 · 선검증 DS14-6)
UPDATE user_hold_quota SET cnt = cnt - :n WHERE schedule_id = :s AND user_id = :u AND cnt >= :n;
-- H6 매수 판정 = 지금 upsert 그대로(UserLimitStrategies.kt:283-287) — 0행이면 H1을 다시 보고 매수 초과(:322-325와 같은 순서)
INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (:s, :u, 1)
ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + 1 WHERE user_hold_quota.cnt + 1 <= :max;
-- H7 좌석 잠금(지금 그대로 — 55P03이면 409)
SELECT id FROM product_seat WHERE id = :seat AND schedule_id = :s FOR UPDATE NOWAIT;
-- H8 잠근 뒤 새 문장으로 최종 판정(§2.3) — RESERVED 또는 활성 있음 → 409 SEAT_NOT_AVAILABLE
SELECT st.status, EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now()) AS active
FROM product_seat st WHERE st.id = :seat;
-- H9 홀드 = 새 행(새 id) · DB 시각
INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at)
VALUES (:seat, :s, :u, now(), now() + :ttl) RETURNING id, expires_at;
-- H10 (B·C만) 힌트 쓰기 — 이미 HELD(남은 거짓 HELD)면 쓰지 않는다 · D는 이 문장이 없다
UPDATE product_seat SET status = 'HELD' WHERE id = :seat AND status = 'AVAILABLE';
```

- **잠금 순서**: 회차(공유) → 자기 쿼터 행 → 자기 만료 홀드 행 → (C만: 회수한 다른 좌석 · SKIP LOCKED) → 대상 좌석 행(NOWAIT) → 새 홀드 행. **남의 쿼터 행은 잡지 않는다**(교착 고리를 만들지 않는다 — Q4). 기다리는 잠금은 회차(공유) · 자기 쿼터 행 · **회수 H4가 지우려는 자기 만료 홀드 행** 셋이고, 좌석 잠금은 모두 기다리지 않는다(NOWAIT · SKIP LOCKED) (선검증 DS14-4 — 초안은 셋째를 빠뜨렸다).
  - **대기 간선 'H4 → 확정 K3가 쥔 홀드 행'**: 같은 사용자의 확정이 그 홀드 행을 K3 `FOR UPDATE`로 쥐고 있으면(만료 직전 시작한 확정 — 시각 역전, 또는 만료로 거부되어 롤백 중인 확정) H4의 DELETE가 확정 트랜잭션의 끝을 기다린다. 확정이 성공하면 행이 사라져 H4는 그 행을 0행으로 받고(규칙 ② — 그만큼 내리지 않는다), 거부되면 H4가 지운다.
  - **고리가 닫히지 않는 이유**: 확정이 이후에 기다릴 수 있는 것은 K5·K6의 자기 행(이미 쥠)과 K7 예약 INSERT의 FK 검사(회차 · 좌석 행 `FOR KEY SHARE`)뿐이다. 회수 쪽이 쥔 것은 회차 `FOR SHARE`(H0 — `FOR KEY SHARE`와 충돌하지 않는다) · 자기 쿼터 행(확정은 쿼터 행을 잡지 않는다 — UserLimitStrategies.kt:51) · 이미 지운 만료 홀드 행뿐이다. 좌석은 아직 잡지 않았다(H7은 H4 뒤). → 확정은 회수가 쥔 것을 기다리지 않는다 (추정 — 동시성 테스트 대상 · §설계-10 #12).
  - **꼬리 영향**: 회수가 쿼터 행을 쥔 채 확정의 끝을 기다리는 동안, 같은 사용자의 다른 선점(H3 · H6 upsert)과 대조 복구(PairRepair.kt:110-112)가 그 쿼터 행에서 줄을 선다 — 상한 = 확정 트랜잭션 길이. 같은 사용자의 '확정 중 다른 좌석 선점'에서만 생긴다. 지표 후보: 회수 H4 대기 시간 · 대기 건수(§설계-8.2).
- **에러 순서**: 마감 → 좌석(H1) → 매수(H6) — 지금과 같다. 회수는 H1 통과 뒤 · H6 앞(명세 '회수하고 카운터를 내린 뒤 판정').
- **자기 좌석 재선점**(가설 2): H1이 거짓 `HELD`(B·C)·자기 만료 홀드를 비어 있음으로 보고 → H4가 그 홀드를 지우고 cnt를 내린 뒤 → H6·H8을 통과한다.
- **문장 수**(지금 대비): 남은 만료 홀드가 없는 요청은 H2 · H8 두 문장이 늘고 H1에 하위 질의가 붙는다. 회수하는 요청은 H3~H5 세 문장이 더 는다. D는 좌석 UPDATE가 없어진다(잠금만). 비용은 측정한다 `[구현 검증]`.
- 구현 형태(권장 — 열린 질문 아님, task 04 판단): B·C·D 경로는 JDBC 문장으로 쓴다. 지금 JPA 애그리거트(`ProductSeat`)는 메모리 안 `isExpired(now)`·`status` 판정이라 A의 의미를 담고 있고, 같은 트랜잭션에서 JPA 엔티티와 JDBC 쓰기를 섞으면 영속성 컨텍스트가 낡은 상태를 flush할 수 있다(추정 `[구현 검증]`). A 경로(JPA)는 건드리지 않는다.

#### 3.2 확정 (B·C·D) — 만료 판정만 통일(명세 §0 · §2)

```sql
-- K1 홀드 찾기(잠금 없음) — 없으면 404 HOLD_NOT_FOUND · 남의 것이면 403 HOLD_NOT_OWNED
SELECT seat_id, user_id FROM seat_hold WHERE id = :hold;
-- K2 좌석 잠금(기다린다) — 돌려받은 status를 K3보다 **먼저** 판정한다(아래 '판정 순서')
SELECT status FROM product_seat WHERE id = :seatId FOR UPDATE;
--   D: status = 'RESERVED' → 409 SEAT_NOT_HELD · B·C: status <> 'HELD' → 409 SEAT_NOT_HELD(지금 계약 그대로) — 여기서 끝(K3로 가지 않는다)
-- K3 잠근 뒤 새 문장 + 홀드 행 잠금(배치·회수의 DELETE가 이 행을 기다리게)
SELECT h.expires_at > now() AS mine_active,
       EXISTS (SELECT 1 FROM seat_hold o WHERE o.seat_id = h.seat_id AND o.id <> h.id AND o.expires_at > now()) AS other_active
FROM seat_hold h WHERE h.id = :hold FOR UPDATE;
--   행 없음(그 사이 회수·배치가 지움) → 404 HOLD_NOT_FOUND(지금 A의 '배치 뒤 확정'과 같은 응답)
--   mine_active = false 또는 other_active = true → 409 HOLD_EXPIRED  ('다른 활성 홀드'는 시각 역전 — §2.2)
-- K4~K6 전환(지금과 같은 쓰기)
UPDATE product_seat SET status = 'RESERVED' WHERE id = :seatId;
DELETE FROM seat_hold WHERE id = :hold;
INSERT INTO reservation (…) VALUES (…);   -- created_at은 지금처럼 앱 Clock(만료 판정이 아니다)
```

- **판정 순서(계약 — 선검증 DS14-1)**: K1(404 · 403) → **K2가 잠근 좌석의 status**(RESERVED → 409 SEAT_NOT_HELD · B·C는 `HELD` 아님 → SEAT_NOT_HELD) → K3 홀드 판정(행 없음 404 · 내 홀드 만료 또는 다른 활성 홀드 → 409 HOLD_EXPIRED). 근거: RC에서 `SELECT … FOR UPDATE`는 잠금을 기다렸다면 잠금 뒤 그 행의 **최신 커밋 버전**을 돌려준다(EPQ — §설계-0. 조건이 `id`뿐이라 다시 보아도 통과하고 새 버전이 나온다). 그래서 K2의 status는 그 사이 커밋된 남의 확정을 반영한다. K3만으로는 이 경우를 못 막는다 — 남의 확정은 자기 홀드 행을 지우므로(K6) '다른 활성 홀드'로 남지 않고, 내 홀드의 `mine_active`는 내 트랜잭션 시작 시각 기준이라 참일 수 있다.
  - 시나리오(시각 역전 + 남의 확정): ① X가 만료 시각 `t_e` 직전 `t0`에 확정을 시작한다(X의 `now()` = `t0 < t_e`) — K1을 지나 K2 앞에서 늦어진다. ② `t_e` 뒤 Y가 같은 좌석을 선점한다 — H8이 X의 홀드를 만료로 보고(Y의 `now()` > `t_e`) 새 홀드를 만든다. ③ Y가 확정한다 — K3에서 X의 홀드는 Y 기준 만료라 `other_active = false` → 좌석 RESERVED · Y 홀드 삭제 · 예약 INSERT · 커밋. ④ X의 K2가 잠금을 얻고 최신 버전 `status = 'RESERVED'`를 받는다 → **409 SEAT_NOT_HELD로 거부**. 순서를 바꿔 K3을 먼저 보면 X의 `mine_active`는 `t0` 기준 참 · Y 홀드는 지워져 `other_active = false` → X도 통과해 **확정 둘(이중 판매)**이 된다. 이 시나리오를 동시성 테스트에 넣는다(§설계-9 N2).
  - 지금 A의 순서(ProductSeat.kt:59-73 — 404 → 403 → HOLD_EXPIRED → SEAT_NOT_HELD)와 '만료됐고 좌석도 RESERVED'인 경우의 응답이 다르다(A는 HOLD_EXPIRED · B·C·D는 SEAT_NOT_HELD). A에서는 RESERVED 좌석에 홀드가 남지 않아 생기지 않는 경우다. A 확정은 바꾸지 않는다(Q3).
- 잠금 순서: 좌석(기다림) → 홀드 행 → 예약 INSERT(FK 검사 = 회차 · 좌석 행 `FOR KEY SHARE` — V1 `reservation.schedule_id`·`seat_id` FK). 확정은 쿼터 행을 잡지 않는다(지금도 그렇다 — 기본 조합의 `beforeConfirm`은 빈 함수, UserLimitStrategies.kt:51). 교착 점검: 확정이 좌석 S를 쥐고 홀드 h를 기다릴 때 h를 쥔 쪽은 회수(그 뒤 좌석 잠금은 NOWAIT) 또는 C 배치(좌석은 SKIP LOCKED)라 확정이 쥔 것을 기다리지 않는다 → 고리 없음 (추정 — 동시성 테스트 대상). 반대 방향 'H4 → K3가 쥔 홀드 행'은 §3.1 잠금 순서(선검증 DS14-4).
  - **정리 ①(B — §3.4)과의 교착(선검증 DS14-3)**: 정리 ①은 회차 행을 `FOR UPDATE`로 쥔다(QuotaCleanup.kt:118-121 — 주석 그대로 '홀드·예약 INSERT의 FK 검사(FOR KEY SHARE)도 막는다'). 정리 ①이 그 회차의 만료 홀드 행을 그냥 `DELETE`하면: 확정 X가 K3로 홀드 h를 쥔 채 K7 예약 INSERT의 FK 검사로 회차 행을 기다리고(정리 ①의 `FOR UPDATE`와 충돌) · 정리 ①은 h의 DELETE에서 X를 기다린다 → **고리(40P01)**. 막는 법: 정리 ①은 `FOR UPDATE SKIP LOCKED`로 고른 홀드 행만 지우고(확정이 쥔 h는 건너뛴다), 건너뛴 행이 남으면 쿼터 행 삭제를 다음 실행으로 미룬다(§3.4 B) → 정리 ①이 홀드 행에서 기다리는 간선이 없다. 확정에는 마감 판정이 없어(ConfirmReservationService.kt:32-58 — SalesCloseGate를 부르지 않는다) 마감 회차의 확정이 들어올 수 있다 — 드물지만 마감 직후 정리 실행과 겹칠 수 있다.
- 이 가드(K2·K3)는 B·C·D의 '이중 판매 0'(명세 §2)에 필요한 최소 조건이다 — A와 달리 B·C·D는 저장된 `HELD` 위에서도 재선점이 일어나므로, 지금 확정(잠금 없는 확인 → 무조건 UPDATE)을 그대로 쓰면 '만료 직전 시작한 확정 + 만료 뒤 재선점'이 이중 판매를 만든다. 확정 원자성 일반 해법(조건부 문장 · 홀드 연장 · 부분 유니크 인덱스)과 A 확정은 ADR-015 몫으로 남긴다(범위 경계 — 열린 질문 Q3).

#### 3.3 좌석 맵 조회 — 새 GET API(모든 방식)

- 계약(권장 — Q11): `GET /api/schedules/{scheduleId}/seats` → `200 [{seatId, section, rowNo, seatNo, status}]`(status = 보이는 상태 `AVAILABLE`·`HELD`·`RESERVED` — 누구의 홀드인지는 내지 않는다) · 회차 없으면 404 · `@Profile("!reconciler")`(지금 컨트롤러와 같게 — SeatHoldController.kt:16). 자동 커밋 문장 1개 · 잠금 없음 · `now()` = 문장 시각. 응답은 조회 시점 스냅숏이다(누를 때 달라질 수 있음 — ADR-018 주제).

```sql
-- A: 저장 상태 그대로
SELECT id, section, row_no, seat_no, status FROM product_seat WHERE schedule_id = :s ORDER BY id;
-- B·C: HELD 행만 홀드를 확인(빠른 길) — 거짓 HELD는 AVAILABLE로 보인다
SELECT id, section, row_no, seat_no,
       CASE WHEN status = 'HELD' AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now())
            THEN 'AVAILABLE' ELSE status END AS status
FROM product_seat st WHERE schedule_id = :s ORDER BY id;
-- D: RESERVED가 아닌 모든 좌석마다 홀드를 확인
SELECT id, section, row_no, seat_no,
       CASE WHEN status = 'RESERVED' THEN 'RESERVED'
            WHEN EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now()) THEN 'HELD'
            ELSE 'AVAILABLE' END AS status
FROM product_seat st WHERE schedule_id = :s ORDER BY id;
```

- 비용 차이(선택지 표의 '조회 시 홀드 조인'): A = 좌석 행만 · B·C = `HELD` 행만 홀드 확인(B는 거짓 `HELD`가 쌓여 확인 대상이 늘어난다) · D = `RESERVED`가 아닌 좌석마다 `idx_seat_hold_seat_id` 탐색(V2). 회차당 좌석 1만 개 기준 실행 계획은 `[구현 검증]`. 캐시·조회 구조 최적화는 ADR-018 몫(명세 §4 금지영역)이고, 여기서는 측정 때 모든 방식 셀에 같은 인덱스(V11)만 켠다 — 기본 Flyway 위치에는 넣지 않는다(§설계-7 · 선검증 DS14-OQ1). V11이 없는 배포에서도 API는 같은 답을 내고, 좌석 표를 훑을 뿐이다.
- **B·C의 빠른 길은 저장 `HELD`가 '활성 홀드 있음'을 빠짐없이 덮는다는 전제 위에 있다** — 저장이 `AVAILABLE`인데 활성 홀드가 있는 좌석은 B·C 문장에서 `AVAILABLE`로 보인다. 방식 전환이 이 전제를 깨지 않도록 전환 정리는 좌석 기준 재계산 하나로 한다(§설계-7 '방식 전환 정리' — D → B·C에서 특히 필요: D는 활성 홀드 좌석을 `AVAILABLE`로 둔다) (선검증 DS14-2).

#### 3.4 만료 배치

| 방식 | 배치 |
|---|---|
| A | **무변경**(§설계-1) |
| B | 주기 배치 없음 — 회수는 선점에서만. 정리 ①(마감 회차 쿼터 행 삭제)이 지금 '그 회차에 홀드 행이 하나라도 있으면 건너뛴다'(QuotaCleanup.kt:129-133)라서 B에서는 마감 회차의 남은 만료 홀드 때문에 쿼터 행이 영영 안 지워진다 → B의 정리 ①은 회차 행 `FOR UPDATE` 뒤 **활성 홀드가 없으면** 그 회차의 만료 홀드 행을 지우고, 홀드 행이 하나도 남지 않았을 때만 쿼터 행을 같은 트랜잭션에서 지운다(마감 회차는 카운터를 쓰지 않으므로 감소 없음 — 명세(ADR-009) §10). 홀드 삭제는 **`FOR UPDATE SKIP LOCKED`로 고른 행만** 지운다(아래 B1~B3 — 확정 K3가 쥔 행을 기다리면 교착 · §3.2 '정리 ①과의 교착') (선검증 DS14-3). 지운 홀드 수 · 건너뛴 수는 로그·지표로(열린 질문 Q7) |
| C | 틱마다 청크 트랜잭션 반복(아래 C1~C6) — 기존 `expiry-interval`(10초)을 그대로 쓴다 |
| D | 쌍 단위 청크(아래 D1~D5 — 좌석 잠금·좌석 쓰기 없음). C의 C3~C5와 같은 규칙을 쌍 후보에 쓴다 (선검증 DS14-5) |

```sql
-- B 정리 ① (회차 :s 하나 = 트랜잭션 하나 · 지금 cleanSchedule의 회차 FOR UPDATE · 마감 재확인 뒤 — QuotaCleanup.kt:115-127 그대로)
-- B1 활성 홀드가 있으면 이 회차는 건너뛴다(기록 holds_seen = 1 — 지금과 같은 뜻)
SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = :s AND expires_at > now());
-- B2 만료 홀드 삭제 — 다른 트랜잭션이 쥔 행(확정 K3 · 회수 H4)은 건너뛴다. 감소 없음(마감 회차)
DELETE FROM seat_hold WHERE id IN (
  SELECT id FROM seat_hold WHERE schedule_id = :s AND expires_at <= now() FOR UPDATE SKIP LOCKED)
RETURNING id;                                                   -- → 지운 수
-- B3 잠금 뒤 새 문장으로 남은 홀드 확인(지금 :129-133과 같은 문장) — 남았으면(건너뛴 행 · 그 사이 커밋) 쿼터 행 삭제를 다음 실행으로 미룬다
SELECT EXISTS (SELECT 1 FROM seat_hold WHERE schedule_id = :s);
DELETE FROM user_hold_quota WHERE schedule_id = :s;            -- B3이 false일 때만
```

- 미룬 회차는 지금처럼 다음 실행의 대상에 다시 든다(대상 = 마감 + 쿼터 행 남음 — QuotaCleanup.kt:86-87). 건너뛴 행을 쥔 확정이 끝나면 행이 사라지거나(성공) 다음 실행이 지운다(거부 · 롤백).

```sql
-- C1 후보 좌석(idx_seat_hold_expires_at — V2) — 진행형 커서(선검증 DS14-5): :after = 앞 청크 후보의 최대 seat_id(틱 시작 때 0).
--    잠그지 못했거나 실패한 앞쪽 후보가 있어도 커서는 그 뒤로 간다 → 앞쪽 후보가 같은 틱의 진행을 막지 않는다. 남은 것은 다음 틱(커서 0부터)이 다시 고른다
SELECT DISTINCT seat_id FROM seat_hold WHERE expires_at <= now() AND seat_id > :after ORDER BY seat_id LIMIT :k;
-- C2 좌석 잠금 — 선점·확정 중인 좌석은 건너뛴다(다음 틱이 다시 고른다: 홀드 행을 지우지 않았으므로 C1에 다시 나온다)
SELECT id FROM product_seat WHERE id = ANY(:candidates) FOR UPDATE SKIP LOCKED;            -- → L
-- C3 쌍 잠금(규칙 ① — 청크 전체 쌍을 정렬해 한 번에) — 잠근 쌍을 돌려받는다
SELECT schedule_id, user_id FROM user_hold_quota
 WHERE (schedule_id, user_id) IN (SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE seat_id = ANY(:L) AND expires_at <= now())
 ORDER BY schedule_id, user_id FOR UPDATE;                                                -- → P(잠근 쌍)
-- C3b 쿼터 행이 없는 쌍(MISSING = 후보 쌍 − P) — A와 같게 지우기 앞에서 PairRepair(선검증 DS14-5):
--    그 쌍의 지울 홀드 id를 읽고(:ids — 잠긴 좌석 L 위라 새 홀드가 생기지 않는다)
SELECT id FROM seat_hold WHERE seat_id = ANY(:L) AND expires_at <= now() AND schedule_id = :s AND user_id = :u;
--    → PairRepair.repairInCurrentTx(EXPIRY_REPAIR, :s, :u, MISSING, excludeHoldIds = :ids, pendingDecrement = |:ids|)
--      = cnt 0 행을 만들고 잠근 뒤(PairRepair.kt:88-94) cnt = 실제 값(지울 홀드 제외)으로 덮고 기록한다.
--      A의 MISSING 판정(ExpiryCounters.kt:73-74 — 행 없음)·호출 인자(ExpiryCounters.kt:82-84)와 같다. 이 쌍은 C5 감소를 하지 않는다(A도 복구 갈래에서 감소하지 않는다 — :57)
-- C4 삭제(규칙 ②) — 잠근 쌍 P로 한정 · MISSING 쌍은 PairRepair에 넘긴 id로만
DELETE FROM seat_hold h USING unnest(:ps::bigint[], :pu::bigint[]) AS p(schedule_id, user_id)
 WHERE h.seat_id = ANY(:L) AND h.expires_at <= now() AND h.schedule_id = p.schedule_id AND h.user_id = p.user_id
RETURNING h.schedule_id, h.user_id;                                                        -- 쌍별 행 수 = n
DELETE FROM seat_hold WHERE id = ANY(:missingIds) RETURNING id;                             -- 돌려받은 수 ≠ |:missingIds|면 청크 롤백(아래)
-- C5 쌍별 가드 감소(P의 쌍만 · 0행이면 PairRepair(pendingDecrement = n) — §2.4)
--    ExpiryCounters 방식별 문장 재사용(stmt면 `, updated_at = now()`) · 1행이면 changes.changed 등록(§2.4 · 선검증 DS14-6)
UPDATE user_hold_quota SET cnt = cnt - :n WHERE schedule_id = :s AND user_id = :u AND cnt >= :n;
-- C6 (C만) 좌석 상태 — 잠근 좌석만 · 잠근 뒤 새 문장(§2.3)
UPDATE product_seat st SET status = 'AVAILABLE'
 WHERE st.id = ANY(:L) AND st.status = 'HELD'
   AND NOT EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now());
```

```sql
-- D 배치(선검증 DS14-5) — 좌석 문장 없음
-- D1 후보 쌍 — 진행형 커서: (:afterS, :afterU) = 앞 청크 후보의 마지막 쌍(틱 시작 때 (0, 0))
SELECT DISTINCT schedule_id, user_id FROM seat_hold
 WHERE expires_at <= now() AND (schedule_id, user_id) > (:afterS, :afterU)
 ORDER BY schedule_id, user_id LIMIT :k;                                                   -- → 후보 쌍 (:cs, :cu)
-- D2 쌍 잠금(규칙 ① — 정렬 · 기다림) — 잠근 쌍을 돌려받는다
SELECT q.schedule_id, q.user_id FROM user_hold_quota q
  JOIN unnest(:cs::bigint[], :cu::bigint[]) AS c(schedule_id, user_id)
    ON q.schedule_id = c.schedule_id AND q.user_id = c.user_id
 ORDER BY q.schedule_id, q.user_id FOR UPDATE OF q;                                        -- → P = (:ps, :pu)
-- D3 MISSING(후보 − P) — C3b와 같다: 그 쌍의 만료 홀드 id(:ids)를 읽고 PairRepair(excludeHoldIds = :ids, pendingDecrement = |:ids|)
SELECT id FROM seat_hold WHERE schedule_id = :s AND user_id = :u AND expires_at <= now();
-- D4 삭제 — 잠근 쌍과 지울 행의 연결 = (schedule_id, user_id) 조인. P에 없는 쌍의 행은 이 문장이 지우지 않는다
DELETE FROM seat_hold h USING unnest(:ps::bigint[], :pu::bigint[]) AS p(schedule_id, user_id)
 WHERE h.schedule_id = p.schedule_id AND h.user_id = p.user_id AND h.expires_at <= now()
RETURNING h.schedule_id, h.user_id;                                                        -- 쌍별 행 수 = n
DELETE FROM seat_hold WHERE id = ANY(:missingIds) RETURNING id;                             -- C4 둘째 문장과 같다
-- D5 = C5(쌍별 가드 감소 · 방식별 문장 · 등록)
```

- **MISSING을 지우기 앞에서 복구하는 이유(C3b · D3)**: 행이 없는 쌍은 C3·D2가 잠그지 못한다. 그 쌍의 홀드를 먼저 지우고 C5에서 0행 → 복구로 가면, 그 사이 그 사용자의 선점 H6 upsert가 행을 새로 만들어(cnt 1 — 기다릴 행이 없다) 커밋할 수 있고, C5의 가드 감소가 그 cnt를 내려 어긋남(below)을 조용히 만든다. PairRepair가 행을 먼저 만들고 잠그면(PairRepair.kt:88-94 — INSERT한 행은 커밋까지 이 트랜잭션이 쥔다) 그 선점의 upsert가 이 트랜잭션 끝을 기다린다 → 규칙 ①이 MISSING 쌍에도 선다 (추정 — 동시성 테스트 대상). `:missingIds` 삭제가 넘긴 수보다 적게 지우면(그 사이 다른 트랜잭션이 지움 — 이미 덮은 cnt가 틀린다) 청크를 롤백하고 ERROR 로그 + 지표(`seat.hold.expiry.tick{outcome}` 실패 · A의 틱 롤백과 같은 처리 — ExpiryCounters.kt:123-136 skipped 기록)로 남긴다(무음 금지).
- **진행형 커서의 경계**: 커서는 틱 안에서만 산다(다음 틱은 처음부터). 청크 하나가 실패해도(40P01 · MISSING 수 불일치 등) 커서를 그 청크 뒤로 옮겨 다음 청크를 계속한다. 틱당 청크 상한 · `k`는 `[구현 검증]`(§설계-10 #7).
  - **틱당 청크 상한(코드 리뷰 CR14-2)**: `seat.hold.expiry-max-chunks-per-tick`(기본 100 · 1 미만은 기동 거부). 닿으면 틱을 끝내고 **커서를 다음 틱에 넘긴다** — 다음 틱은 처음이 아니라 그 커서 뒤부터 이어 가고, 후보 끝에 닿으면 커서를 버린다(그다음 틱은 처음부터). 늘 처음부터 시작하면 앞쪽에서 계속 건너뛰는(SKIP LOCKED) · 실패하는 후보가 상한을 다 써서 뒤쪽 후보가 밀린다. 넘긴 커서는 앱마다(빈 상태) 따로다. 닿은 틱 수는 지표 `seat.hold.expiry.chunk_cap_reached`. 기본 100의 근거: 측정 회차 전부(주 회차 1만 석 + 탐침 회차 1만 석 — §측정-3.1·3.2)가 한 틱에 만료돼도 `k` = 500이면 40청크라 측정 부하에서는 닿지 않는다 — 장애 뒤 밀림처럼 그보다 큰 경우에만 틱 길이를 묶는다(값은 #7).
- **D4의 대기**: D는 좌석을 잠그지 않으므로 D4 DELETE가 확정 K3가 쥔 홀드 행(시각 역전 확정)을 기다릴 수 있다 — 고리는 없다(확정은 쿼터 행을 잡지 않는다 · §3.1 DS14-4와 같은 이유). 그동안 청크의 다른 쌍 쿼터 행이 잡혀 있어 그 사용자들의 선점이 기다린다(꼬리 지표 — §설계-8.2).

- C 배치 잠금 순서: 좌석(SKIP LOCKED — 기다리지 않음) → 쿼터 행(정렬 — 기다림) → 홀드 행. 선점의 순서(쿼터 → 좌석 NOWAIT)와 반대지만, 좌석 쪽은 양쪽 모두 기다리지 않으므로 고리가 생기지 않는다(추정 — 동시성 테스트 대상). 배치를 쥔 동안 같은 좌석의 선점은 NOWAIT로 지고(경합 오류 지표), 배치가 기다리는 쿼터 행의 주인 선점은 배치 청크가 끝날 때까지 기다린다(꼬리 지표).
- 잠긴 좌석 L 위에는 다른 트랜잭션이 홀드를 새로 만들 수 없고(선점은 좌석 잠금 뒤 INSERT), 트랜잭션 안 `now()`는 고정이라 C3과 C4 사이에 `expires_at <= now()`인 행이 늘지 않는다.
- 청크 크기 `k` 기본값 · 틱당 청크 상한은 `[구현 검증]`. 앱 2대에서 두 배치가 돌아도 좌석은 SKIP LOCKED로 나뉘고 쿼터 행은 정렬 순서로 잠근다.

#### 3.5 카운터 회수 — 방식별 요약

| | 회수 주체 | 같은 홀드를 두 번 빼지 않는 방법 |
|---|---|---|
| A | 배치만(틱 트랜잭션 · JPA 삭제 + 쌍 정렬 가드 감소) | 늦은 쪽 틱 롤백(§설계-1 — 추정) |
| B | 선점 회수(H2~H5) + 마감 정리 ①(감소 없음) | 쌍 잠금 + `DELETE … RETURNING` 행 수만큼(§2.4) |
| C | 선점 회수 + 배치(C3~C5) | 같음 |
| D | 선점 회수 + 배치(행 + 카운터) | 같음 |

### 설계-4 D의 '좌석당 활성 홀드 1개' 강제 — 후보와 경합 (명세 §8 가정 2)

부분 유니크 인덱스는 술어에 IMMUTABLE 식만 쓸 수 있어 `now()`(STABLE)를 넣지 못한다 — 초안 문장('부분 유니크 인덱스로 표현할 수 없다')은 맞다. 다만 시간 범위 배제 제약(아래 (다))으로는 '같은 좌석에 시간이 겹치는 홀드 둘'을 DB가 막을 수 있다 — 초안의 '그래서 upsert 모델이 후보'라는 추론에 이 후보를 더한다.

| 시나리오 | (가) 좌석 행 잠금 + 새 행 추가 | (나) 홀드 행 upsert(좌석당 1행 · 만료 행 덮어쓰기) | (다) 시간 범위 배제 제약 |
|---|---|---|---|
| 방법 | H7 `FOR UPDATE NOWAIT` → H8 새 문장에서 활성 없음 확인 → H9 새 행 | 좌석 id 유니크 + `INSERT … ON CONFLICT (seat_id) DO UPDATE SET user_id = …, held_at = now(), expires_at = now() + ttl WHERE seat_hold.expires_at <= now() RETURNING …` — 1행 = 이김 · 0행 = 활성 홀드 있음 | `EXCLUDE USING gist (seat_id WITH =, tstzrange(held_at, expires_at) WITH &&)` — 겹치면 23P01 |
| 빈 좌석에 두 사용자 동시 | 뒤 쪽은 기다리지 않고 409(지금 NOWAIT 의미 그대로) | 앞 쪽 INSERT가 커밋되기 전까지 뒤 쪽이 **기다린다**(ON CONFLICT의 추측 삽입 대기 — UserLimitStrategies.kt:122-125 주석과 같은 성질) → 0행 → 409. NOWAIT 의미가 깨진다 | 뒤 쪽이 앞 쪽 커밋을 기다린 뒤 23P01 → 409(대기) |
| 남의 만료 홀드 위 재선점 | 새 행 추가 · 남의 행·쿼터 행은 건드리지 않는다(남은 만료 홀드는 그 사용자의 회수 · 배치 몫) | 남의 행을 덮어쓴다 → **남의 cnt를 같은 트랜잭션에서 내려야 한다** → 남의 쿼터 행 잠금 → 'A가 B의 만료 좌석을, B가 A의 만료 좌석을' 동시에 잡으면 교착(40P01) | 새 행 추가(남의 행 남음 — (가)와 같음) |
| 만료 직전 시작한 확정 vs 만료 뒤 재선점 | 둘 다 좌석을 잠근다 → K3의 '다른 활성 홀드'로 거부 | 덮어쓴 행 id가 그대로면 확정이 남의 홀드를 자기 id로 찾는다(user_id 검사에 기대야 함) · id를 바꾸면(`id = nextval`) PK 갱신 | 좌석 잠금이 없으면 확정 쪽 가드가 따로 필요 |
| 배치 vs 재선점 | 배치는 만료 행만 지우고 좌석을 안 건드린다 → 무관 | 배치가 지우려던 행이 덮어써짐 → 같은 행 조건이라 EPQ가 새 `expires_at`을 보고 건너뜀 (추정) | (가)와 같음 |
| 자기 좌석 재선점 | 회수 H4가 자기 만료 행을 지운 뒤 추가 | 자기 행을 덮어씀(감소 1 · 증가 1을 한 트랜잭션에) | 회수 뒤 추가 |
| 대조·안전망 | 새 행 = 새 id → 최근 창이 본다(§2.5) | id가 그대로인 덮어쓰기는 최근 창이 못 본다(전체 커서 몫) | 새 id |
| 스키마 | 없음 | 좌석 id 유니크 인덱스(V4 `uq_seat_hold_seat_id`와 **다른 이름**이어야 한다 — 기동 검사가 그 이름의 존재를 UNIQUE 전략과 맞춘다, HoldStrategyStartupCheck.kt:89-102 `run`) · 기존 DB의 중복 행 정리 · 트랜잭션 안 `CREATE UNIQUE INDEX`의 쓰기 차단 | `btree_gist` 확장 필요(`CREATE EXTENSION` 권한 — 확인 못 함) |
| 판단 | **권장** — 기본 조합이 이미 좌석 행을 NOWAIT로 잠그므로 새 경합 지점이 없다 | 기각 후보(NOWAIT 깨짐 · 남의 카운터 교착 · 안전망) | 기각 후보(대기 · 확장 · 확정 가드 별도). '최후 방어선'(ADR-015의 부분 유니크 인덱스와 같은 자리)으로는 고려할 만하다 — 이번 범위 밖 |

- (가)가 성립하는 전제(설계 선검증 대상 — §설계-9 N2): **홀드를 만들거나 좌석을 RESERVED로 바꾸는 모든 경로가 좌석 행 잠금을 먼저 쥔다**(선점 H7 · 확정 K2). 홀드를 지우기만 하는 경로(회수 · 배치)는 활성 여부를 바꾸지 않으므로(만료 행만 지운다) 좌석 잠금이 필요 없다. 이 전제는 기본 조합에서만 확인했다 — 다른 좌석 전략(조건부 UPDATE · 낙관락 · advisory 등)은 좌석 행을 잠그지 않으므로 B·C·D와 섞으면 깨진다(§설계-6의 기동 거부 이유).
- **(가)의 확정 시점(선검증 DS14-OQ2)**: (가)는 아직 **권장**이다. §설계-10 #2 재현 테스트(RC에서 잠금과 하위 질의를 한 문장으로 하면 막 커밋된 남의 활성 홀드를 못 본다 → D에서 두 번째 활성 홀드가 생긴다 · 잠근 뒤 새 문장(H8)으로 나누면 생기지 않는다)가 green이 된 뒤 확정한다 `[구현 검증]`. (가)가 기대는 '잠근 뒤 새 문장은 잠금을 쥐었던 트랜잭션의 커밋을 본다'를 이 저장소에서 아직 재현하지 않았기 때문이다(§2.3 — 외부 근거만). 테스트가 기대와 다르면 (가)를 다시 열고 (다)를 재검토한다.

### 설계-5 배치가 방금 재선점된 좌석을 되돌리는 경합 (초안 가설 3)

| 방식 | 경합이 생길 수 있나 | 막는 것 |
|---|---|---|
| A | 저장 `HELD`가 남은 동안은 재선점이 없다(선조회 ②·⑤). 배치 틱 두 개가 겹치면 늦은 쪽이 롤백 | 지금 코드(§설계-1 — 추정 · `[구현 검증]`). 가설 3은 A에서 '앱 2대 배치 겹침'으로만 잰다 |
| B | 주기 배치가 없다 · 회수는 좌석 행을 쓰지 않는다 | 해당 없음(구조) |
| C | 있다 — 배치가 좌석 상태를 쓴다. 한 문장 조건부 UPDATE면 EPQ 때문에 막 커밋된 재선점을 못 보고 되돌린다(§2.3) | 좌석 `FOR UPDATE SKIP LOCKED`로 먼저 잠그고(C2 — 선점 중인 좌석은 건너뜀) 잠근 뒤 새 문장으로 활성 홀드가 없을 때만 되돌린다(C6) · H4b도 같은 방식. 그래도 잘못 되돌려지면(설계 결함) 비어 있음 규칙이 저장 상태를 쓰지 않으므로 이중 판매로 번지지 않는다 — 판정기 '활성 홀드 있는데 HELD 아님'(B·C 기대 0)이 드러낸다 |
| D | 없다 — 배치가 좌석 행을 쓰지 않는다 | 구조(D 배치 = 행 + 카운터만) |

### 설계-6 적용 범위 · 손잡이 · 기동 검사

- **손잡이 이름(제안 — Q9)**: `seat.hold.expiry-mode` = `sweep`(A · 기본값) · `lazy`(B) · `lazy-sweep`(C) · `derived`(D). `SeatHoldProperties`의 새 필드(기본 SWEEP — 키를 주지 않으면 지금과 같다). 배치 주기는 기존 `seat.hold.expiry-interval`을 A·C·D가 같이 쓴다(비교 조건 같게). C·D 배치 청크 = 새 `seat.hold.expiry-batch-size`(기본값 `[구현 검증]`). `seat.hold.expiry-scheduler-enabled`(HoldExpiryScheduler.kt:10)는 그대로 — `lazy`에서는 만료 유스케이스가 할 일이 없다(0 반환 · 틱 지표만).
- **기동 거부(설정 검사 — 마이그레이션 앞)**: `HoldStrategyStartupCheck.checkConfig`(HoldStrategyStartupCheck.kt:145 — Flyway 전에 도는 검사 · HoldStrategyMigrationGate)에 규칙을 더한다: `expiry-mode ≠ sweep`이면 `strategy = PESSIMISTIC_NOWAIT` 이고 `limit-strategy = COUNTER_UPSERT_PRECHECK`여야 한다. 아니면 거부하고 메시지에 바꿀 키를 안내한다(지금 거부 문구 방식). 조용히 A로 돌지 않는다(명세 §2). 이미 있는 규칙(매수 방식 ≠ none → 좌석 3b 필수 — :147)과 합치면 사실상 '매수 = counter-upsert-precheck' 한 조건이 는다.
  - counter-trigger를 빼는 이유: V8 트리거가 홀드 DELETE마다 cnt −1을 하므로(V8__counter_trigger.sql `trg_seat_hold_quota`) 회수 H5의 앱 감소와 겹친다.
  - counter · counter-upsert를 빼는 이유: 범위(명세 §2 — 기본 조합만). 선조회가 없는 방식은 H1의 자리가 없다.
  - `expiry-mode ≠ sweep` + 측정 주입(`seat.hold.loadtest.inject-app.enabled`)도 거부를 권장(Q13) — 주입 'expiry' 경로가 A의 `ExpiryCounters.decrement` 안에 있다(ExpiryCounters.kt:60 · AppPathInjector.kt:56-59).
- reconciler 프로필(F — 대조 전용 프로세스): 업무 요청·만료 스케줄러가 없고(application-reconciler.yml:12) 대조 정의가 무변경이라 손잡이와 무관하다.
- **한계(코드 리뷰 CR14-17)**: C·D인데 `seat.hold.expiry-scheduler-enabled=false`(reconciler 프로필 아님)면 주기 배치가 돌지 않아 만료 홀드 행이 회수(그 사용자의 다음 선점)로만 지워진다 — 기동은 허용하고(테스트가 스케줄러를 끈다) WARN 로그만 남긴다(`HoldStrategyStartupCheck.expirySchedulerOffWarning`).

### 설계-7 스키마 변경 · 기존 DB 전환

| 버전 | 내용 | 위치(제안 — Q8) | 대상 |
|---|---|---|---|
| V11 | `CREATE INDEX idx_product_seat_schedule_id ON product_seat (schedule_id);`(좌석 맵 — 지금은 이 조회가 좌석 표 전체를 훑는다) | 새 선택 위치 `classpath:db/migration-seat-map` · **application.yml 기본 위치 목록(:16)에는 넣지 않는다** — 측정 때 모든 방식 셀의 `spring.flyway.locations`에 같게 더하고, 기본 위치 편입은 기본값(`expiry-mode`)을 교체할 때 함께 한다 (선검증 DS14-OQ1) | 측정: 모든 방식(A 포함 — 비교 조건 같게) |
| (V12) | D 후보 (나)·(다)를 고를 때만(유니크 · 배제 제약) | 별도 선택 위치 | D |

- **추가형**: B·C·D 데이터 모델은 열·표를 더하지 않는다(좌석 `status` 값 집합 · 홀드 열 그대로 — 새 의미만). V11은 인덱스 추가뿐이고 행을 다시 쓰지 않는다. V1~V10 무변경(명세 §4).
- **V11의 락**: 트랜잭션 안 `CREATE INDEX`는 `SHARE` 락으로 product_seat 쓰기(선점 H10 · 확정 K5 · A 배치)를 끝날 때까지 막는다(ADR-013 §4.4의 외부 근거 · §7.3 '락을 쥐는 시간은 트랜잭션 경계가 정한다'). ADR-013 원칙대로 시드 크기 사본(S4c 시드 — 회차 1,000 × 1만 석 ≈ 1,000만 좌석)에서 시간을 먼저 재고 `[구현 검증]`, 짧으면 Flyway 트랜잭션 안 그대로(ADR-013 ③과 같은 단순한 길), 길면 `CONCURRENTLY` 판(`V11__….sql.conf`의 `executeInTransaction=false` · 실패 시 INVALID 인덱스 처리 규칙)으로 간다.
- **기본 위치 목록(application.yml:16)에 넣지 않는 이유(선검증 DS14-OQ1 — 메인 결정)**: 명세 §2 '기존 동작 보존'(손잡이 기본값을 바꾸기 전까지 기존 기본값 동작 그대로). 손잡이 기본이 `sweep`(A)인 배포에 스키마 변경(인덱스 생성 · 그동안의 쓰기 차단 — 아래 'V11의 락')을 붙이지 않는다. 측정은 모든 셀에 같게 켜므로 비교 조건은 같다. 기본값을 교체할 때(task 06) V11 위치를 기본 목록에 더하는 것과 그 배포의 V11 시간 측정(아래)을 함께 한다.
- **위치를 기본 위치(`db/migration`)에 두지 않는 이유**: K2(옛 방식 — `db/migration`만, V1~V7) DB에 V11이 먼저 붙으면, 뒤에 V9·V10 위치를 더할 때 '적용되지 않은 낮은 버전'으로 Flyway 검증이 실패한다(outOfOrder 기본 false — 추정 `[구현 검증]`). 새 선택 위치면 K2·K3 안내(application.yml:45-46)가 그대로 성립한다. 기동 검사의 위치 예측(`IncrementalSchemaCheck.afterLocations` — IncrementalSchemaCheck.kt:69-80)은 모르는 위치를 무시하므로 영향이 없다. 기존 테스트는 `PIN_FLYWAY_BASE`(`db/migration`만 — IntegrationTest.kt:40)라 V11이 붙지 않는다.
- **ADR-013 §7.3 원칙 대입 — 기존 데이터 수렴**: A가 쌓은 행(`HELD` + 홀드 0..1)은 B·C·D 규칙에서 그대로 유효하다(백필 없음 — '새로 건드린 행만 새 형태'). D는 옛 `HELD`를 '예약 아님'으로 읽고 활성 여부는 홀드 행으로 본다(옛 형태를 맡는 경로 = 비어 있음 규칙).
- **혼재 — A와 B·C·D는 한 DB에서 함께 돌면 안 된다**(설계 사실 — 실코드 근거):
  - B·C·D가 만든 '좌석당 여러 홀드 행'(남은 만료 + 활성) 위에서 A의 배치는 만료분을 지운 뒤 활성 홀드가 남아도 `HELD → AVAILABLE`로 바꾼다(ProductSeat.kt:76-86). A의 선점은 저장 상태만 믿으므로(ProductSeat.kt:44-48 · UserLimitStrategies.kt:340) 그 좌석을 다시 판다 → 이중 판매.
  - D가 만든 활성 홀드의 좌석은 `AVAILABLE`로 저장돼 있다 → A의 선점이 비어 있음으로 본다 → 이중 판매.
  - B·C·D끼리는 비어 있음 규칙이 같아 선점·확정 판정은 갈리지 않는다(추정). 그러나 **보이는 상태와 저장 `HELD`의 뜻은 갈린다** — D는 활성 홀드 좌석을 `AVAILABLE`로 두므로 D가 쌓은 DB 위에서 B·C의 좌석 맵 빠른 길(§3.3)은 활성 홀드 좌석을 `AVAILABLE`로 보이고, B·C의 C6·H4b·H10도 저장 `HELD`가 활성 홀드를 덮는다는 전제(§3.3)에 선다. 그래서 B·C·D 사이 전환에도 정리가 필요하다 (선검증 DS14-2 — 초안의 '섞여도 괜찮다'를 좁힌다).
  - → **방식 전환 절차(Q12 — 선검증 DS14-2로 넓힘)**: **방식 사이 전환은 모두**(A ↔ B·C·D만이 아니라 B·C ↔ D · B ↔ C 포함) ADR-013의 ①(멈추고 바꾸기 — 모든 앱을 내리고, 아래 정리를 돌리고, 손잡이를 바꿔 올린다)만 허용한다. 롤링 배포로 손잡이를 바꾸는 길은 두지 않는다. 한 DB에 다른 방식 앱이 붙는 것을 막는 장치(DB 방식 표시 + 기동 검사)는 이번에 만들지 않는다(NEXT 후보로 제안).
- **방식 전환 정리 — 좌석 기준 재계산 하나(선검증 DS14-2)**: 방향(B·C·D → A · D → B·C · B·C → D · B ↔ C)마다 SQL을 따로 두지 않고 정지 상태에서 같은 두 단계를 한 트랜잭션으로 돌린다(정지 상태라 잠금 경합이 없다 · 시각은 DB `now()` 하나).

```sql
-- T1 남은 만료 홀드 삭제 + 쌍별 감소(지운 행 수만큼 — 규칙 ②). 열린 회차만 감소(마감 회차는 카운터를 쓰지 않는다 — 정리 ①과 같은 뜻)
WITH d AS (DELETE FROM seat_hold WHERE expires_at <= now() RETURNING schedule_id, user_id),
     n AS (SELECT schedule_id, user_id, count(*) AS n FROM d GROUP BY schedule_id, user_id)
UPDATE user_hold_quota q SET cnt = q.cnt - n.n            -- 증분 방식이 stmt면 `, updated_at = now()`를 더한다(§2.4 DS14-6과 같은 뜻)
  FROM n JOIN product_schedule s ON s.id = n.schedule_id AND s.starts_at > now()
 WHERE q.schedule_id = n.schedule_id AND q.user_id = n.user_id;
-- T2 좌석 기준 재계산 — RESERVED가 아닌 좌석을 '활성 홀드 있음 → HELD / 없음 → AVAILABLE'로
UPDATE product_seat st
   SET status = CASE WHEN EXISTS (SELECT 1 FROM seat_hold h WHERE h.seat_id = st.id AND h.expires_at > now())
                     THEN 'HELD' ELSE 'AVAILABLE' END
 WHERE st.status <> 'RESERVED';
```

  - **마감 회차를 감소하지 않는 이유(재점검 DS14-9 기각 근거)**: 마감 회차는 카운터 불변식 범위 밖이다 — 판정기 `v_counter_mismatch`가 마감 회차(`starts_at <= 시각`)를 빼고(LoadtestDataService.kt:190·203), 정리 ①이 마감 회차 쿼터 행을 지운다(QuotaCleanup.kt:86-87). 그래서 T1 뒤 마감 회차의 `cnt`가 홀드 행 수와 달라도 위반이 아니고, 확인의 '열린 회차만'은 이 범위 정의 그대로다.
  - 감소가 음수가 되면 V5 CHECK(`cnt >= 0` — V5__user_hold_quota.sql:6)가 문장을 실패시킨다(트랜잭션 롤백 — 조용히 0으로 자르지 않는다). 쿼터 행이 없는 열린 쌍은 T1의 UPDATE에 걸리지 않으므로 아래 대조가 잡는다.
  - D로 갈 때도 같은 T2를 돌린다 — D는 `HELD`를 쓰지 않고 읽지도 않으므로(옛 `HELD` = '예약 아님') 판정에 영향이 없고, 남은 `HELD`는 §8.3의 '옛 데이터 `HELD`'로 따로 센다.
  - **확인(앱을 올리기 전 · 하나라도 어기면 올리지 않는다)**: `v_held_without_hold = 0`(A로 갈 때 — T2 뒤 `HELD`는 활성 홀드가 있는 좌석뿐) · `v_duplicate_hold_seats = 0`(T1 뒤 남은 홀드는 모두 활성이고 좌석당 활성 ≤ 1 — N2) · 카운터 대조 불일치 0(`v_counter_mismatch` — 열린 회차 · LoadtestDataService.kt:203). 판정기 항목 정의는 LoadtestDataService.kt:140-203 그대로(그 문장을 DB에서 직접 돌린다 — 판정기 엔드포인트는 앱이 떠 있어야 한다).
  - 초안의 방향별 절차(B·C → A = C 배치 SQL 한 번 · D → A = 그에 더해 '활성 홀드 좌석 `HELD` 표시')는 위 두 단계로 바뀐다 — C 배치 SQL은 좌석 잠금 · 청크 · SKIP LOCKED를 전제로 해서 정지 상태의 일괄 정리에 맞지 않고, '활성이면 HELD'만 쓰는 단계는 '거짓 `HELD` → AVAILABLE'을 빠뜨린다(A로 가면 거짓 `HELD`는 A의 배치가 다시 고르지 못한다 — 홀드 행이 없다).

### 설계-8 측정 설계 — 뼈대 (고정값 · 판정 규칙은 task 02)

#### 8.1 시나리오 후보

- 축: 방식 A·B·C·D × 이탈률 20%·50% × TTL 5분·30초 × 좌석 맵 조회(섞음 · 안 섞음). 칸 수 · 회차 수 · 부하 크기는 task 02.
- 이탈 = 선점 뒤 확정하지 않고 떠나는 세션 비율. 자기 좌석 재선점 = 이탈한 사용자 일부가 만료 뒤 같은 좌석을 다시 누르는 흐름(가설 2 — 재방문 비율·간격은 task 02).
- 잔여석 구간: 수요 > 좌석 · 매진 직전에 만료로 풀리는 좌석이 매진 시각을 정하는 구간(초안 가설의 '20~30분'은 TTL 5분 칸에서).
- TTL 30초 칸: 배치 주기 10초가 TTL의 1/3 — 배치 주기만큼 묶이는 효과를 키운다. 배치 주기는 A·C·D 같은 값(지금 10초).
- 좌석 맵: 세션마다 선점 전 n회 조회 + 실패 뒤 다시 조회(n · 비율은 task 02). 조회만 하는 부하 칸도 후보.
- 지금 하네스가 이탈을 만드는 방식과 이번 부하 모델은 §측정-0 · §측정-3.1이 정한다(task 02).

#### 8.2 지표 후보

| 묶음 | 후보 | 출처 후보 |
|---|---|---|
| 판매 | 매진 시각 · 잔여석 구간 길이 · 확정 수 | k6 · DB |
| 묶인 좌석 | **팔 수 있는데 묶인 좌석·초** — '홀드가 만료됐고 예약도 없는데 선점이 거절되는' 좌석 × 초. A = 저장 `HELD`이고 활성 홀드 없는 좌석 수의 1초 표본 합 · B·C·D = 정의상 0에 가깝고 남는 것은 잠금 경합 몫 | 1초 표본 질의(하네스 — 부하 영향 `[구현 검증]`) |
| 사용자 | 자기 좌석 재선점 실패율(`SEAT_NOT_AVAILABLE` · `HOLD_LIMIT_EXCEEDED` 나눠서) · 만료 뒤 확정 응답 분포(404 대 409 `HOLD_EXPIRED`) | k6 |
| 경합 | 409 중 NOWAIT 패배(55P03) 비율 — 지금은 같은 응답이라 앱 지표 태그가 필요 · 40P01 수 · 배치 틱 실패 수(`seat.hold.expiry.tick{outcome}` — ExpireHoldsService.kt:54) · SKIP LOCKED로 건너뛴 좌석 수 · **회수 H4가 확정 K3의 홀드 행을 기다린 시간 · 건수**(그동안 같은 사용자의 쿼터 행 줄 — §3.1 · 선검증 DS14-4) · D4가 K3의 홀드 행을 기다린 시간(§3.4) · B 정리 ①이 SKIP LOCKED로 건너뛴 홀드 수 · 미룬 회차 수(§3.4 · 선검증 DS14-3) · C·D 배치의 MISSING 쌍 수 · `:missingIds` 불일치 롤백 수(§3.4 · 선검증 DS14-5) | 앱 지표 |
| 비용 | 선점 · 확정 · 좌석 맵 p99(상위 1% 응답 시간) · DB CPU · 선점당 문장 수 · 회수 비율·건수 · 배치 틱 시간·행 수 · WAL(로그 선행 기록) 양 | 앱 · `pg_stat_*` |
| 시각 | 판정 문장의 `clock_timestamp() − now()` 분포(가정 1) | 스모크 |
| 정합 | 카운터 대조 출처별 기록(repaired · false-positive — 기대 0) | `counter_drift_log` |

#### 8.3 record-level 끝 검사 — 방식별 기대값 (판정기를 방식별로 나눈다 — 하네스는 task 04)

| 항목 | A | B | C | D |
|---|---|---|---|---|
| 이중 판매: 좌석당 (활성 홀드 + 확정) ≥ 2 · 확정 ≥ 2 | 0 | 0 | 0 | 0 |
| cnt = 홀드 행 + 확정(`v_counter_mismatch`) | 0 | 0 | 0 | 0 |
| 홀드 행 + 확정 > 한도(`v_over_limit_users`) | 0 | 0 | 0 | 0 |
| 좌석당 홀드 **행** > 1(지금 `v_duplicate_hold_seats`) | 0 | > 0 가능 → 방식별 정의는 '활성 홀드 > 1'(기대 0) | 같음 | 같음 |
| 만료됐는데 `HELD` 저장 | 마지막 틱 뒤 만료분만 | 늘어난다(재선점되지 않은 이탈 좌석) | 배치 주기분 + SKIP LOCKED·H4b 건너뜀분 | 0(`HELD`를 쓰지 않음 — 옛 데이터 `HELD`는 따로 센다) |
| `HELD`인데 홀드 행 없음(`v_held_without_hold`) | 0 | > 0 가능(회수가 행만 지움) | H4b 건너뜀분 | 0 |
| **활성** 홀드가 있는데 `HELD` 아님 | 0 | 0 | 0(되돌리기는 활성 없을 때만) | 항목 제외(정의상 활성 홀드 좌석은 `AVAILABLE` 저장) |
| 수렴 뒤(TTL + 배치 주기 + 유예 대기 뒤) 남은 만료 홀드 행 | 0 | > 0 | 0 | 0 |

- 기존 `v_*` 항목의 정의는 바꾸지 않는다 — 방식별 기대값은 **새 키**로 판정한다(코드 리뷰 CR14-3 — 초안의 '`v_hold_on_unheld_seat`을 활성 홀드로 정의를 바꾼다'를 구현과 맞춘다). 새 키(판정기 `expiryStates` — LoadtestDataService.kt:221-234 · `v_` 접두어 없음): `double_sold_seats`(1행) · `multi_active_seats`(4행) · `held_no_active`(5행) · `active_not_held`(7행 · D 제외) · `expired_hold_rows`(8행) · `active_on_reserved_seats`(활성 홀드 + `RESERVED` — 모든 방식 0) · `expiry_mode`. ADR-014 요약기는 위 표의 방식별 기대값을 이 새 키로 대조한다. 홀드 행 · 저장 `HELD`를 A 전제로 세는 `v_*`(`v_duplicate_hold_seats` · `v_excess_hold_rows` · `v_held_without_hold` · `v_hold_on_unheld_seat` · `v_stale_held_seats`)는 B·C·D에서 0보다 클 수 있다(예: `v_hold_on_unheld_seat`은 남의 만료 홀드가 남은 채 확정된 좌석에서도 걸린다) — B·C·D 판정에 쓰지 않고 기록만 한다. 2·3행(`v_counter_mismatch` · `v_over_limit_users`)은 지금 정의 그대로 모든 방식에서 기대 0이다. 기대값과 다르면 회차 판정 자료로 남긴다(무음 금지).

### 설계-9 새 동작 불변식 (설계 선검증 대상 — 코드베이스에 없던 것만)

| # | 불변식 | 강제 위치 | 검증 위치 |
|---|---|---|---|
| N1 | **B·C·D에서 만료 판정·기록은 DB `now()` 하나** — 앱 Clock이 바뀌어도 B·C·D의 선점·확정·조회·배치·회수 결과가 같다 | §2.1 표의 #1~#6 문장 | blind 테스트(MutableClock을 옮겨도 판정 불변 · 만료 상태는 DB 기준 상대 시각 `now() - interval`로 행을 직접 써서 만든다 — Q10) |
| N2 | **좌석당 활성 홀드 ≤ 1 · 활성 홀드와 확정은 한 좌석에 공존하지 않는다**(B·C·D) | 홀드를 만들거나 RESERVED로 바꾸는 경로는 좌석 행 잠금 먼저(H7 · K2) + 잠근 뒤 새 문장 판정(H8 · K3) | 동시성 테스트: 같은 좌석 동시 선점 · **시각 역전**(만료 직전 시작한 확정이 만료 뒤 재선점 커밋 뒤에 진행) · **시각 역전 + 남의 확정**(X 확정 시작 → Y 재선점·확정 커밋 → X가 K2에서 RESERVED를 받아 409 SEAT_NOT_HELD — §3.2 판정 순서 · 선검증 DS14-1) · D에서 잠금·판정 한 문장이면 실패함을 보이는 대조 테스트(§2.3) · 판정기 1번째 행 |
| N3 | **회수는 원자적이다** — 회수 삭제와 cnt 감소는 같은 트랜잭션이고, 선점이 실패하면 함께 롤백된다 | H3~H10이 한 트랜잭션 | 회수 뒤 NOWAIT 패배 · 매수 초과 · 좌석 불가를 일으켜 홀드 행 · cnt 무변경 |
| N4 | **매수 판정은 회수 뒤** — 자기 만료 홀드 때문에 매수 초과가 나지 않는다(그 트랜잭션 `now()` 기준) | H2~H5가 H6 앞 | max-per-user 2 · 만료 홀드 2개인 사용자의 선점이 통과 |
| N5 | **한 홀드 행의 삭제는 cnt를 정확히 한 번 내린다**(회수 · 배치 · 동시) | 쌍 잠금(규칙 ①) + `DELETE … RETURNING` 행 수만큼(규칙 ②) | 회수 · 배치 동시 테스트 → `v_counter_mismatch` 0 · 대조 오탐 0 |
| N6 | **배치는 활성 홀드가 있는 좌석의 상태를 바꾸지 않는다**(C) · **D 배치는 좌석 행을 쓰지 않는다** | C2 SKIP LOCKED 잠금 + C6 새 문장 · H4b 같음 / D 배치에 좌석 문장 없음 | 동시성 테스트(배치 틱 중 재선점) · 판정기 '활성 홀드 있는데 HELD 아님' 0 |
| N7 | **기본 조합 밖에서 `expiry-mode ≠ sweep`이면 기동을 거부하고 스키마를 바꾸지 않는다** | `checkConfig`(마이그레이션 앞) | 기동 테스트(조합표) — 기존 '거부된 기동은 스키마를 바꾸지 않는다' 불변식 안에서 |
| N8 | **만료 뒤 확정은 같은 규칙으로 거부한다**(B·C·D) — K3 `expires_at > now()`가 아니면 409, 행이 이미 지워졌으면 404 | K3 | blind 테스트 |

- 코드베이스에 이미 있는 불변식(다시 세우지 않는다): 같은 쌍 복구의 쿼터 행 직렬화(`PairRepair` — ADR-012 §3-2) · 거부된 기동은 스키마를 바꾸지 않는다(HoldStrategyMigrationGate — HoldStrategyStartupCheck.kt 머리 주석) · 손잡이를 주지 않으면 이전과 같다(명세 §2 '기존 동작 보존' — A 경로 코드 무변경 · 기존 테스트 green) · 대조 실제 값 정의(§2.5 — 무변경).

### 설계-10 `[구현 검증]` 목록

> 중앙 대장: 이 표 + §측정-11의 #15~#25(task 02에서 더함 — 같은 대장의 연속 번호로 본다 · #22~#25는 task 02 리뷰 MR 반영).

1. 부하 중 선점 · 확정 판정 문장의 `clock_timestamp() − now()` 분포(가정 1 — 착수 직후 스모크).
2. RC의 `FOR UPDATE` + 같은 문장 하위 질의가 막 커밋된 홀드를 못 보는 것(§2.3)을 테스트로 재현 — H8 · K3 · C6을 새 문장으로 나눈 근거. **이 테스트가 green이 된 뒤 D 후보 (가)를 확정한다**(§설계-4 · Q5 — 선검증 DS14-OQ2).
3. 선점당 문장 수 · p99 증가(H1 하위 질의 · H2 · H8 · 회수 H3~H5).
4. 좌석 맵 세 문장의 실행 계획(`EXPLAIN (ANALYZE, BUFFERS)`) — 회차 1만 석 · V11 · D의 좌석마다 홀드 탐색 비용.
5. V11 `CREATE INDEX` 시간 · 쓰기 차단 길이(시드 크기 사본 — §설계-7).
6. Flyway: V11을 기본 위치에 둘 때 K2 DB의 검증 실패 여부(outOfOrder 기본값) — 선택 위치를 고른 근거 확인.
7. C 배치 청크 크기 `k` · 틱 시간 · SKIP LOCKED 건너뜀 수 · 배치가 기다리게 한 선점 꼬리 · 틱당 청크 상한 기본 100(`seat.hold.expiry-max-chunks-per-tick` — 상한에 닿은 틱 수 `seat.hold.expiry.chunk_cap_reached` · 코드 리뷰 CR14-2).
8. A의 '늦은 쪽 틱 롤백'(Hibernate 삭제 행 수 검사 — §설계-1 추정).
9. B·C·D 경로를 JDBC로 쓸 때 같은 트랜잭션의 JPA 엔티티 상태와 섞이지 않는지(§3.1).
10. 묶인 좌석·초 1초 표본 질의의 DB 부하 — 방식마다 다르다(B의 만료 행 누적 · 측정-5 머리의 사전 기준 · 측정-11 #16 — 리뷰 MR-1).
11. .164 앱 Clock과 DB `now()`의 차.
12. 교착 점검(§3.2 · §3.4 '고리 없음' 추정)을 동시성 테스트로 — 40P01 0. 넣을 간선: 확정 K3 ↔ 회수 H4(같은 사용자 · §3.1 — 선검증 DS14-4) · 확정 K3·K7 ↔ B 정리 ①(마감 회차 확정 중 정리 실행 — SKIP LOCKED가 없으면 40P01이 나는 대조 테스트 포함 · 선검증 DS14-3) · 확정 K3 ↔ D 배치 D4 · C·D 배치 MISSING 복구(C3b · D3)의 INSERT ↔ 같은 쌍 선점 H6 upsert(선검증 DS14-5).
13. C·D 배치 진행형 커서(C1 · D1)의 실행 계획 — `expires_at <= now()` 범위 + `seat_id > :after` · `(schedule_id, user_id) > (…)` 정렬에서 V2 `idx_seat_hold_expires_at`가 어떻게 쓰이는지(선검증 DS14-5).
14. 방식 전환 정리 T1·T2(§설계-7)의 시드 크기 사본 소요 시간 · 확인 3항목 0(선검증 DS14-2).

### 설계-11 열린 질문 (메인 · 사용자가 정할 것 — 질문마다 권장안 1개)

> **결정(2026-10-26)**: Q1·Q2·Q3·Q11·Q12 = 사용자가 권장안 채택 · Q4~Q10·Q13·Q14 = 메인이 권장안 채택(Q5는 설계 선검증으로 확정). 아래 '권장' 칸이 곧 결정이다.
> **설계 선검증 반영(2026-10-26)**: Q5 = 확정 시점을 '#2 재현 테스트 green 뒤'로 미룸(DS14-OQ2) · Q8 = 기본 위치 목록에 넣지 않음으로 바꿈(DS14-OQ1 — 메인 결정) · Q12 = 전환 ①을 모든 방식 사이로 넓히고 정리를 좌석 기준 재계산 하나로 바꿈(DS14-2 — 명세 §10 R4는 'A ↔ B·C·D'만 적고 있어 동기화가 필요하다).

| # | 질문 | 권장안 | 누가 |
|---|---|---|---|
| Q1 | A도 DB `now()`로 바꾸나(명세 §2 단일 규칙 대 '기존 동작 · 기존 테스트 결과 그대로') | **A는 그대로(앱 Clock)** — 단일 규칙은 B·C·D 경로에. 명세 §2에 예외 1줄 재합의(기존 테스트가 고정 시각 `MutableClock`으로 만료를 만든다 — §2.1). A를 기본값으로 남길 경우의 시각 이관은 NEXT 후보 | 사용자 |
| Q2 | '카운터 = 활성 홀드 수'(명세 §2)의 뜻 | **대조 기준은 지금 정의(홀드 행 수 + 확정 · 만료 무관) 그대로 · 매수 판정은 회수 뒤 cnt**(§2.5) — reconcile-core 무변경 | 사용자 |
| Q3 | B·C·D 확정의 최소 가드(좌석 `FOR UPDATE` + '내 홀드 활성 · 다른 활성 홀드 없음')가 ADR-015 범위와 겹친다 | **넣는다** — B·C·D '이중 판매 0'의 필요조건이다(§3.2). A 확정 · 원자성 일반 해법은 ADR-015로 남긴다 | 사용자 |
| Q4 | 재선점 때 남의 만료 홀드 행을 지우나 | **남긴다**(새 행 추가) — 지우면 남의 쿼터 행을 잡아야 해 교착 고리가 생긴다. 남은 행은 그 사용자의 회수 · 배치 · 정리 ① 몫 | 메인 |
| Q5 | D의 '좌석당 활성 홀드 1개' 강제 | **(가) 좌석 행 잠금 + 새 행 추가**(§설계-4) — 권장 유지. **확정은 §설계-10 #2 재현 테스트가 green이 된 뒤** `[구현 검증]`(선검증 DS14-OQ2) | 메인(#2 green 뒤 확정) |
| Q6 | C의 회수가 회수한 다른 좌석의 거짓 `HELD`도 고치나(H4b) | **고친다**(SKIP LOCKED · 대상 좌석 제외) — 안 고치면 C 배치는 행이 없어진 좌석을 다시 못 찾는다(후보 = 만료 홀드 행). 대안: `product_seat (id) WHERE status = 'HELD'` 부분 인덱스로 배치 후보를 하나 더 두기 | 메인 |
| Q7 | B의 마감 회차 정리 ① | **활성 홀드가 없으면 만료 홀드 행 + 쿼터 행을 같은 트랜잭션에서 지운다**(B만 · 감소 없음 · 지운 수는 로그·지표). 홀드 삭제는 `FOR UPDATE SKIP LOCKED`로 고른 행만 · 건너뛴 행이 남으면 쿼터 행 삭제는 다음 실행으로(§3.4 B1~B3 — 확정 K3와의 교착 방지 · 선검증 DS14-3) | 메인 |
| Q8 | V11(좌석 맵 인덱스)의 위치 · 적용 범위 | **새 선택 위치 `db/migration-seat-map` · 기본 위치 목록(application.yml:16)에는 넣지 않는다 · 측정 때 모든 방식 셀에 같게 켠다 · 기본 위치 편입은 기본값 교체 때 함께**(§설계-7 — 명세 §2 '기존 동작 보존': 기본 sweep 배포에 스키마 변경을 붙이지 않는다) (선검증 DS14-OQ1 — 초안 '기본 목록에 더한다'를 바꿈) | 메인 |
| Q9 | 손잡이 이름 · 값 | **`seat.hold.expiry-mode` = sweep · lazy · lazy-sweep · derived**(기본 sweep) · 청크 `seat.hold.expiry-batch-size` | 메인 |
| Q10 | B·C·D 테스트에서 시각을 다루는 법 | **운영 SQL에 시각 바꾸기 훅을 두지 않는다** — 만료 상태는 DB 기준 상대 시각(`now() - interval`)으로 행을 직접 써서 만들고, 경계는 짧은 TTL로 본다(counter-trigger의 `seatlab.counter_now` 같은 세션 설정을 따라 하지 않는다) | 메인 |
| Q11 | 좌석 맵 응답 계약 | **`GET /api/schedules/{id}/seats` → `[{seatId, section, rowNo, seatNo, status}]` · 페이지 없음(회차당 1만 석) · 보이는 상태만**(§3.3) | 사용자 |
| Q12 | 방식 전환 · 되돌리기 절차(혼재 위험 — §설계-7) | **방식 사이 전환은 모두 ①(멈추고 바꾸기)만**(A ↔ B·C·D · B·C ↔ D · B ↔ C) · **정리 = 좌석 기준 재계산 하나**(T1 남은 만료 홀드 삭제 + 감소 → T2 `status <> 'RESERVED'` 좌석을 활성 홀드 유무로 `HELD`/`AVAILABLE`) · 확인 `v_held_without_hold = 0`(A로 갈 때) · `v_duplicate_hold_seats = 0` · 카운터 대조 불일치 0 · 혼재 감지 장치는 NEXT 후보 (선검증 DS14-2 — 초안 'A ↔ B·C·D만 · 방향별 SQL'을 넓힘. 사용자 채택안(R4)보다 엄격한 쪽으로의 변경) | 사용자(R4) · 넓힘은 선검증 |
| Q13 | 측정 주입(`inject-app`)과 `expiry-mode ≠ sweep` | **기동 거부** — 이번 측정에 주입이 필요한지는 task 02가 정한다 | 메인 |
| Q14 | 회수가 지운 홀드의 늦은 확정이 409 `HOLD_EXPIRED`가 아니라 404 `HOLD_NOT_FOUND`가 되는 비율이 늘어난다 | **계약 그대로**(지금도 배치 뒤 확정은 404) · 분포만 지표로(§8.2) | 메인 |

## 측정 전 고정 규칙

> **초안**(task 02 — 2026-10-26 · 듀얼 리뷰 전). 캠페인 첫 회차를 시작한 뒤에는 이 절을 고치지 않는다. 측정 뒤 다르게 읽어야 하면 원문을 두고 '측정 뒤 재해석' 행을 따로 단다(ADR-013 §5 머리말 · ADR-012 §8.4 선례). 정답 기준 = 명세 §0 Q/A · §2 불변식 · §5 검증 방법 · §10 재합의 · §설계-8(뼈대) · §설계-9(N1~N8).
> 실파일로 확인한 사실에는 경로(`파일:줄`)를 단다. 과거 측정값에는 원자료 경로를 단다. 코드·측정으로 확인하지 못한 값은 **(추정)**, 구현에 닿아야 정할 수 있는 값은 `[구현 검증]`(측정-11에 모음)으로 적는다.
> 값 = 같은 (방식, 셀) 유효 3회의 [최소–최대]. **범위 비겹침으로 낮다** = 한쪽 최대 < 다른 쪽 최소(같은 값이면 겹침 — ADR-013 §5 머리말과 같은 정의).
> 이 절의 번호: 가설 **P1~P13** · 지표 **M1~M14** · 탐침 **PR-1~PR-3** · 교체 관문 **G1~G4** · 열린 질문 **MQ1~MQ10**. §설계의 H·K(선점·확정 문장) · Q(설계 열린 질문) · 명세 §10의 R(재합의)과 섞이지 않게 이름을 나눴다.

### 측정-0 용어 (이 절에서 새로 쓰는 것)

| 기호 | 뜻 |
|---|---|
| 주 회차 · 탐침 회차 | 시드 회차 1(좌석 1만 석 — 주 부하) · 회차 2(좌석 1만 석 — 탐침 PR-1~3 전용. 주 부하는 건드리지 않는다). 시드 = `/internal/reset`(회차 2개 × 1만 석 — LoadtestDataService.kt:40-84) |
| t0 · D_arr | t0 = 주 k6 첫 요청 시각. D_arr = 도착 창 길이(셀마다 고정 — 측정-2). **판정 창** = [t0, t0 + D_arr) |
| 세션 | k6 반복 하나 = 사용자 한 명의 '입장 → 좌석 고르기 → 선점 → 이탈 또는 확정'(S3 흐름 — k6/ADR-009/scenarios/s3-full-flow.js:5) |
| 맹목 흐름 · 지도 흐름 | 맹목 = 좌석 맵을 보지 않고 무작위로 고르는 세션(지금 S3 그대로) · 지도 = 좌석 맵(GET — §3.3)을 읽고 보이는 `AVAILABLE` 중에서 고르는 세션(새로 만든다) |
| 이탈 · 재방문 | 이탈 = 선점 201 뒤 확정하지 않고 세션을 끝냄(홀드는 TTL(홀드 유효 시간)까지 남는다 — s3-full-flow.js:71) · 재방문 = 이탈한 사용자가 자기 홀드 만료 뒤 같은 좌석을 다시 누름(가설 2) |
| 상태 표본 | 판정 창 동안 1초마다 주 회차 좌석 상태를 세는 SQL 한 문장(측정-5 머리) |
| 묶인 좌석·초 W | '규칙상 비어 있는데(활성 홀드 없음 · RESERVED 아님) 저장 상태 때문에 선점이 거절되는' 좌석 수의 시간 적분(M1) |
| T_x | 주 회차 RESERVED 좌석이 처음 x%(1만 석 기준) 이상이 된 시각 − t0(초). **T95**가 판정 지표(M2) |
| ≫(실질적으로 높다) | X ≫ Y ⇔ X가 Y보다 범위 비겹침으로 높다 **그리고** X 중앙값 > Y 중앙값 × 1.10(측정-8) |
| 판정 409 · 배치 잠금 409 | 탐침 PR-1·PR-2의 409를 원인으로 나눈 것(리뷰 MR-5). **판정 409** = 만료·매수 규칙상 거부(저장 `HELD` · 활성 홀드 있음 · 매수 초과 — `assertHoldable`·H6·H8 갈래). **배치 잠금 409** = 같은 순간 C 배치(C2 `FOR UPDATE SKIP LOCKED`)가 그 좌석 행을 쥐고 있어 선점 H7 `FOR UPDATE NOWAIT`가 55P03으로 진 것(DbLockHoldStrategies.kt:55-57 — 지금은 둘 다 같은 409 `SEAT_NOT_AVAILABLE` · ApiExceptionHandler.kt:16-18·23). 가르는 방법은 측정-5 M3 |
| API 계약 양성 대조 | 탐침 PR-3의 이름(리뷰 MR-8) — '만료 전 보낸 확정은 성공 · 만료 뒤 보낸 확정은 거부'라는 API 계약이 살아 있음을 보이는 대조. 시각 역전 경합(만료 전 시작한 확정 대 만료 뒤 재선점)은 재현하지 않는다(측정-3.2) |
| p99 | 상위 1% 응답 시간(99번째 백분위) |
| PRNG | 의사 난수 생성기 — 씨앗이 같으면 같은 난수 열을 낸다(측정-3.5) |

### 측정-1 측정 조건 (모든 셀 공통 · 고정)

- **서버 .164 하나 · 앱 1대 · lb(로드 밸런서 — nginx) 없음**: k6 PC → 앱 8101 직접(ADR-009 하네스 기본 — k6/ADR-009/scenarios/lib/common.js:7). lb가 필요 없는 이유: 이 측정에는 앱 혼재·롤링이 없다(방식 전환은 ①만 — Q12). A의 가설 3 '앱 2대 배치 겹침'(§설계-5)은 이번에 재지 않는다(측정-10-1). 그래서 ADR-013 §5.8-3b의 lb 아티팩트 · nginx 수동 장애 판정 규칙은 적용 대상이 없다.
- **자원**: L4(앱·DB CPU 4개) · DB tmpfs(DB를 메모리에 둔 측정 조건) 12g(DB 메모리 14g) · 앱 메모리 2g · 세대형 ZGC(가비지 컬렉터) — ADR-010 결정 · ADR-013 측정 조건과 같다(k6/ADR-013/README.md H10 행).
- **카운터 구성 = 업무 앱 기본값 그대로**(application.yml:49-61 — A(만료 배치 쌍 단위 견딤) 켬 · 증분 대조 ⓑ 트리거 1초 · 안전망 · split 탐지 · C 끔 · 정리 ① schedule). 하네스는 카운터 키를 넣지 않고 configprops로 이 기대값을 대조한다(`cond-config-mismatch`). ADR-009 `run.sh --cond default`는 ADR-012 기본값 교체 뒤 SHA를 계획 단계에서 거부하므로(k6/ADR-009/README.md 조건 표 `default` 행) 그대로 쓰지 않는다 — ADR-014 하네스가 조건을 따로 렌더한다(측정-3.6).
- **방식 축**: `seat.hold.expiry-mode` ∈ `sweep`(A) · `lazy`(B) · `lazy-sweep`(C) · `derived`(D)(Q9). 회차마다 configprops로 대조(`expiry-mode-mismatch`).
- **TTL 축**: `HOLD_TTL` ∈ {5m, 30s} · **`EXPIRY_INTERVAL` 10s는 모든 셀 고정**(application.yml:36). ADR-009의 `scaled` 설정(TTL 30s · 배치 1s — k6/ADR-009/scripts/run.sh:482)은 쓰지 않는다. 배치 주기를 TTL과 함께 줄이면 TTL 30초 칸이 키우려던 '배치 주기만큼 묶임'이 사라진다(§설계-8.1).
- **Flyway 위치**: 기본 위치 + `classpath:db/migration-seat-map`(V11 — 좌석 맵 인덱스)를 **모든 셀**(A 포함)에 같게 둔다(§설계-7 · Q8). 회차마다 인덱스 존재를 확인한다(`seat-map-index-missing`).
- **주입 없음**: S3e SQL 주입 · 앱 경로 주입(`inject-app`) 모두 끈다. B·C·D와 `inject-app`은 기동 거부다(Q13).
- **마감 없음**: 시드 회차 `starts_at` = 2026-12-31 19:00+09(LoadtestDataService.kt:72)라 측정 중에는 판매 마감이 없다. run.sh는 2026-12-31T09:00Z부터 회차를 거부한다(k6/ADR-009/scripts/run.sh:186) — 캠페인은 그 전에 끝낸다.
- **k6**: ADR-012·013 캠페인과 같은 바이너리(plan.json에 version·sha256 고정 — ADR-011 손잡이). **`--probes` 켬**(ADR-011 서버·PC 측정기 — 50/s `GET /actuator`, DB를 거치지 않는다 — k6/ADR-009/README.md ADR-011 절). 쓰임은 환경 정지 분류(측정-9)다.
- **이미지 고정**: 측정 SHA의 앱 이미지 하나를 plan.json에 고정한다(ADR-013 DS-11 선례). 네 방식은 같은 이미지에서 손잡이만 다르다.
- **측정 전 재해석(2026-10-30 · 확인 스모크 — 명세 R5 ①) — 앱 연결 상한 · 파일 기술자 한도**: 측정 compose(`k6/ADR-014/compose.expiry.yml`)에서만 앱 `server.tomcat.max-connections`를 **20,000**으로, 앱 컨테이너 nofile을 **65,536**(soft = hard)으로 둔다 — 네 방식 · 모든 셀(비용 셀 포함) 같게. 업무 앱 설정(`application*.yml`)은 그대로라 앱 기본값은 Tomcat 기본 8,192다. 다른 Tomcat 키(accept-count · 스레드 · keep-alive)는 바꾸지 않는다. 회차마다 configprops `server.tomcat.maxConnections` = 20,000과 앱 PID 1의 nofile soft·hard ≥ 21,000(연결 + 1,000)을 대조하고, 다르거나 없으면 `cond-config-mismatch`(하네스 — 무효 → 재측정 · 원문 사유 그대로)다. **근거**: 확인 스모크(`k6/ADR-014/results/confirm14-20261030`) a50-t300 D에서 동시 VU 최대 9,254가 VU마다 keep-alive 연결을 쥐어 기본 상한을 넘었다(그 회차 `app-config.json` maxConnections 8192 · k6 첫 30초 새 연결 8,153 직후부터 선점 응답 없음 · 서버 쪽 측정기도 200 아닌 응답 11,714 → `app-metric-missing` 등으로 무효). TTL 30초 칸(VU 약 4,700)은 상한 밑이라 그 칸의 기존 스모크 결과와 조건이 달라지는 것은 상한 값뿐이다. 상한은 연결을 받는 한도라 그 밑에서 도는 부하의 처리 경로는 바꾸지 않는다(추정). 서버 .164의 `somaxconn` · `ip_local_port_range` · 데몬 nofile 기본값은 확인하지 않았다(다음 스모크 전 확인).

### 측정-2 셀 설계 — 셀 수 · 반복 · 길이 · 시간

| 묶음 | 셀 | 방식 | 이탈률 a | TTL | 생각 시간 | 도착 창 D_arr | 셀 수 |
|---|---|---|---|---|---|---|---|
| 주 셀 | `X-a20-t300` · `X-a50-t300` · `X-a20-t30` · `X-a50-t30` | A · B · C · D | 0.2 · 0.5 | 300초 · 30초 | 30–240초(TTL 5분) · 3–24초(TTL 30초) | t300: a20 1,200초 · a50 **2,220초**(리뷰 MR-13 — 초안 1,800초) / t30: a20 240초 · a50 360초 | 4 × 2 × 2 = **16** |
| 비용 셀 | `X-S4c4200` | A · B · C · D | — | 60분(S4c 그대로 — 측정 중 만료 없음) | — | S4c 그대로(램프 60초 + 12구간 × 30초) | **4** |

- **회차 수 3**: ADR-010~013과 같다. '범위 비겹침' 판정의 최소 단위다. 4회면 캠페인이 약 7.1시간 늘어난다(아래 표 ÷ 3 — 리뷰 MR-13의 a50-t300 D_arr 반영) — 늘리지 않는다.
- **합계 = 20셀 × 3회 = 60회**.
- **좌석 맵 조회는 축이 아니다**: 주 셀은 모두 지도 흐름을 섞는다(측정-3.4 — 명세 §0 '조회 부하 포함'). 지도가 없는 비교는 비용 셀(S4c — 지도 없음)이 맡는다. 비용 셀은 선점 경로의 고정 비용(H1 하위 질의 · H2 · H8)만 떼어 본다. S4c는 요청마다 새 사용자·새 좌석이라(k6/ADR-009/scenarios/s4-throughput.js:57-62) 회수 H3~H5와 배치 일이 생기지 않는다.
- **비용 셀이 필요한 이유**: 주 셀의 선점 201은 많아야 초당 수백 건이다(좌석 1만 석이 약 40초에 다 잡힘 — k6/ADR-009/README.md 주입 절). 선점 경로 문장 증가의 비용은 처리량 벽 근처(S4c(초당 4,200 선점 고부하) — ADR-007)에서 드러난다. 기준값 비교가 ADR-009·012의 같은 셀과 이어진다(MQ1).
- **D_arr 근거(라운드 모형 — 추정)**: 첫 약 40초에 만석이 되면, 그 뒤 빈 좌석은 이탈 홀드의 만료로만 생긴다. 만료로 풀린 좌석은 곧 다시 잡히고, 그중 a가 다시 이탈한다. 그래서 라운드 k(만석 뒤 k번째 만료 물결 · 시각 ≈ k × TTL)가 지나면 판매율 ≈ 1 − a^(k+1)이다. A는 라운드마다 배치 지연(주기 10초의 위상 — 평균 약 5초 + 틱 시간 · 추정)이 더해진다.

| 셀 | T95 라운드 | T95(B·C·D) 추정 | A 지연 몫 추정 | T99 추정 | D_arr | 여유(T95 대비) |
|---|---|---|---|---|---|---|
| a20-t300 | 1 | 약 560초 | +5초(약 1%) | 약 880초 | 1,200초 | 약 640초 |
| a50-t300 | 4 | 약 1,330초 | +20초(약 1.5%) | 약 2,100초(창 끝 근처) | **2,220초**(리뷰 MR-13) | 약 890초 |
| a20-t30 | 1 | 약 55초 | +5초(약 9%) | 약 85초 | 240초 | 약 185초 |
| a50-t30 | 4 | 약 130초 | +20초(약 15%) | 약 200초 | 360초 | 약 230초 |

  TTL 5분 칸의 A 효과(약 1~2%)는 3회 범위 안에 묻힐 수 있다(추정 — P2가 그렇게 예측한다). TTL 30초 칸이 효과를 키운다. 스모크에서 T95가 D_arr의 60%를 넘으면 캠페인 시작 전에 한 번만 D_arr를 늘린다(측정-9 시작 조건 ⑥).
  - **늘리는 양(리뷰 MR-13 — 식 고정)**: D_arr′ = ⌈T95_추정 ÷ 0.6 ÷ 60⌉ × 60초(분 단위 올림). T95_추정 = 스모크로 잰 셀(a50-t30)은 그 실측 T95 중 네 방식 최대 · 스모크하지 않은 셀(a20-t30 · a20-t300 · a50-t300)은 a50-t30 실측 라운드 길이로 외삽한 값(측정-11 #21) 중 네 방식 최대. 줄이지 않는다(D_arr′ < D_arr이면 그대로). 60% = 창 끝 앞 40% 여유로 T95가 창 밖(+∞)으로 가는 일을 막는 경계(설계 — 근거 없는 보수적 선택).
  - **표 자체 점검(리뷰 MR-13)**: 초안 a50-t300 D_arr 1,800초는 이 표의 T95 추정 1,330초 기준으로 이미 74%라 ⑥에 걸렸다. 위 식으로 ⌈1,330 ÷ 0.6 ÷ 60⌉ × 60 = 2,220초로 고쳐 두었다(T99 추정 2,100초도 창 안으로 든다). 나머지 셋은 a20-t300 47% · a20-t30 23% · a50-t30 36%로 걸리지 않는다. 스모크 뒤 ⑥은 이 고친 값에서 다시 본다.
  - **⑥ 적용(2026-10-29 · 정식 스모크 k6/ADR-014/results/smoke14-20261029 — 시작 전 1번)**: a50-t30 실측 T95 = A 216.20 · B 197.33 · C 195.57 · D 197.31초 → 네 방식 최대 216.20초 = D_arr 360초의 60.1% → D_arr′ = ⌈216.20 ÷ 0.6 ÷ 60⌉ × 60 = **420초**(`plan14.py --darr-extend` · plan14 `MAIN` 기본값에 반영). **스모크하지 않은 셀의 외삽**(위 식 그대로 · 측정-11 #21): 이 규칙은 스모크 셀만이 아니라 네 셀 모두를 정한다 — 외삽 방법(수식)은 원문에 없어 라운드 모형(T95 ≈ T_fill + k × L · a50의 T95 = 라운드 4 · a20 = 라운드 1)에 실측을 넣었다: **라운드 길이 L = 실측 T95 − T90**(라운드 4와 3의 차 — A 38.0 · B 33.0 · C 32.0 · D 34.0초) · T_fill = T95 − 4L(약 64~66초) · 다른 TTL에서 L = TTL + (L_실측 − 30)(만료 뒤 다시 잡히는 지연 · A 배치 위상은 TTL과 무관하다고 본다 — 추정). 네 방식 최대: a20-t30 102.2초(240초의 42.6%) · a20-t300 372.2초(1,200초의 31.0%) · a50-t300 1,296.2초(2,220초의 58.4%) → **셋 다 그대로**. 주의: L을 TTL에 비례해 늘리면(L = TTL × L_실측 ÷ 30) a50-t300은 1,584.2초(71.4%)로 ⑥에 걸려 2,700초가 된다 — 외삽 방식에 따라 갈리는 셀이다(+480초 × 12회 ≈ +1.6시간). D_arr를 바꾸면 따라 바뀌는 값: a50-t30 탐침 칸 [10, 365) · 계획 수 PR-1·2·3 355 · M9 DB 행 문턱 ⌈420 ÷ 15⌉ = 28(요약기가 창 길이로 계산) · 회차 약 11.4분 · 캠페인 합계 약 21.5시간(추정 — `plan14.py --dry-run`).
- **회차 시간 추정**: 회차 = 기동·예열·시드·시작 대기(87초 — k6/ADR-009/README.md '시작 상태') + 주 k6(D_arr + gracefulStop) + 수렴 대기(TTL + 20 + 2초 — run.sh:861) + 끝 판정·수집·내림. '기동~수집'의 수렴 대기 밖 몫은 과거 S3-a20 tmpfs L4 회차에서 150~181초다(전체 1,381–1,397초 − k6 894–909초 − 수렴 대기 322초 — `k6/ADR-009/results/*/*/L4/S3-a20/rep*/meta.json` 6회의 `started`·`ended`·`k6_started`·`k6_ended`). 아래는 181초로 센다(추정).

| 셀 | 주 k6 | 수렴 대기 | 그 밖 | 회차(추정) | × 12회(4방식 × 3) |
|---|---|---|---|---|---|
| a20-t300 | 1,230초 | 322초 | 181초 | 약 29분 | 약 5.8시간 |
| a50-t300 | 2,250초(리뷰 MR-13 — 초안 1,830초) | 322초 | 181초 | 약 46분 | 약 9.2시간 |
| a20-t30 | 250초 | 52초 | 181초 | 약 8분 | 약 1.6시간 |
| a50-t30 | 370초 | 52초 | 181초 | 약 10분 | 약 2.0시간 |
| S4c4200 | 약 430초 | — | — | 약 13분(과거 640–768초 — `k6/ADR-009/results/*/*/L4/S4c4200/rep*/meta.json` tmpfs · 12구간 48회) | 약 2.6시간 |
| **합계** | | | | | **약 21.2시간(추정 — 리뷰 MR-13 · 초안 19.8시간)** + 재측정 · 스모크 |

- **순서**: 회차 우선(회차 1의 20셀 → 회차 2 → 회차 3). 회차 안 셀 묶음 순서 = a20-t30 → a50-t30 → a20-t300 → a50-t300 → S4c. 묶음 g · 회차 r의 방식 순서 = ABCD를 (r + g) mod 4칸 회전한다(ADR-009 '균형 회전'과 같은 뜻 — 15묶음에서 방식마다 각 자리에 3~4번).
- 회차마다 DB 볼륨·컨테이너를 새로 띄운다(ADR-009 하네스 그대로 — k6/ADR-009/README.md '시작 상태'). 회차 사이에 상태가 넘어가지 않는다.

### 측정-3 부하 모델

#### 3.1 주 회차 흐름 (회차 1 · 1만 석)

- **맹목 흐름(S3 그대로)**: 고정 도착률 300세션/초 · D_arr 동안. 세션 = 핫스팟 무작위 좌석(앞 20% 좌석에 요청 70%) → 선점 → 409면 다른 좌석으로 최대 3번 더 → 201이면 이탈 판정(확률 a) → 이탈이 아니면 생각 시간 뒤 확정(k6/ADR-009/scenarios/s3-full-flow.js:12-21·55-77). 만석 뒤에도 같은 속도로 들어온다 — 수요 > 공급을 창 끝까지 유지한다(3.3).
- **지도 흐름(새로 만든다)**: 고정 도착률 10세션/초 · D_arr 동안. 세션 = `GET /api/schedules/1/seats` → 보이는 `AVAILABLE` 중 하나를 고르게 고른다(핫스팟 가중 없음) → 선점 → 409면 지도를 한 번 더 읽고 다른 `AVAILABLE` → 선점 → 409면 끝(포기). 보이는 `AVAILABLE`이 0개면 '매진 관찰'로 끝. 201 뒤는 맹목 흐름과 같은 이탈·재방문·확정 규칙이다.
- **재방문(가설 2 관측 — 설명용)**: 이탈 세션의 비율 ρ = 0.2가 재방문한다. 재방문 = 서버 시각 '자기 만료 시각 e + δ'(δ ~ 고른 분포 [0, 20]초)에 **같은 좌석**을 한 번 선점한다. 201이면 생각 시간 뒤 확정하고, 409면 끝(기록). 같은 사용자 번호를 쓴다. 판정은 탐침 PR-1·PR-2가 맡는다(3.2) — 주 회차 재방문은 남이 그 좌석을 먼저 잡는 정당한 실패가 섞여 설명 지표다(M3).
- **생각 시간**: TTL 5분 = 30–240초(S3 그대로) · TTL 30초 = 3–24초(ADR-009 S3A 선례 — run.sh:513). 생각 최대 < TTL이라 정상 확정은 만료 전에 나간다. 만료를 넘나드는 확정은 PR-3만 만든다.
- **gracefulStop**: TTL 5분 30초 · TTL 30초 10초. 창 끝에 생각 중인 세션은 끊긴다(그 홀드는 수렴 대기 동안 만료된다). 판정 지표는 모두 판정 창 안에서만 센다.
- **사용자**: 세션마다 새 사용자(흐름별 번호 대역을 나눈다 — 맹목 300000+ · 지도 600000+ · 탐침 900000+ 등 · 정확한 대역은 task 04). 주 회차 세션은 홀드가 1개 이하라 매수 한도 2(application.yml:35)에 닿지 않는다.

#### 3.2 탐침 회차 (회차 2 · 1만 석 · 가설 2 · N4 · N8 API 계약 판정용 — 리뷰 MR-8: N2의 시각 역전 경합은 동시성 테스트 몫)

주 부하가 이 회차를 건드리지 않으므로, 탐침의 409는 방식의 판정만으로 생긴다(남이 먼저 잡은 정당한 실패가 없다). 배치·회수는 같은 테이블을 보므로 탐침 홀드도 주 회차와 같은 만료 처리를 받는다.

| 탐침 | 흐름 | 칸 | 기대(측정 전 예측) |
|---|---|---|---|
| **PR-1 자기 좌석** | 사용자 u가 좌석 s 선점(201 · 만료 e) → 확정하지 않음 → 서버 시각 e + δ에 s를 다시 선점 | δ ∈ {1, 3, 5, 8, 12, 20}초(차례대로 돌림) | A: 배치 전이면 저장 `HELD`로 409 `SEAT_NOT_AVAILABLE` · B·C·D: 201(H1이 자기 만료 홀드를 비어 있음으로 보고 H4가 회수 — §3.1) |
| **PR-2 한도** | u가 s1·s2 선점(cnt 2 = 한도) → 확정하지 않음 → 서버 시각 max(e1, e2) + δ에 새 좌석 s3 선점 | δ 같음 | A: 배치가 둘 다 지우기 전이면 409 `HOLD_LIMIT_EXCEEDED` · B·C·D: 201(회수 뒤 매수 판정 — N4) |
| **PR-3 API 계약 양성 대조**(리뷰 MR-8 — 초안 이름 '확정 경합') | u가 s 선점(e) → 경쟁자 v가 서버 시각 e + 0.3초에 s 선점(확정하지 않음) · u는 e + δc에 확정 | δc ∈ {−1.0, −0.3, +0.3, +1.0}초 | 'u 확정 200'과 'v 선점 201'이 둘 다 나오면 위반(API 수준 관측 — 나오면 P7 반증). δc > 0의 확정 200은 위반(N8 계약). δc < 0의 확정 200은 양성 대조(확정 경로가 산다). **주장 범위**: 요청을 보낸 시각 기준의 계약(만료 전 확정 성공 · 만료 뒤 확정 거부)만이다. 만료 전 확정(δc < 0)과 v 선점(e + 0.3초) 사이 간격이 0.6초 이상이라(δc = +0.3은 v와 같은 순간이지만 둘 다 만료 뒤 시작이라 시각 역전이 아니다) 트랜잭션이 겹치는 시각 역전 경합(만료 직전 시작한 확정이 만료 뒤 재선점 커밋 뒤에 진행 — §설계-9 N2)은 사실상 만들지 못한다(확정 처리 시간 ≪ 0.6초 — 추정). 그 경합의 이중 판매 0은 동시성 테스트(§설계-9 N2 · §설계-10 #12)가 보장하고, 그 green이 캠페인 시작 조건이다(측정-9 ⑦). 경합을 부하로 만들 측정 지연 손잡이는 넣지 않는다(Q13 주입 거부 유지 — 메인 결정) |

- **일정**: 시작 칸 = [t0 + 60초, t0 + D_arr − TTL − 25초)(TTL 30초는 t0 + 10초부터). 칸 안에서 고정 간격으로 시작한다 — TTL 5분: PR-1 0.25/초 · PR-2 0.25/초 · PR-3 0.2/초 / TTL 30초: 셋 다 1/초. 마지막 요청은 t0 + D_arr − 5초보다 앞이다(δ 최대 20초 + 여유 5초 — dry-run으로 확인 · 측정-9 시작 조건 ③).
- **계획 수(회차마다)**: a20-t300 PR-1 약 204(δ마다 약 34) · PR-3 약 163(δc마다 약 41) / a50-t300 PR-1 약 459(약 76) · PR-3 약 367(약 92)(리뷰 MR-13 D_arr 2,220초 — 초안 354 · 283) / a20-t30 PR-1 약 175(약 29) · PR-3 약 175(약 44) / a50-t30 PR-1 약 295(약 49) · PR-3 약 295(약 74). PR-2는 PR-1과 같은 수. 탐침 회차 좌석 사용 최대 약 2,200석(a50-t300 — PR-2가 3석씩 · 초안 약 1,700석) < 1만 석.
- **서버 시각 맞추기**: 서버 시각 = PC 시각 + 시계 차(lib.sh `clock_offset` — 왕복이 가장 짧은 표본 · k6/ADR-009/scripts/lib.sh:160). k6 시작 직전에 잰 값을 환경변수로 넘긴다(S3c `CLOCK_OFFSET_MS` 선례 — s3-full-flow.js:3·25). 판정은 요약기가 회차 앞뒤 `clock-offset.json`으로 다시 한다. 시각 오차 허용은 측정-9 `clock-offset-bad`.
- **실제 송신 시각 대조(리뷰 MR-2)**: 주 k6 CSV의 요청 시각은 초 단위라(k6/ADR-009/scenarios/probe-ping.js:8 머리 주석 — '주 k6 CSV는 이전처럼 초 단위') 탐침 판정에 못 쓴다. 탐침 요청마다 보내기 직전 PC 시각(ms)을 태그로 남긴다(S3c `hold_code` 행의 `t0` 태그 선례 — s3-full-flow.js:1-2). 요약기는 송신 서버 시각 = 태그 시각 + 시계 차(회차 앞뒤 `clock-offset.json` 평균)로 환산해 계획 서버 시각(PR-1·PR-2 = e + δ · PR-3 u 확정 = e + δc · PR-3 v 선점 = e + 0.3초)과의 오차 ε를 낸다. e는 선점 응답이 준 만료 시각이다.
  - **칸별 허용 오차**: PR-1·PR-2 |ε| ≤ 100ms(δ 최소 1초의 10% — 측정-4 시계 행과 같은 근거) · PR-3(u 확정과 v 선점 모두) |ε| ≤ 50ms(δc 최소 0.3초의 1/6 — 시계 오차 허용 20ms를 더해도 70ms < 0.3초의 1/4이라 δc 부호와 '만료 전 · 뒤'가 바뀌지 않는다). 허용을 넘은 탐침은 판정(M3 · M5 ④ · M7)의 분자·분모에서 빼고 `probe-timing-dropped`로 수를 남긴다(무음 금지).
  - **회차 무효**: 한 회차에서 (탐침 종류, δ 또는 δc) 칸 하나라도 뺀 비율 > 10%면 `probe-timing-bad`(하네스 — 측정-9). 10% 근거: 칸당 회차 계획 수 약 29~92개(위 계획 수)라 10%를 빼도 칸마다 26개 이상이 남는다. 빠짐이 부하 몰림과 함께 생기면 남은 표본이 치우치므로 상한을 작게 둔다(보수적 선택).
- 탐침 사용자는 탐침마다 새 번호다(PR-2 사용자는 그 회차 cnt 2로 시작해 한도에 닿는다).

#### 3.3 잔여석 구간 재현

- 수요가 창 끝까지 공급보다 크다(맹목 300세션/초 × 최대 4번 + 지도 10세션/초). 첫 약 40초에 만석이 되고(k6/ADR-009/README.md 주입 절), 그 뒤 빈 좌석은 **이탈 홀드의 만료로만** 생긴다 — 이 구간이 잔여석 구간이다.
- 풀린 좌석은 지도 흐름이 곧 찾는다(지도는 보이는 `AVAILABLE`만 고른다). 맹목 흐름만으로는 풀린 좌석 하나를 찾는 데 평균 약 8초가 걸린다(초당 시도 약 1,200 ÷ 1만 석 ≈ 0.12/초 — 추정). 이 값이 배치 지연(평균 약 5초)과 같은 급이라, 지도 흐름이 없으면 방식 차이가 '찾는 시간' 흔들림에 묻힌다.
- 방식 차이가 드러나는 자리: A의 좌석 맵은 저장 상태를 그대로 보인다(§3.3 A 문장). 그래서 배치 전 좌석을 `HELD`로 보이고, 지도 흐름이 고르지 않는다. B·C는 거짓 `HELD`를 `AVAILABLE`로, D는 활성 홀드로 계산해 보인다. 그 차이가 W(M1)와 T95(M2)에 나타난다.
- ADR-001이 남긴 한계('입장 종료(약 11분) 이후 새 사용자가 없어서' 매진율이 낮아짐 · '잔여석 구간의 재방문은 모델링하지 않았다' — docs/adr/ADR-001-consistency-oracle.md:212)를 이 두 장치(창 끝까지 도착 · 재방문)로 메운다.

#### 3.4 좌석 맵 조회 비율

- 지도 세션 10/초 · 세션당 `GET` 최대 2번 → 초당 최대 20번. 맹목 흐름 선점 요청(초당 300~1,200 — 만석 뒤에는 대부분 409) 대비 약 2~6%다(추정).
- 응답 크기 ≈ 1만 석 × 약 75바이트 ≈ 0.75MB(추정 — 페이지 없음 · Q11). 초당 20번이면 약 15MB/s ≈ 120Mbps다. k6 PC ↔ 서버는 1Gbps 유선이다(2026-10-01 이후 — docs/adr/ADR-002-db-baseline.md:576). 그 12% 안팎이다(추정 — 측정-11 #15로 스모크에서 잰다).
- 10/초를 고른 근거: 풀린 좌석을 찾는 시간(평균 약 0.1초)을 배치 지연(약 5초)보다 한 자릿수 이상 작게 만든다. 대역은 위 추정 안이다. 그보다 큰 조회 부하(20만 명 반복 조회)는 ADR-018 주제다. 10이라는 값 자체는 **보수적 선택**이다(측정 근거 없음).

#### 3.5 공통 난수 — 방식 사이 흔들림 줄이기

- 세션 속성(이탈 여부 · 재방문 여부 · 생각 시간 · δ · 좌석 고르기 난수)은 (회차 번호 r, 흐름, 세션 번호)를 씨앗으로 한 결정적 PRNG로 정한다. 같은 r의 네 방식이 같은 세션 속성 열을 받는다(응답이 갈리면 그 뒤 고른 좌석은 갈린다). 지금 S3는 `Math.random`이다(s3-full-flow.js:50-52·71-72) — 방식 사이 차이에 무작위 흔들림이 그대로 섞인다.
- 요약기가 회차마다 세션 속성 열의 해시를 남긴다. 같은 r에서 방식 사이 해시가 다르면 `crn-mismatch`(하네스 — 측정-9)다.
- **측정 전 재해석(2026-10-27 · 코드 리뷰 CR14-27)**: 해시 대조는 같은 (r, 셀)의 **유효(status ok) 회차끼리** 다수결로 낸다 — 무효 회차는 어차피 재측정되고, 버린 반복 등으로 해시가 달라져 유효 회차까지 `crn-mismatch`로 끌어내리면 안 된다. 유효 회차가 1개 이하면 대조하지 않는다(짝 방식이 그 셀에서 유효 < 3이 되어 방식 사이 비교가 이미 판정 불가). 유효인데 해시 합이 없으면 그대로 `crn-mismatch`.
- **해시의 정의(리뷰 MR-11)**: 해시는 **실제로 실행된 세션의 속성**이다 — 판정 창 안에서 k6가 시작한 세션마다 세션이 시작 직후 스스로 남긴 기록(흐름 · 세션 번호 · 이탈 여부 · 재방문 여부 · 생각 시간 · δ · 좌석 고르기 씨앗)을 (흐름, 세션 번호) 순으로 이어 붙인 해시다. 요약기가 씨앗에서 다시 계산한 계획 열이 아니다(그러면 늘 같아 버린 반복 · 끊긴 세션을 못 잡는다). 모든 속성은 응답과 무관하게 세션 시작에서 뽑는다 — 쓰지 않게 된 속성(예: 409로 끝나 이탈 판정에 가지 않은 세션의 생각 시간)도 기록한다.

#### 3.6 하네스 — 재사용할 것과 새로 만들 것

ADR-013 선례대로 `k6/ADR-009/` 하네스는 **고치지 않는다**. 측정 SHA의 동결 사본에서 source·`-f`로 쓴다(k6/ADR-013/README.md 머리 — 앞 ADR의 렌더·계획이 바뀌지 않게). 새 파일은 `k6/ADR-014/` 아래에 둔다(구현은 task 04).

| 구분 | 무엇 | 출처 |
|---|---|---|
| 재사용 | compose(앱·DB·tmpfs 오버레이) · 기동·내림 · `/internal/reset` 시드 · 예열 뒤 재시드 · 시작 대기(시드 끝 → 첫 요청 87 ± 5초) · 시계 차 · DB 대기 표본(0.5초) · 호스트 표본 · docker stats 10초 · Hikari 1초 · GC 로그(`gc*,safepoint`) · I/O·WAL 스냅숏 · 데드락 증분(`pg_stat_database.deadlocks` — run.sh:774·1189) · 끝 판정 `consistency` · `counter_drift_log` 덤프 · 앱 지표 스냅숏(actsnap) · 측정기(`--probes`) · 회차 합격 사유 중 S3e·S3c·C·F 전용이 아닌 것 | k6/ADR-009/scripts/run.sh · lib.sh · README '회차 합격' 표 |
| 재사용 | 선점·확정 호출과 응답 분류(태그 · 코드 행) | k6/ADR-009/scenarios/lib/common.js |
| 재사용 | 재선점·중복 선점 가르기(같은 좌석의 다음 성공이 앞 홀드 만료 뒤인가 — `hold_ok_seat` 좌석·만료 시각) | k6/ADR-009/scripts/summarize.py:362-432 |
| 재사용 | 1초 p99 · 느린 초 | k6/ADR-009/scripts/tail.py |
| 재사용 | S4c4200 셀 그대로(s4-throughput.js · 시드 200회차 × 1만 석) | run.sh:488·521 |
| 재사용 | 캠페인 골격 — `--sha` 동결(git archive · 해시를 plan.json에) · 이어서 실행 대조 · 재측정(무효만 · 자리마다 2번) · 루프 안 run.sh `< /dev/null` · 끝 집계 전 미실행 자리 검사 | k6/ADR-013/scripts/campaign.sh · hhash.sh(ADR-013 §6.1 대체된 캠페인의 수정 ed2af264) |
| 새로 | 주 시나리오 `s14-flow.js`(맹목 · 지도 · 재방문 · 탐침 PR-1~3 — k6 여러 시나리오 한 실행) · 공통 난수 · 서버 시각 일정 | 측정-3.1~3.5 |
| 새로 | 예열 `warmup14.js` — 선점 + 지도 + 확정을 회차 1·2에 20초(지금 예열은 선점뿐 — k6/ADR-009/scenarios/warmup.js:3-6). 지도 GET과 B·C·D의 JDBC 경로를 측정 전에 데운다 | 측정-9 '차가운 JVM' |
| 새로 | 1초 상태 표본기(측정-5 머리 SQL — DB 대기 표본과 같은 방식: 서버 루프 · 서버 사본 · 끝에 복구). 표본마다 질의 실행 시간 `sample_ms`(서버 쪽에서 잰다)를 함께 남긴다. **주 셀에서만 돈다** — 비용 셀(S4c)은 M1·M2를 쓰지 않으므로 돌리지 않는다(비용 셀 DB CPU에 표본 비용이 섞이지 않는다 · 리뷰 MR-1) | lib.sh `db_sampler` 방식(lib.sh:149-155) |
| 새로 | 조건 렌더: `expiry-mode` · TTL · `EXPIRY_INTERVAL` 10s · Flyway 위치 + `db/migration-seat-map` · configprops 기대값(카운터 = 업무 앱 기본값) | 측정-1 |
| 새로 | 테이블 통계 스냅숏(`pg_stat_user_tables` `product_seat`·`seat_hold` `n_tup_upd`·`n_tup_del`·`n_dead_tup` — k6 직전 · 직후 · 수렴 대기 끝(끝 판정 앞)). '직후'는 k6 마지막 응답 뒤 업무 요청 연결이 10초 넘게 쉰 뒤에 찍는다(리뷰 MR-12 — 측정-6 P6) | P6 |
| 새로 | 요약기 `summarize14.py`: M1~M14 · 탐침 분류 · 진행 중 이중 판매 재구성 · 성공 · 고르는 규칙 · 교체 관문 · `RESULT.md` | 측정-5~8 |
| 새로(task 04 — 앱) | 방식별 판정기(§설계-8.3) · 앱 지표: NOWAIT 패배(55P03) 태그 · 선점 409 원인 표지(판정 409 대 배치 잠금 409 — 측정-5 M3 · 리뷰 MR-5) · H4 회수 문장 표지(측정-5 M10 · 리뷰 MR-13) · 회수 건수·시간(H3~H5) · C6·H4b가 활성 홀드 때문에 되돌리지 않은 좌석 수(되돌림 기회) · SKIP LOCKED 건너뜀 · C·D 배치 청크 행 수·MISSING 쌍·`:missingIds` 불일치 롤백 · B 정리 ① 지운 수 | §설계-8.2 · 측정-11 #19 |
| 쓰지 않음 | S3e 주입 · S3c 마감 · `inject-app` · ADR-013 전환 장치(lb · 슬롯 · 적용판 · 한도 탐침) · ADR-009 `scaled`·`default` 조건 | 측정-1 |

### 측정-4 고정값과 근거 (측정 뒤 바꾸지 않는다)

| 값 | 쓰는 곳 | 근거 | 근거 종류 |
|---|---|---|---|
| 좌석 1만 석 · 핫스팟 20%/70% · 맹목 300세션/초 · 재시도 3 | 3.1 | S3 그대로(s3-full-flow.js:12-17) — ADR-001·009의 S3 결과(재선점 곡선·매진율 — ADR-001:396-397)와 같은 모양 | 선례 |
| 이탈률 0.2 · 0.5 | 축 | 명세 §0 | 사용자 결정 |
| TTL 5분 · 30초 | 축 | 명세 §0 · application.yml:34 | 사용자 결정 |
| 배치 주기 10초(모든 셀) | 측정-1 | application.yml:36 · §설계-8.1 'A·C·D 같은 값' | 기본값 |
| 생각 시간 30–240초 · 3–24초 | 3.1 | S3 · S3A 선례(run.sh:512-513) — 최대 < TTL | 선례 |
| D_arr 1,200 · 2,220 · 240 · 360초(리뷰 MR-13 — a50-t300 초안 1,800) | 측정-2 | 라운드 모형 T95 추정 + 여유(측정-2 표) · 늘리는 식 D_arr′ = ⌈T95_추정 ÷ 0.6 ÷ 60⌉ × 60 | **추정** · 스모크로 확인(시작 조건 ⑥) |
| 지도 흐름 10세션/초 · 세션당 GET ≤ 2 | 3.4 | 찾는 시간 ≪ 배치 지연 · 대역 추정 12% | 설계 · 값은 **보수적 선택** |
| 재방문 ρ = 0.2 · δ ~ [0, 20]초 | 3.1 | 20초 = 배치 주기 × 2(A의 묶임 창을 덮는다). 0.2는 근거 없음 — 설명 지표에만 쓴다 | **보수적 선택** |
| 탐침 δ {1, 3, 5, 8, 12, 20}초 · δc {−1.0, −0.3, +0.3, +1.0}초 · v는 e + 0.3초 | 3.2 | δ: 배치 주기 10초 안팎을 고르게 덮는다(A 실패율 곡선 = 배치 지연 분포). δc ±0.3: 시계 오차 허용(20ms — `clock-offset-bad`)의 15배 | 설계 |
| 탐침 속도(0.25 · 0.25 · 0.2/초 · TTL 30초 1/초) | 3.2 | δ·δc 칸마다 회차당 30개 안팎 이상(측정-3.2 계획 수) | 설계 |
| 판정 지표 T95 | M2 | T99는 a50-t300에서 창 끝 근처(추정 2,100초 대 창 2,220초 — 측정-2 표 · 리뷰 MR-13 뒤에도 창 밖으로 갈 위험이 크다) · T100은 마지막 좌석의 이탈 사슬에 달려 흔들림이 크다(추정) | 설계 |
| 허용선 +10%(≫의 둘째 조건) | 측정-8 3단 · G3 | ADR-009 사용자 결정 '선점 p99·처리량이 대조군 대비 10% 이내'(docs/adr/ADR-009-counter-drift.md:259) · ADR-012 R5 '+10% 초과(ADR-009 허용선)'(docs/adr/ADR-012-incremental-reconcile.md §5 R5). DB CPU에 쓰는 +10%는 선례가 없다 | 선례(p99) · **보수적 선택**(CPU) |
| 비용 판정 셀 = a50-t300 | 측정-8 3단 | 가장 긴 창 · 라운드가 가장 많음 · B의 남은 만료 행이 가장 많이 쌓인다 | 설계 |
| 상태 표본 1초 · 간격 공백 > 3초면 무효(창 양끝 공백 포함 — 리뷰 MR-4) | M1·M2 · 측정-9 | T_x·W의 해상도 1초. 3초 = 배치 주기 10초의 1/3 미만 — 배치 한 틱의 위상을 잃지 않는다 | 설계 |
| 표본 비용 기준: 방식 사이 표본 CPU 몫 차 Δc > 과거 회차 간 DB CPU 범위 R₀이면 표본 방식 변경 · 캠페인 뒤 Δc > A 회차 간 범위면 주 셀 DB CPU를 비용 비교에서 분리(리뷰 MR-1) | 측정-5 머리 · 측정-8 3단 · G3 | 표본기가 만든 차이가 회차 간 흔들림보다 크면 '범위 비겹침'을 표본기 혼자 만들 수 있다 | 설계 |
| 탐침 송신 오차: PR-1·PR-2 ≤ 100ms · PR-3 ≤ 50ms · 칸별 뺀 비율 > 10%면 무효(리뷰 MR-2) | 3.2 · 측정-9 | δ 최소 1초의 10% · δc 0.3초의 1/6 · 칸당 26개 이상 남김 | 설계 · 10%는 **보수적 선택** |
| DB CPU 표본: 창 안 DB 컨테이너 행 ≥ ⌈창 초 ÷ 15⌉ · 최대 공백(양끝 포함) ≤ 30초(리뷰 MR-3) | M9 · 측정-9 | 표본 간격 10초(run.sh:600-602) + `docker stats` 호출 시간 — 한 번 빠져도 평균 간격 15초 안 · 30초 = ADR-009 `path-gap` 경계(run.sh:1341)와 같다 | 선례(30초) · 15초는 **보수적 선택** |
| k6 maxVUs: 흐름마다 max(14,000, ⌈스모크 VU 최대 × 1.5⌉) · 판정 창 안 `dropped_iterations` > 0이면 무효(리뷰 MR-11) | 3.1 · 측정-9 | S3의 maxVUs 14,000(s3-full-flow.js:33) · 여유 1.5배는 스모크가 a50-t30만 돌아 TTL 5분 칸의 VU 모양을 못 보는 몫 | 선례 · 1.5배는 **보수적 선택** |
| 시계 오차: 왕복/2 ≤ 20ms · 회차 앞뒤 차 ≤ 100ms | 3.2 · 측정-9 | δ 최소 1초의 10% = 100ms · δc 0.3초의 1/15 = 20ms | 설계 |
| 환경 정지: 5초 이상 연속 정지 초 · 측정기 공백 > 5초 · GC 정지 최대 < 100ms · DB 쪽 원인 없음(업무 세션 Lock 대기 0 · Hikari pending 0 · 활성 < 최대 — 리뷰 MR-6) | 측정-9 | ADR-013 §6.6-6 환경 정지 덩어리(약 11~13초 연속 · 31회 중 9회 · DB 표본 정상)를 잡고, 1~2초 짧은 꼬리(배치 틱 · GC)는 거르는 경계. DB 쪽 조건은 '잠금·풀 고갈로 앱이 멈춘 방식 정지'를 환경으로 숨기지 않게 한다 | 선례 · 값은 **보수적 선택** |
| L4 · tmpfs 12g · 2g ZGC | 측정-1 | ADR-010 결정 · ADR-012·013 측정 조건 | 선례 |

### 측정-5 지표 정의 · 원자료 · 계산식

**상태 표본(1초 · 주 회차 · 판정 창 + 수렴 대기)** — 원자료 `timeline-seatstate.jsonl`(새). 모든 방식이 같은 문장을 쓰지만 **표본 비용은 방식마다 다르다**(리뷰 MR-1 — 초안의 '같은 문장이라 비용이 같다'를 고침). 같은 문장도 읽는 행 수가 방식의 상태에 달렸다 — B는 주기 배치가 없어 만료 홀드 행이 쌓이므로(P13) `expired_rows` 하위 질의가 세는 행과 `seat_hold` 탐색량이 창 동안 커진다. A·C·D는 배치가 지워 작게 남는다. V11 인덱스 위에서 1만 석 · 활성 홀드 ≤ 1만 행이다(질의 시간은 `[구현 검증]` 측정-11 #16).

- **표본 비용 재기(스모크 · 시작 조건 ⑤)**: 방식마다 a50-t30 스모크에서 표본별 `sample_ms`(측정-3.6)를 모아 ① 표본 질의 시간 p50·p99·최대 ② 표본 CPU 몫 c_X = Σ `sample_ms` ÷ (창 초 × 1,000) × 100(%, docker stats 단위 — 100 = 코어 1개. 실행 시간을 모두 CPU로 치는 상한 — tmpfs라 I/O 대기가 작다 · 추정)을 낸다. B의 a50-t300 값은 스모크 시계열의 '`sample_ms` 대 `expired_rows`' 기울기로 P13의 a50-t300 창 끝 예측 행 수까지 외삽한다(추정).
- **사전 기준(수식)**: Δc = maxₓ c_X − minₓ c_X(네 방식 · a50-t300 외삽값 포함). R₀ = 과거 S3-a20 L4 회차(`k6/ADR-009/results/*/*/L4/S3-a20/rep*/timeline-server.jsonl` — 그중 tmpfs 회차만 · 측정-2 회차 시간 추정과 같은 고르기)에서 같은 조건 3회 묶음마다 DB CPU 창 평균의 (최대 − 최소)를 내고 그중 가장 작은 값(작을수록 엄격 — 시작 조건 ⑤에서 원자료로 계산해 log에 남긴다).
  - Δc ≤ R₀ → 그대로.
  - Δc > R₀ → **표본 방식 변경**(캠페인 전 1번): `expired_rows` 하위 질의를 1초 표본에서 빼서 10초 간격 별도 표본으로 옮기고(M12는 10초 해상도로 충분 — 창 끝 · 수렴 뒤 값과 기울기만 쓴다) 스모크를 다시 잰다. 그래도 Δc > R₀이면 아래 분리를 미리 적용한다(log 1행).
  - **캠페인 뒤 점검**: 셀마다 회차별 c_X를 다시 내서 Δc(a50-t300 · 실측) > A의 a50-t300 DB CPU 3회 범위(최대 − 최소)이면 **주 셀 DB CPU(a50-t300)를 비용 비교에서 분리**한다 — 측정-8 3단 · G3 목록에서 빼고 기록만(c_X를 뺀 값도 함께 적는다). 비용 판단은 표본기가 없는 비용 셀 DB CPU가 맡는다. 측정 뒤 다르게 읽는 것이 아니라 이 측정 전 규칙의 적용이다.

```sql
SELECT clock_timestamp() AS t, now() AS db_now,
       count(*) FILTER (WHERE st.status = 'RESERVED')                         AS reserved,
       count(*) FILTER (WHERE st.status <> 'RESERVED' AND a.n IS NULL)        AS free_by_rule,     -- 규칙상 비어 있음
       count(*) FILTER (WHERE st.status = 'HELD' AND a.n IS NULL)             AS held_no_active,   -- A: 묶임 · B·C: 거짓 HELD · D: 옛 데이터만
       count(*) FILTER (WHERE a.n IS NOT NULL AND st.status <> 'HELD')        AS active_not_held,  -- A·B·C 기대 0(되돌림 위반) · D 제외
       count(*) FILTER (WHERE a.n > 1)                                        AS multi_active,     -- 기대 0(N2)
       count(*) FILTER (WHERE a.n IS NOT NULL AND st.status = 'RESERVED')     AS active_on_reserved, -- 기대 0(N2)
       (SELECT count(*) FROM seat_hold WHERE schedule_id = 1 AND expires_at <= now()) AS expired_rows
FROM product_seat st
LEFT JOIN (SELECT seat_id, count(*) AS n FROM seat_hold WHERE schedule_id = 1 AND expires_at > now() GROUP BY seat_id) a
       ON a.seat_id = st.id
WHERE st.schedule_id = 1;
```

| # | 지표 | 원자료 | 계산식 | 쓰임 |
|---|---|---|---|---|
| M1 | **팔 수 있는데 묶인 좌석·초 W** | 상태 표본 `held_no_active` | 창 [t0, t_e)(t_e = t0 + D_arr · t0은 PC 시각이므로 시계 차를 더해 서버 시각으로 바꾼다 — 표본 `t`는 DB `clock_timestamp()`) 안의 표본 t₁ < … < tₙ으로 **계단 함수**를 만든다: 값 `held_no_active`(tᵢ)는 [tᵢ, tᵢ₊₁)에 머문다 · **앞끝** [t0, t₁)은 t₁ 값으로 채운다(t0 직전은 시드 직후라 기대값 0 — 영향 ≤ 3초 × 그 값) · **뒤끝** [tₙ, t_e)는 tₙ 값으로 채운다. W = `held_no_active`(t₁) × (t₁ − t0) + Σᵢ₌₁ⁿ⁻¹ `held_no_active`(tᵢ) × (tᵢ₊₁ − tᵢ) + `held_no_active`(tₙ) × (t_e − tₙ)(리뷰 MR-4). **최대 간격 검사**는 t0 → t₁ · 이웃 표본 사이 · tₙ → t_e를 모두 넣어 하나라도 > 3초면 회차 무효(`state-sampler-gap` — gapcheck.py:33-34와 같은 양끝 처리). 표본기는 t0보다 먼저 시작한다. T_x(M2)도 같은 창 안 표본만 쓴다. **A만 묶임이다**(A의 선점은 저장 `HELD`를 거절한다 — ProductSeat.kt:44-48). B·C의 같은 값은 '거짓 `HELD` 좌석·초'(거절하지 않음 — 비어 있음 규칙이 저장 상태를 읽지 않는다 · §설계-2.1)로 따로 이름 붙여 기록만 한다. D는 0이어야 한다(`HELD`를 쓰지 않는다). 보조: L_A = W ÷ N_exp(N_exp = 판정 창 안에 만료된 주 회차 홀드 수 — k6 `hold_ok_seat`의 만료 시각이 창 안이고 확정 200이 없는 홀드) = 만료 홀드 하나가 묶인 평균 초 | P1 · G4(양성 대조) |
| M2 | **매진 시간 T95**(잔여석 구간 매진) | 상태 표본 `reserved` | T95 = `reserved` ≥ 9,500인 첫 표본 시각 − t0. 창 안에 없으면 '창 밖'(+∞로 센다 — 가장 나쁨). 기록: T90 · T99 · T100 · 잔여석 구간 길이 T95 − T90 | 측정-8 2단 · G2(a) · P2 |
| M3 | **자기 좌석 재선점 부당 실패율** | k6 탐침 행(PR-1·PR-2 — 응답 코드 · 보낸 시각 · e) | (탐침 종류, δ)마다 = 409 수 ÷ (201 + 409) 수. 탐침 회차는 주 부하가 없어 409는 모두 부당 실패다. 코드별(`SEAT_NOT_AVAILABLE` · `HOLD_LIMIT_EXCEEDED`)로 나눈다. 판정용 값 = TTL 묶음(t300 · t30)마다 δ 전체 합의 비율. 5xx·코드 0 응답 탐침 · 송신 오차 초과 탐침(3.2 — 리뷰 MR-2)은 분모에서 빼고 각각 M11 · `probe-timing-dropped`로 센다.<br>**원인 나누기(리뷰 MR-5)**: 409를 **판정 409**와 **배치 잠금 409**(측정-0)로 나눠 M3ᵣ(판정 409 비율) · M3ᵦ(배치 잠금 409 비율)로 따로 낸다 — M3 = M3ᵣ + M3ᵦ. 둘 다 실제 사용자 실패다. 가르는 방법(계약 무변경 · `[구현 검증]` 측정-11 #22): 측정 프로필(`loadtest`)에서만 켜는 응답 헤더 `X-Seatlab-Reject: rule | nowait`(본문 `{code, message}`와 상태 코드는 그대로 — ApiExceptionHandler.kt:16-18·32-35) + 같은 갈래를 세는 앱 지표 `seat.hold.rejected{cause}`. `nowait` 갈래는 DbLockHoldStrategies.kt:55-57(55P03 → `seatTaken()`)에서 붙인다. PR-1·PR-2의 좌석은 그 탐침 사용자만 쓰므로(3.2), 탐침 409의 `nowait`는 C 배치 C2가 쥔 잠금으로만 생긴다 → C의 `nowait` = 배치 잠금 409.<br>**분류 불가(무음 금지)**: ① B·D의 탐침 `nowait`(좌석 행을 잠그는 배치가 없다 — 쥔 쪽을 설명할 수 없다) ② 헤더가 없거나 `rule`·`nowait` 밖의 값인 탐침 409 → 둘 다 **판정 409로 센다**(P4 쪽 — 실패를 숨기지 않는 쪽) + 이름표 `reject-cause-unknown`(수 · 탐침 id를 결과에 적는다). ③ k6 실행 전체(모든 흐름)의 헤더 합과 k6 직전 → 직후 앱 지표 `seat.hold.rejected{cause}` 증분이 갈래마다 다르거나, **그 회차에 409가 1건 이상인데** 헤더가 한 번도 안 나옴 → 하네스 `app-metric-missing`(측정-9 · 409가 0인 회차는 이 검사를 건너뛴다 — 재점검 MR-17). **설명**: 주 회차 재방문 409를 '정당'(e ~ 보낸 시각 사이에 다른 사용자의 그 좌석 201 또는 확정 200이 있음 — k6 기록) · '부당'(그 밖)으로 나눈 비율 | P3 · P4 · G2(b) |
| M4 | **배치 경합(가설 3)** | 상태 표본 `active_not_held` · 끝 판정기 · 앱 지표 '되돌림 기회'(C6·H4b가 활성 홀드 때문에 되돌리지 않은 좌석 수) · 테이블 통계 | C: `active_not_held` > 0인 표본 수와 최대 · 끝 '활성 홀드 있는데 `HELD` 아님'(§설계-8.3 7행) · 기회 수. D: Δ`n_tup_upd`(product_seat) − 확정 성공 수(DB) = 0(D의 좌석 쓰기는 확정 K4뿐 — 추정 `[구현 검증]` #18). B: 주기 배치 없음 → 해당 없음. A: 앱 1대라 구조상 생기지 않음 → 기록만 | P5 · P6 |
| M5 | **이중 판매** | 끝 판정기(방식별 — §설계-8.3 1행) · 상태 표본 · k6 `hold_ok_seat`·확정 200 · PR-3 | 다음 합이 0이어야 한다: ① 끝 — 좌석당 확정 ≥ 2(`v_duplicate_confirmed_seats` — LoadtestDataService.kt:164) · 좌석당 활성 홀드 ≥ 2 · 활성 홀드 + 확정 공존 ② 표본 — `multi_active` > 0 · `active_on_reserved` > 0인 표본 ③ 진행 중(k6 재구성) — 같은 좌석 두 201의 활성 구간 [만료 − TTL, 만료)이 겹침(`duplicate` — summarize.py:383과 같은 가름) · 확정 200 뒤 그 좌석 201 ④ PR-3 — u 확정 200 + v 선점 201 ⑤ DB 확정 수 < k6 확정 200 수(응답은 성공인데 행이 없음). 기회 수(기록): 남의 만료 홀드 행이 남은 좌석 위의 재선점 수(B·C·D). **PR-3 실행 수는 기회 수가 아니다**(리뷰 MR-8 — 초안 '기회 수 = PR-3 회차 수'를 고침) — PR-3은 만료 전 확정과 v 선점 사이가 0.6초 이상인 API 계약 양성 대조라 시각 역전 경합(트랜잭션이 겹치는 이중 판매 기회)을 만들지 못한다. PR-3 실행 수는 ④의 분모로만 따로 적는다. 시각 역전 경합의 0은 동시성 테스트(§설계-9 N2 · §설계-10 #12 — 시작 조건 ⑦)가 맡는다 | P7 |
| M6 | **카운터 대조 불일치·오탐** | 끝 판정기 `v_counter_mismatch`(열린 회차 1·2 — LoadtestDataService.kt:203) · `counter_drift_log`(drift-db.json — 시드 끝 ~ 끝 판정) · 증분 틱·안전망 실행 기록 | 끝 불일치 수 · `repaired` 기록 수(출처 무관 — 주입이 없으므로 모든 `repaired`는 실제 어긋남 또는 오탐 복구다) · `false-positive` 기록 수(기록 — 탐지 스냅숏 경합으로 설계상 생긴다 · ADR-013 P4) · 생존 = 증분 틱 수 > 0 이고 안전망 실행 수 > 0 | P8 |
| M7 | **만료 뒤 확정**(API 계약 양성 대조 — 리뷰 MR-8) | PR-3 확정 응답(송신 오차 초과 제외 — 3.2) · 주 회차 확정 응답 | δc ∈ {+0.3, +1.0}의 확정 200 수(기대 0) · δc ∈ {−1.0, −0.3}의 확정 200 비율(양성 대조) · 확정 거절의 404 `HOLD_NOT_FOUND` 대 409 `HOLD_EXPIRED` 분포(Q14 — 기록) | P9 |
| M8 | **선점 · 확정 · 좌석 맵 p99** | k6 CSV 요청 행(`http_req_duration` · name · status) | 주 셀: 판정 창 안 선점 201 p99 · 선점 409 p99(기록) · 확정 200 p99 · 지도 200 p99 — 맹목·지도·재방문 합산, 탐침 제외. 비용 셀: 정상 구간(램프 60초 뒤 — ADR-007 고정 셀 정의) 선점 p99. 기록: 1초 p99 최대 · 느린 초(tail.py) | 측정-8 3단 · G3 · P10 · P11 |
| M9 | **DB CPU** | docker stats DB 컨테이너(10초 간격 — `timeline-server.jsonl`) | 판정 창(주 셀) · 정상 구간(비용 셀) 평균 CPU% · 표본 수. 앱 CPU · k6 PC CPU(`timeline-client.jsonl`)는 기록.<br>**결측 규칙(리뷰 MR-3)**: 셀마다(주 셀 · 비용 셀 모두) 그 창 안의 **DB 컨테이너 행**(이름 `seatlab-db-1` · `CPUPerc`를 숫자로 읽을 수 있는 행)이 ⌈창 초 ÷ 15⌉개 이상(a20-t30 16 · a50-t30 24 · a20-t300 80 · a50-t300 148 · 비용 셀 정상 구간 360초 24)이고, 창 시작 → 첫 행 · 이웃 행 사이 · 마지막 행 → 창 끝 공백이 모두 ≤ 30초여야 한다. 아니면 `db-cpu-missing`(하네스 — 측정-9) — **G3 · 3단 평가 전에** 그 회차는 무효다. 이유: 지금 수집 루프는 `docker stats` 오류를 버리고(run.sh:600-601 — `2>/dev/null`) 서버 시각 줄(`{"t":…,"load":…}`)은 docker stats 실패와 무관하게 쓴다. 기존 `path-gap`(run.sh:1341)은 `{"t"`로 시작하는 줄만 세므로(gapcheck.py:26) DB 행이 빠져도 통과한다 — 결측을 보는 판정기가 없다 | 측정-8 3단 · G3 |
| M10 | **회수 · 회수 대기**(§설계-8.2) | 앱 지표(회수 건수 · H3~H5 구간 시간 — task 04) · 0.5초 DB 대기 표본의 **회수 전용** 문장 표지(리뷰 MR-13): 초안 표지 `DELETE … expires_at <= now()`는 배치 문장(C4 · D4 `DELETE FROM seat_hold h USING unnest(…) … h.expires_at <= now()` · B2 `DELETE FROM seat_hold WHERE id IN (…)` — §3.4)에도 맞아 섞인다. 그래서 H4 문장 앞에 측정용 SQL 주석 `/* seatlab:h4-reclaim */`을 붙이고(문장 의미 · API 계약 무변경) 대기 표본의 `pg_stat_activity.query`에서 그 주석만 센다. 주석이 표본에 보이는지는 `[구현 검증]`(측정-11 #24) — 안 보이면 `app-metric-missing` | 회수 건수 · 회수한 행 수 · 선점 중 회수 비율 · H3~H5 시간 p99·최대 · H4가 락을 기다린 표본 수. 기대: 이 부하에는 '같은 사용자의 확정 중 다른 좌석 선점'이 없어 H4 대기 ≈ 0(측정-10-7) | 설명 |
| M11 | **5xx · 40P01(데드락) · 배치 실패** | k6 CSV · `pg_stat_database.deadlocks` 증분(run.sh:774·1189) · 앱 로그 · 앱 지표 `seat.hold.expiry.tick{outcome}`(ExpireHoldsService.kt:54) | 요청 종류별 5xx · 코드 0 수 · 데드락 증분 · 앱 로그 `40P01`/`deadlock detected` 줄 · 틱 실패 수 · `:missingIds` 불일치 롤백 수(§3.4) · 정지 초(완료 응답 0인 초). **세는 창은 판정 창 안**(주 셀 [t0, t0 + D_arr) · 비용 셀 정상 구간 — 리뷰 MR-9). 창 밖(gracefulStop · 수렴 대기)의 같은 수는 기록만 | P12 · 측정-9 |
| M12 | **남은 만료 홀드 행** | 상태 표본 `expired_rows` · 끝 판정기(§설계-8.3 5·6·8행) | 시간 곡선 · 창 끝 값 · 수렴 뒤 값을 방식별 기대값(§설계-8.3)과 대조. **기대값이 0인 칸은 측정-7 성공 조건(상태 기대값 문턱)이 판정한다**(리뷰 MR-7) · 기대값이 0이 아닌 칸(A '마지막 틱 뒤 만료분만' · B '늘어난다' · '> 0 가능' · C '배치 주기분 + 건너뜀분' 등)만 다르면 `expect-mismatch` 이름표(무음 금지 · 판정 자료) — 한 칸이 두 규칙에 함께 들지 않는다 | P13 · 정합 보조 |
| M13 | **NOWAIT 패배 · SKIP LOCKED 건너뜀** | 앱 지표(55P03 태그 · C2·H4b·B 정리 ① 건너뜀 수 — task 04) | 판정 창 합 | 설명 |
| M14 | **시각** | 회차 앞뒤 `clock-offset.json` · 앱 시각과 DB `now()`의 차(회차마다 1번 — 측정-11 #11) · 스모크의 판정 문장 `clock_timestamp() − now()` 분포 | 왕복 · 앞뒤 차 · 앱−DB 차(A 대 B·C·D 비교에 섞이는 시계 출처 차 — §설계-2.1) | 측정-9 · 기록 |

- **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-19) — M5 기회 수 · P7 판정 불가 '재선점 0'**: 남은 만료 홀드 행 여부는 k6로 알 수 없으므로 기회 수를 k6 재구성으로 센다 — **넓은 값** = 같은 좌석에서 앞 홀드(다른 사용자)의 만료 시각 이후에 선점 201이 난 수(`rehold_other`) · **좁힌 값** = 그중 '재선점 시각 − 앞 만료 < 배치 주기(10초)'인 수(`rehold_other_narrow` — 배치가 앞 만료 행을 아직 못 지웠을 수 있는 재선점). 둘 다 남기고, **P7 판정 불가 조건에는 넓은 값을 쓴다**(3회 · 주 셀 합 — k6/ADR-014/README.md 해석 메모 8을 확정). 좁힌 값은 기록이다.
- **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-12) — M10 H4 표지가 표본에 안 보일 때**: 원문 '안 보이면 `app-metric-missing`'(회차 무효)을 **이름표만**으로 읽는다 — 회수(`seat.hold.reclaim` COUNT > 0)가 있었던 B·C·D 회차에서 표지가 한 번도 안 보이면 `h4-marker-unseen`, 주 셀인데 표본 파일(`timeline-h4.jsonl`)이 없으면 `h4-sampler-missing`(판정 자료 — 회차 사유 아님). 이유: H4는 ms 단위 문장이라 0.5초 표본이 우연히 놓칠 수 있고, M10은 '설명' 지표다(성공·고르기에 쓰지 않는다 — README 해석 메모 10). **스모크 #24 뒤 확정(2026-10-29 · 정식 스모크 — 사용자 결정 ③)**: 정식 스모크(k6/ADR-014/results/smoke14-20261029 a50-t30)에서 회수가 있었던 B·C·D(`seat.hold.reclaim` COUNT 1,431 · 538 · 515) 모두 `h4-marker-unseen`이었다(0.5초 표본 약 1,025개 중 표지 0 · 회수 MAX 0.4~1.2ms — 0.5초 표본이 잡기에 너무 짧다). 그래서 ① 표지는 **이름표만** 남긴다(이 재해석 그대로 — 회차 사유가 아니다) ② M10의 **회수 대기 시간은 앱 타이머 지표 `seat.hold.reclaim`**(COUNT · TOTAL_TIME · MAX — actsnap14)으로 본다(0.5초 대기 표본의 표지는 원자료에서 내린다) ③ **H4 경로(회수 문장이 실제로 돌고 이중 감소가 없음)의 보증은 동시성 테스트**(§설계-9 · 시작 조건 ⑦)가 맡는다. 원인 가르기(JDBC 문장 그대로 · 짧은 간격 표본)는 하지 않는다 — M10은 '설명' 지표라 성공·고르기에 영향이 없다. 잠정이 아니다.
- **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-13) — M11 '판정 창 안'을 세는 방법**: 앱 지표(틱 실패 · `:missingIds` 롤백)는 연속 시계열이 아니라 스냅숏 증분이다 — 창 안 값 = k6 직전 스냅숏(시작 대기 70초 시점 — t0 약 17초 앞 · 부하 없음) → **판정 창 끝 스냅숏**(k6 실행 + 창 끝 + 3초 · 시작 시각이 창 끝 뒤 [0, 10]초가 아니면 `app-metric-missing`) 증분. 양끝의 창 밖 몫(앞 약 17초 · 뒤 약 3초 + 조회 시간)은 실패 쪽으로 보수적이다. P12는 이 값을 쓰고, 창 끝 → 회차 끝(gracefulStop · 수렴) 증분은 기록만 한다(MR-9 원문 그대로). 데드락은 `db.log` 줄 시각으로 창 안을 센다(시각을 못 읽으면 k6 직전 → 직후 증분 — 보수적).
- **관측 기록(2026-10-29 · 정식 스모크 — 측정-11 #22)**: PR-1·PR-2 탐침의 `nowait`(배치 잠금 409)는 네 방식 모두 0이었다(C: PR-1·2 판정 탐침 590 중 409 0 · 회차 전체 헤더 nowait는 C 8 · B 2 — 모두 PR-1·2 판정 탐침 밖). **M3ᵦ는 이 스모크에서 빈 지표**다(#22 '0이면 2단 M3ᵦ는 비어 있는 지표다 — 그 사실을 log에 남긴다'). 정의 · 2단 사용은 그대로 두고, 캠페인에서도 0이면 2단 M3ᵦ 비교는 모든 후보 같음(아무도 빼지 않음)이다.
- **측정 전 재해석(2026-10-29 · 스모크 — 사용자 결정) — M3 ③ 원인 표지 대조의 허용 폭**: 원문 '갈래마다 다르면 `app-metric-missing`'을 다음으로 읽는다 — 갈래(`rule` · `nowait`)마다 **0 ≤ 앱 증분 − k6 헤더 합**이고 두 갈래 차의 합 **≤ 그 회차 k6 응답 없는 선점(코드 0 — k6 실행 전체 · 모든 흐름) 수**면 일치로 본다. k6 쪽이 더 많거나 차이가 그보다 크면 원문대로 `app-metric-missing`(하네스 — 무효)이다. '409가 1건 이상인데 헤더 합 0'은 원문 그대로다. **근거**: 정식 스모크(k6/ADR-014/results/smoke14-20261029)에서 탐침 첫 선점이 k6 코드 0(EOF)으로 끝난 일이 있었다 — 서버가 요청을 받아 409로 셌는데 응답이 k6에 닿지 못하면 앱만 센 409가 생긴다(README 해석 메모 11의 '앱만 센 409'). 그 수는 응답 없는 선점 수를 넘을 수 없다. 상한을 선점 요청으로 좁힌 것은 `seat.hold.rejected`를 올리는 요청이 선점뿐이라서다. 응답 없는 선점 자체는 원문대로 M11(5xx · 코드 0 — P12)로 센다(허용은 원인 표지 대조에만 쓴다).

### 측정-6 가설 P1~P13 · 반증 조건

**쓰임**(ADR-013 §5.2와 같은 뜻) — **성공**: 측정-7 성공 조건에 든다(판정 불가면 그 방식은 성공 아님) · **유효**: 캠페인 유효 조건(반증·판정 불가면 그 지표로 고르지 않는다) · **설명**: 기록·해석 — 성공·고르기에 쓰지 않는다. 반증은 해당 (방식, 셀)의 **유효 회차 하나라도**로 센다(따로 적은 것 밖).

| # | 가설(예측) | 반증 조건 | 판정 자료 | 판정 불가 | 쓰임 |
|---|---|---|---|---|---|
| P1(가설 1 — 묶임의 크기) | A에서 만료 홀드는 배치 주기 안에서 풀린다 — L_A는 1~15초다(위상이 고르면 평균 ≈ 주기/2 + 틱 시간 · 추정 5초 안팎) · TTL 30초 두 셀의 A W > 0 | L_A < 1초 또는 > 15초(15 = 주기 10 + 틱·표본 여유 5 — 보수적 선택) | M1 · N_exp | 표본 공백 → 회차 무효 | **유효**(W > 0 부분 — G4 양성 대조: 0이면 이 부하가 A의 묶임을 만들지 못한 것) · 나머지 설명 |
| P2(가설 1 — 매진 시간) | TTL 30초 두 셀에서 B·C·D 각각의 T95가 A보다 범위 비겹침으로 짧다 · TTL 5분 두 셀은 겹친다(효과 약 1~2% — 측정-2 표 · 추정) | TTL 30초 셀에서 B·C·D 중 하나라도 겹침 또는 김(그 (방식, 셀) 반증) · TTL 5분 셀에서 비겹침이면 '예측보다 큼' 이름표 | M2 | 창 밖(+∞)이 3회 모두인 방식이 둘 이상 | 설명(고르기는 M2를 측정-8이 직접 쓴다) |
| P3(가설 2 — A) | A의 PR-1·PR-2 부당 실패율은 δ = 1초에서 ≥ 0.5 · δ가 커지면 줄거나 같다 · δ = 20초에서 0 | δ1 < 0.5 · δ20 > 0 · 이웃 δ 칸 사이 비율이 0.1 넘게 늘어남(칸 크기 30~70개의 흔들림 여유 — 보수적 선택) | M3(A) | 탐침 칸이 계획의 절반 미만 → 회차 무효(`probe-incomplete`) | **유효**(δ ∈ {1, 3} 부당 실패가 3회 모두 0이면 탐침이 A의 묶임을 못 잡는다 → G2(b) 갈래를 쓰지 않는다) · 나머지 설명 |
| P4(가설 2 — B·C·D · N4) | B·C·D의 PR-1·PR-2 **판정 409**는 모든 δ에서 0이다(회수 H2~H5가 매수 판정 H6 앞 — §3.1) | 판정 409 ≥ 1(분류 불가 409 포함 — M3) | M3ᵣ | 같음 | **성공**. 배치 잠금 409(M3ᵦ)는 이 문턱에 넣지 않지만 **버리지 않는다**(리뷰 MR-5): 실제 사용자 실패라 M3 합(G2(b) — X 쪽 부당 실패율)에 그대로 들고, 측정-8 2단의 사용자 실패 지표로 순위에 쓴다 |
| P5(가설 3 — C · N6) | C 배치는 활성 홀드가 있는 좌석을 되돌리지 않는다 — 기회가 있어도 위반 0 | `active_not_held` > 0인 표본 ≥ 1 · 끝 '활성 홀드 있는데 `HELD` 아님' > 0 | M4 | **되돌림 기회 0**(3회 합) — 0이 '막았다'가 아니라 '일이 없었다'일 수 있다 → C 성공 아님(사유 기록) | **성공** |
| P6(가설 3 — D · N6) | D 배치는 좌석 행을 쓰지 않는다 | (리뷰 MR-12 — 초안 '≠ 확정 성공 수'를 고침) 다음 중 하나: ① 창 식 위반 — Δ₁ = `n_tup_upd`(product_seat, 직후 − 직전)가 [DB 확정 성공 수, 확정 시도 수] 밖(확정 시도 수 = k6가 보낸 확정 요청 수 · 코드 0 포함 — 앱에 닿았을 수 있다) ② 배치 쪽 증거 위반 — Δ₂ = `n_tup_upd`(product_seat, 수렴 대기 끝 − 직후) > 0(그 구간에는 업무 요청이 없고 D 배치 · 대조만 돈다 — 대조는 좌석 행을 쓰지 않는다 · 추정 `[구현 검증]` #18). **식이 이 꼴인 이유(외부 지식 — PostgreSQL 통계 수집기 동작 · 이 저장소 재현은 #18)**: `n_tup_upd`는 롤백된 트랜잭션의 UPDATE도 센다(커밋 여부와 무관하게 '시도한 행 연산'을 센다) — 그래서 등식이 아니라 '≤ 확정 시도 수'다. 또 백엔드는 쌓인 수를 트랜잭션이 끝나 쉴 때 내보내고 짧은 간격 안에서는 미루므로(경합이 있으면 더 늦어질 수 있다) 반영이 늦을 수 있다 — 그래서 '직후'를 업무 요청 연결이 10초 넘게 쉰 뒤에 찍는다(측정-3.6) | M4 · 테이블 통계 | 테이블 통계 결측 → 회차 무효 · 스모크에서 반영 지연이 '직후' 뒤까지 남는 것이 보이면(#18) 시작 전에 대기 길이를 늘린다(log 1행) | **성공** |
| — | (가설 3 — A · B) | A: 앱 1대라 구조상 생기지 않는다(§설계-5 — 이번 측정 밖 · 측정-10-1) · B: 주기 배치 없음 | — | — | 기록 |
| P7(N2 — 이중 판매 0) | 이 부하에서 어느 방식에서도 이중 판매가 관측되지 않는다 — 남의 만료 홀드 위 재선점이 실제로 일어나도 · PR-3 API 계약 양성 대조에서 'u 확정 200 + v 선점 201'이 없다. **주장 범위(리뷰 MR-8)**: 끝 · 표본 · 진행 중 재구성 · API 계약 수준까지다. 시각 역전 경합(만료 전 시작한 확정 대 만료 뒤 재선점)의 0은 이 가설이 주장하지 않는다 — 동시성 테스트(§설계-9 N2 · §설계-10 #12) green이 캠페인 시작 조건(측정-9 ⑦)으로 보장한다 | M5 ①~⑤ 합 ≥ 1 | M5 | B·C·D에서 남의 만료 홀드 위 재선점 0(3회 합 — 이 부하가 그 경로를 만들지 못함) · PR-3 실행 < 계획 | **성공**(B·C·D) · A는 기록(A의 위반은 ADR-015 몫 — 측정-8 G2(c)) |
| P8(N3·N5 — 카운터 정합) | 회수·배치·대조가 함께 돌아도 끝 불일치 0 · `repaired` 0 | M6 끝 불일치 > 0 · `repaired` > 0 · `v_over_limit_users` > 0 | M6 · 판정기 | 생존 0(증분 틱 또는 안전망 실행 없음) → 회차 무효(하네스 — 대조가 돌지 않았다) | **성공** |
| P9(N8 — 만료 뒤 확정 거부 · API 계약) | B·C·D는 만료 뒤 보낸 확정을 받지 않고, 만료 전에 보낸 확정은 받는다. **주장 범위(리뷰 MR-8)**: 보낸 시각 기준의 API 계약(PR-3 — API 계약 양성 대조)만이다. 만료 경계를 넘나드는 트랜잭션 겹침은 동시성 테스트 몫(측정-9 ⑦) | δc ∈ {+0.3, +1.0} 확정 200 ≥ 1 · **어느 회차든** δc ∈ {−1.0, −0.3} 확정 중 200이 아닌 것 ≥ 1(송신 오차 초과 탐침 제외 — 탐침 회차엔 경쟁 부하가 없어 만료 전 확정은 모두 받아야 한다 · 재점검 MR-14) | M7 | PR-3 실행 < 계획 | **성공**(B·C·D) · A 기록 |
| P10(비용 — 선점 경로) | B·C·D의 비용 셀 선점 p99와 DB CPU는 A ≫가 아니다(추가 문장 H1 하위 질의 · H2 · H8 — §3.1 '문장 수') | 비용 셀 M8·M9에서 X ≫ A | M8 · M9 | 비용 셀 유효 < 3 | 설명(교체는 G3이 직접 본다) |
| P11(조회 비용) | 좌석 맵 p99 중앙값은 A ≤ B·C < D(D는 `RESERVED`가 아닌 좌석마다 홀드 탐색 — §3.3) | D가 B·C보다 범위 비겹침으로 낮다 · B 또는 C ≫ A | M8(지도) | — | 설명 |
| P12(안정성) | 모든 유효 회차의 판정 창 안에서(M11 — 리뷰 MR-9) 5xx · 코드 0 · 데드락 · 틱 실패 · `:missingIds` 롤백이 0이고, ADR-010 §9 안정성 ①(Full GC·앱 스레드 멈춤 0) · ②(GC 정지 최대 < 10ms)를 지킨다. ④(앱 메모리 첫 1분 → 마지막 1분 증가 < 10% — docs/adr/ADR-010-jvm-gc-baseline.md §9 안정성 판정 4)는 **비용 셀에만** 쓴다(주 셀은 기록만 — 리뷰 MR-9: 주 셀은 첫 약 40초 몰림 · 잔여석 구간 · 수렴으로 부하 모양이 창 안에서 바뀌어 메모리 추세가 누수를 뜻하지 않는다 — 추정) | M11 하나라도 > 0(측정-9가 환경으로 분류한 정지 제외) · ①② 위반 · 비용 셀 ④ 위반 | M11 · GC 로그 · docker stats | GC 로그 결측 → 회차 무효(`gc-log-missing`) · **A도 같은 (항목, 셀)을 어기면 판정 불가**(측정-7 'A도 같은 문턱') | **성공** |
| P13(B 누적) | B의 남은 만료 행은 창 동안 줄지 않고, 창 끝 값 ≈ (주 회차 이탈 홀드 수 − 재방문 회수 수)다(B는 주기 배치가 없다 — §3.4) | 창 끝 값이 그 계산과 ±10% 넘게 다름 | M12 | — | 설명(장기 누적은 측정-10-4) |

- **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-11) — P7 · P9 판정 불가 'PR-3 실행 < 계획'**: 원문 그대로는 실행할 수 없는 규칙이다('실행'을 무엇으로 세는지 정하지 않았다 — 송신 오차 초과 탐침(3.2)을 빼면 정상 회차도 계획에 못 미칠 수 있고, 그 부족은 이미 `probe-timing-bad`(칸별 10%)가 따로 본다). **실행 = 송신된 판정 요청 수(송신 오차 초과 포함)**로 읽는다. 계획 대비 부족(판정 요청이 안 나간 탐침 · 첫 선점 실패 포함)은 `probe-incomplete`가 그 회차를 무효로 처리한다(측정-9). 그래서 유효 회차에서 '실행 < 계획'이 남는 경우는 첫 선점 5xx(방식 — M11)뿐이고, 그때 P7 · P9는 **판정 불가**다(메모가 아니다 — 측정-7 '판정 불가인 성공 가설이 하나라도 있으면 성공이 아니다').

### 측정-7 성공 조건 (방식마다)

- **B·C·D 성공** = P4 · P7 · P8 · P9 · P12 성립 + C는 P5 · D는 P6 성립 + 아래 상태 기대값 문턱 성립(리뷰 MR-7) + 주 셀 4개 × 3회 · 비용 셀 × 3회가 모두 유효. **판정 불가인 성공 가설이 하나라도 있으면 성공이 아니다**(ADR-012 §6 · ADR-013 §5.4 방식).
- **정합 위반은 1건이라도 실패다** — 이중 판매(M5) · 카운터 끝 불일치 · `repaired`(오탐 복구 포함 — M6) · 한도 초과 · 만료 뒤 확정 성공(M7) · C의 되돌림 위반(M4). 어느 셀 어느 회차든, 1건이면 그 방식은 성공이 아니다. 문턱으로만 쓰고 순위에는 쓰지 않는다(0이 아니면 이미 탈락이다).
  - **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-20)**: 위 목록에 판정기의 좌석 ↔ 확정 짝 두 항목을 더한다 — `v_reserved_without_confirmed`(좌석 `RESERVED`인데 확정 예약 없음) · `v_confirmed_on_unreserved_seat`(확정 예약인데 좌석 `RESERVED` 아님 — 키 이름 LoadtestDataService.kt `consistency`). 확정만 `RESERVED`를 쓰는 규칙은 네 방식이 같아(D도 좌석에 `RESERVED`만 쓴다 — §설계-3) 모든 방식에서 기대값이 0이다. A의 위반은 다른 정합 위반처럼 A 결함(MQ9 — G2(c)에 든다)이다.
  - **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-21)**: 위 목록에 끝 판정기의 `active_on_reserved_seats`(활성 홀드가 있는데 좌석이 `RESERVED` — 상태 표본 `active_on_reserved`와 같은 뜻의 끝 값 · 측정-5 표본 SQL '기대 0(N2)')를 더한다. 네 방식 모두 기대값이 0이라(확정만 `RESERVED`를 쓰고 확정은 그 홀드를 소비한다 — §설계-3) 1건이면 정합 위반이고, A의 위반은 위와 같이 A 결함(MQ9 · G2(c))이다. 표본 값(`active_on_reserved_samples`)은 이미 정합 위반이었고 끝 값만 빠져 있었다. 이 열도 아래 CR14-8의 정합 문턱 열이다(없거나 숫자가 아니면 `end-check-failed`).
  - **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-8)**: 정합 문턱(위 목록 · 아래 상태 기대값 문턱 · P6 아래 끝)에 쓰는 판정기 열이 하나라도 없거나 숫자가 아니면 그 회차는 0으로 채우지 않고 무효(`end-check-failed` — 측정-9 ADR-009 사유)다.
- **상태 기대값 문턱(리뷰 MR-7)** — §설계-8.3에서 그 방식의 기대값이 0인 칸은 모두 성공 조건이다. 끝 판정기(수렴 뒤) 값과, 상태 표본에 같은 열이 있으면 판정 창의 모든 표본이 0이어야 한다. 1건이라도 있으면 그 방식은 성공이 아니다(정합 위반과 같이 문턱으로만 쓴다):

  | 항목(§설계-8.3 행) | 기대 0인 방식 | 자료 |
  |---|---|---|
  | 만료됐는데 `HELD` 저장(5행) | D(옛 데이터 `HELD`는 따로 센다 — 시드가 모두 `AVAILABLE`이라 이 측정에서는 0이어야 한다) | 끝 판정기 · 표본 `held_no_active`(D) |
  | `HELD`인데 홀드 행 없음(6행 · `v_held_without_hold`) | A · D | 끝 판정기 |
  | **활성** 홀드가 있는데 `HELD` 아님(7행) | A · B · C(C는 P5와 같은 자료 — **C의 7행은 P5 정합 위반으로만 판정하고 아래 'A도 같은 문턱' 규칙을 적용하지 않는다** · 재점검 MR-15) | 끝 판정기 · 표본 `active_not_held` |
  | 수렴 뒤 남은 만료 홀드 행(8행) | A · C · D | 끝 판정기 · 수렴 뒤 `expired_rows` |
  | (1~4행 — 이중 판매 · cnt · 한도 · 활성 홀드 > 1) | 모든 방식 | 이미 M5 · M6 · P8의 정합 문턱 |

  기대값이 0이 아닌 칸은 이 문턱이 아니라 `expect-mismatch`(M12 · 측정-9 — 판정 자료)로만 본다. 한 칸이 두 규칙에 함께 들지 않는다.
- **A도 같은 문턱(리뷰 MR-9)**: P12와 위 상태 기대값 문턱은 A에도 같은 식 · 같은 창으로 계산한다. B·C·D 중 X가 어긴 (항목, 셀)을 **A도 같은 셀의 유효 회차에서 어겼으면**(상태 기대값 문턱은 A의 기대값도 0인 칸만 견준다 — 6·7·8행. D의 5행처럼 A 기대값이 0이 아닌 칸은 A와 견주지 않는다), 그 항목은 '하네스·조건 원인 → **판정 불가** · 사용자 보고'로 분류하고 X의 방식 실패로 세지 않는다. 결과는 '판정 불가'다 — 성공도 실패도 아니고, 기본값 교체 여부는 사용자 보고 뒤 사용자가 정한다(측정-8 '판정 불가 결론' ⓐ). A가 어기지 않은 항목을 X가 어기면 그대로 X의 실패다. **정합 위반(위 둘째 항목 목록 · C의 7행 포함 — 재점검 MR-15)은 이 규칙 밖이다** — A의 정합 위반은 MQ9대로 A 결함(ADR-015 입력)이고, X의 정합 위반은 A와 무관하게 실패다.
  - **측정 전 재해석(2026-10-27 · 코드 리뷰 CR14-30)**: 'A도 같은 셀의 유효 회차에서 어겼나'를 세울 수 없을 때(A의 그 (항목, 셀)이 판정 불가 — P12 입력 없음(`p12_unknown`) · 계산 기록 없음 · A 유효 < 3)는 'A가 어기지 않았다'로 읽지 않는다 — X의 그 위반은 **실패가 아니라 판정 불가**로 분류한다(보수적 처리 · 하네스·조건 원인을 가를 수 없다). 분류 갈래는 위 'A도 어김'과 같아 측정-8 '판정 불가 결론' ⓐ로 간다(결론 '판정 불가 → 사용자 보고'). A와 견주지 않는 칸(D의 5행)과 정합 위반은 그대로다. 구현 `summarize.py` `campaign` — k6/ADR-014/README.md '판정 입력 출처 표'.
- **A는 후보가 아니라 기준이다**: A는 기존 기본값이라 '성공'을 따지지 않는다. 대신 주 셀 · 비용 셀 모두 유효 3회가 있어야 비교가 성립한다(G4). A에서 정합 위반이 나오면 지금 기본값의 결함으로 기록하고 ADR-015(확정 원자성)에 넘긴다 — A 확정·배치는 이번에 바꾸지 않는다(Q3).
- 설명 가설(P1 · P2 · P3의 설명 부분 · P10 · P11 · P13)의 성립·반증·판정 불가는 성공에 쓰지 않는다.

### 측정-8 고르는 규칙 · 기본값 교체 조건

**후보 줄 세우기**(성공한 B·C·D만 — 명세 순서 '정합 → 판매 효과 → 비용 → 단순성'):

| 단 | 지표 | 빠지는 규칙 |
|---|---|---|
| 1 정합 | 측정-7 정합 항목 | 문턱만 — 위반이 있으면 이미 후보가 아니다. 이 단은 아무도 빼지 않는다 |
| 2 판매 효과 · 사용자 실패 | M2 T95 — 주 셀 4개(a20-t30 · a50-t30 · a20-t300 · a50-t300) · **M3ᵦ 배치 잠금 409 비율 — TTL 묶음 2개(t30 · t300)**(리뷰 MR-5) | 후보 X는, 다른 후보 Y가 이 단의 어느 (지표, 셀·묶음)에서 X보다 범위 비겹침으로 낫고(T95는 짧고 · M3ᵦ는 낮고) 어느 (지표, 셀·묶음)에서도 범위 비겹침으로 나쁘지 않으면 빠진다 — 두 지표를 한 지배 관계로 함께 본다(기존 T95 규칙과 같은 꼴). M3ᵦ는 **범위가 겹치지 않을 때만** 순위에 영향을 준다(예: C [0–2] 대 B·D [0–0]은 겹침 → 영향 없음) |
| 3 비용 | 비용 셀 선점 p99 · 비용 셀 DB CPU · a50-t300 선점 201 p99 · a50-t300 확정 p99 · a50-t300 지도 p99 · a50-t300 DB CPU | X는, Y가 어느 지표에서 X보다 실질적으로 낮고(X ≫ Y) 어느 지표에서도 Y ≫ X가 아니면 빠진다 |
| 4 단순성 | 고정 순서 **D > B > C** | 남은 후보 중 앞선 것 |

- **동률**: 한 단에서 아무도 빠지지 않으면 다음 단으로 간다. 4단은 동률이 없다.
- **순환**: 빠지는 관계가 돌아 아무도 남지 않으면, 순환에 든 후보만 남겨 다음 단으로 넘긴다(ADR-013 §5.5 S5-12 규칙 그대로).
- **순위**: 후보가 빠진 단이 늦을수록 앞이다. 같은 단에서 빠진 후보끼리는 4단 순서로 정한다. 그래서 성공한 후보 전부에 순위 1, 2, 3이 정해진다.
- **왜 W(M1)가 순위에 없나**: B·C·D는 정의상 묶임이 0이다(저장 상태로 거절하지 않는다 — §설계-2.1). W는 A의 비용을 재는 원인 지표다 — P1과 G4(양성 대조)에 쓴다.
- **왜 2단에 탐침은 M3ᵦ만 있나**(리뷰 MR-5 — 초안 '탐침 없음'을 고침): 판정 409(M3ᵣ)는 성공 조건(P4)에서 이미 모두 0이다. 배치 잠금 409(M3ᵦ)는 P4 문턱 밖이지만 사용자가 자기 좌석을 못 잡는 실제 실패라 매진 시간과 같은 단(사용자에게 보이는 결과)에 둔다. 구조상 C에서만 생긴다(B는 주기 배치가 없고 · D 배치는 좌석 행을 잠그지 않는다 — §3.4) — 분류 불가 409는 M3ᵦ가 아니라 판정 409로 센다(M3).
- **왜 3단은 범위 비겹침 + 10%인가**: 범위 비겹침만 쓰면 범위가 좁을 때 +2% 같은 작지만 일정한 차이로 빠진다 — 허용선(+10%)이 허락하려던 크기다. +10%만 쓰면 흔들림이 큰 지표에서 우연으로 빠진다. 둘 다 = '실제로 있고 크기도 의미 있는' 차이다. 지도 p99는 a50-t300 한 셀만 쓴다 — 남은 만료 행이 가장 많이 쌓여 B·D 조회 비용 차이가 가장 크게 드러나는 셀이다(추정).
- **왜 단순성이 D > B > C인가**(측정 전 고정): 셈 = 저장 `HELD` 힌트를 쓰거나 고치는 경로 수 + 주기 배치 유무 — D 0 + 1 = 1(힌트 없음 · 배치는 행 + 카운터만) · B 1(H10) + 0 = 1 · C 3(H10 · H4b · C6) + 1 = 4. B와 D가 같으면 '남은 만료 행이 시간에 비례해 쌓이지 않는다'(D — 배치가 지움 · B는 쌓임 — P13)로 D를 앞에 둔다. **전제 조건(리뷰 MR-7)**: 'D 배치가 지움'은 D의 '수렴 뒤 남은 만료 홀드 행 = 0'(측정-7 상태 기대값 문턱)이 성립할 때만 근거가 된다. 4단에 닿은 D는 성공 후보라 이 문턱을 이미 통과했다(문턱을 못 넘었거나 판정 불가인 D는 성공이 아니라 4단에 오지 않는다). 결과 문서에는 이 전제가 성립한 자료(D의 수렴 뒤 `expired_rows` · 끝 판정기 8행)를 함께 적는다.

**기본값 교체 관문**(순위 차례로 G1~G4를 대 보고, 처음 모두 통과한 후보가 새 기본값이다 — 명세 §0 '이기면 기본값 교체'):

| 관문 | 조건 |
|---|---|
| G1 | 성공(측정-7) |
| G2 이긴다 | 다음 중 하나: (a) M2 T95가 주 셀 하나 이상에서 A보다 범위 비겹침으로 짧고, 어느 셀에서도 범위 비겹침으로 길지 않다 · (b) M3 탐침 부당 실패율(TTL 묶음별 δ 전체 합)이 한 묶음 이상에서 A보다 범위 비겹침으로 낮다 — **P3 유효일 때만** · (c) A에 정합 위반(측정-7 목록)이 있고 X에는 없다 |
| G3 비용 | 선점 경로 지표 — 비용 셀 선점 p99 · 비용 셀 DB CPU · a50-t300 선점 201 p99 · 확정 p99 · DB CPU — 에서 X ≫ A가 하나도 없다. **평가 전 조건**: DB CPU를 쓰는 셀의 회차는 `db-cpu-missing`(M9 — 리뷰 MR-3)이 아니어야 한다(결측 회차는 G3 평가 전에 무효 → 재측정). a50-t300 DB CPU는 측정-5 머리의 표본 비용 기준으로 분리되면 이 목록과 3단에서 빠진다(리뷰 MR-1). **지도 p99는 관문에 넣지 않는다**(MQ2): A의 지도는 배치 전 좌석을 `HELD`로 보이는 다른 의미라 싸다. 조회 최적화는 ADR-018 몫이다. 대신 결과 문서에 X ÷ A 배수를 적는다 |
| G4 캠페인 유효 | A의 모든 셀 유효 3회 · P1의 'TTL 30초 두 셀 A W > 0'(3회 모두) 성립 · 탐침·표본 계획 대 실행 일치 |

- **교체가 성립하면**: `seat.hold.expiry-mode` 기본값 = X · V11 위치(`db/migration-seat-map`)를 기본 위치 목록에 더함(§설계-7 · Q8)을 **별도 커밋**으로 한다(ADR-012 §3 '기본값 교체는 판정 뒤 별도 커밋' — docs/adr/ADR-012-incremental-reconcile.md:160). 기존 DB 전환은 ①(멈추고 바꾸기) + 정리 T1·T2 + 확인 3항목만이다(§설계-7 · Q12 · ADR-013 §7.3 원칙). 옛 방식(A)은 손잡이로 되돌릴 수 있게 둔다.
- **A가 남는 조건**(하나라도면 기본값 `sweep` 그대로): ① 성공한 후보가 없다 ② 성공한 후보가 모두 G2를 못 넘는다 — 방식 차이가 판매·사용자 결과로 드러나지 않았다 ③ G2를 넘은 후보가 모두 G3에서 걸린다 — 이득보다 선점 경로 비용이 크다 ④ G4 실패 — 측정 전 규칙으로 고를 수 없다(판정 불가 · 사용자 보고 · NEXT 후보) ⑤ 캠페인이 하네스 결함으로 'superseded'되고 재측정을 마치지 않았다. ①~④에서도 결과 문서는 방식별 실패 항목 · 원인 후보를 적는다(무음 금지).
- **'A 유지'도 '방식 실패'도 아닌 판정 불가 결론**(리뷰 MR-9 · MR-10): ⓐ 측정-7 'A도 같은 문턱'으로 판정 불가가 된 항목 때문에 후보가 성공을 얻지 못했다 ⓑ `env-stall`(환경) 재측정 상한(측정-9)을 다 써 유효 < 3인 자리가 남았다. 이때 결론은 **'판정 불가 → 사용자 보고'**다 — 추가 재측정 · 조건 수정 여부는 사용자가 정한다. 기본값 설정은 사용자 결정 전까지 손대지 않지만(sweep 그대로), 결과 문서는 이를 'A 유지(A가 이겼다)'나 그 방식의 실패로 적지 않는다. 다른 사유(하네스)의 상한 소진은 이 갈래가 아니라 기존 규칙(측정-9 재측정 · 위 ④)을 따른다.
  - **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-18) — ⓒ G3 판정 불가**: G1 · G2 · G4를 넘었고 G3에서 X ≫ A인 지표는 없는데 비용 지표 범위를 낼 수 없어(유효 회차의 값 None 등) G3가 판정 불가인 후보는 'G3에서 걸렸다'가 아니다. 그런 후보만 남아 관문을 넘은 후보가 없으면 결론은 위 ③(A 유지)이 아니라 **'판정 불가 → 사용자 보고'**다. 관문을 넘은 후보가 있어도 그보다 앞 순위에 G3 판정 불가 후보가 있으면(그 후보가 이겼을 수 있다) 같은 결론이다(잠정 결과를 함께 적는다 — ⓐ·ⓑ와 같은 처리). G3에 걸린 지표가 있는 후보는 판정 불가 지표가 함께 있어도 ③ 그대로다.
  - **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-24) — ⓓ 고르는 규칙 판정 불가**: 3단(비용)에 남은 후보가 둘 이상인데 어느 (지표, 후보)의 3회 범위를 낼 수 없으면(유효 회차의 값 None 등) 3단 결과는 **판정 불가**다 — 범위를 낼 수 없는 지표를 'X ≫ Y 아님'으로 읽어 빠질 후보를 남기지 않는다. 2단(T95 · M3ᵦ — M3ᵦ 분모 0 포함)도 같은 꼴로 본다(요약기 판정 입력 전수 점검). 후보 줄 세우기는 잠정 결과로 그대로 내고, **관문을 넘은 후보 말고도 관문을 넘었거나 넘었을 수 있는(ⓒ) 후보가 있으면** 그 후보가 앞 순위였을 수 있으므로 결론은 ⓐ·ⓑ·ⓒ와 같은 **'판정 불가 → 사용자 보고'**(잠정 결과를 함께 적는다)다. 다른 후보가 모두 관문에서 걸렸으면 순위와 무관하게 관문을 넘은 후보가 새 기본값이다. 같은 꼴로: G2 (a)·(b)를 셀 범위가 없으면(유효 회차의 값 None) G2는 '못 넘음'이 아니라 판정 불가이고, G3에 걸린 지표가 없으면 ⓒ와 같은 갈래로 본다(A 유지 ②는 G2가 모두 확정 실패일 때만). 측정-5 머리의 표본 비용 분리 여부를 정할 수 없으면(Δc 또는 A의 a50-t300 DB CPU 범위 없음) a50-t300 DB CPU가 3단 결과나 G3 걸림을 가를 때 그 단·관문은 판정 불가다(분리하지 않은 것으로 읽지 않는다).
- **주의(측정 전에 밝혀 둔다)**: G2(b)는 P3가 서면 사실상 늘 A가 진다 — A의 저장 `HELD`는 배치 전까지 재선점을 막는 것이 설계이기 때문이다(§설계-1). 그래서 P3가 서는 캠페인에서 교체 여부는 사실상 G1(정합)과 G3(비용)이 정한다. 이것이 '이기면 교체'의 뜻에 맞는지는 MQ3에서 사용자가 확인한다.

### 측정-9 회차 유효 조건 · 재측정 규칙

**캠페인 시작 조건**(측정 전 1번 — 각 log 1행):

0. 하네스 동결: `--sha`를 git archive로 동결하고 해시를 plan.json에 남긴다. 이미지 ID를 고정한다(ADR-013 CR13-21 · DS-11 선례).
1. .164 스모크: 네 방식 × a50-t30 1회 + A·D × 비용 셀 1회 — 모두 상태 ok(단 `env-stall`(MR-6 조건을 모두 만족한 환경 정지)만으로 무효인 회차는 시작을 막지 않고 아래 빈도 자료로 센다 — 그 밖의 무효 사유는 시작을 막는다 · 재점검 MR-16) · 탐침·표본 계획 대 실행 일치. 스모크 결과는 판정에 쓰지 않는다.
2. **캠페인 스모크(ADR-013 교훈 — 루프 stdin)**: `campaign.sh`로 **두 자리 이상**을 돌려 두 자리가 모두 실행됐는지 본다. ADR-013의 첫 캠페인은 회차 루프(`while read … <<< "$ORDER"`) 안의 run.sh(ssh·docker)가 표준 입력을 읽어 남은 29줄을 먹고 1회만 돌았다. run.sh 단독 스모크와 dry-run으로는 못 잡았다(docs/adr/ADR-013-counter-config-cutover.md §6.1 '대체된 캠페인'). 루프 안 run.sh는 `< /dev/null`로 부르고, 끝 집계 전에 미실행 자리가 있으면 멈춘다.
3. dry-run: 60자리 계획 · 순서 · 예상 시간 · 탐침 일정(모든 셀에서 마지막 탐침 요청 ≤ t0 + D_arr − 5초 — **탐침 경계 교훈**: ADR-013은 사건 실제 길이를 넣자 계획 칸 안 탐침이 경계로 빠졌다 · §5.8-0). 여기서는 탐침이 사건이 아니라 서버 만료 시각 e에 묶이므로, 경계 위험 = '창 끝을 넘는 탐침'과 '시계 오차 > δ'다.
4. k6 PC 점검: 측정과 무관한 컨테이너·재시작을 되풀이하는 프로세스가 없다(`docker ps` 기록 — ADR-011 §6.1: PC의 `text-es`가 약 24초마다 기동 실패를 되풀이해 꼬리 지표를 오염시켰다).
5. 측정-11 `[구현 검증]` 결과(#15~#25) — 특히 지도 응답 크기·PC 수신량 · 상태 표본 질의 시간 · 앱−DB 시계 차. 그리고 스모크로 잰 다음 값을 log에 남긴다:
   - (리뷰 MR-1) 방식별 표본 질의 시간 · 표본 CPU 몫 c_X · Δc · R₀와 측정-5 머리 기준의 적용 결과(그대로 · 표본 방식 변경 · 분리).
   - (리뷰 MR-10) **이 구성(lb 없음 · 앱 1대)의 환경 정지 빈도** — 스모크 전 회차(네 방식 × a50-t30 + A·D × 비용 셀 = 6회)에서 `env-stall`로 분류된 덩어리 수 · 그런 회차 수 ÷ 6. 판정에는 쓰지 않는다. 캠페인의 재측정 예상 시간과 아래 환경 상한 소진 가능성을 사용자 보고에 넣는 자료다.
   - (리뷰 MR-11) 흐름별(맹목 · 지도 · 재방문 · 탐침) k6 VU 최대(`vus` 최대 · 흐름별 시나리오 태그). maxVUs = 흐름마다 max(14,000, ⌈VU 최대 × 1.5⌉)로 고정한다(S3 선례 maxVUs 14,000 — s3-full-flow.js:33 · 스모크는 a50-t30만이라 TTL 5분 칸의 긴 생각 시간에서 VU가 더 클 수 있다 — 추정). 스모크에서 판정 창 안 `dropped_iterations` > 0이 하나라도 나오면 maxVUs를 올려 다시 스모크한다.
6. 시작 전 1번만 바꿀 수 있는 값: 스모크 결과로 D_arr(T95_추정이 D_arr의 60% 초과면 D_arr′ = ⌈T95_추정 ÷ 0.6 ÷ 60⌉ × 60초로 늘림 — 측정-2 · 리뷰 MR-13) · 지도 흐름 속도(PC 수신 > 50MB/s면 줄임 — 1Gbps의 40%)를 고칠 수 있다. 고친 값과 이유를 log에 남긴다. **시작 뒤에는 고정**이다.
7. **동시성 테스트 green(리뷰 MR-8)**: §설계-9 N2의 동시성 테스트(같은 좌석 동시 선점 · 시각 역전 · 시각 역전 + 남의 확정 · D 한 문장 대조)와 §설계-10 #12 교착 점검이 측정 SHA에서 모두 green이다. P7 · P9는 시각 역전 경합을 주장하지 않으므로(측정-6), 그 경합의 이중 판매 0은 이 조건이 보장한다. 측정 지연 손잡이로 부하에서 경합을 만들지 않는다(Q13 — 메인 결정). green이 아니면 캠페인을 시작하지 않는다.

**회차 사유**:

| 사유 | 분류 | 처리 |
|---|---|---|
| ADR-009 회차 합격 사유 중 S3e·S3c·C·F 전용이 아닌 것(`pre-k6-mismatch` · `pre-k6-overrun` · `gc-log-missing` · `cond-config-mismatch` · `drift-snap-failed` · `container-log-missing` 등 — k6/ADR-009/README.md '회차 합격' 표) | 하네스 | 무효 → 재측정 |
| `expiry-mode-mismatch` · `ttl-mismatch`(configprops `ttl`·`expiry-interval` ≠ 셀) · `seat-map-index-missing` · `seed-mismatch` · `crn-mismatch`(3.5) | 하네스 | 무효 → 재측정 |
| `state-sampler-gap` — 판정 창에서 상태 표본 간격 > 3초가 한 번이라도(t0 → 첫 표본 · 마지막 표본 → 창 끝 포함 — 리뷰 MR-4) · 표본 수 < 창 초 × 0.95 | 하네스 | 무효 → 재측정(M1·M2를 계산할 수 없다) |
| `db-cpu-missing` — 그 셀 창 안 DB 컨테이너 docker stats 행 < ⌈창 초 ÷ 15⌉ · 공백(양끝 포함) > 30초(M9 — 리뷰 MR-3) | 하네스 | 무효 → 재측정 — **G3 · 3단 평가 전에** 가른다(결측 회차의 DB CPU로 비용을 판정하지 않는다) |
| `dropped-iterations` — 판정 창 안에서 흐름(맹목 · 지도 · 재방문 · 탐침) 하나라도 k6 `dropped_iterations` > 0(리뷰 MR-11 — summarize.py:206-231이 읽는 같은 CSV 행) | 하네스 | 무효 → 재측정(도착 모형이 계획과 다르다 · 공통 난수 열도 갈린다) |
| `probe-timing-bad` — 송신 오차 허용(3.2)을 넘어 뺀 탐침 비율이 어느 (탐침 종류, δ·δc) 칸에서 > 10%(리뷰 MR-2) | 하네스 | 무효 → 재측정 |
| `probe-incomplete` — 계획된 탐침 요청이 나가지 않음 · 탐침 기록 수 ≠ 계획 · 탐침 칸이 계획의 절반 미만 | 하네스 | 무효 → 재측정 |
| 탐침 요청은 나갔는데 응답이 5xx·코드 0 | **방식** | 유효 — 그 탐침은 분모에서 빠지고 M11로 센다(P12 반증). ADR-013 §5.8-5 `probe-short-by-procedure`와 같은 뜻: 방식이 만든 부족을 무효로 숨기지 않는다 |
| `clock-offset-bad` — 회차 앞 또는 뒤 시계 차 표본 왕복/2 > 20ms · 앞뒤 차 > 100ms · 표본 없음 | 하네스 | 무효 → 재측정 |
| `table-stats-missing` · `app-metric-missing`(측정-3.6 앱 지표 — 회수·되돌림 기회·NOWAIT 태그) | 하네스 | 무효 → 재측정 |
| `env-stall` — 판정 창에서 정지 초(완료 응답 0인 초)가 5초 이상 이어진 덩어리가 있고, **같은 구간에** 서버 측정기(`GET /actuator` — DB를 거치지 않는다)도 5초 넘게 응답이 없고, 앱 GC 정지 최대 < 100ms(GC가 설명하지 못함) **그리고 DB 쪽 원인 없음**(리뷰 MR-6 — 아래 셋 모두): ① 그 구간의 0.5초 대기 표본(lib.sh:153 `db_sampler` — 서버 쪽 루프라 앱이 멈춰도 돈다)마다 업무 세션(`by_src`의 `app|` 갈래 — ApplicationName=seatlab-app · k6/ADR-009/compose.yml:21·71)의 `Lock:*` 대기 수 = 0 ② Hikari `pending` = 0 ③ Hikari `active` < 최대(`hikaricp.connections.max` — run.sh:543 `app-config.json`). ②③은 1초 Hikari 표본(run.sh:637-641)을 쓴다 — 이 표본은 앱 actuator를 거쳐(`curl --max-time 2`) 앱이 멈춘 구간에는 빌 수 있으므로, 구간 안 표본 + 구간 바로 앞 · 바로 뒤 표본 각 1개로 본다. ①의 표본이 구간에서 빠졌거나(공백 > 1초) ②③의 앞 · 뒤 표본이 없으면 'DB 쪽 원인 없음'을 세우지 못한 것이다 | 하네스(환경) | 무효 → 재측정(환경 상한 — 아래 '재측정'). 근거: ADR-013 §6.6-6 환경 정지 덩어리(약 11~13초 연속 정지 · S4c 31회 중 9회 · 앱 지표 표본 13.5초 빔 · DB 표본 정상 · GC 0 · 원인 미확인). 필요한 표본 수집(업무 세션 갈래 · 앞뒤 Hikari)은 `[구현 검증]`(측정-11 #23) |
| 정지 덩어리인데 서버 측정기는 응답했다(앱은 살아 있고 DB 경로가 멈춤) · 또는 GC가 설명한다 · **또는 DB 쪽 원인 없음 ①②③ 중 하나라도 어기거나 세우지 못했다**(리뷰 MR-6) · 또는 가르지 못했다 | **방식** | 유효 — **방식 정지**로 두고 유효 회차의 실패로 센다(P12 — M11 정지 초). 실패를 숨기지 않는 쪽(ADR-013 §5.3 '원인을 가르지 못한 `transition-failed` → 절차로 본다'와 같은 규칙) |
| 정합 위반 · 5xx · 데드락 · 틱 실패(측정-7 · P12) | **방식** | 유효 실패 회차 — 재측정하지 않는다 |
| `expect-mismatch`(M12 — §설계-8.3 기대값과 다름 · **기대가 0인 칸은 제외**: 그 칸은 측정-7 상태 기대값 문턱이 방식 실패로 판정한다 — 리뷰 MR-7) · `reject-cause-unknown`(M3 — 리뷰 MR-5) · `probe-timing-dropped`(3.2) · 1초 p99 최대 · 느린 초 | 판정 자료 | 회차 사유 아님 — 결과 문서에 원인 · 수를 적는다 |

- **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-9 · CR14-10 · CR14-14) — 사유 표의 입력 검사를 좁힌다(새 무효 경로 · 원문 사유 이름 그대로 또는 하나 더함)**: `clock-offset-bad`의 '표본 없음'에 앞뒤 표본의 `rtt_s`가 없거나 음수인 경우를 넣는다(0으로 채우면 왕복/2 문턱을 통과한다). `probe-incomplete`의 '계획된 탐침 요청이 나가지 않음'에 **첫 선점(PR-1 h0 · PR-2 h0a·h0b · PR-3 u0)이 5xx가 아닌 이유로 실패해 판정 요청이 나가지 않은 탐침**을 넣는다(원인은 `probe-anomaly` 이름표로 남긴다 — 첫 선점 5xx는 원문대로 방식 M11). 새 사유 `warmup-failed`(하네스 — 무효 → 재측정): 예열 k6(측정-3.6 `warmup14.js`)가 rc ≠ 0이거나 그 기록이 없다 — 데워지지 않은 앱이 부하를 받는다(측정-9 '차가운 JVM'). 예열 요청의 이름:상태 분포를 회차 폴더에 남긴다.
- **측정 전 재해석(2026-10-26 · 코드 리뷰 CR14-22 · CR14-23) — `probe-incomplete`에 판정 탐침 쪽 두 경우를 넣는다**: ① **첫 선점 기록 행 자체가 없는 탐침**(PR-1 h0 · PR-2 h0a·h0b · PR-3 u0 행 없음) — 홀드가 섰는지 모르는 채 판정 요청을 정상 탐침처럼 판정하지 않는다(CR14-10의 '첫 선점 실패'와 같은 '판정 요청이 판정 가능한 조건에서 나가지 않음'). ② **판정 불가 응답** — 판정 요청은 나갔는데 응답이 5xx·코드 0 밖의 예상 밖 값(PR-1·2 h1 ∉ {201, 409} · PR-3 다른 사용자 선점 v ∉ {201, 409} · 확정 c ∉ {200, 409} — 400 · 404 등)인 탐침. 그 탐침은 M3 · PR-3 분모에서 빼고, 하나라도 있으면 그 회차는 `probe-incomplete`(하네스 — 무효 → 재측정)다. 원인은 `probe-anomaly` 이름표로 남긴다. **근거**: 사유 표는 나간 탐침 요청의 응답을 '5xx·코드 0 → 방식(M11 · P12)'만 정했고 나머지 예상 밖 응답의 갈래가 없다. 그 응답은 '판정 409인가 201인가'를 셀 수 없으니 판정 탐침 기록이 계획만큼 서지 않은 것('탐침 기록 수 ≠ 계획')으로 읽는다 — 새 사유 이름이 필요 없고 CR14-10과 같은 꼴이다. P12(5xx · 코드 0)로 세면 원문 P12 정의를 넓히게 된다. 방식이 실제로 400·404를 내는 결함이라도 숨지 않는다: 재측정 2번 뒤에도 무효면 그 (방식, 셀)은 유효 < 3 → 판정 불가 → 성공이 아니다(아래 '재측정').
- **측정 전 재해석(2026-10-28 · 스모크 — 사용자 결정)**: 위 ②의 확정 c 예상 값에 **404 HOLD_NOT_FOUND**를 더한다(확정 경로의 404는 이것뿐). 배치·sweep이 만료 홀드 행을 확정보다 먼저 지우면 정상적인 '만료 뒤 확정 거부'가 404로 나온다 — 스모크 A a50-t30에서 δc > 0 탐침의 약 7%. 그래서 δc > 0의 404 = 거부(P9의 '만료 뒤 확정을 받지 않는다' 쪽), δc < 0의 404 = 만료 전에 홀드가 지워진 것 = 실패(P9 반증 — '만료 전 확정 중 200이 아닌 것'). 400 등 그 밖의 값은 ② 그대로 판정 불가 응답.
- **측정 전 재해석(2026-10-26 · 요약기 판정 입력 전수 점검 — CR14-21~24 재발 방지)**: 판정 입력이 빠지거나 예상 밖 값이면 0 · 정상으로 처리하던 경로를 모두 무효 또는 판정 불가로 바꾼다(원문 사유 이름 그대로 · 둘 더함 — 입력 출처와 처리는 k6/ADR-014/README.md '판정 입력 출처 표'가 단일 출처). 새 사유(하네스 — 무효 → 재측정): `k6-window-missing`(`k6-window.json` 없음 · rc 숫자 아님 — k6 rc를 모른다) · `harness-reasons-missing`(`harness-reasons.txt` 없음 — 하네스 사유를 모른다). 기존 사유로 가는 것: 상태 표본 · 대기 표본의 깨진 줄(찢긴 마지막 줄 하나 제외)과 판정 열 결측(`state-sampler-gap` · `waits-gap`) · docker stats DB·앱 줄을 못 읽음(`db-cpu-missing`) · `drift_log` 행의 시각·action을 못 읽음(`drift-db-failed` — 버리면 `repaired`가 줄어 정합 위반이 숨는다) · 탐침 기록을 못 읽는 행 · 계획에 없는 칸 · 기록 없는 계획 칸(`probe-incomplete`) · 주 흐름 홀드·확정 기록을 못 읽는 행(`k6-csv-missing`) · 스냅숏 · 시드 확인 · 키 확인의 판정 열 결측·숫자 아님(각 원문 사유). env-stall 입력(대기 표본 `by_src` · Hikari 표본)이 깨졌으면 'DB 쪽 원인 없음'을 세우지 못한 것이다 → 방식 정지(위 env-stall 행 그대로). 캠페인 집계는 유효 회차 요약에 판정 열이 빠졌으면 그 회차를 유효로 세지 않고(→ 유효 < 3 → 판정 불가), 계산 기록이 없는 P12 항목은 0이 아니라 판정 불가로 본다. crn 합이 없는 유효 회차는 `crn-mismatch`(3.5 — 같음을 세우지 못함), 조기 확인에서 A의 W·δ별 결과가 없으면 멈춘다.
- **측정 전 재해석(2026-10-29 · 스모크 — 사용자 결정) — PC 링크 끊김 = 환경 무효(새 사유 둘)**: `pc-link-down`(**하네스(환경)** — 무효 → 재측정 · **환경 상한**: 아래 '재측정'의 `env-stall` 몫과 같은 몫을 함께 쓴다 — 자리마다 환경 2번): 회차 시작 ~ 끝 사이 k6 PC 커널 로그(`journalctl -k`)에 `eno1: Link is Down` 줄이 1건 이상. 다른 사유와 함께 나와도 환경으로 가른다(끊김이 만든 결과로 본다 — `env-stall`은 원문대로 그것만일 때 환경). `pc-link-missing`(하네스 — 무효 → 재측정): 그 로그를 못 읽음(rc ≠ 0 · 권한 경고 · 이번 부팅 커널 줄 0) · 기록 없음 · 수집 구간이 판정 창을 덮지 않음 — 판정 입력 결측 규칙(위 전수 점검 행 · README 출처 표)대로 '끊김 없음'으로 넘기지 않는다. **근거**: 10-28 첫 스모크 A a50-t30(k6/ADR-014/results/smoke14-20261028)이 PC 유선 eno1 끊김(16:45:56~16:46:16 — 원인은 랜선 · 10-28 21:18 교체)으로 `db-cpu-missing` · `dropped-iterations` · `path-gap` · `probe-incomplete` · `probe-missing`이 함께 나며 무효가 됐다(그 사유들이 끊김의 결과다). 그 뒤 스모크는 끊김 시각을 사람이 피했고, 정식 스모크(10-29)는 회차 중 Link Down 0을 확인했다 — 이 확인을 하네스가 회차마다 기록·판정한다. 링크 끊김은 방식과 무관한 측정 환경 사건이라 방식 실패(P12)나 하네스 결함으로 세지 않는다. 읽기는 sudo 없이 된다(이 PC — 2026-10-29 확인 · `k6/ADR-014/scripts/pclink14.sh`).
- **측정 전 재해석(2026-10-29 · 정식 스모크) — 시작 조건 ⑤ `dropped_iterations` 처방**: 원문 '판정 창 안 `dropped_iterations` > 0이 하나라도 나오면 maxVUs를 올려 다시 스모크한다'를 **'preAllocatedVUs(미리 만든 VU) · maxVUs를 올려'**로 읽는다. **근거**: 정식 스모크 a50-t30에서 맹목 흐름이 t0 + 17~20초에 7건을 버렸는데 VU 최대는 약 2,244로 maxVUs 14,000에 닿지 않았다 — 원인은 미리 만든 VU 2,000을 넘는 순간 k6가 VU를 실행 중에 늦게 만들며 그 사이 반복을 버린 것이다(커밋 1ac55ffa — blind 2,000 → 4,000 · map 200 → 400). maxVUs만 올리면 같은 결함이 되풀이된다. maxVUs = max(14,000, ⌈VU 최대 × 1.5⌉) 원문은 그대로다. **TTL 5분 칸은 스모크하지 않았다** — 생각 시간 30–240초 · 재방문 대기 ≤ TTL + 20초로 세션이 VU를 오래 쥐어 동시 반복이 TTL 30초 칸(약 2,128~2,244)보다 클 수 있다(첫 만석 약 40~65초 동안 확정할 세션(a20 약 80% · a50 약 50%)이 생각 시간만큼 남는다 — 추정 수천~1만). **미리 만든 VU 4,000이 충분한지는 캠페인 첫 TTL 5분 회차(회차 1의 a20-t300 첫 자리)에서 조기 확인**한다: 그 회차가 `dropped-iterations`로 무효면 캠페인을 멈추고 값을 정한 뒤 처음부터(측정-9 '캠페인 시작 뒤 하네스 결함' — 측정 경로 수정이면 60회 전체를 새 id로).

- **측정 전 재해석(2026-10-30 · 확인 스모크 — 명세 R5 ②) — 시작 조건 ④ · 시작 대기 · 시작 조건 ⑤ 처방 적용 · k6 PC 메모리(새 사유 둘)**: ① **시작 조건 ④에 'k6 PC 다른 부하 없음'을 더한다** — 사람 기록(`docker ps` · 재시작 루프 없음)에 더해 하네스가 시작 · 이어서 실행 때마다 이 PC를 읽어(`k6/ADR-014/scripts/pccheck14.sh`) seatlab 밖 실행 중 컨테이너 0 · 1분 load ≤ 4.0 · MemAvailable ≥ 16GiB가 아니거나 못 읽으면 시작하지 않는다. 캠페인 동안 다른 프로젝트 부하를 멈추는 것은 사용자 몫이다. 문턱 근거: 이 PC 24코어 · 메모리 39.6GB · k6 주 프로세스 최대 9.84코어 · k6 RSS 최대 10.07GiB(VU 13,300 — 확인 스모크 a50-t300 D) → VU 15,300 비례 외삽 11.6GiB(추정) + 아래 회차 중 하한 4GiB. 확인 스모크의 다른 부하는 load 14~36이었다. ② **새 사유 `pc-mem-low`(하네스(환경) — 무효 → 재측정 · 환경 상한: `env-stall` · `pc-link-down`과 같은 몫)**: 판정 창 안 k6 PC MemAvailable(5초 표본 `timeline-client.jsonl`) 최소 < 4GiB(메모리의 약 10%). 다른 사유와 함께 나와도 환경으로 가른다(메모리 부족이 만든 결과로 본다 — `pc-link-down`과 같은 규칙). **새 사유 `pc-mem-missing`(하네스 — 무효 → 재측정)**: 그 표본이 없음 · 깨짐 · 값 결측 · 공백(창 양끝 포함) > 30초 — 판정 입력 결측 규칙(README 출처 표)대로 '부족 없음'으로 넘기지 않는다. 이름표가 아니라 무효인 이유: 확인 스모크에서 PC 부하 아래 A·B p99가 정식 스모크보다 4~8배 높았다 — 지연 · 정지 지표가 PC 쪽 원인으로 오염된 회차를 유효로 세면 방식 비교가 흔들린다. 회차 앞 PC 점검 결과는 이름표만 남긴다(`pc-busy-before`). ③ **시작 대기(87 ± 5초 — 판정 기준 그대로)의 k6 lead를 미리 만든 VU 수로 잡는다**: lead = 0.15초 + VU × 0.37ms(계획 `k6.lead_s` — 전: 고정 1.2초). 확인 스모크 a50-t300 D(미리 만든 VU 13,300)는 k6 실행 → 첫 요청이 6.61초라 시드 → 첫 요청 93.2초(`pre-k6-mismatch`)였다. 식은 결과 11회(PC 한가함 ≈ 0.30ms/VU · 부하 ≈ 0.43ms/VU의 가운데)와 모두 2.5초 안이다. 판정은 원문대로 실측 t0다. ④ **시작 조건 ⑤ 처방 적용(위 2026-10-29 재해석의 조기 확인을 확인 스모크가 대신했다)**: TTL 5분 칸 흐름별 VU 최대 맹목 9,254 · 지도 583 → 미리 만든 VU 맹목 ⌈9,254 × 1.5⌉ = 13,881 → 14,000(= maxVUs 14,000 = max(14,000, 13,881)) · 지도 875 → 1,000 그대로. 그 실측은 연결 상한 실패가 섞인 회차 값이라(실패 세션이 일찍 끝나 VU를 덜 쥐었거나 응답 대기로 더 쥐었을 수 있다) 상한을 올린 다음 스모크에서 VU 최대를 다시 본다.

- **재측정**: 같은 (방식, 셀, 회차 번호) 자리 최대 2번 — 캠페인 끝에 원래 순서대로 돈다. 결과 표에 'rerun'을 붙이고 앞 회차는 `attemptN/`으로 보존한다(ADR-013 §5.3 · §6.1 선례). 2번 뒤에도 무효면 그 (방식, 셀)은 유효 < 3 → 그 방식 판정 불가 → 성공 아님(A면 G4 실패 → A 유지).
  - **환경 사유 상한은 따로 센다(리뷰 MR-10)**: 자리마다 `env-stall`(재해석 2026-10-29로 `pc-link-down`도 같은 몫) 재측정 최대 2번 · 그 밖의 하네스 사유 재측정 최대 2번을 **따로** 센다(한쪽 소진이 다른 쪽 몫을 줄이지 않는다). 2번은 기존 상한과 같은 값이다(근거: ADR-013 선례 · 환경 정지 빈도는 시작 조건 ⑤ 스모크 기록으로 사용자에게 미리 보고).
  - **환경 상한을 다 쓴 자리**: 마지막 시도도 `env-stall`이면 그 자리를 '방식 실패'로도, 위 'A면 G4 실패 → A 유지'로도 바꾸지 않는다. **'판정 불가 → 사용자 보고'**로 두고(측정-8 '판정 불가 결론' ⓑ), 추가 재측정 여부는 사용자가 정한다. 하네스 사유로 상한을 다 쓴 자리만 위 기존 규칙(성공 아님 · A면 G4 실패)을 따른다. 두 사유가 섞인 자리는 마지막 시도의 사유로 가른다.
- **캠페인 시작 뒤 하네스 결함**: 측정 경로(부하 · 탐침 · 표본 · 판정)를 바꾸는 수정이면 **60회 전체를 새 id로** 다시 돈다(사용자 확인 뒤). 앞 결과는 'superseded'로 남긴다(ADR-013 §5.8-5 — 결과를 보고 일부만 다시 재서 고르는 경로를 없앤다). 기록 경로만이면 원시 자료로 다시 집계하고 복원 표시를 단다.
- **방식 코드 결함**(B·C·D 정합 위반 등): 그 방식은 이 캠페인에서 실패로 확정한다. 고쳐서 다시 재려면 task 04로 되돌리고(사용자 확인 — task 결정), 새 이미지로 60회 전체를 돈다 — 모든 방식이 같은 이미지여야 비교 조건이 같다.
- **캠페인 멈춤**: 하네스 실패 연속 5회(ADR-013 캠페인 멈춤 규칙) · 회차 1의 TTL 30초 두 묶음이 끝났을 때 A의 W가 0이거나 A의 PR-1 δ ∈ {1, 3} 부당 실패가 0(G4·P3 양성 대조가 처음부터 무너짐 — 남은 시간을 쓰지 않고 보고한다). 자동 재실행은 없다.
- **차가운 JVM(ADR-013 교훈)**: ADR-013의 504 구간은 lb 뒤에서 예열 안 된 새 JVM이 혼자 전체 부하를 받을 때 났다(§6.6-3). 이 측정은 앱이 회차 시작에 한 번 뜨고, 예열(측정-3.6 `warmup14.js` — 선점 · 지도 · 확정) 뒤 재시드와 시작 대기를 거쳐 네 방식이 같은 상태에서 부하를 받는다. 회차 중 새 JVM 투입이 없다. 시작 대기 동안 Tomcat 유휴 스레드가 내려가는 예열 약화는 모든 방식에 같다(k6/ADR-009/README.md 리뷰 R9).
- **lb 아티팩트(ADR-013 교훈)**: lb가 없어 해당 없다(측정-1).
- 캠페인 시작 뒤에는 탐침 코드 · 일정 · 지표 정의 · 판정식을 바꾸지 않는다(ADR-013 §5.8-5).

### 측정-10 받아들이는 한계 (측정 전)

1. **앱 1대 · 서버 1대**: 가설 3의 A판(앱 2대 배치 겹침 — §설계-5)과 여러 인스턴스의 배치·회수 경합을 재지 않는다(ADR-009 '대조는 1대에서만' 제약 유지). 앱과 DB가 한 호스트라 네트워크 지연이 없다.
2. **부하는 흉내다**: 맹목 무작위 + 지도 10세션/초 + 선점 직후 정해지는 이탈 + 재방문 ρ — 실제 사용자 행동 자료가 없다. ρ · 지도 속도 · δ 분포는 보수적 선택이다. 맹목 흐름은 만석 뒤에도 초당 약 1,200건의 409를 계속 만든다 — 실제보다 거절 경로 비중이 크다(추정).
3. **TTL 5분 칸의 효과는 작다**: 예측 약 1~2%라 3회 범위 안에 묻힐 수 있다(P2). TTL 30초는 효과를 키우는 장치이지 운영값이 아니다. TTL 30초 창(240·360초)은 짧아 그 칸의 p99·CPU는 첫 몰림이 대부분이다 — 비용 판정은 a50-t300과 비용 셀로만 한다(측정-8).
4. **B의 장기 누적을 재지 않는다**: 회차는 최대 약 30분이다. B의 남은 만료 행은 회차가 마감될 때까지 늘고(정리 ①만 지운다 — §3.4) 조회·선조회 비용도 함께 는다(추정). 이번에는 창 안 기울기만 기록한다(P13). B가 기본값이 되면 장기 측정을 NEXT 후보로 남긴다(MQ8).
5. **마감 경로를 재지 않는다**: 시드 회차가 측정 중 마감되지 않아 B의 정리 ①(§3.4 B1~B3)과 마감 회차 확정 경합(DS14-3)은 테스트(§설계-10 #12)만 본다.
6. **주입 없음**: 카운터 정합은 자연 부하에서만 본다. 어긋남 탐지 효과는 ADR-012 결과를 그대로 믿는다. 대조가 '돌았다'는 생존(M6)으로만 확인한다.
7. **회수 대기(H4 → K3)를 만드는 부하가 없다**: '같은 사용자의 확정 중 다른 좌석 선점'(§3.1 DS14-4)을 부하에 넣지 않았다 — 타이밍 창이 ms 단위라 흉내가 어렵다. M10 대기는 0에 가까울 것이고(설명 — 회수 전용 표지로만 센다 · 배치 DELETE 대기는 섞이지 않는다 · 리뷰 MR-13), 교착 없음은 동시성 테스트(§설계-10 #12)가 맡는다.
8. **시계 출처가 섞인다**: A는 앱 Clock · B·C·D는 DB `now()`(명세 §10 R1). A 대 B·C·D 비교에 두 시계의 차가 섞인다 — 회차마다 차를 기록한다(M14). 상태 표본은 DB `now()`로 '활성'을 센다 — A의 W에도 그 차가 섞인다.
9. **좌석 맵 부하는 작다**: 초당 최대 20번 · 캐시 없음. 반복 조회 폭주(ADR-018 문제 — '20만 명이 좌석맵을 반복 조회')에서 D의 조회 비용이 어떻게 커지는지는 재지 않는다. 지도 p99는 교체 관문에 넣지 않았다(G3 · MQ2).
10. **해상도**: 상태 표본 1초 · docker stats 약 10초(ADR-012 §7-8) · 탐침 칸 회차당 약 30~70개 × 3회 — 드문 부당 실패(< 1%)는 0으로 보일 수 있다.
11. **tmpfs**: 디스크 DB의 쓰기 벽(ADR-009 §7)은 없다 — 회수·배치의 WAL 비용은 기록만 한다.
12. **라운드 모형과 D_arr는 추정이다**: 첫 몰림 길이 · 찾는 시간 · 확정 시점 분포가 모형과 다르면 T95가 창 밖으로 갈 수 있다 — 시작 조건 ⑥이 스모크로 한 번 고친다. 그 뒤 창 밖이면 +∞로 센다(가장 나쁨 — 그 방식에 불리하게).
13. **공통 난수는 응답이 갈리기 전까지만 같다**: 409가 갈리면 그 뒤 좌석 선택이 갈린다 — 흔들림을 줄일 뿐 없애지 않는다.
14. **고정값 일부는 보수적 선택이다**(측정-4 '근거 종류'): 지도 10/초 · ρ 0.2 · DB CPU +10% · P1 15초 · P3 0.1 · 환경 정지 5초 · 시계 20ms/100ms — 측정 뒤 바꾸지 않는다.
15. **환경 정지 분류는 기계적 근사다**: 서버 측정기 · GC · DB 쪽 원인 없음(업무 세션 Lock 대기 · Hikari — 리뷰 MR-6)으로 가른다. 잠금 · 풀 고갈로 멈춘 방식 정지는 DB 쪽 조건이 걸러 방식으로 남기지만, 앱 CPU 굶주림처럼 DB 표본에 드러나지 않는 방식 원인은 여전히 환경으로 잘못 분류될 수 있다(그 회차는 재측정되어 결과에 'rerun'으로 남는다).

### 측정-11 `[구현 검증]` (이 절에서 더한 것 — §설계-10 중앙 대장의 #15~#25 · #22~#25는 리뷰 MR 반영)

15. 좌석 맵 응답 크기 · 지도 흐름 10세션/초에서 k6 PC 수신량 · PC CPU(JSON 파싱) — 시작 조건 ⑥의 입력.
16. 상태 표본 SQL(측정-5 머리)의 부하 중 질의 시간 — 네 방식 · a50-t30 스모크(§설계-10 #10의 구체판 · 초안 'a50-t300 스모크'는 스모크 셀 목록(측정-9 ①)과 어긋나 고침). 1초 간격을 지키는지(`state-sampler-gap` 0). (리뷰 MR-1) 방식별 `sample_ms` 분포 · 표본 CPU 몫 c_X · B의 '`sample_ms` 대 `expired_rows`' 기울기(a50-t300 외삽 입력) · R₀ 계산 — 측정-5 머리 기준의 입력.
17. 탐침 시각 정확도 — 계획 서버 시각 대비 실제 보낸 시각(PC + 시계 차)의 오차 분포(k6 sleep 흔들림 포함). δc 0.3초 대비 충분히 작은지. (리뷰 MR-2) 송신 직전 ms 태그가 모든 탐침 요청에 남는지 · 칸별 허용(100ms · 50ms) 초과 비율이 스모크에서 10%보다 충분히 작은지(크면 캠페인 전에 탐침 일정 방식을 고친다 — 허용값은 고치지 않는다).
18. `pg_stat_user_tables.n_tup_upd`가 `SELECT … FOR UPDATE [NOWAIT]`를 세지 않는지(P6 — D의 H7은 잠금만 한다). 세면 P6 식을 '확정 + 잠금 수'로 바꾸는 것이 아니라 측정 전에 다른 판정 자료(배치 문장 표지)로 바꾼다. (리뷰 MR-12) **반영 지연 · 롤백 계수**: ① 롤백된 확정의 UPDATE가 `n_tup_upd`에 들어가는지(외부 지식으로는 든다 — 이 저장소 PostgreSQL 판에서 재현) ② k6 끝 뒤 '업무 요청 연결 10초 넘게 쉼' 시점의 스냅숏이 모든 확정 UPDATE를 이미 담았는지 — 스모크(A · D)에서 그 시점과 그 뒤 60초 스냅숏을 견줘 차가 0인지(외부 지식: 경합이 있으면 반영이 더 미뤄질 수 있다). 차가 있으면 시작 전에 '직후' 대기 길이를 늘린다(측정-6 P6) ③ 수렴 대기 구간에 D 배치 · 대조 말고 `product_seat`를 쓰는 경로가 없는지(Δ₂ = 0의 전제).
19. 앱 지표 태그(측정-3.6 '새로(task 04 — 앱)' 행)가 모두 나오는지 — 특히 C6·H4b 되돌림 기회(P5의 판정 불가 조건)와 NOWAIT 패배 태그.
20. 공통 난수 — 같은 r의 네 방식에서 세션 속성 열 해시가 같은지(`crn-mismatch` 0).
21. 라운드 모형 대 스모크 실측 — a50-t30 T95가 D_arr(360초)의 60% 안인지 · a50-t300은 스모크하지 않으므로 a50-t30의 라운드 길이로 외삽한 값을 log에 남긴다(추정). (리뷰 MR-13) 외삽한 T95_추정으로 시작 조건 ⑥의 식(D_arr′ = ⌈T95_추정 ÷ 0.6 ÷ 60⌉ × 60)을 네 셀 모두에 대 본다.
22. (리뷰 MR-5) 선점 409 원인 표지 — `loadtest` 프로필에서만 켜는 응답 헤더 `X-Seatlab-Reject: rule | nowait`와 앱 지표 `seat.hold.rejected{cause}`가 API 계약(상태 코드 · 본문 `{code, message}`)을 바꾸지 않고 붙는지 · 기본 프로필에서는 헤더가 없는지 · k6 헤더 합 = 앱 지표 증분인지 · C 스모크에서 배치 틱과 겹친 PR-1의 `nowait`가 실제로 나오는지(0이면 2단 M3ᵦ는 비어 있는 지표다 — 그 사실을 log에 남긴다).
23. (리뷰 MR-6) env-stall의 DB 쪽 조건 표본 — 0.5초 대기 표본 `by_src`의 `app|Lock:*` 갈래가 업무 세션만 세는지(C의 표지 갈래와 섞이지 않는지 — lib.sh:153 `by_src` CASE 순서) · 앱이 멈춘 구간에도 대기 표본이 빠지지 않는지 · Hikari 표본이 그 구간 앞뒤로 남는지.
24. (리뷰 MR-13) H4 회수 문장의 SQL 주석 표지 `/* seatlab:h4-reclaim */`가 JDBC를 거쳐 `pg_stat_activity.query`에 그대로 보이는지 · 배치 문장(C4 · D4 · B2)에는 없는지.
25. (리뷰 MR-3 · MR-11) 스모크에서 DB 컨테이너 docker stats 행의 실제 간격(10초 + 호출 시간)이 ⌈창 초 ÷ 15⌉ 기준을 넉넉히 넘는지 · 흐름별 VU 최대와 `dropped_iterations` 행이 흐름 태그로 나뉘어 나오는지.

### 측정-12 열린 질문 (메인 · 사용자가 정할 것 — 질문마다 권장안 1개)

> **결정(2026-10-26)**: 사용자 — MQ2(좌석 맵 p99는 관문에서 빼고 보고만) · MQ3(이김 = 매진 시간 · 탐침 · A 정합 위반 중 하나 — 탐침 갈래 때문에 교체는 사실상 정합·비용이 정한다는 점을 결과에 밝힌다) · MQ4(60회 그대로 · 줄이면 비용 셀 먼저) · MQ7(단순성 D > B > C) · MQ9(A 정합 위반 = ADR-015 입력 · 위반 0 후보는 G2(c) 충족) 모두 권장안. 메인 — MQ1 · MQ5 · MQ6 · MQ8 · MQ10 권장안. 아래 '권장' 칸이 곧 결정이다.

| # | 질문 | 권장안 | 누가 |
|---|---|---|---|
| MQ1 | 비용 셀(S4c4200 × 4방식 × 3 — 약 2.6시간)을 넣나 | **넣는다** — 주 셀의 선점 201은 초당 수백 건이라 선점 경로 문장 증가(H1 하위 질의 · H2 · H8)의 비용이 드러나지 않는다. ADR-009·012 비용 판정과 같은 셀이다 | 메인 |
| MQ2 | 좌석 맵 p99를 A 교체 관문(G3)에 넣나 | **넣지 않는다** — A의 지도는 배치 전 좌석을 `HELD`로 보이는 다른 의미라 싸고, 조회 최적화는 ADR-018 몫이다. B·C·D 사이 순위(3단)에만 쓰고, 결과 문서에 X ÷ A 배수를 적어 사용자 보고에 넣는다 | 사용자 |
| MQ3 | '이긴다'(G2)를 무엇으로 보나 — G2(b)(자기 좌석 탐침)는 P3가 서면 A가 늘 진다(측정-8 '주의') | **(a) 매진 시간 · (b) 탐침 · (c) A 정합 위반 중 하나** — 탐침 실패는 사용자가 겪는 실제 손해다(만료 뒤 최대 약 10초 동안 자기 좌석을 못 잡음). 그 결과 교체는 사실상 정합과 비용이 정한다는 것을 결과 보고에 밝힌다. 대안: (a)만 — TTL 5분에서 묻힐 가능성이 커서 교체가 거의 안 일어난다 | 사용자 |
| MQ4 | 캠페인 약 19.8시간(추정) + 재측정 · 3회 — **리뷰 MR-13 뒤 약 21.2시간**(a50-t300 D_arr 1,800 → 2,220초 · 측정-2. 사용자 결정 당시 값과 달라졌다 — 재확인 대상) | **그대로** — 회차를 줄이면 범위 비겹침 판정이 약해진다. 시간을 줄여야 하면 TTL 5분 a50 D_arr를 줄이지 말고(창 밖 위험) 비용 셀(MQ1)을 먼저 뺀다 | 사용자 |
| MQ5 | 재방문 비율 ρ = 0.2 · δ ~ [0, 20]초 | **그대로** — 근거 없는 보수적 선택이지만 설명 지표에만 쓴다(판정은 탐침 PR-1·PR-2). 0이면 주 회차의 재방문 경합이 아예 없어진다 | 메인 |
| MQ6 | 지도 흐름 10세션/초 · 세션당 GET ≤ 2 | **그대로** — 시작 조건 ⑥에서 PC 수신량이 50MB/s를 넘을 때만 캠페인 전에 줄인다 | 메인 |
| MQ7 | 단순성 순서 D > B > C(측정-8) | **그대로** — 측정 전에 고정한 셈(힌트 경로 수 + 배치 유무, 동률이면 만료 행 누적 없음)이다 | 사용자 |
| MQ8 | B의 남은 만료 행 장기 누적(측정-10-4)을 성공 조건에 넣나 | **넣지 않는다** — 30분 창에서는 판정할 수 없다. 기록(P13)만 하고, B가 기본값이 되면 장기 측정을 NEXT 후보로 남긴다 | 메인 |
| MQ9 | A에서 정합 위반이 나오면 | **A의 기존 결함으로 기록해 ADR-015에 넘기고, 같은 캠페인에서 위반 0인 후보는 G2(c)를 채운 것으로 본다** — A 경로는 이번에 고치지 않는다(Q3) | 사용자 |
| MQ10 | 확정 경합 탐침 PR-3를 넣나(하네스 일이 는다) | **넣는다** — 이중 판매 0(P7)과 만료 뒤 확정 거부(P9)가 '기회가 있었는데 0'임을 보이는 양성 대조다(ADR-013 P8 교훈 — 0이 비어 있는 성립이 되지 않게). 빼면 P7·P9의 판정 불가 조건을 다시 정해야 한다. **(리뷰 MR-8 — 범위 좁힘)** PR-3은 'API 계약 양성 대조'(만료 전 확정 성공 · 만료 뒤 확정 거부)다 — 시각 역전 경합의 기회는 만들지 못하므로 그 0은 동시성 테스트 green(측정-9 ⑦)이 맡는다. 측정 지연 손잡이는 넣지 않는다(Q13 — 메인 결정) | 메인 |

## 실험 결과

미측정.

## 결정

미정.
