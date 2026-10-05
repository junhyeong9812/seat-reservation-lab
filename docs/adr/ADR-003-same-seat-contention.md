# ADR-003: 같은 좌석 동시 선점 (Q1) — 락 방식 비교
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 가설 (Hypothesis) — 구현·하네스 완료, 측정 전 검증 중(§6)
- 날짜: 2026-10-03
- 대응 질문: Q1 "같은 좌석을 1,000명이 동시에 눌렀다면?"
- 선행: ADR-000(측정 대상), ADR-001(판정기·하네스), **ADR-002(기준선 = 일반 인덱스 + 풀 10, before 수치 D5)**
- 후행: ADR-004(임계 구역 길이·락 보유 시간 — S6 지연 변형), ADR-005(1인 2매 — 사용자 단위 직렬화), ADR-006(홀드 만료), ADR-007(확정 원자성)
- 명세: `docs/plans/2026-10-03/adr-003-lock-comparison/requirement-spec.md`

## 1. 문제

기준선의 선점은 `좌석 조회 → AVAILABLE인지 앱에서 비교 → HELD 저장`이다(check-then-act). 두 요청이 모두 `AVAILABLE`을 읽은 뒤 각자 저장할 수 있다.

ADR-002가 관측한 before(인덱스 있음·풀 10):

- S1(같은 좌석 1,000명) 성공 **L2 10 [2–10] · L4 10 [10–10]** — 정합이면 1. 중복 성공의 **상한이 풀 크기**(풀 20 → 20, 40 → 40)였다: 동시에 "비어 있음"을 볼 수 있는 요청 수가 동시에 DB를 쓰는 요청 수다(ADR-002 D3).
- 좌석 테이블만 보면 `HELD` 하나로 보이지만 홀드 행은 여러 개다 — 판정기가 `v_duplicate_hold_seats`·`v_excess_hold_rows`로 잡는다.
- 처리량은 L2 2,867 · L4 ≥6,294건/s(계단 상한 — 한계 미관측), 병목은 앱 CPU 쪽(ADR-002 D1).

## 2. 선택지

| # | 방식(전략 키) | 1명만 이기게 하는 수단 | 진 쪽 | 대기형 | 비고 |
|---|------|------|------|------|------|
| 0 | `none` | — | — | — | 대조군(기준선) |
| 1a | `jvm-lock` | 좌석별 JVM 락(줄무늬 65,536개)을 **트랜잭션 밖**에서 — 커밋까지 포함 | 커밋된 HELD를 읽고 409 | 예(JVM) | 인스턴스 1대에서만 유효 |
| 1b | `jvm-lock-in-tx` | 같은 락을 트랜잭션 **안**에서 — 커밋 전에 해제 | 막지 못함 | 예(JVM) | 함정 재현(@Transactional 안 synchronized) |
| 2 | `conditional-update` | `UPDATE … SET status='HELD' WHERE id=? AND status='AVAILABLE'` 영향 행 수 | 0행 → 409 | 행 락(짧게) | DB 원자 연산 |
| 3a | `pessimistic` | `SELECT … FOR UPDATE`(좌석 행) | 앞 트랜잭션 커밋 후 HELD 읽고 409 | 예(DB 행 락) | 커넥션을 잡고 기다림 |
| 3b | `pessimistic-nowait` | `FOR UPDATE NOWAIT` | 잠겨 있으면 즉시 409 | 아니오 | |
| 4 | `optimistic` | 버전 열 `UPDATE … SET version=version+1 WHERE version=?` | 0행 → 409(재시도 없음) | 커밋 대기(짧음) — 진 쪽의 좌석 UPDATE가 이긴 쪽 행 락을 커밋까지 기다린 뒤 버전 불일치로 진다 | `@Version`은 엔티티에 달지 않는다(모든 전략에 걸려 기준선이 바뀜) → 버전 조회·버전 UPDATE로 **왕복 2회가 더 든다** — JPA `@Version`(flush 때 한 문장)보다 비용이 크게 측정된다(편향) |
| 5 | `unique` | `seat_hold(seat_id)` 유니크(V4, 이 전략에서만) | 중복 키 → 409 | 인덱스 삽입 대기 | 홀드는 확정·만료 시 행이 지워져 좌석당 1행 불변식과 맞음 |
| 6 | `advisory` | `pg_advisory_xact_lock(seat_id)` | 앞 트랜잭션 후 HELD 읽고 409 | 예(DB 락) | 행과 무관한 락 |
| 7 | `redis-nx` | Redis `SET hold:seat:{id} {token} NX EX <TTL초>` 성공만 DB 저장 — **Redis는 관문, DB가 원천** | 키 실패 → 409 | 아니오 | DB 실패 시 자기 키 삭제. 삭제 실패면 TTL 동안 좌석이 막힘(부분 실패) |
| 8 | `redis-lock` | Redisson 분산락(대기 무제한, watchdog 임대) 안에서 DB 로직, 해제는 커밋 뒤 | 커밋된 HELD를 읽고 409 | 예(Redis) | 커넥션은 잡지 않고 기다림 |

- 진 쪽 응답은 모든 방식에서 기준선과 같은 **409 `SEAT_NOT_AVAILABLE`** — 응답 계약 불변.
- 확정·만료 경로와 1인 2매(`HoldLimitPolicy`)는 바꾸지 않는다.

> **SQL 표기에 대해**: 아래 SQL은 **Hibernate가 실제로 보낸 문장**이다 — 본측정 캠페인이 끝난 뒤(2026-10-05) 전략 테스트를 `spring.jpa.show-sql=true`로 돌려 받은 로그에서 옮겼다(별칭 `ps1_0` 등은 Hibernate가 붙인 그대로). 네이티브 쿼리(조건부 UPDATE·버전)는 코드 문자열 그대로, advisory는 JdbcTemplate이라 Hibernate 로그 밖이다.

### 2.1 공통 선점 흐름 — 모든 방식이 공유하는 규칙과 쿼리

선점 규칙 자체는 하나(`HoldSeatProcess.hold`)이고, 방식마다 **어디서 락을 잡고 트랜잭션을 어디까지 감싸는지**만 다르다. 규칙 쪽은 트랜잭션을 직접 열지 않는다(`Propagation.MANDATORY` — 전략이 연 트랜잭션 안에서만 돈다).

