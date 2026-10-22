# ADR-006: 매수 카운터를 한 문장 upsert로 — counter vs advisory-try 재비교
> 번호 이동(2026-10-12, 5차): 운영 중 매수 방식 전환을 새 ADR-010으로 넣으며 기존 ADR-010~015를 011~016으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-12, 4차): 카운터 어긋남 탐지·격리·복구를 새 ADR-009로 넣으며 기존 ADR-009~014를 010~015로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-11, 3차): counter-upsert-precheck 기본 적용·검증을 새 ADR-008로 넣으며 기존 ADR-008~013을 009~014로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09, 2차): ADR-006 처리량 벽 진단을 새 ADR-007로 넣으며 기존 ADR-007~012를 008~013으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09): counter 1문장 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-15, 6차): 앱 JVM GC·메모리 기준을 새 ADR-010으로 넣으며 기존 ADR-010~016을 011~017로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-16, 7차): 측정 환경 꼬리 지연 원인을 새 ADR-011로 넣으며 기존 ADR-011~017을 012~018로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-18, 8차): 카운터 증분 대조·Kafka/복제본 대조를 새 ADR-012·013으로 넣으며 기존 ADR-012~018을 014~020으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-23, 9차): Kafka·읽기 복제본 대조(옛 ADR-013)를 착수 조건이 생기는 오픈 폭주 뒤로 보내 ADR-020으로 옮기고, 기존 ADR-014~020을 013~019로 당겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: **결정 (Accepted, 2026-10-09)** — 매수 제어 기본은 advisory-try 유지. counter-upsert의 처리량 벽 원인을 ADR-007에서 찾고, 해결되면 counter-upsert로 전환(§6 전환 규칙)
- 명세: `docs/plans/2026-10-09/adr-006-counter-upsert/requirement-spec.md`
- 날짜: 2026-10-09
- 선행: **ADR-005(1인 매수 제어 10종 — 결정 L2(advisory-try) 기본, L5(counter) 조건부)**, ADR-003(좌석 3b(`for no key update nowait`))
- 후행: **ADR-007(counter-upsert 처리량 벽 진단 — 전환 판정)**, ADR-014(홀드 만료 — 카운터를 내리는 주체)

## 1. 문제

ADR-005에서 L5 `counter`(카운터 조건부 UPDATE)는 매수 정합성·가짜 거절이 advisory 계열과 같고, **매수 초과 사용자가 좌석 락을 아예 잡지 않는 유일한 방식**이었다(S7-m1(2매 보유자 + 일반 1명) 억울한 좌석 0석 vs 63~81석). 같은 사용자 동시 요청의 지연도 가장 짧았다(S2(사용자 100명 × 각자 좌석 10개 동시) L2(앱·DB CPU 2개) p50 258ms vs advisory-try 980ms).

그러나 이번 구현은 S4(도착률 계단, 요청마다 새 사용자·좌석)에서 처리량이 회귀했다 — L4(앱·DB CPU 4개) 엄격 한계 4,300 → 1,911(−56%), 회차에 따라 커짐. 원인은 판정이 아니라 **트랜잭션 밖 준비 단계(prepare)**: 요청마다 쿼터 행 확인 `SELECT` → 없으면 `INSERT`(자동 커밋) → 본 트랜잭션으로 **커넥션을 3번 빌렸다**(ADR-005 §7.3 ③).

질문: 판정을 **트랜잭션 안 한 문장**으로 바꿔 빌림을 1번으로 만들면, counter의 장점을 유지한 채 처리량이 대조군 수준으로 돌아오는가? 돌아오면 ADR-005의 기본을 counter로 바꿀 근거가 된다.

## 2. 선택지 (명세 확정 2026-10-09)

| 방식 | 설명 |
|------|------|
| none | 제어 없음(대조군) |
| advisory-try | ADR-005 결정 — `pg_try_advisory_xact_lock(회차, 사용자)`, 좌석 확인 뒤 거절 |
| **counter-upsert** | 트랜잭션 안 한 문장: `INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1) ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + 1 WHERE user_hold_quota.cnt + 1 <= ?` — 영향 행 1 = 통과, 0 = 매수 초과(409). prepare 없음 |

- counter-upsert의 동시성: 같은 사용자 동시 요청은 그 행의 락에서 줄을 서고, `ON CONFLICT DO UPDATE`는 최신 행 버전으로 `WHERE`를 다시 본다(READ COMMITTED). 첫 요청 두 개가 동시에 INSERT하면 하나는 기다렸다가 UPDATE 경로로 간다.
- 좌석에서 지면 같은 트랜잭션 롤백으로 +1이 되돌아간다. 만료 배치의 −1은 ADR-005 구현 그대로.

### 2.1 코드 변화 — ADR-005 `counter` → ADR-006 `counter-upsert`

매수 방식들은 **스스로 실행되지 않는다**. 각 방식은 `UserLimitStrategy` 인터페이스 구현체이고, Spring이 기동할 때 전부 빈(@Component)으로 만든다. 그중 설정값(`seat.hold.limit-strategy`)과 같은 하나를 `ActiveUserLimit`이 고른다. 선점 요청 하나가 지나가는 흐름 안의 정해진 자리에서 그 구현체의 메서드가 불린다(전략 패턴). 그래서 먼저 흐름을 보고, 그다음 방식별로 그 자리에 무엇이 들어가는지 본다.

**(1) 선점 요청 하나의 실행 흐름** — 아래 코드는 실파일에서 순서대로 발췌했다(타이머 세부·주석 일부 생략). 설정은 측정과 같은 좌석 3b `pessimistic-nowait`이다.

```kotlin
// ① api/SeatHoldController.kt — POST /api/schedules/{scheduleId}/seats/{seatId}/hold
val result = holdSeatUseCase.hold(HoldSeatCommand(scheduleId = scheduleId, seatId = seatId, userId = userId))

// ② service/impl/HoldSeatService.kt — 트랜잭션 밖
override fun hold(command: HoldSeatCommand): HoldSeatResult {
    val limit = userLimit.strategy                                   // ActiveUserLimit이 설정으로 고른 매수 방식 하나
    if (limit.hasPrepare) meters.timer("seat.hold.limit.prepare").record(Runnable { limit.prepare(command) })  // ⓐ 트랜잭션 밖 준비
    return limit.around(command) { strategy.hold(command) }         // ⓑ 감싸기(기본은 그냥 block() 실행) → 좌석 전략 호출
}

// ③ service/impl/hold/DbLockHoldStrategies.kt — 좌석 3b: 여기서 트랜잭션이 열린다
override fun hold(command: HoldSeatCommand): HoldSeatResult = try {
    tx.execute { process.hold(command, loadSeat = seats::findForUpdateNoWait) }!!
} catch (e: PessimisticLockingFailureException) {
    if (sqlStateOf(e) == LOCK_NOT_AVAILABLE) seatTaken()             // 좌석이 잠겨 있으면 409 SEAT_NOT_AVAILABLE
    throw e
}

// ④ service/impl/HoldSeatProcess.kt — 트랜잭션 안(Propagation.MANDATORY)
var entered = true
meters.timer("seat.hold.limit.acquire").record(Runnable { entered = limit.acquire(command) })   // ⓒ 사용자 단위 진입 — 좌석 읽기 전
val seat = try {
    val load = loadSeat ?: productSeatRepository::findByIdAndScheduleId
    val loaded = load(command.seatId, command.scheduleId)            // 3b: select … from product_seat … for no key update nowait (Hibernate 실측 SQL — ADR-003)
        ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
    loaded.assertHoldable()                                          // 좌석 불가가 매수 초과보다 먼저
    meters.timer("seat.hold.limit.check").record(Runnable { limit.check(command, entered) })    // ⓓ 매수 판정 자리
    loaded
} finally { /* span 타이머 */ }
val hold = seat.hold(command.userId, clock.instant(), properties.ttl) // 홀드 INSERT · 좌석 HELD
productSeatRepository.flush()
// ⑤ tx.execute가 끝나며 커밋. ⓒ·ⓓ나 좌석에서 예외가 나면 트랜잭션 전체가 롤백된다(카운터 +1도 함께 되돌아감)
```

매수 방식이 끼어드는 자리는 ⓐ~ⓓ 넷이다. 구현하지 않은 자리는 인터페이스 기본값이 채운다(`UserLimitStrategy` 발췌 — `type`·주석 생략):

```kotlin
interface UserLimitStrategy {
    val hasPrepare: Boolean get() = false                         // ⓐ를 할지
    fun prepare(command: HoldSeatCommand) {}                      // ⓐ
    fun <T> around(command: HoldSeatCommand, block: () -> T): T = block()   // ⓑ
    fun acquire(command: HoldSeatCommand): Boolean = true         // ⓒ
    fun check(command: HoldSeatCommand, entered: Boolean)         // ⓓ
    fun onHoldsExpired(expired: List<SeatHold>) {}                // 만료 배치가 부른다
}
```