```kotlin
// src/main/kotlin/.../service/impl/HoldSeatProcess.kt
@Transactional(propagation = Propagation.MANDATORY)
fun hold(
    command: HoldSeatCommand,
    loadSeat: ((seatId: Long, scheduleId: Long) -> ProductSeat?)? = null,
    beforeMutation: (ProductSeat) -> Unit = {},
    afterFlush: (ProductSeat) -> Unit = {},
): HoldSeatResult {
    val load = loadSeat ?: productSeatRepository::findByIdAndScheduleId
    val seat = load(command.seatId, command.scheduleId)
        ?: throw SeatReservationException(ErrorCode.SEAT_NOT_FOUND)
    seat.assertHoldable() // 에러 우선순위: 선점 불가가 매수 초과보다 먼저
    holdLimitPolicy.check(command.scheduleId, command.userId, properties.maxPerUser)

    beforeMutation(seat)
    val hold = seat.hold(command.userId, clock.instant(), properties.ttl)
    productSeatRepository.flush()
    afterFlush(seat)
    return HoldSeatResult(holdId = hold.id!!, seatId = seat.id!!, expiresAt = hold.expiresAt)
}
```

단계와 그때 나가는 쿼리(락 없음 기준):

| 단계 | 하는 일 | 쿼리 |
|------|--------|------|
| ① 트랜잭션 시작 | 커넥션 풀(Hikari)에서 커넥션 획득, `BEGIN` | — |
| ② 좌석 읽기 | 좌석 애그리거트 로드 | `select ps1_0.id,ps1_0.row_no,ps1_0.schedule_id,ps1_0.seat_no,ps1_0.section,ps1_0.status from product_seat ps1_0 where ps1_0.id=? and ps1_0.schedule_id=?` |
| ③ 선점 가능 확인 | `status == AVAILABLE`인지 **앱 메모리에서** 비교 (아니면 409) | — |
| ④ 1인 2매 확인 | 홀드 수 + 확정 예약 수 < 2 (아니면 409 `HOLD_LIMIT_EXCEEDED`) | `select count(ps1_0.id) from product_seat ps1_0 left join seat_hold h1_0 on ps1_0.id=h1_0.seat_id where ps1_0.schedule_id=? and h1_0.user_id=?` (메서드 이름 경로라 Hibernate가 `left join`을 만든다 — WHERE의 `h1_0.user_id` 조건 때문에 결과는 내부 조인과 같다) · `select count(r1_0.id) from reservation r1_0 where r1_0.schedule_id=? and r1_0.user_id=? and r1_0.status=?` |
| ⑤ 전이 전 확장점 | 방식별(조건부 UPDATE 자리) | — |
| ⑥ 상태 전이 | `seat.hold()` — 상태를 HELD로, 홀드 컬렉션에 추가(소유 쪽 단방향 컬렉션이라 추가하려면 먼저 로드된다) | `select h1_0.seat_id,h1_0.id,h1_0.expires_at,h1_0.held_at,h1_0.schedule_id,h1_0.user_id from seat_hold h1_0 where h1_0.seat_id=?` |
| ⑦ flush | 홀드 INSERT, 좌석 UPDATE, **홀드 외래키 UPDATE** | `insert into seat_hold (seat_id,expires_at,held_at,schedule_id,user_id) values (?,?,?,?,?)` · `update product_seat set row_no=?,schedule_id=?,seat_no=?,section=?,status=? where id=?`(변경 감지가 전체 열을 쓴다) · `update seat_hold set seat_id=? where id=?` — 단방향 `@OneToMany @JoinColumn`은 자식 INSERT 뒤 외래키를 한 번 더 UPDATE한다(INSERT에 이미 seat_id가 있어도). **선점 한 번에 쓰기 3문장** |
| ⑧ flush 후 확장점 | 방식별(낙관락 버전 검사 자리) | — |
| ⑨ 커밋 | `COMMIT` → 커넥션 반납 | — |

**경합 구멍(락 없음)**: ②의 읽기와 ⑦의 쓰기 사이에 아무것도 막지 않는다. 두 요청이 모두 ②에서 AVAILABLE을 읽으면 둘 다 ③을 통과한다. ⑦의 좌석 UPDATE는 먼저 쓴 쪽이 행 락을 잡고 있다가 커밋하면 뒤쪽이 이어서 쓴다(조건 없는 UPDATE라 다시 검사하지 않음) — 홀드 행이 두 개 생긴다. 격리 수준은 PostgreSQL 기본 READ COMMITTED.

### 2.2 [0] `none` — 락 없음(대조군)

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute { process.hold(command) }!!
```

- **락**: 없다. 트랜잭션 하나로 §2.1의 ①~⑨를 그대로 돈다.
- **락 구간**: 없음 — 경합 구멍은 §2.1 그대로(② 읽기 ~ ⑦ 쓰기).
- **쿼리**: §2.1 표 그대로.

### 2.3 [1a] `jvm-lock` — 좌석별 JVM 락, 트랜잭션 밖

```kotlin
class SeatStripedLocks(stripes: Int = 65_536) {
    private val locks = Array(stripes) { ReentrantLock() }