| 자리 | ADR-005 `counter` | ADR-006 `counter-upsert` |
|------|-------------------|--------------------------|
| ⓐ 트랜잭션 밖 준비 | **함** — `ensureQuotaRow`(SELECT → 없으면 INSERT, 각각 자동 커밋) | **안 함**(`hasPrepare` 기본 false) |
| ⓑ 감싸기 | 기본(그냥 실행) | 기본(그냥 실행) |
| ⓒ 진입 = 매수 판정 | `UPDATE … SET cnt = cnt + 1 WHERE … AND cnt + 1 <= ?` | `INSERT … ON CONFLICT DO UPDATE … WHERE cnt + 1 <= ?` |
| ⓓ 판정 자리 | 빈 메서드(ⓒ에서 끝남) | 빈 메서드(ⓒ에서 끝남) |

**(2) 방식별로 ⓐ~ⓓ에 들어가는 코드** — `service/impl/limit/UserLimitStrategies.kt`에서 옮겼다. 두 방식은 같은 파일에 나란히 있다. `counter`는 비교용으로 남겼다.

**ADR-005 `counter`(L5)** — ⓐ 트랜잭션 밖 준비 + ⓒ 트랜잭션 안 조건부 UPDATE:

```kotlin
// CounterUserLimit
override val hasPrepare = true
override fun prepare(command: HoldSeatCommand) = ensureQuotaRow(jdbc, command)
override fun acquire(command: HoldSeatCommand): Boolean {
    val updated = jdbc.update(
        "UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ? AND cnt + 1 <= ?",
        command.scheduleId, command.userId, properties.maxPerUser,
    )
    if (updated == 0) limitExceeded()
    return true
}

private fun ensureQuotaRow(jdbc: JdbcTemplate, command: HoldSeatCommand) {
    val exists = jdbc.queryForList(
        "SELECT 1 FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", command.scheduleId, command.userId,
    ).isNotEmpty()
    if (exists) return
    jdbc.update(
        "INSERT INTO user_hold_quota (schedule_id, user_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
        command.scheduleId, command.userId,
    )
}
```

**ADR-006 `counter-upsert`** — ⓐ 없음, ⓒ에서 트랜잭션 안 한 문장:

```kotlin
// CounterUpsertUserLimit — hasPrepare 기본값 false → HoldSeatService가 prepare를 건너뛴다
override fun acquire(command: HoldSeatCommand): Boolean {
    if (!upsertQuota(jdbc, command, properties.maxPerUser)) limitExceeded()
    return true
}

private fun upsertQuota(jdbc: JdbcTemplate, command: HoldSeatCommand, maxPerUser: Int): Boolean = jdbc.update(
    """
    INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1)
    ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + 1
    WHERE user_hold_quota.cnt + 1 <= ?
    """.trimIndent(),
    command.scheduleId, command.userId, maxPerUser,
) == 1
```

| | ADR-005 `counter` | ADR-006 `counter-upsert` |
|---|---|---|
| 쿼터 행이 없을 때 | 트랜잭션 밖에서 `SELECT` → `INSERT … ON CONFLICT DO NOTHING`(자동 커밋)으로 cnt 0 행을 만든다 | upsert의 `INSERT` 경로가 cnt = 1로 바로 만든다 |
| 쿼터 행이 있을 때 | 트랜잭션 밖 `SELECT` 1회 뒤, 트랜잭션 안 `UPDATE … WHERE cnt + 1 <= ?` | `ON CONFLICT DO UPDATE … WHERE cnt + 1 <= ?`(같은 판정) |
| 요청당 커넥션 빌림 | 새 사용자 3회(SELECT · INSERT · 본 트랜잭션), 기존 사용자 2회. S4는 요청마다 새 사용자라 3.00 | 1회(본 트랜잭션만) — 측정 1.000 |
| 통과·거절 | 영향 행 1 / 0 | 같다(영향 행 1 / 0) |
| 판정 시점 | 트랜잭션 첫 단계(`acquire`) — 좌석보다 먼저 | 같다 |
| 만료 감소(`onHoldsExpired`) | `UPDATE … SET cnt = cnt - ?` | 같은 함수(`decrementCounters`) 공유 |

- 준비를 트랜잭션 밖에 둔 까닭(ADR-005)은 L4 `quota-nowait`의 '기다리지 않음' 때문이었다. 트랜잭션 안 `INSERT … ON CONFLICT`는 아직 커밋 안 된 같은 키 행을 만나면 기다린다. counter-upsert는 원래 같은 사용자 요청을 행 락에서 줄 세우는 방식이라, 그 대기를 문제로 보지 않고 트랜잭션 안으로 넣었다.
- 판정 위치는 그대로다. `HoldSeatProcess.hold`의 `limit.acquire(command)`가 좌석을 읽기(`findByIdAndScheduleId` → `assertHoldable`) 전에 불린다. 그래서 매수 초과 사용자는 좌석 락을 잡지 않는다(억울한 좌석 0). 같은 이유로 팔린 좌석을 누른 매수 초과 사용자에게 `HOLD_LIMIT_EXCEEDED`가 간다. 이것은 사용자가 허용한 순서 예외이고, ADR-007 precheck가 이 순서를 고친다.

## 3. 가설

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| H1 | counter-upsert는 요청당 커넥션 빌림 1회다 | Hikari 획득 COUNT ÷ 선점 요청 > 1 |
| H2 | counter-upsert의 S4 엄격 한계는 none·advisory-try와 같다 | 계단 한 칸 이상 낮음 |
| H3 | counter-upsert는 ADR-005 counter의 장점(S7-m1 억울한 좌석 0, S2 지연 최소, 매수 정합 0)을 유지한다 | S7-m1 > 0 / 매수 초과 > 0 |
| H4 | 조건 순서를 섞으면 ADR-005에서 보인 시간에 따른 회귀가 방식 간에 고르게 퍼진다(시간 효과와 방식 효과 분리) | 같은 방식의 회차 간 차이가 방식 간 차이보다 큼 |
| H5 | S3(입장→선점→확정·이탈→만료) 이탈 20%에서 counter-upsert의 카운터 = 홀드 + 확정 예약(`v_counter_mismatch` 0) | `v_counter_mismatch` > 0 |

## 4. 측정 계획 (명세 §9.2 — 사용자 2026-10-09 "세 방식을 같은 캠페인에서")

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| none · advisory-try · counter-upsert | S2(사용자 100명 × 각자 좌석 10개 동시) · S4(도착률 계단) | L2 · L4(앱·DB CPU 2·4개) | 5 |
| 같음 | S7-m1(2매 보유자 + 일반 1명) | L4 | 5 |
| 같음 | S3-a20(실제 흐름, 이탈 20%) | L4 | 3 |

- 하네스 `k6/ADR-006/`(ADR-005 복사 + 개선 3): ① S4 엄격 한계 규칙 — 목표 미달(포화) 단계에서 멈춤, 포화점 병기, ADR-005 결과도 새 규칙으로 재계산해 나란히 ② 회차마다 조건 순서를 고정 시드로 섞음(회차 우선 유지) ③ 서버 I/O 지표(WAL fsync·디스크 대기) 회차 전후 차분.
- 판정 기준(명세 §9.4): ① 매수 정합성 ② 처리량(S4) ③ 요청당 커넥션 빌림 ④ 롤백 피해(S7-m1) ⑤ 같은 사용자 지연(S2) ⑥ 시간 효과 ⑦ 실패 모드.
- 약 9시간(추정).

## 4.1 측정 전 검증

- 테스트 **162개 green** — counter-upsert는 ADR-005 계약 묶음(같은 사용자 동시 10좌석 초과 0 · 다른 사용자 비차단 · 같은 좌석 50명 1명 · 에러 순서(매수 먼저) · 카운터 정합 · 격리 수준) + **새 사용자의 동시 요청 10개(행이 없는 상태, 출발선 래치) 5라운드 모두 성공 정확히 2 = 카운터**(가정 1) + 첫 요청이 좌석에서 지면 카운터 0으로 롤백 + prepare(트랜잭션 밖 준비 단계) 타이머 0건.
- 하네스 스모크: S4(도착률 계단, 요청마다 새 사용자·좌석) L4(앱·DB CPU 4개) counter-upsert 요청당 커넥션 빌림 **1.0001**(가정 2) · S2(사용자 100명 × 각자 좌석 10개 동시) 매수 초과 0 · S7-m1(2매 보유자 + 일반 1명) 억울한 좌석 0 · 실서버에서 선점 요청 수 집계 1,000 = 획득 1,000.
- 코드·하네스 리뷰(中(중간 stakes) 듀얼 1패스 + 재점검): 8건 + 재점검 1건 반영(작업 log ledger).
- S4 새 한계 규칙(목표 미달 단계에서 멈춤)을 ADR-005 결과에 적용했다. 값이 바뀐 셀은 serializable L4(SERIALIZABLE, 앱·DB CPU 4개) 하나다 — 엄격 한계 708 [662–2,021] → **1,982** [1,972–2,021](5회 중 4회 변경). 포화점 1,994와 일관된다. 나머지 19셀은 옛 규칙과 같다(`k6/ADR-006/results/adr005-recomputed-s4/S4-RECOMPUTED.md`).

## 5. 실험 결과

### 5.1 측정 경과

| 항목 | 값 |
|------|----|
| 캠페인 | `20261009-adr006-022eca41` — 2026-10-09 02:24 ~ 11:25 (약 9시간), SHA 022eca41 |
| 조건 × 회차 | 9조건(none · advisory-try · counter-upsert × {s24(S2·S4, L2·L4) · s7m1(S7-m1, L4) · s3a20(S3-a20, L4)}), 회차 우선 + **회차마다 조건 순서 균형 순환**(`order.txt`). 정상 회차 **84** = s24 60 + s7m1 15 + s3a20 9 — 계획과 같다 |
| 비정상 회차 | **0**. 끝의 재측정 단계가 S3 9회차를 `invalid-borrow-ratio`(요청당 빌림 범위 밖)로 잘못 골랐다 — 하네스 버그(`run.sh`의 `[[ S3 ]] \|\| jq … && 사유추가`가 bash에서 `(A \|\| B) && C`로 읽혀 S3에서 늘 사유가 붙음). 측정값과는 무관하다. 사용자 결정으로 재측정을 멈추고 원 회차를 복원했다. 다시 잰 회차(advisory-try-s3a20 rep1 완료·rep2 부분)는 `_aborted-remeasure-20261009/`에 보존하고 집계에서 뺐다. 9회차 status를 ok로 정정했다(`status.orig`·`meta.status_corrected`·CAMPAIGN.log). 수정 fbaaf815 |
| 실패 모드 | 5xx 0 · 데드락 0(DB 로그 84회차) · Hikari 풀 타임아웃 0. 타임아웃(0-1050)·연결 실패(0-1211)·연결 끊김(0-1220)은 S4 12단계(목표 6,487건/s) 이후에만 나오고 세 방식이 같은 크기다(약 7,200건/회차) |
| 경로 | 유선(ADR-003·005와 같음) |
| 측정 조건 차이(ADR-005 대비) | `track_io_timing`·`track_wal_io_timing` on(I/O 지표 수집용) — ADR-005 수치와 직접 비교하지 않는다 |

### 5.2 결과 요약

| 가설 | 판정 | 핵심 수치 |
|------|------|---------|
| H1 counter-upsert는 요청당 커넥션 빌림 1회 | **채택** | S2(사용자 100명 × 각자 좌석 10개 동시)·S4(도착률 계단, 요청마다 새 사용자·좌석) 1.000 [1.000–1.001](none·advisory-try와 같다). ADR-005 counter는 3.00 |
| H2 counter-upsert의 S4 엄격 한계는 none·advisory-try와 같다 | **기각(L4(앱·DB CPU 4개))** · L2(앱·DB CPU 2개)는 같다 | L4 엄격 한계 counter-upsert **2,866** [2,866–4,162](5회 중 3회가 한 칸 아래) vs none·advisory-try 4,300 ×5. 포화점 4,143 [4,128–4,162] vs 4,300. 11단계(목표 4,325건/s)에 들어가 **26~27초 뒤 같은 초에 무너진다**(10회 전부, L2·L4 같음 → CPU가 아니다). 원인은 미확정이다. 같은 시각은 같은 누적 지점이자 진행 중인 체크포인트의 같은 위치라서 이 설계로는 가를 수 없다(§5.3 ②). 판정은 기준 근처다 — 11단계 p99 477~542ms(기준 500ms) |
| H3 ADR-005 counter의 장점 유지 | **채택** | S7-m1(2매 보유자 + 일반 1명) 억울한 좌석 **0** ×5(none 72 · advisory-try 67). S2 L4 p50 / p99 **82 / 230ms**(advisory-try 160 / 433). 매수 초과 0 |
| H4 순서를 섞으면 시간 효과가 방식 간에 고르게 퍼진다 | **부분 채택(지표별)** | S4 L4 포화점·S7-m1 억울한 좌석(counter-upsert 0 vs 67~72)에서는 같은 방식의 회차 간 흔들림이 방식 간 차이보다 작다(채택). **S2 L4 p99·S2 L2 p99·S4 L2 포화점에서는 기각**된다. S4 L4 엄격 한계는 경계다(폭 1,296 vs 차 1,434). 실행 순번과의 상관은 계산하지 않았다(§5.3 ⑥ 표) |
| H5 S3-a20(입장→선점→확정·이탈, 이탈 20%)에서 카운터 = 홀드 + 확정 예약 | **채택** | `v_counter_mismatch` 0 ×3 |

### 5.3 판단 기준별 분석

#### ① 매수 정합성

**결론**: counter-upsert·advisory-try 모두 매수 초과 0. none(대조군)만 초과한다.

**근거**: S2(사용자 100명 × 각자 좌석 10개 동시) 매수 초과 사용자(판정기) — none L2(앱·DB CPU 2개) 18 [12–33] · L4(앱·DB CPU 4개) 17 [11–24], advisory-try·counter-upsert 0 ×5(L2·L4). S7-m1(2매 보유자 + 일반 1명) U(2매 보유자) 201 0(세 방식). S3-a20(실제 흐름, 이탈 20%) 매수 초과 0 · counter-upsert `v_counter_mismatch` 0 ×3. 판정기 전수: none S2 외 위반 0(`COMPARISON.md` 판정기 표). 가짜 거절(매수가 남았는데 거절) 0 — 세 방식 모두.

**편향**: S2는 같은 사용자 경합만 본다(ADR-005 §7.3 ②의 민감도 한계 그대로).

#### ② 처리량 — S4(도착률 계단, 요청마다 새 사용자·좌석)

**결론**: counter-upsert는 **같은 처리량에서 CPU를 덜 쓰고, L2(앱·DB CPU 2개)에서는 오히려 더 많이 처리**한다. 그러나 **CPU와 무관한 벽**을 만난다. 매 회차 11단계 시작 26~27초 뒤, 누적 선점 약 37만 건 시점에서다. 그래서 L4(앱·DB CPU 4개)의 엄격 한계가 한 칸 아래로 떨어진다. 엄격 한계 2,866(−33%)은 계단 폭(×1.5) 때문에 크게 보이는 값이다. 다만 **벽이 11단계 끝에서 시작해 얼마나 지속되는지는 이 계단 설계로 관측할 수 없다**. 바로 12단계(모든 방식이 과부하)로 넘어가기 때문이다. 12단계 성공 처리량도 counter-upsert L4 2,605건/s로 none 2,793·advisory-try 2,803보다 낮다(13~15단계는 세 방식 모두 약 2,300~2,450).

**근거 1 — 엄격 한계·포화점**(건/s, 새 규칙 — 목표 미달 단계에서 멈춤):

| 방식 | L2 엄격 한계 | L2 포화점 | L4 엄격 한계(회차별) | L4 포화점 | CPU% 최대 앱 / DB (L4) | 커넥션 획득 대기 평균 ms L2 / L4 |
|------|------|------|------|------|------|------|
| none | 2,866 ×5 | 3,674 [3,437–3,769] | 4,300 ×5 | 4,300 | 206 / 170 | 41.9 / 34.1 |
| advisory-try | 2,866 ×5 | 3,533 [3,419–3,563] | 4,300 ×5 | 4,300 | 214 / 179 | 44.7 / 34.1 |
| counter-upsert | 2,866 ×5 | **4,121** [4,102–4,159] | **2,866** ×3 · 4,132 · 4,162 | 4,143 [4,128–4,162] | **150 / 149** | 35.8 / 35.8 |

- 참고(시간 변화 때문에 직접 비교 안 함): ADR-005 counter(prepare 있음, 3회 빌림)는 같은 새 규칙으로 L4 1,911 [1,911–2,866] · 포화점 2,568 — counter-upsert는 이보다 포화점이 +61% 높다(`k6/ADR-006/results/adr005-recomputed-s4/`).

**근거 2 — 11단계(목표 4,325건/s) 초 단위**(k6 요청별 기록, 단계 시작 기준 초별 p99가 200ms를 처음 넘는 초 — `S4-WALL.md`):