    fun <T> withLock(seatId: Long, block: () -> T): T {
        val lock = locks[Math.floorMod(seatId.hashCode(), locks.size)]
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}

override fun hold(command: HoldSeatCommand): HoldSeatResult =
    locks.withLock(command.seatId) { tx.execute { process.hold(command) }!! }
```

- **락**: 앱 메모리의 `ReentrantLock` — 좌석 id를 65,536개 줄무늬(stripe) 중 하나에 대응시킨다. 같은 줄무늬를 쓰는 요청은 한 번에 하나만 들어간다. DB 쿼리는 §2.1 그대로.
- **락 구간**: `lock()` → **①~⑨ 전체(커밋 포함)** → `unlock()`. 다음 요청은 앞 요청의 커밋이 끝난 뒤에 ②를 읽으므로 HELD를 보고 ③에서 진다(409).

```
요청 A: lock ─ ① BEGIN ─ ② 읽기(AVAILABLE) ─ … ─ ⑦ 쓰기 ─ ⑨ COMMIT ─ unlock
요청 B:   lock 대기 ……………………………………………………………………………………………… ① ② 읽기(HELD) ─ ③ 409
```

- **대기**: JVM 스레드(Tomcat 워커)가 `lock()`에서 기다린다. DB 커넥션은 아직 잡지 않은 상태로 기다린다(①이 락 안쪽).
- **한계**: 락이 JVM 안에만 있다 — 앱이 2대면 서로의 락을 모른다(⑦ 수평 확장).

#### 줄무늬(striping) 65,536개는 좌석 수의 상한이 아니다

좌석이 몇 개든(공연 여러 개, 호텔 객실 수백만 개) 그 좌석들을 **65,536개의 락에 나눠 담는다** — 락 번호 = `floorMod(seatId.hashCode(), 65536)`.

- **정확성은 좌석 수와 무관하게 유지된다**: 같은 좌석은 항상 같은 락에 걸린다 → 같은 좌석을 동시에 둘이 잡지 못한다.
- **비용은 '서로 다른 좌석이 같은 락을 공유'하는 것(거짓 경합)**: 좌석 1번과 65,537번은 같은 락이라, 두 좌석에 동시에 요청이 오면 상관없는 좌석인데도 한쪽이 기다린다.
- **거짓 경합 확률을 정하는 것은 전체 좌석 수가 아니라 '그 순간 동시에 처리 중인 요청 수'다**: 요청 하나가 진행 중인 다른 요청과 락을 공유할 확률 ≈ 동시 처리 수 ÷ 65,536. Tomcat 워커 200개가 전부 다른 좌석을 처리 중이어도 약 0.3% — 객실이 천만 개여도 동시 처리 수가 같으면 같다.
- **이 측정에서**: S4는 연속 좌석 번호라 동시에 처리되는 좌석끼리 65,536 이상 차이 날 일이 거의 없다 → 거짓 경합은 사실상 0. S1은 같은 좌석 하나라 줄무늬와 무관하다.
- **락 키에 범위(회사·공연)를 붙여야 하나**: 이 랩의 좌석 id는 DB가 발급하는 전역 고유 번호(BIGSERIAL)라 좌석 id만으로 유일하다 — 회사·공연 id를 붙여도 구분력은 늘지 않는다. 키를 합쳐야 하는 것은 id가 **범위 안에서만 유일할 때**다(공연장마다 다시 시작하는 좌석 번호, 회사별로 따로 발급되는 id) — 이때 `회사:공연:좌석`으로 범위를 붙이지 않으면 다른 좌석이 같은 키가 된다(정확성 문제). 키를 길게 해도 줄무늬 바구니 수는 그대로라 거짓 경합 확률은 변하지 않는다.
- **동시 처리 수가 큰 시스템의 대안**: 좌석 키마다 락을 만들고 다 쓰면 회수하는 방식(키별 락 맵 + 참조 수 / 약한 참조 맵) — 거짓 경합이 없는 대신 락 생성·회수 비용이 든다. 다만 여러 공연·호텔을 다루는 실서비스는 대개 앱이 여러 대라 **JVM 락 자체가 선택지가 아니다**(⑦) — 이 실험에서 1a는 "앱 1대일 때의 기준점"으로만 본다.

#### JVM 락의 본질 — DB 조회 앞에 세운 JVM 안의 대기열

JVM 락은 DB에 좌석을 **읽으러 가기 전에** 앱 안에 좌석별 대기열을 세워 두고, 앞 요청의 처리(1a는 커밋까지)가 끝날 때까지 다음 요청을 **앱 안에서 멈춰 두는** 방식이다. DB는 경합을 모른다 — 같은 좌석의 요청이 DB에는 한 번에 하나씩만 도착하게 앱이 미리 줄을 세울 뿐이고, "이 좌석이 이미 HELD인가"의 판정은 여전히 DB에서 읽어 앱이 비교한다(§2.1의 ②·③).

```
요청 1 ─┐
요청 2 ─┤  [JVM: 좌석 1번 대기열]  ──한 번에 하나──▶  ① BEGIN ② 읽기 ③ 비교 … ⑨ COMMIT  ──▶ 다음 요청 입장
요청 3 ─┘    (Tomcat 워커 스레드가 lock()에서 멈춰 있음)
```

- **멈춰 있는 것은 Tomcat 워커 스레드**다(1a는 DB 커넥션을 잡기 전, 1b는 잡은 뒤). 같은 좌석에 1,000명이 몰리면 워커(기본 200개)가 대기열에 묶이고, 그 사이 다른 좌석의 요청은 남은 워커로 처리된다.
- **줄 서는 순서는 보장되지 않는다**: 코드의 `ReentrantLock()`은 기본값이 비공정(non-fair) — 락이 풀리는 순간 막 도착한 스레드가 기다리던 스레드보다 먼저 잡을 수 있다(처리량을 위한 기본값). 대기열이지만 FIFO가 아니라, 공정성(② · H7)의 "대기형은 먼저 온 요청이 이긴다"는 1a에 대해 성립하지 않을 수 있다. FIFO가 필요하면 `ReentrantLock(true)`(공정 모드 — 처리량이 떨어진다).
- **대기열의 단위**가 세 가지로 갈린다:

| 구현 | 대기열(또는 판정)의 단위 | 같은 좌석 요청 | 다른 좌석 요청 | 판정 원천 | 이 측정 |
|------|----------------------|-------------|-------------|---------|--------|
| **줄무늬 락**(1a·1b, 지금 구현) | 좌석을 65,536개 바구니로 나눈 바구니마다 대기열 | 줄 선다 | 같은 바구니면 줄 선다(거짓 경합) | DB(락 안에서 읽어 비교) | 비교함 |
| **키별 락** | 좌석마다 대기열, 대기자가 없어지면 회수 | 줄 선다 | 줄 서지 않는다 | DB(락 안에서 읽어 비교) | 비교 안 함 |
| **메모리 관문**(`ConcurrentHashMap.putIfAbsent(seatId, 소유자)`) | 좌석 키에 "홀드됨" 기록 — 대기열이 아니다 | 줄 서지 않고 즉시 409 | 영향 없음 | **앱 메모리의 기록**(먼저 기록한 쪽이 이김) | 비교 안 함 |

- **키별 락**은 줄무늬에서 거짓 경합만 뺀 것이다. 이 측정에서는 거짓 경합이 사실상 0이라(S1은 좌석 하나, S4는 연속 좌석) 줄무늬와 결과가 같을 것으로 보고 비교하지 않았다.
- **메모리 관문**은 락이 아니라 7번 redis-nx(`SET NX`)를 앱 메모리에 둔 것과 같다 — 줄 세우지 않고 바로 실패시켜 빠르지만, 홀드 만료·확정 때 기록을 지워야 하고, 재시작하면 기록이 사라지고, 앱이 2대면 서로의 기록을 모른다. redis-nx 결과에서 Redis 왕복을 뺀 정도로 예상할 수 있어 비교하지 않았다.
- 셋 다 **앱 1대에서만 유효**하다 — 서버가 여러 대(MSA)면 DB·Redis 쪽 수단이나 키 기준 라우팅으로 가야 한다(NEXT N7).

### 2.4 [1b] `jvm-lock-in-tx` — 같은 락을 트랜잭션 안에서(함정 재현)

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult =
    tx.execute { locks.withLock(command.seatId) { process.hold(command) } }!!
```

- **락**: 1a와 같은 JVM 락. 위치만 다르다 — `@Transactional` 메서드 안에서 `synchronized`를 거는 흔한 실수와 같은 구조.
- **락 구간**: ① BEGIN → `lock()` → ②~⑧ → `unlock()` → **⑨ COMMIT**. 락을 푼 뒤에 커밋한다.

```
요청 A: ① BEGIN ─ lock ─ ② 읽기(AVAILABLE) ─ … ─ ⑦ 쓰기(미커밋) ─ unlock ─┐ ⑨ COMMIT
요청 B: ① BEGIN ─ lock 대기 ……………………………………………… lock ─ ② 읽기 ──┘
                                           ↑ 이 틈(unlock ~ A의 COMMIT)에 B가 읽으면 커밋된 AVAILABLE을 본다 → 중복
```

- **구멍**: unlock과 COMMIT 사이의 틈. 그 사이에 B가 ②를 읽으면 A의 HELD는 아직 커밋 전이라 보이지 않는다(READ COMMITTED). B는 ③을 통과하고, ⑦의 좌석 UPDATE는 A의 행 락을 기다렸다가 A 커밋 뒤 그대로 써서 홀드가 둘이 된다.
- **관측(§6.2)**: 로컬 테스트에서는 커밋이 빨라 틈이 거의 없어 중복이 나지 않았다 — 본측정(원격 DB)에서 드러나는지 본다(H2).
- **대기**: B는 ①에서 커넥션을 이미 잡은 채로 `lock()`을 기다린다 — 1a와 달리 대기 중에도 커넥션을 점유한다.

### 2.4.1 1a와 1b 비교 — 같은 락, 위치만 다르다

**코드 — 감싸는 순서 하나만 다르다**

```kotlin
// 1a jvm-lock: 락이 트랜잭션을 감싼다
override fun hold(command: HoldSeatCommand): HoldSeatResult =
    locks.withLock(command.seatId) { tx.execute { process.hold(command) }!! }
//  └ lock ────────── └ BEGIN … COMMIT ──────────────┘ ── unlock

// 1b jvm-lock-in-tx: 트랜잭션이 락을 감싼다
override fun hold(command: HoldSeatCommand): HoldSeatResult =
    tx.execute { locks.withLock(command.seatId) { process.hold(command) } }!!
//  └ BEGIN ──── └ lock ─────────────── unlock ┘ ── COMMIT
```

1b는 실무에서 흔한 실수와 같은 구조다:

```kotlin
@Transactional
fun hold(command: HoldSeatCommand) {
    synchronized(lockFor(command.seatId)) {   // 메서드 본문 = 트랜잭션 안
        // 읽고 → 검사하고 → 쓴다
    }
}   // ← 커밋은 메서드가 끝난 뒤 프록시가 한다 = 락을 푼 다음
```

**단계별 비교 (§2.1의 ①~⑨)**

| 단계 | 1a jvm-lock | 1b jvm-lock-in-tx |
|------|------------|-------------------|
| 락 획득 | ① **이전** | ① BEGIN **이후** |
| ① 커넥션 획득·BEGIN | 락 안 | 락 밖(먼저) |
| ②~⑧ 읽기·검사·쓰기 | 락 안 | 락 안 |
| 락 해제 | ⑨ COMMIT **이후** | ⑨ COMMIT **이전** |
| 다음 요청이 ②에서 보는 것 | 앞 요청의 **커밋된** HELD | 틈 안이면 커밋 전이라 **AVAILABLE**(READ COMMITTED) |
| 정합성 | 성공 1(앱 1대) | 틈에 들어오면 중복 |
| 진 쪽이 기다리는 곳 | `lock()` — 커넥션 **없이** | `lock()` — 커넥션을 **잡은 채** |
| 대기 중 커넥션 풀 | 비어 있다(이긴 쪽 1개만 사용) | 대기자 수만큼 찬다(풀 10이면 최대 10명이 커넥션을 쥐고 락을 기다림) |

**시간 축에서 본 차이**

```
1a ─ 락이 커밋까지 덮는다
  A: [lock][BEGIN ② ③ ④ ⑥ ⑦ COMMIT][unlock]
  B:  ······ lock 대기(커넥션 없음) ······[lock][BEGIN ② HELD → 409]

1b ─ 락 해제와 커밋 사이에 틈이 있다
  A: [BEGIN][lock ② ③ ④ ⑥ ⑦ unlock] ▒▒ [COMMIT]
  B: [BEGIN][lock 대기(커넥션 쥔 채) ·····][lock ② ……
                                         ▲ ▒▒ = 틈: B의 ②가 여기서 읽히면 A의 HELD는 아직 커밋 전 → AVAILABLE을 본다
                                           → B는 ③ 통과 → ⑦ 좌석 UPDATE가 A의 행 락을 기다렸다가 A 커밋 뒤 그대로 씀 → 홀드 2행
```

**왜 1b가 늘 깨지지는 않나**: 틈의 길이 = A의 `unlock` → `COMMIT` 완료 시간(커밋 왕복 + WAL 기록). B가 그 안에 ②의 SELECT를 DB에 도달시켜야 중복이 난다. 로컬 테스트 DB에서는 커밋이 빨라 50명 × 5라운드에서 중복 0이었다(§6.2). 원격 DB·부하 중에는 커밋이 느려져 틈이 커진다 — 본측정이 판정한다(H2). 틈이 드물게만 열린다면 "가끔 깨지는" 버그가 되어 테스트로 잡기 더 어렵다는 점이 이 함정의 실제 위험이다.

**고치는 법(1b → 1a)**: 락이 커밋까지 덮게 순서를 바꾼다 — 트랜잭션을 여는 쪽(서비스 메서드)을 락 안쪽으로 옮기거나, 락을 트랜잭션 경계 밖(호출하는 쪽)에서 건다. 단 어느 쪽이든 앱 여러 대에서는 무의미하다(⑦) — 그때는 DB(3a·2·5·6)나 Redis(7·8) 쪽 수단으로 간다.

### 2.5 [2] `conditional-update` — 조건부 UPDATE

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute {
    process.hold(command, beforeMutation = { seat ->
        if (seats.holdIfAvailable(seat.id!!, seat.scheduleId) == 0) seatTaken()
    })
}!!
```

```kotlin
// ProductSeatRepository
@Modifying
@Query(
    "UPDATE product_seat SET status = 'HELD' WHERE id = :id AND schedule_id = :scheduleId AND status = 'AVAILABLE'",
    nativeQuery = true,
)
fun holdIfAvailable(id: Long, scheduleId: Long): Int
```

- **추가 쿼리(⑤)**: `UPDATE product_seat SET status = 'HELD' WHERE id = ? AND schedule_id = ? AND status = 'AVAILABLE'` — 영향 행 수 1이면 이김, 0이면 진 것(409).
- **락**: DB 행 락(UPDATE가 자동으로 거는 배타 행 락). 별도 락 문장은 없다.
- **락 구간**: ⑤에서 행 락 획득 → ⑨ 커밋 때 해제. 같은 좌석의 두 번째 UPDATE는 첫 번째의 커밋을 기다렸다가 **WHERE를 최신 행으로 다시 평가**한다(PostgreSQL READ COMMITTED의 UPDATE 재검사) — status가 HELD라 0행 → 진 것.

```
요청 A: ① ② ③ ④ ⑤ UPDATE … status='AVAILABLE' (1행, 행 락) ─ ⑥ ⑦ ─ ⑨ COMMIT(락 해제)
요청 B: ① ② ③ ④ ⑤ UPDATE … (A의 행 락 대기) ……………………… 재평가: HELD → 0행 → 409
```

- ②·③은 여전히 낡은 스냅샷을 읽을 수 있지만, 최종 판정은 ⑤의 원자적 조건이 한다. 이긴 쪽은 ⑦에서 좌석 UPDATE가 한 번 더 나간다(같은 값 — 무해).
- **대기**: 짧다 — 이긴 쪽의 ⑤~⑨ 동안만. 진 쪽은 커넥션을 잡고 기다린다.

### 2.6 [3a] `pessimistic` — 비관락 `SELECT … FOR UPDATE`

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult =
    tx.execute { process.hold(command, loadSeat = seats::findForUpdate) }!!
```

```kotlin
// ProductSeatRepository
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select s from ProductSeat s where s.id = :id and s.scheduleId = :scheduleId")
fun findForUpdate(id: Long, scheduleId: Long): ProductSeat?
```

- **바뀐 쿼리(②)**: `select ps1_0.id,… from product_seat ps1_0 where ps1_0.id=? and ps1_0.schedule_id=? for no key update` — Hibernate 6 PostgreSQL 방언의 PESSIMISTIC_WRITE는 `FOR UPDATE`보다 약한 `FOR NO KEY UPDATE`다(키가 아닌 열만 바꿀 때의 배타 락 — 다른 테이블의 외래키 참조(`FOR KEY SHARE`)와 충돌하지 않는다). 같은 좌석의 두 `FOR NO KEY UPDATE`끼리는 충돌하므로 직렬화는 그대로다.
- **락**: 좌석 행 배타 락을 **읽을 때** 잡는다.
- **락 구간**: ② 읽기 시점에 획득 → ⑨ 커밋 때 해제. 뒤에 온 요청은 ②에서 기다렸다가 앞 요청 커밋 뒤의 최신 행(HELD)을 읽고 ③에서 진다.

```
요청 A: ① ② SELECT … FOR UPDATE (행 락) ─ ③ ④ ⑥ ⑦ ─ ⑨ COMMIT(해제)
요청 B: ① ② SELECT … FOR UPDATE (대기) ……………………… HELD 읽음 ─ ③ 409
```

- **대기**: 진 쪽이 **커넥션을 잡고** DB에서 기다린다 — 같은 좌석에 몰리면 풀(10)이 대기자로 찬다(스모크: S1에서 락 대기 최대 8, 커넥션 획득 대기 평균 52ms).

### 2.7 [3b] `pessimistic-nowait` — `FOR UPDATE NOWAIT`

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult = try {
    tx.execute { process.hold(command, loadSeat = seats::findForUpdateNoWait) }!!
} catch (e: PessimisticLockingFailureException) {
    if (sqlStateOf(e) == LOCK_NOT_AVAILABLE) seatTaken()   // 55P03
    throw e
}
```

```kotlin
@Lock(LockModeType.PESSIMISTIC_WRITE)
@QueryHints(QueryHint(name = "jakarta.persistence.lock.timeout", value = "0"))
@Query("select s from ProductSeat s where s.id = :id and s.scheduleId = :scheduleId")
fun findForUpdateNoWait(id: Long, scheduleId: Long): ProductSeat?
```

- **바뀐 쿼리(②)**: `… for no key update nowait` — 락 타임아웃 힌트 0이 NOWAIT로 바뀐다(실측 SQL·'기다리지 않음' 테스트 모두 확인).
- **락**: 3a와 같은 행 락. 이미 잠겨 있으면 **기다리지 않고** PostgreSQL이 `55P03 lock_not_available` 오류를 낸다 → 409.
- **락 구간**: 3a와 같다(② ~ ⑨). 진 쪽은 ②에서 즉시 실패하고 롤백한다.
- **주의**: 이긴 쪽이 커밋한 **뒤**에 도착한 요청은 락이 없으니 ②를 통과해 HELD를 읽고 ③에서 진다 — 진 쪽의 실패 코드가 두 갈래(55P03 / 상태 검사)지만 응답은 같은 409.

### 2.8 [4] `optimistic` — 버전 열 낙관락

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute {
    var expected = 0L
    process.hold(
        command,
        loadSeat = { id, scheduleId ->
            seats.findVersion(id)?.let { version ->
                expected = version
                seats.findByIdAndScheduleId(id, scheduleId)
            }
        },
        afterFlush = { seat -> if (seats.bumpVersion(seat.id!!, expected) == 0) seatTaken() },
    )
}!!
```

```kotlin
@Query("SELECT version FROM product_seat WHERE id = :id", nativeQuery = true)
fun findVersion(id: Long): Long?

@Modifying
@Query("UPDATE product_seat SET version = version + 1 WHERE id = :id AND version = :expected", nativeQuery = true)
fun bumpVersion(id: Long, expected: Long): Int
```

- **추가 쿼리**: ②에서 `SELECT version FROM product_seat WHERE id = ?`를 **좌석보다 먼저**, ⑧에서 `UPDATE product_seat SET version = version + 1 WHERE id = ? AND version = ?`.
- **락**: 명시적 락 없음 — "읽은 버전이 그대로일 때만 쓴다"로 충돌을 **커밋 직전에 발견**한다. 0행이면 진 것 → 409, 트랜잭션 롤백(⑦의 홀드 INSERT도 취소).
- **버전을 먼저 읽는 이유**: 좌석을 먼저 읽고 버전을 나중에 읽으면, 그 사이 커밋된 새 버전을 기준으로 삼아 낡은 AVAILABLE 스냅샷이 통과할 수 있다.
- **락 구간(실제)**: 진 쪽도 ⑦의 좌석 UPDATE에서 이긴 쪽의 행 락을 커밋까지 **짧게 기다린다**(UPDATE는 행 락을 건다). 그 뒤 ⑧에서 버전 불일치로 진다.

```
요청 A: ① ② version=0 → 좌석(AVAILABLE) ─ ③ ④ ⑥ ⑦(행 락) ─ ⑧ version 0→1 (1행) ─ ⑨ COMMIT
요청 B: ① ② version=0 → 좌석(AVAILABLE) ─ ③ ④ ⑥ ⑦ (A의 행 락 대기) …… ⑧ WHERE version=0 → 0행 → 409(롤백)
```

- **편향**: JPA `@Version`을 엔티티에 달면 모든 방식에 낙관락 검사가 걸려 기준선이 바뀐다 — 그래서 버전 열을 엔티티 밖에서 직접 다룬다. `@Version`(flush 때 한 문장)보다 **왕복이 2번 더** 든다.

### 2.9 [5] `unique` — 유니크 제약

```sql
-- src/main/resources/db/migration-unique/V4__unique_seat_hold.sql (이 방식에서만 적용)
CREATE UNIQUE INDEX uq_seat_hold_seat_id ON seat_hold (seat_id);
```

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult = try {
    tx.execute { process.hold(command) }!!
} catch (e: DataIntegrityViolationException) {
    val cause = e.mostSpecificCause
    if (cause is SQLException && cause.sqlState == "23505" && cause.message.orEmpty().contains(UNIQUE_HOLD_INDEX)) seatTaken()
    throw e
}
```

- **쿼리**: §2.1 그대로. 판정은 ⑦의 `INSERT INTO seat_hold …`가 한다 — 같은 좌석의 두 번째 INSERT는 유니크 위반(`23505`) → 409.
- **락**: 유니크 인덱스 삽입 시 PostgreSQL이 같은 키를 넣는 다른 트랜잭션을 **그 트랜잭션이 끝날 때까지 기다리게** 한다(먼저 넣은 쪽이 커밋하면 위반, 롤백하면 통과).
- **락 구간**: ⑦ INSERT ~ ⑨ 커밋.

```
요청 A: ① ② ③ ④ ⑥ ⑦ INSERT seat_hold(seat=1) ─ ⑨ COMMIT
요청 B: ① ② ③ ④ ⑥ ⑦ INSERT seat_hold(seat=1) (A 커밋 대기) … 23505 → 409(롤백)
```

- **전제**: 좌석당 살아 있는 홀드는 1행 — 홀드는 확정·만료 때 행이 지워지므로 `seat_id` 유니크와 맞는다. 유니크 인덱스는 이 방식에서만 적용하고(기동 검사가 짝을 강제), 다른 방식의 측정에는 없다.

### 2.10 [6] `advisory` — PostgreSQL advisory lock

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult = tx.execute {
    jdbc.queryForList("SELECT pg_advisory_xact_lock(?)", command.seatId)
    process.hold(command)
}!!
```

- **추가 쿼리(① 직후)**: `SELECT pg_advisory_xact_lock(?)` — 좌석 id를 키로 한 **트랜잭션 범위** advisory 락. 키가 같으면 앞 트랜잭션이 끝날 때까지 기다린다.
- **락**: 테이블·행과 무관한 이름(숫자) 락. 커밋·롤백 때 자동으로 풀린다.
- **락 구간**: ① BEGIN 직후 획득 → ⑨ 커밋 때 해제 — ②~⑧ 전체가 직렬화된다. 뒤 요청은 앞 커밋 뒤 ②에서 HELD를 읽고 진다.

```
요청 A: ① pg_advisory_xact_lock(1) ─ ② ③ ④ ⑥ ⑦ ─ ⑨ COMMIT(해제)
요청 B: ① pg_advisory_xact_lock(1) (대기) ……………… ② HELD ─ ③ 409
```

- **대기**: 진 쪽이 커넥션을 잡고 DB에서 기다린다(3a와 같은 대기형). `JdbcTemplate`이 JPA 트랜잭션과 같은 커넥션을 쓰므로 락과 선점이 한 트랜잭션이다.

### 2.11 [7] `redis-nx` — Redis 선점 관문(DB가 원천)

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult {
    val key = "$KEY_PREFIX${command.seatId}"
    val token = UUID.randomUUID().toString()
    if (redis.opsForValue().setIfAbsent(key, token, properties.ttl) != true) seatTaken()
    try {
        return tx.execute { process.hold(command) }!!
    } catch (e: Throwable) {
        try {
            redis.execute(RELEASE_IF_OWNER, listOf(key), token)
        } catch (releaseFailure: Exception) {
            e.addSuppressed(releaseFailure)
            log.warn("redis-nx 키 해제 실패 — 키가 TTL 동안 남아 좌석 {}을 막는다: {}", command.seatId, releaseFailure.toString())
        }
        throw e
    }
}
// RELEASE_IF_OWNER: if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end
```

- **Redis 명령(① 이전)**: `SET hold:seat:{id} {token} NX EX 300` — 키가 없을 때만 설정, 홀드 TTL과 같은 만료. 실패하면 DB에 가지 않고 409.
- **DB 쿼리**: 이긴 요청만 §2.1 그대로.
- **실패 시(DB 단계 예외)**: `EVAL "if get==token then del" 1 hold:seat:{id} {token}` — 자기 토큰일 때만 지운다(다른 요청의 키를 지우지 않게).
- **락**: Redis 키 자체가 "이 좌석은 누가 가져가는 중/가져갔다"는 표식 — 대기 없이 즉시 실패형.
- **락 구간**: ① **이전**에 획득, 해제는 하지 않는다 — 키는 홀드 TTL 동안 남아 그 좌석을 막는다(성공한 경우). DB 단계가 실패하면 그때만 지운다.

```
요청 A: SET NX (성공) ─ ① ② ③ ④ ⑥ ⑦ ⑨ COMMIT        (키는 TTL 동안 유지)
요청 B: SET NX (실패) ─ 409                          (DB에 가지 않음)
```

- **부분 실패**: 키는 생겼는데 DB가 실패하고 키 해제도 실패하면, 그 좌석은 DB에서 AVAILABLE인데 TTL 동안 선점이 막힌다(경고 로그 + 원 예외에 붙임). 반대로 Redis 키는 만료됐는데 DB 홀드가 남아 있으면 DB 쪽 ③이 막는다(DB가 원천).

### 2.12 [8] `redis-lock` — Redis 분산락(Redisson)

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult {
    val lock = client.value.getLock("$LOCK_PREFIX${command.seatId}")
    lock.lock()
    try {
        return tx.execute { process.hold(command) }!!
    } finally {
        lock.unlock()
    }
}
```

- **Redis 명령**: Redisson `RLock`이 라이브러리 안에서 Lua 스크립트로 키 `lock:seat:{id}`(해시: 소유자 → 재진입 횟수)를 원자적으로 만들고 만료를 건다. 잡지 못한 요청은 pub/sub 채널로 해제 알림을 받아 다시 시도한다. 임대 시간은 watchdog(기본 30초, 보유 중 자동 연장) — 락을 쥔 앱이 죽으면 임대 만료로 풀린다. (명령 단위의 정확한 형태는 Redisson 내부 구현)
- **DB 쿼리**: §2.1 그대로.
- **락 구간**: `lock()` → **①~⑨ 전체(커밋 포함)** → `unlock()` — 1a의 JVM 락을 Redis로 옮긴 구조. 앱 여러 대가 같은 Redis를 보면 앱 사이에서도 유효하다.

```
요청 A(앱1): lock(Redis) ─ ① ② ③ ④ ⑥ ⑦ ⑨ COMMIT ─ unlock
요청 B(앱2):   lock 대기(pub/sub) ………………………………… ① ② HELD ─ ③ 409 ─ unlock
```

- **대기**: 진 쪽은 **커넥션을 잡지 않고** Redis 락을 기다린다(①이 락 안쪽) — 3a·6과 달리 풀을 대기자로 채우지 않는다. 대신 Redis 왕복과 Tomcat 워커 점유가 든다.

### 2.13 요약 — 락을 거는 곳

| 방식 | 락의 실체 | 획득 시점 | 해제 시점 | 진 쪽이 기다리나 | 기다리는 동안 DB 커넥션 |
|------|---------|---------|---------|---------------|------------------|
| 0 none | 없음 | — | — | — | — |
| 1a jvm-lock | JVM `ReentrantLock` | ① 이전 | ⑨ 이후 | 예 | 잡지 않음 |
| 1b jvm-lock-in-tx | JVM `ReentrantLock` | ① 이후 | ⑨ **이전**(구멍) | 예 | 잡음 |
| 2 conditional-update | DB 행 락(UPDATE) | ⑤ | ⑨ | 짧게 | 잡음 |
| 3a pessimistic | DB 행 락(FOR UPDATE) | ② | ⑨ | 예 | 잡음 |
| 3b pessimistic-nowait | DB 행 락(FOR UPDATE NOWAIT) | ② | ⑨ | 아니오(즉시 실패) | — |
| 4 optimistic | 버전 비교 (+ ⑦의 행 락) | ⑦·⑧ | ⑨ | 짧게 | 잡음 |
| 5 unique | 유니크 인덱스 삽입 대기 | ⑦ | ⑨ | 짧게 | 잡음 |
| 6 advisory | DB advisory 락 | ① 직후 | ⑨ | 예 | 잡음 |
| 7 redis-nx | Redis 키(SET NX) | ① 이전 | 해제 안 함(TTL) — 실패 시만 삭제 | 아니오(즉시 실패) | — |
| 8 redis-lock | Redis 분산락 | ① 이전 | ⑨ 이후 | 예 | 잡지 않음 |

## 3. 가설

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| H1 | 0·1b를 뺀 9개 방식은 S1에서 성공이 정확히 1이고 판정기 위반이 0이다(단일 앱) | 어느 회차든 성공 > 1 또는 위반 > 0 |
| H2 | 1b(트랜잭션 안 JVM 락)는 중복을 막지 못한다 — 락 해제와 커밋 사이에 들어온 요청이 커밋된 AVAILABLE을 읽는다 | 1b가 전 회차 성공 1 |
| H3 | 앱 2대에서는 1a(JVM 락)가 깨지고(성공 > 1), DB·Redis 기반 방식은 1을 지킨다 | 1a가 앱 2대에서 성공 1 / 다른 방식이 > 1 |
| H4 | **경합이 극단적인 S1**에서는 대기형(3a·6·8·1a)이 진 쪽을 줄 세워 지연이 길고, 즉시 실패형(3b·7)은 진 쪽이 빠르다. 2·4·5는 이긴 쪽 커밋까지만 짧게 기다리는 중간형 | S1 p99가 묶음 사이에 같다 |
| H5 | **경합이 없는 S4**(요청마다 다른 좌석)에서는 방식 간 처리량 차이가 작다 — 락을 다투지 않으므로 추가 비용(락 왕복·Redis 왕복)만 보인다. Redis 두 방식은 왕복이 하나 더 있어 약간 낮다 | S4 한계가 방식 간 계단 2칸 이상 차이 |
| H6 | 대기형 DB 락(3a·6)은 경합 중 커넥션을 잡고 기다려 풀 대기가 생기고, 풀 20에서 S1 지연이 달라진다. Redis 분산락(8)은 커넥션 밖에서 기다려 풀 대기가 없다 | 3a·6의 풀 대기 최대가 0 / 8에서 풀 대기 발생 |
| H7 | 공정성: 대기형은 대략 먼저 온 요청이 이기고(대기열), 즉시 실패형·조건부는 먼저 DB에 닿은 요청이 이긴다 — 둘 다 첫 승자의 도착 순위가 앞쪽이다. 단 1a의 `ReentrantLock()`은 비공정(FIFO 아님)이라 순서가 섞일 수 있다(§2.3) | 첫 승자 순위가 무작위(중앙 근처)로 흩어짐 |
| H8 | S3 핫스팟(L4)에서 0·1b 외 방식은 위반 0, 유령 확정 0을 유지하고, 즉시 실패형은 409 재시도가 늘어난다 | 위반 > 0 |

## 4. 판정 기준 (결과 작성 시 — ADR-001·002 형식: 결론 → 근거 → 편향 → 다음 ADR 입력)

| 기준 | 지표 |
|------|------|
| ① 정합성 | 판정기 위반 전부 0, S1 성공 정확히 1 — 전 회차 |
| ② 공정성 | S1 첫 승자의 도착 순위(k6 요청 시각 `t0` 기준, 1,000 중) |
| ③ 경합 강도별 거동 | S1(극단) · S3 핫스팟(중간) · S4(경합 없음)에서 순위가 뒤집히는가 |
| ④ 자원 점유 | 한계 단계의 앱·DB CPU, Hikari 대기(pending), DB 락 대기 수, 진 쪽 응답 시간 |
| ⑤ 실패 모드 | 응답 분류(타임아웃·연결 수립 실패·연결 끊김·5xx·409), 데드락·락 타임아웃, Redis 부분 실패, 과부하 붕괴 모양 |
| ⑥ 운영 복잡도(정성) | 코드 줄 수·추가 인프라·트랜잭션 경계 제약·관측 가능성 |
| ⑦ 수평 확장 | 앱 2대에서 정합성 유지 여부 |

## 5. 측정 계획

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| 전략 11개 · 풀 10 | S1 · S4(16단계, 약 2.2만 건/s까지) | L2 · L4 | 5 |
| 전략 11개 · 풀 10 | S3 원본 × 이탈 0/20/50 | **L4만** | 5 |
| 대기형 3개(3a·6·8) · 풀 20 | S1 · S4 | L2 · L4 | 5 |
| 전략 11개 · 앱 2대(nginx, 앱마다 2 CPU) | S1 | L4 | 5 |

| **S6 경합 강도 스윕**(2026-10-04 추가) — 전략 11개 × 임계 구역 지연 0 / 20ms | 핫 좌석 K(1·10·100) × 경쟁자 M=20, 핫 도착률 200→3,200건/s 계단(30초씩) + 이웃 500건/s | L4 | 3 |

- 인덱스 V2·배경 0·타임아웃 30s 등 나머지 조건은 ADR-002 기준선과 같다.
- **S6를 더한 이유(2026-10-04)**: 1회차에서 S1(좌석 하나, 2초 버스트)은 정합성만 가르고, S3·S4(흩어진 좌석)는 같은 좌석을 다투는 일이 거의 없어 방식 간 성능이 같았다 — **경합이 계속 이어지는 구간**이 비어 있었다. S6는 좌석마다 M명이 다투는 상태를 계단식으로 키우면서, 경합 없는 이웃 요청이 영향을 받는지(커넥션 풀·워커 공유로 인한 번짐)를 함께 본다. 지연 0 결과는 이 ADR, 지연 0 vs 20ms는 ADR-004. 본측정 캠페인이 끝난 뒤 별도 캠페인(`campaign.sh --suite s6`)으로 잰다. Redis는 전략과 무관하게 항상 뜬다(compose 내부망, CPU 1).
- **S3는 L4만**: ADR-002에서 L2 S3는 인덱스·풀과 무관하게 두 갈래(붕괴/비붕괴)였다 — 그 위에서 락 방식을 비교하면 락 효과와 붕괴 여부가 섞인다(사용자 결정 2026-10-02).
- **회차 우선 순서**: 1회차를 전 조건 한 바퀴 → 2회차 … — ADR-002 리뷰가 지적한 조건 간 드리프트(측정 날짜 차이)를 조건마다 고르게 퍼뜨린다. 측정 중 경로 단절(`path-gap`) 회차는 끝에 다시 잰다.
- 약 70시간(추정). 하네스·수집은 `k6/ADR-003/README.md`.

## 6. 측정 전 검증

(진행 중 — 구현 테스트·스모크·리뷰의 기존 → 변경을 ADR-002 §6 형식으로 기록한다)

### 6.1 구현 중 발견 — Kotlin 기본 인자 × Spring 프록시

선점 규칙을 전략들이 공유하도록 `HoldSeatProcess.hold(…, loadSeat = productSeatRepository::findByIdAndScheduleId)`처럼 기본 인자로 조회 함수를 줬더니, 기존 테스트 38개 중 14개가 NPE로 실패했다. Kotlin 기본 인자는 **호출하는 쪽**에서 계산되는데, 호출 대상이 `@Transactional` CGLIB 프록시라 **프록시 객체의 (초기화되지 않은) 필드**를 읽었다. 기본 인자를 `null`로 두고 본문에서 고르도록 바꿨다(load-bearing 가정 1 — "기본 전략 none은 기준선 동작 그대로" — 을 착수 직후 기존 테스트로 실증하다 잡았다). 수정 후 63개 green.

### 6.2 테스트 — 양성 대조와 1b 관측

- 경합 테스트(50명 동시)가 경합을 실제로 만든다는 **양성 대조**: 락 없음(none)에서 5라운드 안에 중복이 나야 통과 — 실제로 남(리뷰 지적 반영, 2026-10-03).
- **1b(트랜잭션 안 JVM 락)는 테스트에서 중복이 한 번도 나지 않았다**(50명 × 5라운드). 1b의 구멍은 '락 해제 ~ 커밋' 틈인데 로컬 테스트 DB는 커밋이 빨라 그 틈이 거의 없다. 함정이 실제 부하·원격 DB에서 드러나는지는 본측정이 판정한다(H2) — 드러나지 않으면 "구멍은 있으나 관측되기 어렵다"가 결론이다.

### 6.3 측정의 한계 (측정 전에 알려진 것)

- **측정 경로**: k6 PC → 서버가 유선이다(10-01부터). 유선에서는 기준선(none)도 L4 S4가 약 4,300건/s에서 무너지고 서버 CPU는 남는다 — 원인 미확정, 조사 보류(사용자 결정 2026-10-03). L4 S4에서 4,300을 넘는 차이는 이 측정으로 판정하지 않는다. ADR-002 정정 참고.
- **path-gap**은 서버 지표 수집(SSH) 공백으로 판정하므로 경로 단절과 서버 과부하(수집 정체)를 가르지 못한다 — 재측정 후에도 남는 회차는 목록으로 남기고, 붕괴가 심한 전략의 회차가 빠지는 생존 편향이 있는지 결과에서 확인한다.
- **S1 순간 표본**: 락 대기(0.5초)·Hikari 대기(1초) 표본은 2초 안팎의 S1 버스트를 놓칠 수 있다 — 표본 3개 미만이면 '미측정'으로 표기하고, 누적 지표(Hikari 획득 대기 timer·타임아웃 수, DB 데드락 수)를 함께 본다. Hikari 대기 표본은 앱 HTTP로 조회하므로 포화 중에는 응답이 늦어 빠질 수 있다(0 쪽 편향).
- **시계**: path-gap·CPU 창은 서버 시각 표본을 k6 PC 시각 창으로 자른다 — 두 시계 차이는 회차 전후 스냅샷(`server-*.txt`·`client-*.txt`의 `date`)으로 확인할 수 있다.

## 7. 실험 결과

미측정.

## 8. 결정

미정.