| 방식 | L2 | L4 |
|------|----|----|
| none | +1 ~ +9초(CPU 포화) | +31 ~ +33초(= 12단계 시작) |
| advisory-try | +2 ~ +3초(CPU 포화) | +30 ~ +32초(= 12단계 시작, 4회) · rep4 +0초(단계 전환 순간 1초만, 이후 12단계까지 정상 — 엄격 한계 4,300) |
| counter-upsert | **+26 ~ +27초 ×5** | **+26 ~ +27초 ×5** |

- counter-upsert L4 rep2(11단계 시작 = 331초, `S4-WALL.md` 기준): 331~356초는 초당 4,309~4,341건 · 초별 p99 4~25ms(none과 같다), 357초(+26)부터 초당 3,336 → 2,000대로 꺾이고 p99 230 → 1,388ms. Hikari 대기는 1초 안에 0 → 190으로 뛴다(`timeline-hikari.jsonl` 06:28:27→28) — 서서히 늘어나는 압박보다 전역 정지에 가까운 모양이다. none은 같은 단계를 끝까지 p99 4~14ms로 버티고 12단계(6,487)로 올라간 순간 꺾인다. (벽 검출 기준 '초별 p99 > 200ms'는 엄격 한계 기준 500ms보다 낮게 잡아 무너지기 시작한 초를 찾는다.)
- **L2와 L4에서 같은 초에 꺾인다** → CPU가 아니다. L2에서 none·advisory-try는 11단계 시작 직후 CPU로 포화되는데(앱 CPU 206%) counter-upsert는 앱·DB CPU 약 150%로 4,325건/s를 26초 버틴다 — **같은 처리량에서 CPU가 더 적게 든다**(none·advisory-try는 매수 확인에 `count` 두 번 — 홀드 수·확정 예약 수, `HoldLimitPolicy.kt:18-21` — counter-upsert는 upsert 한 문장. check 구간 0.21ms → 0, acquire 0.12ms).
- 꺾이는 시점의 누적 선점 ≈ 0~10단계 약 25.5만 + 11단계 26초 × 4,325 ≈ **약 37만 건**. 도착 계획이 고정이라 '같은 누적'과 '같은 경과 시간'(DB 재시작 후 약 410초)이 겹친다 — 이 설계로는 둘을 가를 수 없다.

**근거 3 — I/O**(L4 S4 회차 중앙값, k6 직전·직후 차분):

| 방식 | WAL MB | 선점당 WAL B | 선점당 WAL 레코드 | 관계 파일 확장(extends) | 백엔드가 직접 쓴 버퍼 | 읽기(reads) | WAL fsync 평균 ms | 디스크 util % |
|------|------|------|------|------|------|------|------|------|
| none | 605 | 882 | 11.8 | 15,041 | 22,039 | 39,356 | 0.719 | 46.6 |
| advisory-try | 608 | 885 | 11.8 | 15,129 | 22,002 | 39,801 | 0.718 | 46.6 |
| counter-upsert | 733 | 1,087 | 14.8 | **21,700** | **28,025** | **48,054** | 0.734 | 47.2 |

- counter-upsert는 선점마다 `user_hold_quota` 행 + PK 인덱스 항목이 하나 더 생긴다 — WAL +21%, 파일 확장 +44%, 백엔드 직접 쓰기 +27%, 읽기 +22%. fsync 시간·디스크 사용률은 **회차 평균으로는** 같다. 순간 병목은 이 지표(519초 구간 전후 차분)로 배제할 수 없다.
- **체크포인트가 벽 동안 진행 중이었다**(DB 로그 `log_checkpoints`). 시간 기준 체크포인트는 세 방식 모두 k6 시작 약 +249초에 시작한다. 그런데 완료 시점이 다르다 — counter-upsert는 +422~429초(10/10)이고, 이어서 WAL 기준 체크포인트가 +438~446초에 시작한다. none·advisory-try는 +457~480초에 완료한다. 쓴 버퍼 수는 같고(약 2,680) 쓰기 시간만 짧다(`checkpoint_write_time` counter-upsert 172~179초 vs 나머지 206~230초). WAL이 빨리 쌓여(+21%) 체크포인트가 속도를 올린 것으로 보인다(PostgreSQL은 `max_wal_size` 소진 속도에 맞춰 체크포인트를 당긴다). 벽(+357초)이 이 가속 구간 안에 있다.

**원인 후보(미확정)**: 대기 이벤트(`pg_stat_activity.wait_event`)를 수집하지 않아 가를 수 없다. 후보는 넷이다.
  1. 체크포인트 가속·WAL 압박 — 위 시각 정렬과 맞는다.
  2. 버퍼 교체 — 누적 데이터가 `shared_buffers`(이 실험 128MB, PostgreSQL 기본값)를 넘는 경우.
  3. 관계 확장 락.
  4. B-tree 오른쪽 끝 페이지 경합 — S4는 사용자 ID가 늘 증가한다.

  counter-upsert는 행·인덱스·WAL이 더 많아 어느 후보든 먼저 닿는다. 진단 설계는 §7.

**편향·한계**:
- S4는 **요청마다 새 사용자**라 선점마다 쿼터 행이 하나씩 INSERT된다.
- **쿼터 행은 만료로 지워지지 않는다**. 만료는 `cnt`만 내린다(`UserLimitStrategies.kt` `decrementCounters` — `UPDATE … SET cnt = cnt - ?`, DELETE 없음). 그래서 살아 있는 쿼터 행은 '그 회차에서 한 번이라도 선점에 **성공해 커밋된** 고유 사용자 수'로 계속 늘어난다(`cnt`가 0으로 돌아와도 행은 남는다). 좌석에서 져 롤백된 INSERT는 살아 있는 행이 아니지만, vacuum 전까지 dead tuple(지워진 행 잔해)과 인덱스 항목으로 남는다. 따라서 실서비스에서 만료가 돌아도 이 비용은 줄지 않는다. 회차가 끝난 뒤 정리(삭제·파티션)가 따로 필요하다(§7).
- 홀드 TTL(유효 시간) 60분이라 S4에서는 만료가 없다.
- 계단 폭 ×1.5라 '한 칸 아래'가 −33%로 보인다. 4,325와 6,487 사이 실제 한계는 세 방식 모두 측정하지 않았다(none·advisory-try 4,300은 측정 경로 상한 근처 — ADR-003 §6.3).

#### ③ 커넥션 빌림

**결론**: counter-upsert는 요청당 1회(H1). ADR-005 counter의 3회 → 1회.

**근거**: Hikari 획득 COUNT ÷ 선점 요청 — S2·S4 1.000 [1.000–1.001], S7-m1 1.002(setup 포함, 세 방식 같음). prepare 타이머 0건(테스트·앱 지표 '미측정' = 기록 없음).

#### ④ 롤백 피해 — S7-m1(2매 보유자 + 일반 1명)

**결론**: counter-upsert만 억울한 좌석 0. 매수 초과 사용자가 좌석 락을 잡기 전에 거절되기 때문이다.

**근거**: 억울한 좌석(끝 상태 AVAILABLE인데 일반 사용자가 409 SEAT_NOT_AVAILABLE을 받은 좌석, 100석 중) — none 72 [63–82] · advisory-try 67 [62–74] · **counter-upsert 0 ×5**. counter-upsert에서 일반 사용자 201은 100 ×5, U(2매 보유자)는 전부 HOLD_LIMIT_EXCEEDED(100). p99 9ms(none 16 · advisory-try 18).

**편향**: M=1(경쟁자 1명)은 피해가 가장 잘 보이는 설정이다. ADR-005 S7(M=20)에서는 전 방식 0~1석이었다(경쟁자가 많으면 늦게 온 사람이 가져간다).

#### ⑤ 같은 사용자 지연 — S2(사용자 100명 × 각자 좌석 10개 동시)

**결론**: counter-upsert가 가장 빠르다. 같은 사용자 요청끼리 쿼터 행의 한 문장 UPDATE만 줄을 선다.

**근거**(중앙값 [최소–최대]):

| 방식 | L2(앱·DB CPU 2개) p50 / p99 ms | L4(앱·DB CPU 4개) p50 / p99 ms | 커넥션 획득 대기 평균 ms L2 / L4 |
|------|------|------|------|
| none | 1,285 / 2,637 | 122 / 436 | 567 / 44.5 |
| advisory-try | 1,821 / 3,510 | 160 / 433 | 676 / 56.6 |
| counter-upsert | **180 / 507** | **82 / 230** | **75.8 / 24.8** |

- counter-upsert의 acquire(매수 판정) 평균 3.35ms(L2) · 1.10ms(L4)는 같은 사용자 행 락 대기를 포함한다. 앱 타이머의 span 구간은 acquire **뒤**부터 재고, acquire에서 거절된 요청은 기록하지 않는다(`HoldSeatProcess.kt`). 그래서 counter-upsert의 span(n=200, 성공 요청만)은 다른 방식(n=1,000)과 비교하지 않는다 — 판단은 위 k6 p50·p99로 한다.

**편향**: S2 p99는 회차 간 흔들림이 크다(none L2 1,381~3,556). 하지만 counter-upsert와 나머지의 차이는 그보다 크다.

#### ⑥ 시간 효과 · I/O

**결론**: 순서 섞기로 시간 효과가 방식 차이보다 작아진 것은 **일부 지표에서만**이다(H4 부분 채택). I/O는 S4보다 **실제 흐름 S3에서 차이가 훨씬 크다**. counter-upsert의 WAL이 2.8배다.

**근거 1 — 지표별 '회차 간 흔들림 vs 방식 간 차이'**(H4 기각 기준 = 같은 방식의 회차 간 차이 > 방식 간 차이):

| 지표 | 같은 방식 회차 간 폭(최대) | 방식 간 중앙값 차 | 판정 |
|------|------|------|------|
| S4 L4 포화점 | counter-upsert 34 (4,128~4,162) | 157 (4,143 vs 4,300) | 채택 |
| S7-m1 억울한 좌석 | none 19 (63~82) | counter-upsert vs 나머지 67~72 | 채택 |
| S2 L4 p99 | advisory-try 653 (334~987) | counter-upsert vs advisory-try 203 | 기각 |
| S2 L2 p99 | none 2,175 (1,381~3,556) | none vs advisory-try 873 | 기각 |
| S4 L2 포화점 | none 332 (3,437~3,769) | none vs advisory-try 141 | 기각 |
| S4 L4 엄격 한계 | counter-upsert 1,296 (2,866~4,162) | 1,434 | 경계(폭 < 차지만 근소) — 11단계 p99가 기준 500ms에 걸친 결과 |

- 실행 순번과 결과의 상관은 계산하지 않았다. counter-upsert L4 S4 포화점은 순번 3·17·22·33·35에서 4,132 · 4,143 · 4,154 · 4,162 · 4,128이다. none·advisory-try L4 S4는 순번 1~36 내내 4,300이다.
- ADR-005에서 보인 '뒤 회차일수록 회귀'가 counter-upsert에 없다는 것은 **순서 섞기의 효과 근거가 아니다**. prepare를 없앤 코드 변경으로도 설명된다.

**근거 2 — I/O, S3-a20(실제 흐름, 이탈 20%) L4 회차 중앙값**:

| 방식 | WAL MB | 선점 요청당 WAL B | 디스크 쓰기 MB |
|------|------|------|------|
| none | 86.5 | 114 | 515 |
| advisory-try | 86.4 | 114 | 515 |
| counter-upsert | **243.0** | **320** | **796** |

- S3 선점 시도 228만 건 중 98.4%가 409다(매진 뒤 계속 시도). counter-upsert는 매수 판정이 좌석보다 먼저라서, 좌석에서 지는 시도마다 쿼터 행에 upsert(+1)를 쓰고 롤백한다. 그래서 WAL이 생기는 것으로 **추정**한다. none·advisory-try는 좌석 NOWAIT에서 먼저 지고 쓰기가 없다.
- 매진 직후 재시도가 몰리는 상황에서 counter-upsert는 **실패한 시도까지 디스크 쓰기 비용**을 낸다. S4의 +21%보다 큰 운영 비용이다.
- WAL fsync 평균(0.67~0.74ms)·디스크 쓰기 대기(0.37~0.45ms)는 회차 간 흔들림이 작다(`COMPARISON.md` 회차별 표).

**편향**: 5회 ÷ 3방식이라 첫 자리 균형이 완전하지 않다. 이월 효과(바로 앞 방식)는 균형하지 않았다. 순환이라 counter-upsert-s24 바로 앞이 5회 중 4회 advisory-try-s24다. DB는 회차마다 재시작되지만 호스트 수준(페이지 캐시·디스크)의 이월은 남는다(`k6/ADR-006/README.md`).

#### ⑦ 실패 모드

**결론**: 세 방식 모두 5xx·데드락·풀 타임아웃 0.

**근거**: 응답 분류(errsplit, 84회차) — S2·S3·S7-m1 응답 코드 0(타임아웃·연결 실패) 0. S4는 세 방식 모두 12단계(목표 6,487건/s)부터 타임아웃(0-1050) 약 5.7만 · 연결 실패(0-1211) 약 1.4만(L2·L4 10회 합, 회차당 약 7,200)이다. 연결 끊김(0-1220)은 none 443 · advisory-try 457 · counter-upsert 0. DB 로그 deadlock 0. S3-a20 확정 에러율 0 · 유령 확정 0.

### 5.4 실측 결과 전체 (2026-10-19 보강)

> §5.2·§5.3이 묶어서 보인 값을 셀별로 모두 펼친다. 값은 결과 파일에서 옮겼다. 2026-10-19에 기존 §5 표의 숫자를 결과 파일과 다시 대조했고, 틀린 숫자는 없었다(11단계 초 단위 서술의 초당 건수·p99는 커밋 안 한 원시 CSV 기반이라 이번에 다시 대조하지 않았다). 캠페인 `20261009-adr006-022eca41`, 하네스 SHA 022eca41, 2026-10-09 02:24~11:25(KST(한국 표준시)). 84회차 모두 ok(S3 9회차는 판정 버그 정정 — §5.1). 좌석 3b·풀 10·앱 1대·배경 0·`track_io_timing` on. 값 = 중앙값 [최소–최대]. 출처 표기의 `R6` = `k6/ADR-006/results/20261009-adr006-022eca41`.

#### 5.4.1 측정 전 스모크 — 판정에 쓰인 것

| 스모크 | 날짜·시각 | 앱 SHA | 구성 | 상태 | 핵심 값 | 쓰인 곳 |
|------|------|------|------|------|------|------|
| `smoke-s2-counter-upsert` | 10-09 01:33 | 6be8a410 | S2(사용자 100명 × 각자 좌석 10개 동시) L4(앱·DB CPU 4개) | ok | 매수 초과 0 · 카운터 불일치 0 · 빌림 1.000 · span 0.46ms | 하네스·판정기 확인 |
| `smoke-s4-counter-upsert` | 10-09 01:35 | 6be8a410 | S4(도착률 계단, 요청마다 새 사용자·좌석) L4 | ok | **빌림 1.0001**(가정 2 실증) · 엄격 한계 2,866 · 포화점 4,128 | H1 사전 확인 · 본측정 H2 기각의 첫 신호 |
| `smoke-s7m1-counter-upsert` | 10-09 01:45 | 6be8a410 | S7-m1(2매 보유자 + 일반 1명) L4 | ok | 억울한 좌석 0 · U HLE(매수 초과) 100 · 빌림 1.0025 | H3 사전 확인 |
| `smoke2-s2-none` | 10-09 02:19 | e88ec7bf | S2 L4 · none | ok | 매수 초과 17 · 선점 요청 집계 1,000 = 획득 1,000(작업 log — 결과 파일에서 획득 수는 다시 대조하지 않음) | 빌림 분모(URI 태그) 가정 실증 |
| `smoke-order-dryrun`·`smoke-order-rotation-dryrun` | 10-09 | — | 순서 생성만 | — | `order.txt` 재현 | 조건 순서 섞기 확인 |

출처: `k6/ADR-006/results/smoke*/summary.json`·회차 `meta.json`·`consistency.json`

#### 5.4.2 S2·S7-m1·S3-a20 — 셀별 전체

| 방식 | S2 201 수 L2 · L4 | S2 매수 초과(회차별) L2 / L4 | S2 acquire(매수 진입) 평균 ms L2 · L4 | S2 check 평균 ms L2 · L4 | S7-m1 억울한 좌석(회차별) | S7-m1 일반 201 · U HLE / SNA | S7-m1 p99 ms |
|------|------|------|------|------|------|------|------|
| none | 224 [214–257] · 224 [213–232] | **18 [12–33]** (12, 18, 27, 16, 33) / **17 [11–24]** (11, 17, 24, 16, 22) | 0.0010 · 0.0005 | 17.71 · 1.92 | 72 [63–82] (82, 76, 72, 69, 63) | 28 · 76 / 24 | 16 |
| advisory-try | 200 · 200 | 0 / 0 | 4.82 · 0.70 | 17.89 · 1.91 | 67 [62–74] (62, 68, 74, 67, 67) | 33 · 70 / 30 | 18 |
| counter-upsert | 200 · 200 | 0 / 0 | 3.35 · 1.10 | 0.0008 · 0.0007 | **0 ×5** | 100 · 100 / 0 | 9 |

- 가짜 거절·응답≠끝 상태·201 − 홀드 행은 세 방식 모두 0이다. 40001은 전 셀 0이다.
- S2·S7-m1의 I/O: 선점당 WAL(쓰기 앞 로그) B — S2 L4 none 489 · advisory-try 473 · counter-upsert 505, S7-m1 none 1,047 · advisory-try 1,063 · counter-upsert 1,318. WAL 총량은 0.4~0.5MB라 판정에 쓰지 않는다.

| 방식(S3-a20(실제 흐름, 이탈 20%) L4 × 3) | CONFIRMED | 확정 에러율 · 유령 확정 · 매수 초과 | 카운터 불일치 | 선점 요청 3회 합 2xx / 409 | WAL MB | 디스크 쓰기 MB | 체크포인트 수 |
|------|------|------|------|------|------|------|------|
| none | 9,727 [9,721–9,754] | 0 · 0 · 0 | 해당 없음 | 36,508 / 2,242,003 | 86.5 | 515 [499–520] | 3 |
| advisory-try | 9,735 [9,733–9,749] | 0 · 0 · 0 | 해당 없음 | 36,479 / 2,241,669 | 86.4 | 515 [501–516] | 3 |
| counter-upsert | 9,729 [9,713–9,745] | 0 · 0 · 0 | **0 ×3** | 36,581 / 2,242,128 | **243.0** | **796 [760–798]** | 3 |

- 선점 시도 중 409 비율은 회차마다 약 98.4%다(예: counter-upsert rep1 747,350 / 759,575). 요청당 빌림 1.013은 S3에서 확정 요청도 커넥션을 빌려서 생기는 값이다(분모는 선점 요청만).

출처: `R6/COMPARISON.md` 'S2'·'S7-m1'·'S3 원본 S3-a20'·'매수 제어 구간 타이머'·'요청당 커넥션 빌림·서버 I/O' 표 · `R6/errsplit.json`

#### 5.4.3 S4 — 회차별 한계·멈춘 단계·11단계

| 방식 | 단계 | 회차별 엄격 한계(새 규칙) | 회차별 멈춘 단계:사유 | 11단계(목표 4,325건/s) p99 ms 회차별 | 11단계 시작 → 무너진 초(차이 s) | 12·13·14·15단계 성공 건/s(중앙값) |
|------|------|------|------|------|------|------|
| none | L2 | 2,866 ×5 | 11:p99+under-delivered ×5 | — | 1~9초(CPU 포화) | — |
| none | L4 | 4,300, 4,300, 4,300, 4,300, 4,299 | 12:p99+error+under-delivered ×5 | — | 31, 32, 31, 33, 31(= 12단계 시작) | 2,793 · 2,443 · 2,385 · 2,364 |
| advisory-try | L2 | 2,866 ×5 | 11:p99+under-delivered ×5 | — | 3, 2, 2, 2, 2 | — |
| advisory-try | L4 | 4,300 ×5 | 12:p99+error+under-delivered ×5 | — | 32, 31, 32, 0, 30 | 2,803 · 2,452 · 2,442 · 2,433 |
| counter-upsert | L2 | 2,866 ×5 | 11:p99 ×5(목표 미달 없음) | 527, 568, 533, 515, 507 | **26, 26, 26, 27, 27** | — |
| counter-upsert | L4 | 4,132, 2,866, 2,866, 4,162, 2,866 | 12:p99+error+under-delivered, 11:p99, 11:p99, 12:p99+error+under-delivered, 11:p99 | **477, 514, 506, 496, 542** | **27, 26, 27, 27, 26** | 2,605 · 2,454 · 2,382 · 2,315 |

- counter-upsert L4 0~10단계 누적 요청은 회차마다 255,004~255,029건이다. 여기에 11단계 26초 × 4,325를 더하면 §5.3 ②의 '약 37만 건'이 된다.
- counter-upsert L4 실행 순번(회차 1~5): 3 · 17 · 22 · 33 · 35, 포화점 4,132 · 4,143 · 4,154 · 4,162 · 4,128(§5.3 ⑥).
- counter-upsert L4 rep2 Hikari(커넥션 풀) 대기: 06:28:27 0 → 06:28:28 190(`timeline-hikari.jsonl`).

| 방식 | 체크포인트 쓰기 시간 s(DB 로그 `checkpoint complete … write=`), L2 회차별 | L4 회차별 | 쓴 버퍼 수(L4) | L4 관계 파일 확장 · 백엔드 직접 쓰기 · 읽기 · 선점당 WAL 레코드 |
|------|------|------|------|------|
| none | 219.1, 224.9, 216.4, 211.7, 221.7 | 219.1, 209.4, 216.3, 206.5, 210.0 | 2,669~2,715 | 15,041 · 22,039 · 39,356 · 11.8 |
| advisory-try | 230.1, 229.6, 229.5, 222.7, 220.1 | 208.0, 210.3, 208.4, 211.9, 209.6 | 2,690~2,731 | 15,129 · 22,002 · 39,801 · 11.8 |
| counter-upsert | 172.5, 174.8, 175.9, 174.9, 172.6 | 172.8, 179.4, 175.9, 173.3, 172.3 | 2,684~2,733 | **21,700 · 28,025 · 48,054 · 14.8** |

출처: `R6/COMPARISON.md` 'S4 처리량 한계'·'회차별 실행 시각·순서' 표 · `R6/S4-WALL.md` · 조건별 `summary.json`(`stages`·`io`) · 회차 `db.log.gz` · `R6/counter-upsert-s24/L4/S4/rep2/timeline-hikari.jsonl`

#### 5.4.4 S4 응답 분류 — 셀 합(L2·L4 10회)

| 방식 | 2xx | 타임아웃(0-1050) | 연결 실패(0-1211) | 연결 끊김(0-1220) | 5xx |
|------|------|------|------|------|------|
| none | 6,699,330 | 57,744 | 13,530 | 443 | 0 |
| advisory-try | 6,651,572 | 56,769 | 14,495 | 457 | 0 |
| counter-upsert | 6,726,921 | 57,685 | 14,635 | 0 | 0 |

출처: `R6/errsplit.json`(재측정 중단본 `_aborted-remeasure-20261009/` 제외)

## 6. 결정

**결정(2026-10-09 사용자 확정)** — **B 유지 + 진단 후 전환**. 매수 제어 기본은 L2 `advisory-try`(ADR-005) 그대로다. counter-upsert의 처리량이 무너지는 원인을 **ADR-007**에서 찾는다. 해결되면(아래 전환 규칙) 기본을 counter-upsert로 바꾼다. 사용자 원문: "처리량이 무너지는 이유를 찾고 이게 해결되면 바꾸는 방향으로 가보자", "이걸 7번으로 해야겠네".

비교한 두 안:

| 기준 | advisory-try(ADR-005 기본) | counter-upsert |
|------|------|------|
| ① 매수 정합성 | 0 | 0 · 카운터 불일치 0 |
| ② S4 L4(앱·DB CPU 4개) 엄격 한계 / 포화점 | **4,300 / 4,300** | 2,866(3/5) / 4,143 — 11단계 시작 26~27초 뒤 벽, 지속 기간·원인 미확정 |
| ② S4 L4 12단계 성공 처리량 | 2,803 | 2,605 |
| ② S4 L2(앱·DB CPU 2개) 포화점 · CPU | 3,533 · 앱 CPU 포화 | **4,121** · 앱·DB CPU 약 150% |
| ③ 커넥션 빌림 | 1 | 1 |
| ④ S7-m1(2매 보유자 + 일반 1명) 억울한 좌석 | 67 | **0** |
| ⑤ S2 L4 p50 / p99 ms | 160 / 433 | **82 / 230** |
| ⑥ S3-a20(실제 흐름) WAL / 디스크 쓰기 | 86.4MB / 515MB | **243.0MB(×2.8) / 796MB(×1.55)** — 좌석에서 지는 시도도 upsert 후 롤백(추정) |
| 추가 스키마·경로 | 없음 | 쿼터 테이블(만료로 행이 지워지지 않음 — 고유 사용자 수만큼 증가) + 만료 배치 감소(놓치면 카운터가 어긋난다) |
| 에러 우선순위 | 보존(좌석 먼저) | 매수 먼저(ADR-005에서 허용) |

- **A. 기본을 counter-upsert로 바꾼다**: ④(롤백 피해)를 없애는 유일한 방식이다. "대기·롤백 때문에 다른 좌석을 놓치는 클라이언트가 없어야 한다"(ADR-003·004 우선순위)에 가장 맞고, 같은 사용자 지연·CPU 비용도 가장 작다. 대가는 넷이다. ① S4 벽의 원인과 지속 기간을 모른다. ② 실제 흐름에서 WAL 생성량이 2.8배, 디스크 쓰기량이 1.55배다. ③ 쿼터 테이블이 줄지 않는다. ④ 카운터 유지 경로가 생긴다.
- **B. advisory-try를 유지하고 counter-upsert는 조건부로 둔다**: 측정한 모든 처리량 지표에서 대조군과 같고 스키마 추가가 없다. 대가는 S7-m1 67석의 롤백 피해가 그대로 남는다는 점이다.
- **메인 권장: 지금은 B, 진단 결과에 따라 A로 전환**. spec §1의 질문("빌림을 1회로 줄이면 처리량 회귀가 사라지는가")에 대한 답이 L4에서 '아니오'다. 그리고 그 원인을 모른다. ④·⑤의 이득은 크고 반복마다 같다(0 vs 67, p99 230 vs 433). 하지만 원인 모를 처리량 상한과 실제 흐름의 WAL 2.8배(디스크 쓰기 1.55배)를 기본값으로 들이기엔 근거가 모자라다. 전환 규칙은 다음과 같다(§7 진단 후 판정).
  - 벽이 **설정으로 사라지거나 계단 상한 밖으로 밀리고**(체크포인트·WAL·버퍼 설정 — 운영에서 조정 가능), 고정 도착률에서도 지속 처리량이 none과 같다. **그리고** 실패 시도의 쓰기 비용이 해소되거나(예: 좌석이 남아 있을 때만 upsert하도록 순서를 바꾼 변형 — 에러 순서 재검토 필요) S3 재측정에서 WAL·디스크 쓰기 증가를 수용 가능한 수준으로 확인한다 → **A로 전환**. 쿼터 행 정리 설계를 함께 넣는다.
  - 벽이 **쿼터 테이블 구조**(확장 락·인덱스 경합)에서 오고 설정으로 안 사라진다 → **B 유지**. counter-upsert는 구조를 바꾼 뒤(예: 행 재사용·파티션) 다시 잰다.

### 6.1 의사결정 기록 (시간 순, 2026-10-19 보강)

> 시각은 KST(한국 표준시)다. 대화 기록의 시각(UTC)을 바꿔 적었다. 작업 log(`docs/plans/2026-10-09/adr-006-counter-upsert/log.md`)의 오후 행(13:10·13:30·13:40·14:00)은 대화 기록보다 약 1시간 늦게 적혀 있다. 아래는 대화 기록 시각이다. 사용자 원문은 그대로 옮겼고(오타 포함), 선택지를 고른 경우는 '선택:'으로 적는다.

| # | 시각 | 근거 지표(파일:표) | 결정 | 고르지 않은 것 | 누가 |
|---|------|------|------|------|------|
| 1 | 10-09 00:27 | ADR-005 §7.3 ③(요청당 빌림 3.00, L4 4,300 → 1,911) | counter를 트랜잭션 안 한 문장 upsert로 바꿔 새 ADR-006에서 비교한다. 기존 ADR-006~011은 한 칸씩 민다 | — | 사용자: "위 방식으로 ADR6에서 그럼 카운트를 1방쿼리 형태로 바꾼다는거잖아? 그렇게 해서 두개를 비교하고 기존의 다음 ㅁ단계들을 한단계씩 비뤄서 advisory-try는 기존 데이터 그대로 하고 count만 수정해서 재측정해서 두개만 비교하는게 깔끔해보이는데?" |
| 2 | 10-09 00:39 | ADR-005 §7.3 ③ 회차 갈림(같은 조건이 10-08 0시 무렵부터 2,866 → 1,911) | none·advisory-try·counter-upsert **세 방식을 같은 캠페인에서** 잰다. 메인이 '다른 날 데이터와 비교하면 방식 차이와 시간 변화를 가를 수 없다'고 권했다 | advisory-try는 ADR-005 데이터를 재사용하고 counter만 재측정(사용자 첫 제안) | 선택: "세 방식을 같은 캔페인에서 (Recommended)" |
| 3 | 10-09 01:04 | ADR-005 리뷰(S4 한계 규칙의 SERIALIZABLE 708, 회차 갈림, I/O 미수집) | 하네스 개선 3가지 전부(S4 한계 규칙 수정·조건 순서 섞기·서버 I/O 지표). 시나리오는 기본 안(S2·S4 L2·L4 + S7-m1 L4 × 5) + S3 이탈 20%만(L4 × 3). 기존 counter는 그대로 두고 counter-upsert를 새로 넣는다 | S3 생략 · 기존 counter 교체 | 선택: "S4 한계 규칙 수정, 조건 순서 섞기, 서버 I/O 지표 수집", "기본 안 + S3 이탈 20%만 (Recommended)", "그대로 두고 새 방식 추가 (Recommended)" |
| 4 | 10-09 01:15 | — | 명세 합의. 자율성 auto | lazy | 사용자: "진행해보자" |
| 5 | 10-09 01:33~01:48 | §5.4.1 스모크(빌림 1.0001 · S2 초과 0 · S7-m1 0) · ADR-005 S4 재계산(serializable L4 708 → 1,982) | 가정 1·2 실증으로 보고 하네스를 커밋한다. ADR-005 §7.3 ③의 '4,325 단계 목표 미달' 문구를 정정 대상으로 기록 | — | 메인 판단 — 명세 합의(01:15) 범위 |
| 6 | 10-09 02:05~02:24 | 코드·하네스 리뷰 R1~R8 + 재점검 N1(log ledger) | 앱 2건(`hasPrepare` 등)과 하네스 6건을 고치고, S3를 빌림 범위 검사에서 뺀다 → 본측정 시작(02:24) | — | 메인 — 리뷰 반영 |
| 7 | 10-09 11:50 | `R6/CAMPAIGN.log` 재측정 9회차(S3 전부 `invalid-borrow-ratio`) | 재측정을 멈추고 원 회차를 복원한다. 원인은 `run.sh`의 bash 우선순위 버그(N1 수정이 반대로 동작). 측정값은 무관 → `fbaaf815` 수정, S3 9회차 status를 ok로 정정(근거 행 보존) | 재측정을 2라운드까지 계속(약 6시간) | 선택: "중단 후 원본 복원 (Recommended)" · 사용자 확인: "측정이 잘못된게 아니라 측정값인식을 잘못했다는거지?" |
| 8 | 10-09 12:21 | §5.3 ②(L4 엄격 한계 2,866(3/5) · 11단계 +26~27초 붕괴) · ④(S7-m1 0 vs 67~72) · ⑤ | **1차 제안: A(counter-upsert 기본, 조건부)** + 짧은 진단. 1차 해석은 '단계 마지막 4초를 놓친 것이 −33%로 보인다', '누적 데이터가 `shared_buffers` 128MB를 넘는 시점이 먼저 온다(추정)'였다 | — | 메인 제안(확정 전) |
| 9 | 10-09 12:2x~12:38 | 결과 리뷰 D1~D11 + 재점검 P1~P4(log ledger) — `summary.json` 12단계 성공 2,605 < 2,793·2,803 · `UserLimitStrategies.kt` `decrementCounters`(DELETE 없음) · `db.log` 체크포인트 시각 · `COMPARISON.md` S3 I/O | **권장을 바꿨다: 지금 B, 진단 후 전환.** 1차 해석 정정: ① 벽 지속 기간은 계단 설계로 관측 불가('마지막 4초' 철회) ② 쿼터 행은 만료로 안 줄어든다 ③ 체크포인트가 벽 동안 진행 중이었고 counter-upsert만 가속 ④ 실제 흐름 S3에서 WAL 2.8배·디스크 쓰기 1.55배 ⑤ '누적 상태' 단정 철회 — 누적과 경과 시간이 겹친다 ⑥ H4는 지표별 부분 채택 | A(1차 제안) | 메인 — 리뷰 반영 |
| 10 | 10-09 12:37~12:38 | §6 비교표 · 위 9행 정정 셋(지속 기간 미관측·쿼터 행 비삭제·S3 쓰기 비용) | **ADR-006 결정: B 유지 + 진단 후 전환.** 기본은 advisory-try. 벽 원인 진단을 새 ADR-007로 하고 기존 ADR-007~012 → 008~013. 전환 규칙은 §6 | A(지금 counter-upsert로 전환) | 선택: "B 유지 + 진단 후 전환 (Recommended)" · 사용자: "처리량이 무너지는 이유를 찾고 이게 해결되면 바꾸는 방향으로 가보자", "이걸 7번으로 해야겠네" |
| 11 | 10-09 12:43~12:45 | — | push + PR + 머지(PR #6). study-note 아카이브는 ADR-007 원인 확정 뒤로 보류. 사용자 선택이 '이슈 3건'과 '이번엔 아카이브 안 함'으로 엇갈려 메인이 다시 물었다 | 이슈 3건 지금 아카이브 | 선택: "push + PR 생성 + 머지" · 사용자: "2번으로 하자" |
| 12 | 10-09 12:48~12:55 | §7 진단 축 · §5.3 ⑥ 근거 2(S3 WAL 2.8배) | ADR-007 범위: 진단 4축 전부, 쓰기 비용을 줄이는 변형(counter-upsert-precheck)을 함께 잰다. 비교 대상에서 advisory-try를 뺀다(롤백 피해를 풀지 못하므로) | advisory-try를 ADR-007 비교에 포함 | 선택: "대기 이벤트 샘플링 (Recommended),체크포인트 설정 축 (Recommended),shared_buffers 128MB vs 512MB,고정 도착률(누적 vs 시간·지속성)", "이번에 함께 (Recommended)" · 사용자: "try는 이걸 한다고 억울한 좌석이 해결되는건 아니지않아?" — 이후 결정은 ADR-007 |

**검토 기록 — 2026-10-19 사후 대조 검토**

- **무엇을 받았나**: 사용자 요청("각 실제 지표를 기반으로 다 기록하고 이 기록에 따른 의사결정을 어떻게 했는지 전부 ADR 문서에") 뒤, 별도 에이전트(Opus 워커 — 생성과 검증을 나눈 독립 컨텍스트)가 이 ADR의 결과·결정을 다시 대조했다.
  - 기준: 결과 파일(COMPARISON·TAIL·SUMMARY·원시 json)이 숫자의 유일한 출처. 작업 log의 타임라인·리뷰 ledger와 명세 §0이 결정의 정본. 대화 기록은 빠진 표·결정을 찾는 데만 썼다.
  - 범위: 이 ADR의 기존 숫자 전부를 결과 파일과 대조하고, 빠진 실측 표와 결정 행을 보강했다(위 결과 절과 결정 기록).
- **어떻게 판단했나(공통 원칙)**: 측정 전에 고정한 절(가설·성공 기준·측정 전 한계·측정 전 설계)은 **문구를 고치지 않는다** — 무엇을 먼저 정했는지가 판정의 근거이기 때문이다. 그 절에서 찾은 틀린 값·철회된 결정은 아래 표에 실제 값과 함께 남긴다. 측정 뒤 절(결과·판정·결정)에서 찾은 오류는 각주를 달아 고친다(무음 수정 금지). 결과 파일로 다시 확인할 수 없는 값은 '결과 파일 미확인'으로 표시하고 판정 근거에서 뺀다.
- **작업 log 시각**: log 타임라인의 일부 행이 대화 기록·커밋 시각과 몇 시간씩 어긋난다. log는 발생 시점 기록이라 고치지 않는다 — 이 ADR의 결정 기록은 대화 기록 시각을 기준으로 쓴다.

| 발견 | 근거 | 판단 | 반영 |
|---|---|---|---|
| `smoke2-s2-none` '선점 요청 1,000 = 획득 1,000' | 작업 log 값 — after-k6.json에서 획득 수 키를 못 찾음 | '결과 파일 미확인' | §5.4 |
| §5.3 ② 11단계 초당 4,309~4,341건 · 초별 p99 4~25ms | 커밋 안 한 원시 CSV 기반 — 재집계 안 함 | '결과 파일 미확인' | §5.4 머리 |
| 'checkpoint_write_time 206~230초 대 172~179초' · '228만 건 중 98.4%' | db.log.gz(L2·L4) · S3 3회 합과 대조 — 일치(L4만 보면 206~219) | 변경 없음 | — |
| 작업 log 오후 행(13:10~14:00) | 대화 기록보다 약 1시간 늦음 — 사용자 결정 12:37~12:38 KST | log 무변경 · 결정 기록은 대화 시각 | §6.1 주석 |

## 7. 다음 ADR로 넘기는 것

- **ADR-007 벽 진단(사용자 확정 — 새 ADR-007로)** — counter-upsert·none, S4 L4. 한 실험으로 원인을 확정하려 하지 않는다. 각 축의 결과는 원인 **단서**이며, 대기 이벤트·지표와 함께 본다.
  1. 대기 이벤트: `pg_stat_activity` `wait_event_type`·`wait_event`를 초 단위로 샘플링한다(LWLock:WALWrite·BufferContent·extend, IO:DataFileWrite 등). 체크포인트 로그 시각과 벽 시각을 정렬한다.
  2. 설정 축: `max_wal_size`(체크포인트 간격) · `shared_buffers` 128MB vs 512MB. 설정을 바꾸면 캐시·쓰기·체크포인트 시점이 함께 달라진다. 그래서 벽이 밀린 것만으로 원인을 단정하지 않는다.
  3. 누적 vs 시간: 도착률을 낮춰 같은 누적(약 37만 건)에 더 늦게 닿게 한다. 벽이 누적을 따라가는지 시간(체크포인트 주기)을 따라가는지 가른다.
  4. 지속성: 4,0xx~4,325건/s **고정 도착률로 5분 이상** 걸어, 벽이 일시적인지 상한인지 본다.
- **ADR-014(홀드 만료)·ADR-009(카운터 어긋남 탐지·복구 — 카운터 정합 점검·쿼터 행 정리)**: counter-upsert를 택하면 만료 배치가 카운터를 내리는 주체다. 만료·취소·확정 경로마다 카운터 정합(`v_counter_mismatch`)을 판정기로 계속 본다. **쿼터 행은 만료로 지워지지 않는다** — 회차 종료 뒤 정리(삭제·파티션)와 롤백 잔해(dead tuple) 정리(vacuum) 비용을 함께 본다.
- **측정 경로 상한**: 12단계(6,487건/s)에서 세 방식이 같은 크기로 무너지는 현상이 ADR-003 §6.3의 경로 상한과 같은 것인지 별도 확인이 필요하다(이번 측정에서는 판정하지 않음).
- **하네스**: 재측정 판정 버그(bash 우선순위)는 수정했다(fbaaf815). 쉘 판정식은 `if`로 묶는다.

## 8. 사용한 파일 — 위치와 설명

| 파일 | 설명 |
|------|------|
| `src/main/kotlin/com/jun/labs/seatreservation/service/impl/limit/UserLimitStrategies.kt` | `CounterUpsertUserLimit` — acquire의 한 문장 upsert, 0행이면 `HOLD_LIMIT_EXCEEDED` · 만료 감소 공용 함수 |
| `src/main/kotlin/com/jun/labs/seatreservation/service/UserLimitStrategyType.kt` | `COUNTER_UPSERT` 설정값 |
| `src/main/kotlin/com/jun/labs/seatreservation/service/impl/HoldSeatService.kt` | `hasPrepare`일 때만 prepare 호출·타이머 |
| `src/main/kotlin/com/jun/labs/seatreservation/loadtest/LoadtestDataService.kt` | 판정기 `v_counter_mismatch`·배경 행 거부를 counter 계열에 |
| `src/test/kotlin/.../UserLimitStrategyTest.kt` | counter-upsert 계약 묶음 + 새 사용자 동시 10요청 경합 · 롤백 · prepare 0건 |
| `k6/ADR-006/` | 하네스(ADR-005 복사 + S4 새 한계 규칙 · 균형 순환 순서 · I/O 지표) — `README.md` 사용법 |
| `k6/ADR-006/results/20261009-adr006-022eca41/COMPARISON.md` | 이 문서의 표 원본(스크립트 산출) · `comparison.json` · `errsplit.json` · 조건별 `SUMMARY.md` |
| `k6/ADR-006/results/20261009-adr006-022eca41/order.txt` · `CAMPAIGN.log` | 계획 순서 · 실제 실행·정정 기록 |
| `k6/ADR-006/results/20261009-adr006-022eca41/S4-WALL.md` | 11단계 안에서 지연이 무너진 초(회차별) — §5.3 ② 근거 2 |
| `k6/ADR-006/results/adr005-recomputed-s4/S4-RECOMPUTED.md` | ADR-005 S4를 새 규칙으로 다시 계산한 표 |
| `docs/plans/2026-10-09/adr-006-counter-upsert/` | 명세·작업 로그(리뷰 ledger) |

- 11단계 초 단위 분석(근거 2)은 `k6/ADR-006/scripts/s4_wall.py`가 요청별 원시 기록(`k6-requests.csv.gz`, 커밋 안 함 — `requests-sha256.txt`)에서 낸다 → `results/20261009-adr006-022eca41/S4-WALL.md`.
