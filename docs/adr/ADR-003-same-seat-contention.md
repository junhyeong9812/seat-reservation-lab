# ADR-003: 같은 좌석 동시 선점 (Q1) — 락 방식 비교
> 번호 이동(2026-10-12, 5차): 운영 중 매수 방식 전환을 새 ADR-010으로 넣으며 기존 ADR-010~015를 011~016으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-12, 4차): 카운터 어긋남 탐지·격리·복구를 새 ADR-009로 넣으며 기존 ADR-009~014를 010~015로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-11, 3차): counter-upsert-precheck 기본 적용·검증을 새 ADR-008로 넣으며 기존 ADR-008~013을 009~014로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09, 2차): ADR-006 처리량 벽 진단을 새 ADR-007로 넣으며 기존 ADR-007~012를 008~013으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-09): counter 1문장 upsert 재측정을 새 ADR-006으로 넣으며 기존 ADR-006~011을 007~012로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-04): 임계 구역 길이·락 보유 시간을 새 ADR-004로 넣으며 기존 ADR-004~010을 005~011로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-15, 6차): 앱 JVM GC·메모리 기준을 새 ADR-010으로 넣으며 기존 ADR-010~016을 011~017로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-16, 7차): 측정 환경 꼬리 지연 원인을 새 ADR-011로 넣으며 기존 ADR-011~017을 012~018로 옮겼다 — 이 문서의 번호 참조는 새 번호다.
> 번호 이동(2026-10-18, 8차): 카운터 증분 대조·Kafka/복제본 대조를 새 ADR-012·013으로 넣으며 기존 ADR-012~018을 014~020으로 옮겼다 — 이 문서의 번호 참조는 새 번호다.

- 상태: 결정됨(Accepted) — 2026-10-06, 3b `pessimistic-nowait`(§8)
- 날짜: 2026-10-03
- 대응 질문: Q1 "같은 좌석을 1,000명이 동시에 눌렀다면?"
- 선행: ADR-000(측정 대상), ADR-001(판정기·하네스), **ADR-002(기준선 = 일반 인덱스 + 풀 10, before 수치 D5)**
- 후행: ADR-004(임계 구역 길이·락 보유 시간 — S6(핫 좌석 K개 × 20명 경합 스윕) 지연 변형), ADR-005(1인 2매 — 사용자 단위 직렬화), ADR-015(홀드 만료), ADR-016(확정 원자성)
- 명세: `docs/plans/2026-10-03/adr-003-lock-comparison/requirement-spec.md`

## 1. 문제

기준선의 선점은 `좌석 조회 → AVAILABLE인지 앱에서 비교 → HELD 저장`이다(check-then-act). 두 요청이 모두 `AVAILABLE`을 읽은 뒤 각자 저장할 수 있다.

ADR-002가 관측한 before(인덱스 있음·풀 10):

- S1(같은 좌석 1,000명) 성공 **L2(앱·DB CPU 2개) 10 [2–10] · L4(앱·DB CPU 4개) 10 [10–10]** — 정합이면 1. 중복 성공의 **상한이 풀 크기**(풀 20 → 20, 40 → 40)였다: 동시에 "비어 있음"을 볼 수 있는 요청 수가 동시에 DB를 쓰는 요청 수다(ADR-002 D3(풀과 정합성)).
- 좌석 테이블만 보면 `HELD` 하나로 보이지만 홀드 행은 여러 개다 — 판정기가 `v_duplicate_hold_seats`·`v_excess_hold_rows`로 잡는다.
- 처리량은 L2(앱·DB CPU 2개) 2,867 · L4(앱·DB CPU 4개) ≥6,294건/s(계단 상한 — 한계 미관측), 병목은 앱 CPU 쪽(ADR-002 D1(인덱스 효과)).

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
- **이 측정에서**: S4(도착률 계단, 요청마다 새 사용자·좌석)는 연속 좌석 번호라 동시에 처리되는 좌석끼리 65,536 이상 차이 날 일이 거의 없다 → 거짓 경합은 사실상 0. S1(좌석 1개에 1,000명 동시)은 같은 좌석 하나라 줄무늬와 무관하다.
- **락 키에 범위(회사·공연)를 붙여야 하나**: 이 랩의 좌석 id는 DB가 발급하는 전역 고유 번호(BIGSERIAL)라 좌석 id만으로 유일하다 — 회사·공연 id를 붙여도 구분력은 늘지 않는다. 키를 합쳐야 하는 것은 id가 **범위 안에서만 유일할 때**다(공연장마다 다시 시작하는 좌석 번호, 회사별로 따로 발급되는 id) — 이때 `회사:공연:좌석`으로 범위를 붙이지 않으면 다른 좌석이 같은 키가 된다(정확성 문제). 키를 길게 해도 줄무늬 바구니 수는 그대로라 거짓 경합 확률은 변하지 않는다.
- **동시 처리 수가 큰 시스템의 대안**: 좌석 키마다 락을 만들고 다 쓰면 회수하는 방식(키별 락 맵 + 참조 수 / 약한 참조 맵) — 거짓 경합이 없는 대신 락 생성·회수 비용이 든다. 다만 여러 공연·호텔을 다루는 실서비스는 대개 앱이 여러 대라 **JVM 락 자체가 선택지가 아니다**(⑦(수평 확장)) — 이 실험에서 1a(JVM 락, 트랜잭션 밖)는 "앱 1대일 때의 기준점"으로만 본다.

#### JVM 락의 본질 — DB 조회 앞에 세운 JVM 안의 대기열

JVM 락은 DB에 좌석을 **읽으러 가기 전에** 앱 안에 좌석별 대기열을 세워 두고, 앞 요청의 처리(1a(JVM 락, 트랜잭션 밖)는 커밋까지)가 끝날 때까지 다음 요청을 **앱 안에서 멈춰 두는** 방식이다. DB는 경합을 모른다 — 같은 좌석의 요청이 DB에는 한 번에 하나씩만 도착하게 앱이 미리 줄을 세울 뿐이고, "이 좌석이 이미 HELD인가"의 판정은 여전히 DB에서 읽어 앱이 비교한다(§2.1의 ②·③).

```
요청 1 ─┐
요청 2 ─┤  [JVM: 좌석 1번 대기열]  ──한 번에 하나──▶  ① BEGIN ② 읽기 ③ 비교 … ⑨ COMMIT  ──▶ 다음 요청 입장
요청 3 ─┘    (Tomcat 워커 스레드가 lock()에서 멈춰 있음)
```

- **멈춰 있는 것은 Tomcat 워커 스레드**다(1a(JVM 락, 트랜잭션 밖)는 DB 커넥션을 잡기 전, 1b(JVM 락, 트랜잭션 안)는 잡은 뒤). 같은 좌석에 1,000명이 몰리면 워커(기본 200개)가 대기열에 묶이고, 그 사이 다른 좌석의 요청은 남은 워커로 처리된다.
- **줄 서는 순서는 보장되지 않는다**: 코드의 `ReentrantLock()`은 기본값이 비공정(non-fair) — 락이 풀리는 순간 막 도착한 스레드가 기다리던 스레드보다 먼저 잡을 수 있다(처리량을 위한 기본값). 대기열이지만 FIFO가 아니라, 공정성(② · H7(공정성 — 먼저 온 쪽 승))의 "대기형은 먼저 온 요청이 이긴다"는 1a(JVM 락, 트랜잭션 밖)에 대해 성립하지 않을 수 있다. FIFO가 필요하면 `ReentrantLock(true)`(공정 모드 — 처리량이 떨어진다).
- **대기열의 단위**가 세 가지로 갈린다:

| 구현 | 대기열(또는 판정)의 단위 | 같은 좌석 요청 | 다른 좌석 요청 | 판정 원천 | 이 측정 |
|------|----------------------|-------------|-------------|---------|--------|
| **줄무늬 락**(1a·1b, 지금 구현) | 좌석을 65,536개 바구니로 나눈 바구니마다 대기열 | 줄 선다 | 같은 바구니면 줄 선다(거짓 경합) | DB(락 안에서 읽어 비교) | 비교함 |
| **키별 락** | 좌석마다 대기열, 대기자가 없어지면 회수 | 줄 선다 | 줄 서지 않는다 | DB(락 안에서 읽어 비교) | 비교 안 함 |
| **메모리 관문**(`ConcurrentHashMap.putIfAbsent(seatId, 소유자)`) | 좌석 키에 "홀드됨" 기록 — 대기열이 아니다 | 줄 서지 않고 즉시 409 | 영향 없음 | **앱 메모리의 기록**(먼저 기록한 쪽이 이김) | 비교 안 함 |

- **키별 락**은 줄무늬에서 거짓 경합만 뺀 것이다. 이 측정에서는 거짓 경합이 사실상 0이라(S1(좌석 1개에 1,000명 동시)은 좌석 하나, S4(도착률 계단, 요청마다 새 사용자·좌석)는 연속 좌석) 줄무늬와 결과가 같을 것으로 보고 비교하지 않았다.
- **메모리 관문**은 락이 아니라 7번 redis-nx(`SET NX`)를 앱 메모리에 둔 것과 같다 — 줄 세우지 않고 바로 실패시켜 빠르지만, 홀드 만료·확정 때 기록을 지워야 하고, 재시작하면 기록이 사라지고, 앱이 2대면 서로의 기록을 모른다. redis-nx 결과에서 Redis 왕복을 뺀 정도로 예상할 수 있어 비교하지 않았다.
- 셋 다 **앱 1대에서만 유효**하다 — 서버가 여러 대(MSA)면 DB·Redis 쪽 수단이나 키 기준 라우팅으로 가야 한다(NEXT N9(다중 인스턴스·MSA — 2026-10-06 재번호 전 N7)).

### 2.4 [1b] `jvm-lock-in-tx` — 같은 락을 트랜잭션 안에서(함정 재현)

```kotlin
override fun hold(command: HoldSeatCommand): HoldSeatResult =
    tx.execute { locks.withLock(command.seatId) { process.hold(command) } }!!
```

- **락**: 1a(JVM 락, 트랜잭션 밖)와 같은 JVM 락. 위치만 다르다 — `@Transactional` 메서드 안에서 `synchronized`를 거는 흔한 실수와 같은 구조.
- **락 구간**: ① BEGIN → `lock()` → ②~⑧ → `unlock()` → **⑨ COMMIT**. 락을 푼 뒤에 커밋한다.

```
요청 A: ① BEGIN ─ lock ─ ② 읽기(AVAILABLE) ─ … ─ ⑦ 쓰기(미커밋) ─ unlock ─┐ ⑨ COMMIT
요청 B: ① BEGIN ─ lock 대기 ……………………………………………… lock ─ ② 읽기 ──┘
                                           ↑ 이 틈(unlock ~ A의 COMMIT)에 B가 읽으면 커밋된 AVAILABLE을 본다 → 중복
```

- **구멍**: unlock과 COMMIT 사이의 틈. 그 사이에 B가 ②를 읽으면 A의 HELD는 아직 커밋 전이라 보이지 않는다(READ COMMITTED). B는 ③을 통과하고, ⑦의 좌석 UPDATE는 A의 행 락을 기다렸다가 A 커밋 뒤 그대로 써서 홀드가 둘이 된다.
- **관측(§6.2)**: 로컬 테스트에서는 커밋이 빨라 틈이 거의 없어 중복이 나지 않았다 — 본측정(원격 DB)에서 드러나는지 본다(H2(1b는 중복 못 막음)).
- **대기**: B는 ①에서 커넥션을 이미 잡은 채로 `lock()`을 기다린다 — 1a(JVM 락, 트랜잭션 밖)와 달리 대기 중에도 커넥션을 점유한다.

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

1b(JVM 락, 트랜잭션 안)는 실무에서 흔한 실수와 같은 구조다:

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

**왜 1b(JVM 락, 트랜잭션 안)가 늘 깨지지는 않나**: 틈의 길이 = A의 `unlock` → `COMMIT` 완료 시간(커밋 왕복 + WAL 기록). B가 그 안에 ②의 SELECT를 DB에 도달시켜야 중복이 난다. 로컬 테스트 DB에서는 커밋이 빨라 50명 × 5라운드에서 중복 0이었다(§6.2). 원격 DB·부하 중에는 커밋이 느려져 틈이 커진다 — 본측정이 판정한다(H2(1b는 중복 못 막음)). 틈이 드물게만 열린다면 "가끔 깨지는" 버그가 되어 테스트로 잡기 더 어렵다는 점이 이 함정의 실제 위험이다.

**고치는 법(1b(JVM 락, 트랜잭션 안) → 1a(JVM 락, 트랜잭션 밖))**: 락이 커밋까지 덮게 순서를 바꾼다 — 트랜잭션을 여는 쪽(서비스 메서드)을 락 안쪽으로 옮기거나, 락을 트랜잭션 경계 밖(호출하는 쪽)에서 건다. 단 어느 쪽이든 앱 여러 대에서는 무의미하다(⑦(수평 확장)) — 그때는 DB(3a·2·5·6(pessimistic·conditional·unique·advisory))나 Redis(7·8(redis-nx·redis-lock)) 쪽 수단으로 간다.

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

- **대기**: 진 쪽이 **커넥션을 잡고** DB에서 기다린다 — 같은 좌석에 몰리면 풀(10)이 대기자로 찬다(스모크: S1(좌석 1개에 1,000명 동시)에서 락 대기 최대 8, 커넥션 획득 대기 평균 52ms).

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
- **락**: 3a(FOR UPDATE 대기)와 같은 행 락. 이미 잠겨 있으면 **기다리지 않고** PostgreSQL이 `55P03 lock_not_available` 오류를 낸다 → 409.
- **락 구간**: 3a(FOR UPDATE 대기)와 같다(② ~ ⑨). 진 쪽은 ②에서 즉시 실패하고 롤백한다.
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

- **추가 쿼리(①.2 직후)**: `SELECT pg_advisory_xact_lock(?)` — 좌석 id를 키로 한 **트랜잭션 범위** advisory 락. 키가 같으면 앞 트랜잭션이 끝날 때까지 기다린다.
- **락**: 테이블·행과 무관한 이름(숫자) 락. 커밋·롤백 때 자동으로 풀린다.
- **락 구간**: ① BEGIN 직후 획득 → ⑨ 커밋 때 해제 — ②~⑧ 전체가 직렬화된다. 뒤 요청은 앞 커밋 뒤 ②에서 HELD를 읽고 진다.

```
요청 A: ① pg_advisory_xact_lock(1) ─ ② ③ ④ ⑥ ⑦ ─ ⑨ COMMIT(해제)
요청 B: ① pg_advisory_xact_lock(1) (대기) ……………… ② HELD ─ ③ 409
```

- **대기**: 진 쪽이 커넥션을 잡고 DB에서 기다린다(3a(FOR UPDATE 대기)와 같은 대기형). `JdbcTemplate`이 JPA 트랜잭션과 같은 커넥션을 쓰므로 락과 선점이 한 트랜잭션이다.

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

- **Redis 명령(⓪ — 트랜잭션 전)**: `SET hold:seat:{id} {token} NX EX 300` — 키가 없을 때만 설정, 홀드 TTL과 같은 만료. 실패하면 DB에 가지 않고 409.
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
- **락 구간**: `lock()` → **①~⑨ 전체(커밋 포함)** → `unlock()` — 1a(JVM 락, 트랜잭션 밖)의 JVM 락을 Redis로 옮긴 구조. 앱 여러 대가 같은 Redis를 보면 앱 사이에서도 유효하다.

```
요청 A(앱1): lock(Redis) ─ ① ② ③ ④ ⑥ ⑦ ⑨ COMMIT ─ unlock
요청 B(앱2):   lock 대기(pub/sub) ………………………………… ① ② HELD ─ ③ 409 ─ unlock
```

- **대기**: 진 쪽은 **커넥션을 잡지 않고** Redis 락을 기다린다(①이 락 안쪽) — 3a·6(pessimistic·advisory)과 달리 풀을 대기자로 채우지 않는다. 대신 Redis 왕복과 Tomcat 워커 점유가 든다.

### 2.13 요약 — 락을 거는 곳

먼저 단계 번호. §2.1의 ①~⑨에 트랜잭션 **앞**(⓪)을 더하고, ①과 ⑨를 쪼갰다. 락을 '언제 풀었나'는 ⑨ 안에서 갈린다.

| 단계 | 하는 일 | 이 단계에서 락을 잡거나 푸는 방식 |
|------|--------|-----------------------------|
| ⓪ 전략 진입 | 트랜잭션 밖, 커넥션 없음 | 잡음: 1a(JVM 락), 7(Redis 키 SET NX — 실패면 여기서 바로 409), 8(Redis 분산락) |
| ①.1 커넥션 획득 | Hikari 풀에서 커넥션을 빌린다(모자라면 여기서 기다린다) | — |
| ①.2 `BEGIN` | 트랜잭션 시작 | 잡음(직후): 1b(JVM 락), 6(`pg_advisory_xact_lock`) |
| ② 좌석 읽기 | `select … from product_seat` | 잡음: 3a(`FOR NO KEY UPDATE` — 기다림), 3b(`… NOWAIT` — 잠겨 있으면 바로 실패) |
| ③ 선점 가능 확인 | `status == AVAILABLE`(앱 메모리) — 아니면 409 | — |
| ④ 1인 2매 확인 | 홀드·예약 수 조회 — 초과면 409. **ADR-004의 지연(20ms)은 ④ 뒤** | — |
| ⑤ 전이 전 확장점 | | 잡음: 2(조건부 `UPDATE … WHERE status='AVAILABLE'` — 행 락) |
| ⑥ 상태 전이 | 메모리에서 HELD로, 홀드 추가 | — |
| ⑦ flush | 홀드 INSERT · 좌석 UPDATE · 홀드 FK UPDATE | 잡음: 5(유니크 인덱스 — 먼저 넣은 트랜잭션이 끝날 때까지 기다림), 4(좌석 UPDATE의 행 락) |
| ⑧ flush 후 확장점 | | 4(버전 비교 — 바뀌었으면 409) |
| ⑨.1 트랜잭션 안 마지막 | `process.hold` 반환 직후, 커밋 전 | 풂: **1b(JVM 락 — 커밋 전에 풀어서 구멍)** |
| ⑨.2 `COMMIT`(또는 `ROLLBACK`) | 변경이 다른 트랜잭션에 보이기 시작 | 풂: 2·3a·3b·4·5(conditional·pessimistic·nowait·optimistic·unique)의 DB 행 락·인덱스 대기, 6의 advisory xact 락 |
| ⑨.3 커넥션 반납 | Hikari 풀로 돌려준다 | — |
| ⑨.4 전략 반환 | 트랜잭션 밖 | 풂: 1a(JVM 락), 8(Redis 분산락), 7(실패했을 때만 키 삭제 — 성공하면 키는 홀드 TTL까지 남는다) |

| 방식 | 락의 실체 | 획득 시점 | 해제 시점 | 진 쪽이 기다리나 | 기다리는 동안 DB 커넥션 |
|------|---------|---------|---------|---------------|------------------|
| 0 none | 없음 | — | — | — | — |
| 1a jvm-lock | JVM `ReentrantLock` | ⓪ | ⑨.4 | 예 | 잡지 않음 |
| 1b jvm-lock-in-tx | JVM `ReentrantLock` | ①.2 직후 | ⑨.1 — **⑨.2 커밋 전**(구멍) | 예 | 잡음 |
| 2 conditional-update | DB 행 락(UPDATE) | ⑤ | ⑨.2 | 짧게 | 잡음 |
| 3a pessimistic | DB 행 락(FOR UPDATE) | ② | ⑨.2 | 예 | 잡음 |
| 3b pessimistic-nowait | DB 행 락(FOR UPDATE NOWAIT) | ② | ⑨.2 | 아니오(즉시 실패) | — |
| 4 optimistic | 버전 비교 (+ ⑦의 행 락) | ⑦·⑧ | ⑨.2 | 짧게 | 잡음 |
| 5 unique | 유니크 인덱스 삽입 대기 | ⑦ | ⑨.2 | 짧게 | 잡음 |
| 6 advisory | DB advisory 락 | ①.2 직후 | ⑨.2 | 예 | 잡음 |
| 7 redis-nx | Redis 키(SET NX) | ⓪ | 해제 안 함(홀드 TTL) — 실패 시만 ⑨.4에서 삭제 | 아니오(즉시 실패) | — |
| 8 redis-lock | Redis 분산락 | ⓪ | ⑨.4 | 예 | 잡지 않음 |

## 3. 가설

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| H1 | 0·1b(none·jvm-lock-in-tx)를 뺀 9개 방식은 S1(좌석 1개에 1,000명 동시)에서 성공이 정확히 1이고 판정기 위반이 0이다(단일 앱) | 어느 회차든 성공 > 1 또는 위반 > 0 |
| H2 | 1b(트랜잭션 안 JVM 락)는 중복을 막지 못한다 — 락 해제와 커밋 사이에 들어온 요청이 커밋된 AVAILABLE을 읽는다 | 1b(JVM 락, 트랜잭션 안)가 전 회차 성공 1 |
| H3 | 앱 2대에서는 1a(JVM 락)가 깨지고(성공 > 1), DB·Redis 기반 방식은 1을 지킨다 | 1a(JVM 락, 트랜잭션 밖)가 앱 2대에서 성공 1 / 다른 방식이 > 1 |
| H4 | **경합이 극단적인 S1(좌석 1개에 1,000명 동시)**에서는 대기형(3a·6·8·1a(pessimistic·advisory·redis-lock·jvm-lock))이 진 쪽을 줄 세워 지연이 길고, 즉시 실패형(3b·7(nowait·redis-nx))은 진 쪽이 빠르다. 2·4·5(conditional·optimistic·unique)는 이긴 쪽 커밋까지만 짧게 기다리는 중간형 | S1(좌석 1개에 1,000명 동시) p99가 묶음 사이에 같다 |
| H5 | **경합이 없는 S4**(요청마다 다른 좌석)에서는 방식 간 처리량 차이가 작다 — 락을 다투지 않으므로 추가 비용(락 왕복·Redis 왕복)만 보인다. Redis 두 방식은 왕복이 하나 더 있어 약간 낮다 | S4(도착률 계단, 요청마다 새 사용자·좌석) 한계가 방식 간 계단 2칸 이상 차이 |
| H6 | 대기형 DB 락(3a·6(pessimistic·advisory))은 경합 중 커넥션을 잡고 기다려 풀 대기가 생기고, 풀 20에서 S1(좌석 1개에 1,000명 동시) 지연이 달라진다. Redis 분산락(8)은 커넥션 밖에서 기다려 풀 대기가 없다 | 3a·6(pessimistic·advisory)의 풀 대기 최대가 0 / 8(redis-lock)에서 풀 대기 발생 |
| H7 | 공정성: 대기형은 대략 먼저 온 요청이 이기고(대기열), 즉시 실패형·조건부는 먼저 DB에 닿은 요청이 이긴다 — 둘 다 첫 승자의 도착 순위가 앞쪽이다. 단 1a(JVM 락, 트랜잭션 밖)의 `ReentrantLock()`은 비공정(FIFO 아님)이라 순서가 섞일 수 있다(§2.3) | 첫 승자 순위가 무작위(중앙 근처)로 흩어짐 |
| H8 | S3(입장→선점→확정/이탈 전체 흐름) 핫스팟(L4(앱·DB CPU 4개))에서 0·1b(none·jvm-lock-in-tx) 외 방식은 위반 0, 유령 확정 0을 유지하고, 즉시 실패형은 409 재시도가 늘어난다 | 위반 > 0 |

## 4. 판정 기준 (결과 작성 시 — ADR-001·002 형식: 결론 → 근거 → 편향 → 다음 ADR 입력)

| 기준 | 지표 |
|------|------|
| ① 정합성 | 판정기 위반 전부 0, S1(좌석 1개에 1,000명 동시) 성공 정확히 1 — 전 회차 |
| ② 공정성 | S1(좌석 1개에 1,000명 동시) 첫 승자의 도착 순위(k6 요청 시각 `t0` 기준, 1,000 중) |
| ③ 경합 강도별 거동 | S1(극단) · S3(입장→선점→확정/이탈 전체 흐름) 핫스팟(중간) · S4(경합 없음)에서 순위가 뒤집히는가 |
| ④ 자원 점유 | 한계 단계의 앱·DB CPU, Hikari 대기(pending), DB 락 대기 수, 진 쪽 응답 시간 |
| ⑤ 실패 모드 | 응답 분류(타임아웃·연결 수립 실패·연결 끊김·5xx·409), 데드락·락 타임아웃, Redis 부분 실패, 과부하 붕괴 모양 |
| ⑥ 운영 복잡도(정성) | 코드 줄 수·추가 인프라·트랜잭션 경계 제약·관측 가능성 |
| ⑦ 수평 확장 | 앱 2대에서 정합성 유지 여부 |

## 5. 측정 계획

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| 전략 11개 · 풀 10 | S1(좌석 1개에 1,000명 동시) · S4(16단계, 약 2.2만 건/s까지) | L2 · L4(앱·DB CPU 2·4개) | 5 |
| 전략 11개 · 풀 10 | S3(입장→선점→확정/이탈 전체 흐름) 원본 × 이탈 0/20/50 | **L4(앱·DB CPU 4개)만** | 5 |
| 대기형 3개(3a·6·8(pessimistic·advisory·redis-lock)) · 풀 20 | S1 · S4(같은 좌석 1,000명·처리량 계단) | L2 · L4(앱·DB CPU 2·4개) | 5 |
| 전략 11개 · 앱 2대(nginx, 앱마다 2 CPU) | S1 | L4(앱·DB CPU 4개) | 5 |

| **S6(핫 좌석 K개 × 20명 경합 스윕) 경합 강도 스윕**(2026-10-04 추가) — 전략 11개 × 임계 구역 지연 0 / 20ms | 핫 좌석 K(1·10·100) × 경쟁자 M=20, 핫 도착률 200→3,200건/s 계단(30초씩) + 무경합 500건/s | L4(앱·DB CPU 4개) | 3 |

- 인덱스 V2·배경 0·타임아웃 30s 등 나머지 조건은 ADR-002 기준선과 같다.
- **S6(핫 좌석 K개 × 20명 경합 스윕)를 더한 이유(2026-10-04)**: 1회차에서 S1(좌석 하나, 2초 버스트)은 정합성만 가르고, S3(입장→선점→확정/이탈 전체 흐름)·S4(흩어진 좌석)는 같은 좌석을 다투는 일이 거의 없어 방식 간 성능이 같았다 — **경합이 계속 이어지는 구간**이 비어 있었다. S6(핫 좌석 K개 × 20명 경합 스윕)는 좌석마다 M명이 다투는 상태를 계단식으로 키우면서, 경합 없는 무경합 요청이 영향을 받는지(커넥션 풀·워커 공유로 인한 번짐)를 함께 본다. 지연 0 결과는 이 ADR, 지연 0 vs 20ms는 ADR-004. 본측정 캠페인이 끝난 뒤 별도 캠페인(`campaign.sh --suite s6`)으로 잰다. Redis는 전략과 무관하게 항상 뜬다(compose 내부망, CPU 1).
- **S3(입장→선점→확정/이탈 전체 흐름)는 L4(앱·DB CPU 4개)만**: ADR-002에서 L2(앱·DB CPU 2개) S3는 인덱스·풀과 무관하게 두 갈래(붕괴/비붕괴)였다 — 그 위에서 락 방식을 비교하면 락 효과와 붕괴 여부가 섞인다(사용자 결정 2026-10-02).
- **회차 우선 순서**: 1회차를 전 조건 한 바퀴 → 2회차 … — ADR-002 리뷰가 지적한 조건 간 드리프트(측정 날짜 차이)를 조건마다 고르게 퍼뜨린다. 측정 중 경로 단절(`path-gap`) 회차는 끝에 다시 잰다.
- 약 70시간(추정). 하네스·수집은 `k6/ADR-003/README.md`.

## 6. 측정 전 검증

구현 중 발견·테스트·측정 전에 알려진 한계.

### 6.1 구현 중 발견 — Kotlin 기본 인자 × Spring 프록시

선점 규칙을 전략들이 공유하도록 `HoldSeatProcess.hold(…, loadSeat = productSeatRepository::findByIdAndScheduleId)`처럼 기본 인자로 조회 함수를 줬더니, 기존 테스트 38개 중 14개가 NPE로 실패했다. Kotlin 기본 인자는 **호출하는 쪽**에서 계산되는데, 호출 대상이 `@Transactional` CGLIB 프록시라 **프록시 객체의 (초기화되지 않은) 필드**를 읽었다. 기본 인자를 `null`로 두고 본문에서 고르도록 바꿨다(load-bearing 가정 1 — "기본 전략 none은 기준선 동작 그대로" — 을 착수 직후 기존 테스트로 실증하다 잡았다). 수정 후 63개 green.

### 6.2 테스트 — 양성 대조와 1b 관측

- 경합 테스트(50명 동시)가 경합을 실제로 만든다는 **양성 대조**: 락 없음(none)에서 5라운드 안에 중복이 나야 통과 — 실제로 남(리뷰 지적 반영, 2026-10-03).
- **1b(트랜잭션 안 JVM 락)는 테스트에서 중복이 한 번도 나지 않았다**(50명 × 5라운드). 1b(JVM 락, 트랜잭션 안)의 구멍은 '락 해제 ~ 커밋' 틈인데 로컬 테스트 DB는 커밋이 빨라 그 틈이 거의 없다. 함정이 실제 부하·원격 DB에서 드러나는지는 본측정이 판정한다(H2(1b는 중복 못 막음)) — 드러나지 않으면 "구멍은 있으나 관측되기 어렵다"가 결론이다.

### 6.3 측정의 한계 (측정 전에 알려진 것)

- **측정 경로**: k6 PC → 서버가 유선이다(10-01부터). 유선에서는 기준선(none)도 L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석)가 약 4,300건/s에서 무너지고 서버 CPU는 남는다 — 원인 미확정, 조사 보류(사용자 결정 2026-10-03). L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석)에서 4,300을 넘는 차이는 이 측정으로 판정하지 않는다. ADR-002 정정 참고.
- **path-gap**은 서버 지표 수집(SSH) 공백으로 판정하므로 경로 단절과 서버 과부하(수집 정체)를 가르지 못한다 — 재측정 후에도 남는 회차는 목록으로 남기고, 붕괴가 심한 전략의 회차가 빠지는 생존 편향이 있는지 결과에서 확인한다.
- **S1(좌석 1개에 1,000명 동시) 순간 표본**: 락 대기(0.5초)·Hikari 대기(1초) 표본은 2초 안팎의 S1 버스트를 놓칠 수 있다 — 표본 3개 미만이면 '미측정'으로 표기하고, 누적 지표(Hikari 획득 대기 timer·타임아웃 수, DB 데드락 수)를 함께 본다. Hikari 대기 표본은 앱 HTTP로 조회하므로 포화 중에는 응답이 늦어 빠질 수 있다(0 쪽 편향).
- **시계**: path-gap·CPU 창은 서버 시각 표본을 k6 PC 시각 창으로 자른다 — 두 시계 차이는 회차 전후 스냅샷(`server-*.txt`·`client-*.txt`의 `date`)으로 확인할 수 있다.

## 7. 실험 결과

> 경로는 repo 루트 기준. `M` = `k6/ADR-003/results/20261003-adr003-96e2e6c`(본측정), `X` = `k6/ADR-003/results/20261005-adr003-s6-853ef7a`(S6(핫 좌석 K개 × 20명 경합 스윕)). 표는 `M/COMPARISON.md`·`X/COMPARISON.md`(`scripts/compare.py`)와 조건별 `SUMMARY.md`(`scripts/summarize.py`)에서 옮겼다. 값 = 중앙값 [최소–최대], n = 계산에 쓴 회차/전체.

### 7.1 측정 경과

| 항목 | 본측정 | S6 |
|------|-------|-----|
| 캠페인 | `20261003-adr003-96e2e6c` — 10-03 09:26 ~ 10-05 19:32 (58h) | `20261005-adr003-s6-853ef7a` — 10-05 21:53 ~ 10-06 09:42 (11.8h) |
| 측정 SHA | 96e2e6c → b574543(10-04 12:14 조건 경계에서 전환 — 캠페인 순서만 바뀜, 앱·시나리오·compose diff 0) | 853ef7a |
| 조건 × 회차 | 36조건, 회차 우선(1회차 전 조건 → 2회차 …). S3(입장→선점→확정/이탈 전체 흐름)는 3회(10-04 재합의), 나머지 5회 = **434회** | 22조건(전략 11 × 지연 0/20ms) × K 3 × 3회 = **198회** |
| 비정상 회차 | **0** (재측정 0, 자동 재시작 0) | **0** |
| 경로 | 처음부터 끝까지 유선(eno1) | 유선 |

- 회차 우선 순서 덕에 조건 간 시간대 차이가 조건마다 고르게 퍼졌다(ADR-002 §7.4의 드리프트 편향 대응).

### 7.2 결과 요약

| 가설 | 판정 | 핵심 수치 |
|------|------|---------|
| H1 0·1b(none·jvm-lock-in-tx)를 뺀 9개는 S1(좌석 1개에 1,000명 동시) 성공 정확히 1, 위반 0(앱 1대) | **채택** | S1(좌석 1개에 1,000명 동시) L2·L4(앱·DB CPU 2·4개) 각 5회 모두 1 · S3(입장→선점→확정/이탈 전체 흐름) 판정기 위반 0 · S6(핫 좌석 K개 × 20명 경합 스윕) 중복 0(지연 0·20ms, K 1·10·100 전부) |
| H2 1b(JVM 락, 트랜잭션 안)는 중복을 막지 못한다 | **채택** | S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) 2 [1–2] · L4(앱·DB CPU 4개) 2 [2–2] · **S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0 K=1에서 중복 홀드 7,399건**(판정기) · 로컬 테스트에서는 0이었다(§6.2) |
| H3 앱 2대에서 1a(JVM 락, 트랜잭션 밖)는 깨지고 DB·Redis 방식은 1 | **채택** | 앱 2대 S1(좌석 1개에 1,000명 동시): none 20 [16–20] · 1a(JVM 락, 트랜잭션 밖) 2 [2–2] · 1b(JVM 락, 트랜잭션 안) 3 [2–3] · 나머지 8개 1 [1–1] |
| H4 S1(좌석 1개에 1,000명 동시)에서 대기형은 느리고 즉시 실패형은 빠르다 | **기각(축이 다르다)** | L2(앱·DB CPU 2개) S1(좌석 1개에 1,000명 동시) p50: pessimistic **181ms**(가장 빠름) · nowait 222 · redis-nx 237 … optimistic **1,018** · redis-lock 915 · conditional 556. 갈리는 축은 '대기 vs 즉시'가 아니라 **진 쪽이 무엇을 하고 무엇을 쥐는가**(§7.3 ④) |
| H5 경합 없는 S4(도착률 계단, 요청마다 새 사용자·좌석)에서는 차이가 작다, Redis는 약간 낮다 | **채택** | S4(도착률 계단, 요청마다 새 사용자·좌석) 엄격 한계 L2(앱·DB CPU 2개): 10개 2,866 / **redis-lock 1,911**(5회 모두, 한 단계 아래) · L4(앱·DB CPU 4개): 전부 4,260~4,300(유선 경로 상한 — §6.3) |
| H6 대기형 DB 락은 풀 대기가 생기고 풀 20에서 지연이 바뀐다, redis-lock은 풀 대기가 없다 | **채택(일부 수정)** | S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) 커넥션 획득 대기 평균: pessimistic 52ms · advisory 204 · **redis-lock 0** · jvm-lock 0. 예상 밖: 진 쪽도 끝까지 DB를 타는 none 374 · optimistic 394 · unique 232 · conditional 217이 더 크다. 풀 20은 S1 L2 p99를 **늘렸다**(pessimistic 446 → 965) |
| H7 공정성 — 첫 승자 도착 순위가 앞쪽 | **판정 불가** | 조건별 첫 승자 순위 중앙값 대부분 1~5, 예외 redis-nx L2(앱·DB CPU 2개) 14 · 2apps 27 등(S1(좌석 1개에 1,000명 동시) 195회 중 순위 10 이상 27%·20 이상 15%). 요청 1,000이 65ms 안에 48개 ms로 몰려 같은 ms 동순위가 최대 39) — ms 해상도로는 방식 간 차이를 가를 수 없다 |
| H8 S3(입장→선점→확정/이탈 전체 흐름) 핫스팟에서 0·1b(none·jvm-lock-in-tx) 외 위반 0·유령 확정 0 | **채택** | 판정기 위반 전 방식 0 · 일시 중복은 none(0~2/회)·1b(0~3/회)만 · 확정·409 재시도·지연이 11개 방식 간 같다 |

### 7.3 판단 기준별 분석

#### ① 정합성

**결론**: 단일 앱에서 9개 방식(1a·2·3a·3b·4·5·6·7·8)은 **모든 시나리오·모든 회차에서 1명만 이겼다**. 1b(트랜잭션 안 JVM 락)는 S1(좌석 1개에 1,000명 동시)에서 매번 2명, **경합이 이어지는 S6(핫 좌석 K개 × 20명 경합 스윕)에서는 수천 건** 중복을 냈다. 락 없음(none)은 S1(좌석 1개에 1,000명 동시)에서 풀 크기만큼(10), S6(핫 좌석 K개 × 20명 경합 스윕)에서 수천 건.

**근거**

| 방식 | S1(좌석 1개에 1,000명 동시) L2 / L4(앱·DB CPU 2/4개) 성공 | S3(입장→선점→확정/이탈 전체 흐름) 판정기 위반(a0·a20·a50(이탈 0·20·50%)) | S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0 K=1 중복 좌석 · 초과 홀드 | S6(핫 좌석 K개 × 20명 경합 스윕) 지연 20 K=1 중복 좌석 · 초과 홀드 |
|------|---------------|------------------------|----------------------------------|------------------|
| none | 10 [10–10] / 10 [10–10] | 0 · 0 · 0 | 7,289 · 16,421 | 3,131 · 17,809 |
| 1a jvm-lock | 1 / 1 | 0 | 0 · 0 | 0 · 0 |
| **1b jvm-lock-in-tx** | **2 [1–2] / 2 [2–2]** | 0 | **7,399 · 7,399** | **2,417 · 2,417** |
| 2·3a·3b·4·5·6·7·8 | 1 / 1 | 0 | 0 · 0 | 0 · 0 |

- 초과 홀드(`v_excess_hold_rows`) = 좌석마다 홀드 수 − 1의 합. 요청 기록의 일시 중복 수(`duplicate`)와 S6(핫 좌석 K개 × 20명 경합 스윕) 전 셀에서 같다 — S6는 TTL 60분·실행 약 3분이라 만료가 없어 끝 상태가 요청 기록을 그대로 담는다.
- 전수 확인(`M/COMPARISON.md`·`X/COMPARISON.md` '판정기 전수' 절): 대조군(none·1b(JVM 락, 트랜잭션 안)·앱 2대의 1a(JVM 락, 트랜잭션 밖))을 뺀 **523회**(정상 회차 632 − 대조군 109) 모두 판정기 위반 합 0이다. S6(핫 좌석 K개 × 20명 경합 스윕) 일시 중복도 9개 방식 × K 1·10·100 × 지연 0·20ms 전부 0이다. 판정기가 실제로 잡는다는 양성 확인: 대조군은 경합 셀에서 위반을 냈다 — none S1(좌석 1개에 1,000명 동시) 10/10, 1b(JVM 락, 트랜잭션 안) S1 9/10(L2(앱·DB CPU 2개) 1회는 성공 1), S6(핫 좌석 K개 × 20명 경합 스윕) K 1·10에서 none·1b 전 회차, **지연 20ms에서는 K=100도** 전 회차(none 1~2 · 1b 1좌석 — ADR-004 H5(정합성은 지연과 무관)). S4(경합 없음)·S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0 K=100·S3(입장→선점→확정/이탈 전체 흐름)에서는 대조군도 끝 상태 위반이 0이다.
- S3(입장→선점→확정/이탈 전체 흐름)의 일시 중복(요청 기록): none 0~2/회, 1b(JVM 락, 트랜잭션 안) 0~3/회, 나머지 0 — 끝 상태 판정기에는 잡히지 않는다(S3는 TTL 300초 — 중복 홀드 중 하나가 확정되면 다른 하나는 판정 전에 만료 배치가 지운다).

**편향·한계**
- **끝 상태 판정기는 만료가 낀 일시 중복을 놓친다**: S3(TTL 300초)에서 요청 기록 0~3/회 vs 판정기 0. 만료가 없는 S6(핫 좌석 K개 × 20명 경합 스윕)에서는 두 값이 같다. ADR-001 판정기의 한계로 기록한다 — 만료가 끼는 시나리오에서는 요청 기록 기반(이긴 홀드의 좌석·만료 시각)이 더 민감하다.
- 1b(JVM 락, 트랜잭션 안)는 '락 해제 ~ 커밋' 틈의 길이에 따라 드러나는 정도가 다르다: 로컬 테스트 0 → S1(좌석 1개에 1,000명 동시) 원격 2 → S6(핫 좌석 K개 × 20명 경합 스윕) 지속 경합 수천.

**다음 ADR 입력**: 정합성만으로는 9개 방식이 갈리지 않는다 — 선택은 ②~⑦(공정성~수평 확장)로 한다. 만료가 끼는 시나리오(S3(입장→선점→확정/이탈 전체 흐름)·ADR-015)에서는 끝 상태 판정기와 함께 요청 기록 기반 중복을 판정 지표로 쓴다.

#### ② 공정성

**결론**: **판정할 수 없었다.** 대부분 회차에서 첫 승자는 '가장 이른 ms 묶음'의 요청이었지만, S1(좌석 1개에 1,000명 동시) 195회 중 27%는 10번째 이후, 15%는 20번째 이후 요청이 이겼다. redis-nx는 L2(앱·DB CPU 2개)·앱 2대에서 조건 중앙값이 14·27로 높고 L4(앱·DB CPU 4개)에서도 125번째가 이긴 회차가 1번 있다(L4 중앙값 1) — 신호일 수 있으나 ms 동순위 해상도로는 방식 차이로 단정하지 못한다.

**근거**: S1(좌석 1개에 1,000명 동시) 첫 승자 도착 순위(1,000 중, 회차별) — 예: pessimistic L4(앱·DB CPU 4개) [1, 8, 1, 19, 1] · redis-nx L4 [125, 6, 1, 1, 1] · redis-lock L2(앱·DB CPU 2개) [1, 1, 1, 1, 1] · advisory L4 [1, 1, 1, 47, 23].

**편향·한계**: 요청 1,000개가 약 65ms 안에 48개 ms로 몰려 같은 ms에 최대 39개가 있다 — 순위는 같은 ms 동순위(최소 순위)라 거칠다. 1a(JVM 락, 트랜잭션 밖)의 `ReentrantLock()`은 비공정(§2.3)이라 FIFO 기대 자체가 없다. 공정성을 재려면 µs 단위 시각과 서버 쪽 도착 순서가 필요하다(후속).

#### ③ 경합 강도별 거동

**결론**: 경합이 **짧거나(S1(좌석 1개에 1,000명 동시) 2초 버스트) 흩어지면(S3·S4(전체 흐름·처리량 계단))** 방식 간 성능 차이는 작다. 차이는 **경합이 이어지고 같은 좌석에 동시에 몰릴 때(S6(핫 좌석 K개 × 20명 경합 스윕) K=1)** 커지고, 그때 갈리는 축은 "진 쪽이 무엇을 쥐고 무엇을 하는가"다.

**근거 1 — 흩어진 경합(S3(입장→선점→확정/이탈 전체 흐름) L4(앱·DB CPU 4개), 3회)**: 확정 10,000 / 9,715~9,756 / 8,038~8,110(a0/a20/a50(이탈 0·20·50%)), 409 재시도 약 74.6만~74.7만, 선점 p99 2~2.4ms — 11개 방식이 회차 간 흔들림 안에서 같다(§6.3, S3 1회차 분석 log 10-04).

**근거 2 — 경합 없음(S4(도착률 계단, 요청마다 새 사용자·좌석))**: L2(앱·DB CPU 2개) 엄격 한계 10개 방식 2,866 · redis-lock 1,911. L4(앱·DB CPU 4개)는 전부 4,260~4,300(경로 상한).

**근거 3 — 이어지는 경합(S6(핫 좌석 K개 × 20명 경합 스윕), 지연 0, 핫 3,200건/s 단계)**

| 방식 | K=1 핫 201/s | K=1 무경합 p99 ms | K=100 핫 201/s |
|------|------------|---------------|---------------|
| none | 600(중복 포함) | 4 | 160 |
| 1b | 317(중복 포함) | 4 | 160 |
| 나머지 9개 | 159 | 3~7 | 160 |

- 지연 0이면 이어지는 경합에서도 정합한 9개 방식의 처리(좌석 159~160/s = 목표 3,200/20)와 무경합 영향(K=1 p99 3~7ms)이 같다 — 임계 구역이 짧으면 락을 오래 쥐지 않는다. 예외: redis-lock은 K=10·100에서 무경합 p99가 30·19ms로 다른 방식(3~4ms)보다 크다(Redisson 대기·왕복이 무경합 요청의 워커와 겹치는 것으로 추정, 미분해). 차이는 임계 구역이 길어질 때 나온다(ADR-004).

**편향·한계**: L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) 상한은 경로(§6.3). S6(핫 좌석 K개 × 20명 경합 스윕)는 L4(앱·DB CPU 4개)·풀 10만. '핫 201/s'는 201 응답 수라 중복 승리도 센다(none 600·1b(JVM 락, 트랜잭션 안) 317) — 정합 방식에서만 좌석이 넘어간 속도다.

#### ④ 자원 점유 — 진 쪽이 무엇을 쥐고 기다리나

**결론**: S1(같은 좌석 1,000명) L2(앱·DB CPU 2개)에서 커넥션 획득 대기(획득 1회당 평균)는 **진 쪽이 DB 전에 막히거나 커넥션 밖에서 기다리는 방식이 0에 가깝고**, **진 쪽도 끝까지 DB 경로를 타는 방식이 가장 크다**. 대기형 DB 락(3a(FOR UPDATE 대기))은 그 사이다. 풀을 20으로 늘리면 지연은 오히려 늘었다.

**근거 — S1(좌석 1개에 1,000명 동시) (`M/COMPARISON.md` S1 표)**

| 방식 | L2(앱·DB CPU 2개) p50 / p99 ms | L2(앱·DB CPU 2개) 커넥션 획득 대기 평균 ms | L4(앱·DB CPU 4개) p50 / p99 ms | 진 쪽이 쥐는 것(§2.13) |
|------|---------------|----------------------|---------------|-------------------|
| pessimistic | **181 / 446** | 52 | 97 / 224 | 커넥션(DB 행 락 대기) |
| pessimistic-nowait | 222 / 821 | 95 | 33 / 288 | 즉시 실패 |
| redis-nx | 237 / 680 | **0.5** | 21 / 251 | 없음(DB에 안 감) |
| jvm-lock | 367 / 787 | **0** | 91 / 329 | 워커(커넥션 밖 대기) |
| advisory | 391 / 816 | 204 | 127 / 262 | 커넥션(advisory 대기) |
| unique | 409 / 1,522 | 232 | 29 / 223 | 커넥션(끝까지 진행 후 INSERT에서 짐) |
| conditional-update | 556 / 1,300 | 217 | 44 / 236 | 커넥션(⑤(조건부 UPDATE 단계)까지 진행) |
| redis-lock | 915 / 1,611 | **0** | 233 / 640 | 워커(Redis 락 대기) |
| optimistic | **1,018 / 2,301** | 394 | 32 / 218 | 커넥션(끝까지 진행 후 버전에서 짐) |
| none(대조) | 893 / 2,078 | 374 | 35 / 235 | 커넥션 |

- 풀 20(대기형 3개): S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) p99 pessimistic 446 → **965**, advisory 816 → **1,469**, redis-lock 1,611 → **2,090** · S4(도착률 계단, 요청마다 새 사용자·좌석) L2 엄격 한계는 대부분 같지만 advisory 풀 20은 5회 중 2회가 한 단계 아래(1,911)였고, 풀 20 대기형 3개는 L2 에러가 11단계(약 4,300건/s)부터 나왔다(풀 10은 redis-lock 외 12단계부터 — §7.5). 3a·6(pessimistic·advisory)은 같은 좌석을 동시에 다투는 DB 세션이 늘어난 것으로 설명되지만(ADR-002 D2(풀 효과)와 같은 방향), redis-lock의 p99 증가(획득 대기 0)는 이것으로 설명되지 않는다 — 원인 미분해.
- L4(앱·DB CPU 4개)에서는 차이가 작다(p50 21~233ms) — CPU가 남아 진 쪽 작업이 빨리 끝난다. redis-lock만 L4(앱·DB CPU 4개)에서도 느리다(Redis 왕복·대기).

**편향·한계**: 커넥션 획득 대기는 부하 직전·직후 차분(예열 제외)의 평균이다. DB 락 대기 표본(0.5초)은 S1(좌석 1개에 1,000명 동시) 버스트를 대부분 놓쳐(최대값이 한 자리) **쓰지 않았다** — 순간 대기는 획득 대기 누적으로 본다. **획득 대기 평균은 분모(획득 횟수)가 방식마다 다르다** — redis-nx는 진 쪽이 DB에 가지 않아 분모가 작다(S6(핫 좌석 K개 × 20명 경합 스윕) 지연 20ms에서는 오히려 약 413ms로 가장 크다, ADR-004). 방식 간 비교는 '대기가 어디에 생기는가'의 방향으로만 읽는다.

**다음 ADR 입력**: '대기형이 느리다'가 아니라 '진 쪽이 커넥션을 쥔 채 일을 많이 할수록 느리다'. 진 쪽을 DB 앞에서 걸러 내는 방식(redis-nx·jvm-lock)은 커넥션 앞 줄을 만들지 않고, nowait는 커넥션을 얻되 DB에서 즉시 실패해 락 대기 시간을 줄인다 — 단 jvm-lock은 앱 1대 한정(⑦(수평 확장)).

#### ⑤ 실패 모드

§7.5에 따로 적는다(응답 코드 분류·데드락·풀 타임아웃·Redis 부분 실패).

#### ⑥ 운영 복잡도(정성)

| 방식 | 추가 인프라 | 코드(전략 본문) | 트랜잭션 경계 제약 | 장애 시 |
|------|-----------|---------------|-----------------|--------|
| 1a jvm-lock | 없음 | 줄무늬 락 + 감싸기 | 락이 트랜잭션을 감싸야 함(1b(JVM 락, 트랜잭션 안)가 함정) | 앱 재시작 시 풀림 · 앱 2대 무효 |
| 2 conditional-update | 없음 | 네이티브 UPDATE 1개 | 없음 | DB가 판정 |
| 3a·3b pessimistic | 없음 | 리포지토리 메서드 1개(+NOWAIT는 55P03 처리) | 없음 | 커밋·롤백 시 해제 |
| 4 optimistic | 버전 열 | 버전 조회·증가 2개 | 버전을 좌석보다 먼저 읽어야 함 | DB가 판정 |
| 5 unique | 유니크 인덱스(이 방식만) | 예외 판별 | 좌석당 홀드 1행 불변식 필요 | DB가 판정 |
| 6 advisory | 없음 | SQL 1개 | 같은 커넥션(JdbcTemplate) 보장 필요 | 트랜잭션 종료 시 해제 |
| 7 redis-nx | Redis | 키 획득·토큰 해제(Lua) | DB 실패 시 키 정리 | **Redis↔DB 부분 실패**(키만 남아 TTL 동안 좌석 막힘) |
| 8 redis-lock | Redis + Redisson | 락 감싸기 | 락이 트랜잭션을 감싸야 함 | watchdog 임대 만료로 해제 |

#### ⑦ 수평 확장 (앱 2대)

**결론**: JVM 락(1a·1b(jvm-lock·jvm-lock-in-tx))은 앱 2대에서 깨지고(앱마다 1명씩 이김), DB·Redis 기반 8개는 1명을 지킨다.

**근거**(`M/COMPARISON.md` S1(좌석 1개에 1,000명 동시), `*-p10-2apps`, L4(앱·DB CPU 4개) 5회): none 20 [16–20] · 1a(JVM 락, 트랜잭션 밖) **2 [2–2]** · 1b(JVM 락, 트랜잭션 안) **3 [2–3]** · 2·3a·3b·4·5·6·7·8 **1 [1–1]**. 두 앱 모두 요청을 받았는지(`after-k6.json` 앱별 선점 요청 수)를 회차 판정이 확인했다.

**편향·한계**: S1(좌석 1개에 1,000명 동시) L4(앱·DB CPU 4개)만, 앱마다 2 CPU + nginx 1 CPU. 처리량 확장은 재지 않았다.

### 7.4 공통 편향

- **측정 경로 상한**: 유선 경로에서 L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석)가 약 4,300건/s에서 막힌다(§6.3, 원인 미확정). L4(앱·DB CPU 4개) 처리량 비교는 그 아래만 유효.
- **같은 캠페인 안 비교**: 본측정과 S6(핫 좌석 K개 × 20명 경합 스윕)는 다른 캠페인이다 — 두 캠페인 사이의 수치(예: S1(좌석 1개에 1,000명 동시)과 S6의 지연)는 직접 비교하지 않는다.
- **S6(핫 좌석 K개 × 20명 경합 스윕) 무경합 스트림도 임계 구역 지연을 탄다**(ADR-004의 주 편향) — 지연 0의 S6 결과는 영향 없다.


### 7.5 실패 모드 — 응답 코드 분류 (⑤)

**결론**: 11개 방식 모두 **5xx 0, 데드락 0, Hikari 풀 타임아웃 0**이다. 실패는 S4(도착률 계단, 요청마다 새 사용자·좌석) 과부하 단계에만 있고, 종류는 k6 타임아웃(30초)과 연결 수립 실패다. 붕괴할 때 모양도 방식 간 같다. 다만 **풀 10에서는 redis-lock만 L2(앱·DB CPU 2개)에서 한 단계 먼저 무너지고**, 풀 20의 대기형 3개(3a·6·8(pessimistic·advisory·redis-lock))도 L2에서 같은 단계부터 에러가 났다. Redis↔DB 부분 실패(키만 남는 경우)는 관측되지 않았다. 장애를 일부러 넣지 않았으므로 '없다'가 아니라 '이 부하에서는 일어나지 않았다'로 읽어야 한다.

**근거 — 본측정 전체(`M/errsplit.json`, 434회)**

| 분류 | 건수 | 어디서 |
|------|-----|-------|
| 2xx | 90,587,629 | — |
| 409(진 것·매수 초과) | 74,132,265 | — |
| k6 타임아웃(0-1050) | 803,708 | **S4(도착률 계단, 요청마다 새 사용자·좌석)만** |
| 연결 수립 실패(0-1211 dial timeout) | 201,672 | **S4(도착률 계단, 요청마다 새 사용자·좌석)만** |
| 연결 끊김(0-1220) | 4,718 | S4(도착률 계단, 요청마다 새 사용자·좌석)만(140회 중 69회, 회차 최대 255) |
| 기타(0-1000) | 4 | S3(입장→선점→확정/이탈 전체 흐름) 확정 4건(4개 조건에 1건씩) |
| **5xx** | **0** | — |

- S4(도착률 계단, 요청마다 새 사용자·좌석) 에러는 140회 모두 **계단 11단계(약 4,300건/s) 이후**에 처음 나온다(12단계부터가 114회, 11단계부터가 20회, 13단계부터가 6회). L2(앱·DB CPU 2개)에서 11단계부터 나는 것은 redis-lock 풀 10·풀 20, pessimistic 풀 20, advisory 풀 20(각 5/5회)이고, 나머지 L2 조건은 5/5회 12단계부터다. 즉 엄격 한계(L2(앱·DB CPU 2개) 2,866 / L4(앱·DB CPU 4개) 약 4,300)를 넘은 과부하 구간이다. 멈춤 분석(`M/stalls.json`): S4(도착률 계단, 요청마다 새 사용자·좌석) 140회 중 **부하 중 멈춤 0**, 종료 직전 멈춤만 97회.
- 회차당 S4(도착률 계단, 요청마다 새 사용자·좌석) 에러(중앙값, 타임아웃 / 연결 실패):

| 방식 | L2(앱·DB CPU 2개) | L4(앱·DB CPU 4개) |
|------|----|----|
| none · 1a(jvm-lock) · 1b(jvm-lock-in-tx) · 2(conditional) · 3a(pessimistic) · 3b(nowait) · 4(optimistic) · 5(unique) · 6(advisory) · 7(redis-nx) (풀 10) | 5,633~5,935 / 940~1,568 | 5,704~5,902 / 1,223~1,431 |
| **redis-lock(풀 10)** | **6,685 / 2,321** | 5,436 / 1,560 |
| 대기형 풀 20(3a·6·8(pessimistic·advisory·redis-lock)) | 5,924~6,761 / 1,831~2,279 | **4,353~4,798 / 1,050~1,215** |

- 데드락(`pg_stat_database.deadlocks` 차분)과 Hikari 커넥션 타임아웃(누적 차분)은 두 캠페인 632회 전부 0이다. NOWAIT의 55P03은 409로 바뀌므로(§2.7) 5xx로 새지 않았다. 다른 SQLState는 올리도록 구현했지만 한 번도 나오지 않았다.
- S6(`X/errsplit.json`, 198회): 에러는 **지연 20ms 조건에만** 있다(1,600·3,200 단계). 지연 0에서는 0이다. **이 에러 수는 하네스 상한에 걸려 있다**: 타임아웃 + 연결 실패 합이 정확히 **3,616**인 회차가 33회 — 3,616 = 2단계 × (k6 VU 상한 10,000 − Tomcat 기본 max-connections 8,192)로 설명된다(추정: 연결을 못 얻은 VU가 30초 타임아웃마다 실패). 상한 3,616에 정확히 닿은 회차는 33회(대부분 K 1·10), 나머지는 3,119~3,613이고, **redis-nx만 2,375~2,524로 뚜렷이 낮다**. 방식 비교 근거로는 쓰지 않는다(ADR-004 §5.5).

**편향·한계**
- 연결 수립 실패와 타임아웃은 앱 앞(Tomcat 연결 상한·accept 대기열)인지 경로(§6.3)인지 이 측정으로 확정할 수 없다 — 서버 쪽 도착 기록이 없다(NEXT N5 — L2 S3 붕괴 원인 조사의 서버 쪽 도착 기록과 같은 한계, 재번호 전 N3). S6(핫 좌석 K개 × 20명 경합 스윕)에서는 위 3,616 일치가 Tomcat 연결 상한을 강하게 가리킨다.
- **풀 20의 L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) 에러가 더 적은 것**(타임아웃 약 4,600 vs 5,800)은 한계 단계(L4 약 4,300 = 경로 상한)가 같은 상태에서 나온 차이라 처리량 이득으로 읽지 않는다. 관측으로만 남긴다.
- 장애 주입(Redis 정지·DB 재시작·네트워크 분할)은 하지 않았다. Redis 방식의 부분 실패 모드(§2.11 키 잔류, §2.12 watchdog 만료)는 코드 경로만 있고 측정은 없다.

**다음 ADR 입력**: 실패 모드로는 9개 방식이 갈리지 않는다(Redis 장애 주입 전까지). redis-lock의 'L2(앱·DB CPU 2개)에서 한 단계 먼저'는 §7.3 ③ H5(무경합 S4는 차이 작음)와 같은 현상이다(Redis 왕복 + 대기).

### 7.6 실측 결과 전체 (2026-10-19 보강)

> 이 절은 이 ADR이 판정에 쓴 측정을 캠페인별로 모두 모은다. 값은 결과 파일에서 그대로 옮겼다. 스크립트로 다시 만들지 않았다. 값 = 중앙값 [최소–최대], n = 계산에 쓴 회차/전체 회차. §7.1~§7.5에 이미 있는 요약 표는 다시 싣지 않고 위치만 적는다. 경로 약칭 `M`·`X`는 §7 머리말과 같다.

#### 7.6.1 캠페인과 구성

| 캠페인 | 하네스 SHA | 기간(KST) | 조건 × 회차 | 상태 | 구성 |
|------|-----------|---------|-----------|------|------|
| 본측정 `20261003-adr003-96e2e6c` | 96e2e6c → b574543(10-04 12:14 조건 경계, 측정 대상 diff 0) | 10-03 09:26 ~ 10-05 19:32 | 36조건, 434회 | 비정상 0, 재측정 0 | 아래 표 |
| S6(핫 좌석 K개 × 20명 경합 스윕) `20261005-adr003-s6-853ef7a` | 853ef7a | 10-05 21:53 ~ 10-06 09:42 | 22조건(전략 11 × 지연 0/20ms) × K 3 × 3회 = 198회 | 비정상 0 | L4(앱·DB CPU 4개)·풀 10·앱 1대, K=1·10·100, 경쟁자 M=20, 핫 200→3,200건/s(2배씩 5단계, 30초) + 무경합 500건/s, 홀드 TTL 60분 |
| 보정 `calib-adr002-harness-c01-L4S4` | 0aeebc6(ADR-002 하네스·앱, 557dff4와 diff 0) | 10-03 01:38 ~ 01:46 | 1조건 × 1회 | ok | ADR-002 c01(인덱스 있음·풀 10·배경 0) L4 S4, 50건/s × 1.5 13단계(30초) |
| 스모크 13개 `smoke*` | 38af24f · ee2843f · f9eea28 · 96e2e6c · 853ef7a | 10-03 01:21 ~ 09:25, 10-05 21:41 ~ 21:49 | 각 1회 | §7.6.7 | §7.6.7 |

본측정의 조건 묶음:

| 묶음 | 조건 이름 | 단계(서버 사양) | 셀(부하) | 회차 | 홀드 TTL |
|------|---------|---------------|---------|------|---------|
| 전략 11개 · 풀 10 · 앱 1대 | `<전략>-p10` | L2(앱·DB CPU 2개) · L4(앱·DB CPU 4개) | S1(VU 1,000, 좌석 1개) · S4(50건/s × 1.5, 16단계, 30초) | 5 | S1 5분 · S4 60분 |
| 전략 11개 · S3 | `<전략>-p10-s3` | L4 | S3(입장 300명/s, 총 20만 명, 좌석 1만, 재시도 3, 생각 시간 30~240초) × 이탈 0·20·50% | **3**(계획 5 — 10-04 재합의) | 5분 |
| 대기형 3개 · 풀 20 | `pessimistic-p20` · `advisory-p20` · `redis-lock-p20` | L2 · L4 | S1 · S4 | 5 | S1 5분 · S4 60분 |
| 전략 11개 · 앱 2대 | `<전략>-p10-2apps` | 앱 2대 × CPU 2 + nginx, DB CPU 4 | S1 | 5 | 5분 |

- 공통: postgres 16, 인덱스 V2, 배경 0, k6 타임아웃 30초, Redis는 전략과 무관하게 항상 뜬다(compose 내부망, CPU 1, 메모리 1g). 경로는 처음부터 끝까지 유선(eno1)이다.

출처: `M/conditions.txt` · `X/conditions.txt` · 회차별 `meta.json`(`k6_env`·`app_env`·`ttl_s`) · `M/CAMPAIGN.log` · `X/CAMPAIGN.log` · `calib-adr002-harness-c01-L4S4/plan.json`

#### 7.6.2 S1(좌석 1개에 1,000명 동시) — 전 조건

| 조건 | 단계 | n | 201 | p50 ms | p99 ms | 첫 승자 순위 | 회차별 순위 | DB 락 대기 최대 | 획득 대기 평균 ms | 획득 대기 최대 ms |
|---|---|---|---|---|---|---|---|---|---|---|
| none-p10 | L2 | 5/5 | 10 [10–10] | 893 [666–1,190] | 2,078 [1,424–2,780] | 1 [1–86] | 1, 86, 8, 1, 1 | 0 [0–0] | 374.0 [193.7–439.1] | 5,504 [4,495–7,113] |
| none-p10 | L4 | 5/5 | 10 [10–10] | 35 [25–45] | 235 [184–439] | 1 [1–1] | 1, 1, 1, 1, 1 | 0 [0–0] | 6.1 [2.1–8.6] | 84 [27–92] |
| jvm-lock-p10 | L2 | 5/5 | 1 [1–1] | 367 [226–503] | 787 [588–847] | 1 [1–14] | 1, 1, 14, 4, 1 | 0 [0–0] | 0.0 [0.0–0.0] | 5,302 [3,798–6,002] |
| jvm-lock-p10 | L4 | 5/5 | 1 [1–1] | 91 [74–108] | 329 [219–365] | 1 [1–30] | 1, 30, 9, 1, 1 | 0 [0–0] | 0.0 [0.0–0.0] | 6 [1–10] |
| jvm-lock-in-tx-p10 | L2 | 5/5 | 2 [1–2] | 407 [167–495] | 1,118 [486–1,264] | 1 [1–12] | 1, 8, 12, 1, 1 | 0 [0–0] | 255.7 [89.7–274.3] | 5,396 [3,800–7,004] |
| jvm-lock-in-tx-p10 | L4 | 5/5 | 2 [2–2] | 97 [78–153] | 319 [280–379] | 2 [1–28] | 1, 1, 28, 4, 2 | 0 [0–0] | 61.0 [48.2–68.7] | 232 [164–320] |
| conditional-update-p10 | L2 | 5/5 | 1 [1–1] | 556 [288–853] | 1,300 [904–2,525] | 1 [1–13] | 1, 1, 1, 1, 13 | 0 [0–0] | 216.9 [154.3–408.7] | 6,401 [4,695–8,303] |
| conditional-update-p10 | L4 | 5/5 | 1 [1–1] | 44 [17–67] | 236 [216–436] | 1 [1–15] | 1, 15, 1, 1, 1 | 0 [0–0] | 6.2 [4.8–18.3] | 101 [67–199] |
| pessimistic-p10 | L2 | 5/5 | 1 [1–1] | 181 [110–283] | 446 [319–769] | 2 [1–7] | 7, 2, 1, 3, 1 | 0 [0–7] | 52.1 [42.8–144.5] | 3,804 [2,519–5,097] |
| pessimistic-p10 | L4 | 5/5 | 1 [1–1] | 97 [47–110] | 224 [217–297] | 1 [1–19] | 1, 8, 1, 19, 1 | 0 [0–8] | 40.6 [33.8–53.2] | 160 [142–258] |
| pessimistic-nowait-p10 | L2 | 5/5 | 1 [1–1] | 222 [109–500] | 821 [570–1,026] | 1 [1–12] | 1, 1, 12, 1, 1 | 0 [0–0] | 94.7 [69.7–166.2] | 3,494 [2,999–3,898] |
| pessimistic-nowait-p10 | L4 | 5/5 | 1 [1–1] | 33 [24–102] | 288 [208–445] | 1 [1–54] | 32, 54, 1, 1, 1 | 0 [0–0] | 12.5 [3.4–34.6] | 190 [88–275] |
| optimistic-p10 | L2 | 5/5 | 1 [1–1] | 1,018 [871–1,163] | 2,301 [1,883–2,688] | 1 [1–17] | 1, 17, 1, 1, 4 | 0 [0–0] | 394.3 [262.0–425.3] | 7,390 [6,504–9,421] |
| optimistic-p10 | L4 | 5/5 | 1 [1–1] | 32 [20–215] | 218 [214–438] | 1 [1–30] | 1, 4, 1, 30, 1 | 0 [0–0] | 7.6 [2.7–12.5] | 108 [28–162] |
| unique-p10 | L2 | 5/5 | 1 [1–1] | 409 [227–713] | 1,522 [873–1,915] | 5 [1–42] | 20, 42, 3, 5, 1 | 0 [0–0] | 231.7 [64.3–366.9] | 4,416 [696–5,309] |
| unique-p10 | L4 | 5/5 | 1 [1–1] | 29 [21–31] | 223 [205–241] | 1 [1–4] | 1, 1, 4, 1, 2 | 0 [0–0] | 4.9 [2.1–14.3] | 76 [19–113] |
| advisory-p10 | L2 | 5/5 | 1 [1–1] | 391 [206–455] | 816 [590–1,043] | 1 [1–18] | 1, 1, 18, 1, 15 | 9 [8–9] | 203.8 [127.0–244.0] | 4,608 [3,807–5,497] |
| advisory-p10 | L4 | 5/5 | 1 [1–1] | 127 [86–154] | 262 [221–439] | 1 [1–47] | 1, 1, 1, 47, 23 | 0 [0–9] | 62.4 [42.5–78.0] | 227 [177–320] |
| redis-nx-p10 | L2 | 5/5 | 1 [1–1] | 237 [86–256] | 680 [439–744] | 14 [1–104] | 14, 104, 4, 1, 15 | 0 [0–0] | 0.5 [0.2–0.7] | 6,605 [5,692–8,809] |
| redis-nx-p10 | L4 | 5/5 | 1 [1–1] | 21 [15–30] | 251 [93–440] | 1 [1–125] | 125, 6, 1, 1, 1 | 0 [0–0] | 0.7 [0.4–1.1] | 2 [1–31] |
| redis-lock-p10 | L2 | 5/5 | 1 [1–1] | 915 [692–1,546] | 1,611 [1,279–2,515] | 1 [1–1] | 1, 1, 1, 1, 1 | 0 [0–0] | 0.0 [0.0–0.0] | 8,002 [7,597–10,794] |
| redis-lock-p10 | L4 | 5/5 | 1 [1–1] | 233 [207–475] | 640 [529–799] | 3 [1–13] | 1, 1, 13, 3, 3 | 0 [0–0] | 0.0 [0.0–0.0] | 492 [307–992] |
| pessimistic-p20 | L2 | 5/5 | 1 [1–1] | 523 [397–603] | 965 [807–1,086] | 21 [1–121] | 1, 1, 121, 25, 21 | 19 [18–19] | 223.5 [180.9–269.6] | 5,689 [3,698–7,004] |
| pessimistic-p20 | L4 | 5/5 | 1 [1–1] | 87 [67–111] | 213 [208–233] | 5 [1–45] | 45, 5, 1, 11, 1 | 0 [0–0] | 34.5 [29.5–41.6] | 139 [124–157] |
| advisory-p20 | L2 | 5/5 | 1 [1–1] | 847 [646–1,272] | 1,469 [1,397–1,798] | 5 [1–22] | 8, 22, 1, 5, 1 | 19 [19–19] | 322.6 [297.9–353.7] | 8,060 [7,187–9,699] |
| advisory-p20 | L4 | 5/5 | 1 [1–1] | 165 [120–249] | 415 [379–457] | 2 [1–47] | 2, 47, 11, 1, 1 | 19 [18–19] | 69.2 [57.4–78.5] | 301 [253–410] |
| redis-lock-p20 | L2 | 5/5 | 1 [1–1] | 1,315 [1,138–1,528] | 2,090 [1,980–2,771] | 1 [1–5] | 2, 1, 5, 1, 1 | 0 [0–0] | 0.0 [0.0–0.0] | 8,803 [7,597–9,893] |
| redis-lock-p20 | L4 | 5/5 | 1 [1–1] | 342 [293–417] | 717 [574–781] | 1 [1–18] | 18, 1, 1, 12, 1 | 0 [0–0] | 0.0 [0.0–0.0] | 404 [369–703] |
| none-p10-2apps | L4 | 5/5 | 20 [16–20] | 284 [279–373] | 1,356 [1,100–2,075] | 1 [1–1] | 1, 1, 1, 1, 1 | 0 [0–6] | 44.3 [29.2–106.9] | 707 [677–1,003] |
| jvm-lock-p10-2apps | L4 | 5/5 | 2 [2–2] | 340 [316–385] | 1,306 [1,242–2,088] | 24 [1–55] | 13, 41, 55, 1, 24 | 0 [0–0] | 0.0 [0.0–0.0] | 836 [375–926] |
| jvm-lock-in-tx-p10-2apps | L4 | 5/5 | 3 [2–3] | 336 [273–1,080] | 1,293 [1,129–2,083] | 1 [1–39] | 1, 2, 1, 1, 39 | 0 [0–0] | 119.2 [76.5–137.5] | 884 [835–1,808] |
| conditional-update-p10-2apps | L4 | 5/5 | 1 [1–1] | 306 [126–1,026] | 1,280 [1,106–1,443] | 1 [1–70] | 70, 1, 1, 1, 1 | 0 [0–0] | 34.2 [17.6–66.6] | 722 [490–1,195] |
| pessimistic-p10-2apps | L4 | 5/5 | 1 [1–1] | 313 [291–434] | 1,270 [1,094–2,071] | 1 [1–14] | 1, 2, 1, 1, 14 | 9 [0–18] | 120.4 [96.8–176.2] | 500 [400–699] |
| pessimistic-nowait-p10-2apps | L4 | 5/5 | 1 [1–1] | 295 [189–1,029] | 1,308 [1,168–2,069] | 2 [1–16] | 6, 1, 1, 2, 16 | 0 [0–0] | 21.2 [14.5–53.6] | 694 [300–908] |
| optimistic-p10-2apps | L4 | 5/5 | 1 [1–1] | 297 [246–365] | 1,290 [1,144–1,333] | 15 [6–38] | 15, 13, 6, 38, 37 | 1 [0–5] | 77.6 [27.8–96.3] | 1,109 [980–1,413] |
| unique-p10-2apps | L4 | 5/5 | 1 [1–1] | 291 [176–348] | 1,453 [1,233–2,053] | 1 [1–39] | 1, 39, 1, 1, 1 | 0 [0–0] | 21.7 [11.1–68.7] | 906 [506–1,289] |
| advisory-p10-2apps | L4 | 5/5 | 1 [1–1] | 487 [461–525] | 1,258 [1,103–1,289] | 3 [1–25] | 3, 25, 1, 1, 15 | 19 [18–19] | 204.9 [183.2–238.9] | 826 [705–1,096] |
| redis-nx-p10-2apps | L4 | 5/5 | 1 [1–1] | 51 [47–103] | 1,224 [209–1,276] | 27 [1–76] | 27, 1, 31, 17, 76 | 0 [0–0] | 0.5 [0.3–3.0] | 671 [591–1,507] |
| redis-lock-p10-2apps | L4 | 5/5 | 1 [1–1] | 1,016 [745–1,316] | 1,791 [1,521–2,107] | 1 [1–8] | 7, 1, 8, 1, 1 | 0 [0–0] | 0.0 [0.0–0.0] | 1,000 [698–1,390] |

- §7.3 ④의 표는 이 표에서 풀 10·앱 1대만 옮긴 것이다. 앱 2대 행의 201이 §7.3 ⑦의 근거다.
- DB 락 대기 최대는 0.5초 표본이라 S1 버스트를 대부분 놓친다(§7.3 ④ 편향). 판정에 쓰지 않았다.

출처: `M/COMPARISON.md` 'S1 같은 좌석 1,000명' 절

#### 7.6.3 S4(도착률 계단, 요청마다 새 사용자·좌석) — 전 조건

| 조건 | 단계 | n | 엄격 한계(건/s) | 회차별 엄격 한계 | 포화점 | 앱 CPU% 최대 | DB CPU% 최대 | 획득 대기 평균 ms |
|---|---|---|---|---|---|---|---|---|
| none-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 3,219 [3,162–3,377] | 205 [204–212] | 164 [156–175] | 48.27 [45.83–52.14] |
| none-p10 | L4 | 5/5 | 4,300 [4,293–4,300] | 4,300, 4,300, 4,299, 4,300, 4,293 | 4,300 [4,293–4,300] | 226 [218–253] | 172 [167–182] | 34.81 [34.04–35.13] |
| jvm-lock-p10 | L2 | 5/5 | 2,866 [2,865–2,866] | 2,866, 2,866, 2,866, 2,865, 2,866 | 3,261 [3,099–3,419] | 205 [204–208] | 164 [162–178] | 47.59 [45.07–51.94] |
| jvm-lock-p10 | L4 | 5/5 | 4,300 [4,300–4,300] | 4,300, 4,300, 4,300, 4,300, 4,300 | 4,300 [4,300–4,300] | 218 [215–254] | 167 [162–171] | 34.48 [34.28–38.18] |
| jvm-lock-in-tx-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 3,154 [2,996–3,285] | 205 [204–213] | 170 [162–178] | 47.35 [46.50–48.76] |
| jvm-lock-in-tx-p10 | L4 | 5/5 | 4,300 [4,300–4,300] | 4,300, 4,300, 4,300, 4,300, 4,300 | 4,300 [4,300–4,300] | 219 [216–222] | 174 [173–178] | 34.40 [33.92–34.55] |
| conditional-update-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 2,866 [2,866–3,065] | 209 [204–211] | 180 [173–185] | 51.83 [48.36–53.16] |
| conditional-update-p10 | L4 | 5/5 | 4,297 [4,262–4,300] | 4,300, 4,269, 4,297, 4,300, 4,262 | 4,297 [4,262–4,300] | 250 [249–274] | 201 [195–207] | 34.50 [34.03–34.79] |
| pessimistic-p10 | L2 | 5/5 | 2,866 [2,849–2,866] | 2,849, 2,866, 2,866, 2,866, 2,866 | 3,661 [3,627–3,713] | 207 [204–216] | 171 [164–177] | 43.25 [42.81–44.54] |
| pessimistic-p10 | L4 | 5/5 | 4,293 [4,284–4,300] | 4,300, 4,292, 4,300, 4,293, 4,284 | 4,293 [4,284–4,300] | 202 [197–204] | 163 [158–171] | 34.39 [33.77–34.49] |
| pessimistic-nowait-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 3,616 [3,434–3,681] | 207 [203–211] | 170 [167–181] | 42.62 [40.17–43.54] |
| pessimistic-nowait-p10 | L4 | 5/5 | 4,300 [4,279–4,300] | 4,300, 4,279, 4,300, 4,300, 4,281 | 4,300 [4,279–4,300] | 207 [197–208] | 172 [166–176] | 34.14 [33.63–35.19] |
| optimistic-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 2,866 [2,866–2,866] | 206 [204–213] | 174 [170–176] | 55.57 [52.20–59.63] |
| optimistic-p10 | L4 | 5/5 | 4,299 [4,280–4,300] | 4,299, 4,280, 4,300, 4,298, 4,300 | 4,299 [4,280–4,300] | 272 [269–308] | 219 [215–221] | 35.06 [34.40–35.36] |
| unique-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 3,125 [3,043–3,188] | 207 [205–208] | 170 [158–176] | 47.58 [46.33–52.19] |
| unique-p10 | L4 | 5/5 | 4,260 [4,240–4,280] | 4,248, 4,240, 4,270, 4,260, 4,280 | 4,260 [4,240–4,280] | 224 [221–246] | 175 [169–178] | 35.10 [34.70–36.35] |
| advisory-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 2,961 [2,881–3,152] | 206 [204–207] | 170 [168–180] | 50.10 [48.55–51.88] |
| advisory-p10 | L4 | 5/5 | 4,300 [4,300–4,300] | 4,300, 4,300, 4,300, 4,300, 4,300 | 4,300 [4,300–4,300] | 232 [226–234] | 181 [174–183] | 34.75 [34.06–36.02] |
| redis-nx-p10 | L2 | 5/5 | 2,866 [2,866–2,866] | 2,866, 2,866, 2,866, 2,866, 2,866 | 2,866 [2,866–2,866] | 206 [205–213] | 159 [158–173] | 55.49 [52.28–55.70] |
| redis-nx-p10 | L4 | 5/5 | 4,300 [4,299–4,300] | 4,299, 4,300, 4,299, 4,300, 4,300 | 4,300 [4,299–4,300] | 250 [242–263] | 180 [175–181] | 34.64 [34.14–35.65] |
| redis-lock-p10 | L2 | 5/5 | 1,911 [1,911–1,911] | 1,911, 1,911, 1,911, 1,911, 1,911 | 1,911 [1,911–2,099] | 210 [209–214] | 132 [130–133] | 43.80 [42.53–48.97] |
| redis-lock-p10 | L4 | 5/5 | 4,297 [4,293–4,300] | 4,297, 4,300, 4,297, 4,295, 4,293 | 4,297 [4,293–4,300] | 411 [310–413] | 220 [201–224] | 27.62 [27.02–30.39] |
| pessimistic-p20 | L2 | 5/5 | 2,843 [2,825–2,865] | 2,841, 2,825, 2,865, 2,843, 2,844 | 2,843 [2,825–2,865] | 210 [205–215] | 194 [190–199] | 59.33 [57.60–66.85] |
| pessimistic-p20 | L4 | 5/5 | 4,300 [4,299–4,300] | 4,300, 4,300, 4,300, 4,300, 4,299 | 4,423 [4,300–4,721] | 310 [295–317] | 277 [273–296] | 24.99 [23.27–25.22] |
| advisory-p20 | L2 | 5/5 | 2,866 [1,911–2,866] | 1,911, 2,866, 1,911, 2,866, 2,866 | 2,866 [2,566–2,866] | 209 [203–215] | 186 [182–197] | 61.33 [61.03–69.47] |
| advisory-p20 | L4 | 5/5 | 4,300 [4,300–4,300] | 4,300, 4,300, 4,300, 4,300, 4,300 | 4,326 [4,300–4,737] | 348 [340–381] | 282 [281–284] | 25.83 [22.96–27.45] |
| redis-lock-p20 | L2 | 5/5 | 1,911 [1,911–1,911] | 1,911, 1,911, 1,911, 1,911, 1,911 | 1,964 [1,911–2,777] | 212 [209–214] | 141 [132–144] | 31.33 [25.04–37.52] |
| redis-lock-p20 | L4 | 5/5 | 4,265 [4,258–4,291] | 4,258, 4,265, 4,286, 4,291, 4,262 | 4,265 [4,258–4,304] | 407 [401–412] | 249 [242–257] | 16.40 [15.45–17.13] |

- 엄격 한계 = p99 < 500ms이고 에러 < 1%인 마지막 단계의 성공 처리량. 서버 지표 수집 공백(>30초)과 비정상 회차는 전 조건 0이다.
- L4의 4,260~4,300은 유선 경로 상한이다(§6.3). 그 위 차이는 판정하지 않는다.

출처: `M/COMPARISON.md` 'S4 처리량 한계' 절

#### 7.6.4 S3(입장→선점→확정/이탈 전체 흐름) — 전 방식, L4, 3회

| 방식 | 확정(CONFIRMED) a0 / a20 / a50 | 선점 p99 ms a0 / a20 / a50 | 재선점 a20 / a50 | 일시 중복(요청 기록) a0 / a20 / a50 |
|---|---|---|---|---|
| none | 10,000 [10,000–10,000] / 9,756 [9,707–9,759] / 8,110 [8,085–8,113] | 2.08 [2.07–2.09] / 2.13 [2.12–2.26] / 2.14 [2.12–2.15] | 2,130 [2,119–2,138] / 6,076 [6,070–6,090] | 1 [0–2] / 1 [0–1] / 1 [1–2] |
| jvm-lock | 10,000 [10,000–10,000] / 9,729 [9,693–9,745] / 8,038 [8,038–8,067] | 2.07 [2.06–2.14] / 2.12 [2.08–2.12] / 2.13 [2.08–2.14] | 2,180 [2,159–2,268] / 6,122 [6,027–6,197] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| jvm-lock-in-tx | 10,000 [10,000–10,000] / 9,735 [9,721–9,747] / 8,056 [8,038–8,084] | 2.04 [2.02–2.08] / 2.12 [2.11–2.13] / 2.13 [2.08–2.14] | 2,191 [2,169–2,220] / 6,047 [6,039–6,084] | 0 [0–3] / 1 [1–2] / 0 [0–2] |
| conditional-update | 10,000 [10,000–10,000] / 9,736 [9,729–9,742] / 8,053 [8,044–8,092] | 2.15 [2.14–2.16] / 2.19 [2.17–2.21] / 2.21 [2.21–2.22] | 2,175 [2,093–2,248] / 6,098 [6,090–6,098] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| pessimistic | 10,000 [10,000–10,000] / 9,743 [9,728–9,747] / 8,076 [8,051–8,095] | 2.03 [1.99–2.03] / 2.08 [2.06–2.09] / 2.11 [2.10–2.20] | 2,124 [2,100–2,160] / 6,138 [6,054–6,138] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| pessimistic-nowait | 10,000 [10,000–10,000] / 9,715 [9,708–9,747] / 8,044 [8,025–8,087] | 1.99 [1.95–2.08] / 2.09 [2.03–2.23] / 2.15 [2.12–2.18] | 2,143 [2,109–2,159] / 6,153 [6,110–6,175] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| optimistic | 10,000 [10,000–10,000] / 9,724 [9,711–9,733] / 8,068 [8,058–8,075] | 2.16 [2.14–2.18] / 2.21 [2.18–2.27] / 2.25 [2.23–3.09] | 2,193 [2,193–2,246] / 6,112 [6,061–6,162] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| unique | 10,000 [10,000–10,000] / 9,746 [9,737–9,753] / 8,047 [7,984–8,129] | 2.04 [2.03–2.07] / 2.13 [2.12–2.60] / 2.16 [2.13–4.31] | 2,170 [2,144–2,230] / 6,156 [6,080–6,172] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| advisory | 10,000 [10,000–10,000] / 9,731 [9,723–9,742] / 8,079 [8,045–8,103] | 2.20 [2.10–2.23] / 2.18 [2.11–2.31] / 2.16 [2.15–2.26] | 2,198 [2,140–2,208] / 6,112 [6,061–6,211] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| redis-nx | 10,000 [10,000–10,000] / 9,725 [9,725–9,728] / 8,044 [8,011–8,102] | 2.21 [2.20–2.29] / 2.27 [2.26–2.29] / 2.27 [2.26–2.38] | 2,222 [2,196–2,276] / 6,058 [6,013–6,136] | 0 [0–0] / 0 [0–0] / 0 [0–0] |
| redis-lock | 10,000 [10,000–10,000] / 9,737 [9,724–9,741] / 8,052 [8,001–8,070] | 2.25 [2.25–2.26] / 2.35 [2.28–2.37] / 2.33 [2.33–2.34] | 2,187 [2,134–2,223] / 6,129 [6,074–6,250] | 0 [0–0] / 0 [0–0] / 0 [0–0] |

- 전 조건에서 확정 에러율 0, 선점 에러율 0, 유령 확정(확정 200 − CONFIRMED) 0, 끝 상태 판정기 위반 0이다(`M/COMPARISON.md` S3 절·판정기 전수 절).
- 일시 중복(요청 기록)은 none과 1b(JVM 락, 트랜잭션 안)에서만 0이 아니다. 끝 상태 판정기는 이를 잡지 못했다(§7.3 ①).
- 1회차만 본 분석(10-04, S3 축소 결정의 근거): 이탈 0%에서 409 재시도 746,370~746,960건(11개 방식, `M/errsplit.json`의 rep1 합), 선점 p99 1.99~2.29ms, 일시 중복 none 2/1/1 · 1b 0/1/2(a0/a20/a50, 조건별 `summary.json` rep1)이었다.

출처: `M/<전략>-p10-s3/SUMMARY.md` 'S3 선점→확정 전체 흐름' 절 · `M/COMPARISON.md` S3 절

#### 7.6.5 S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0 — 핫 3,200건/s 단계, 전 방식 × K

| 방식 | K | 핫 201/s | 핫 p99 ms | 핫 에러율 | 무경합 처리/s | 무경합 p99 ms | 중복 좌석·초과 홀드·일시 | 커넥션 획득 평균 ms | Hikari 대기 최대 | dropped | 락 대기 최대 |
|---|---|---|---|---|---|---|---|---|---|---|---|
| none | 1 | 600.4 | 5 | 0.000 | 500.0 [500.0–500.0] | 4 [4–5] | 7,289·16,421·16,421 | 0.0 | 7 | 0 | 7 |
| none | 10 | 160.1 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 42·42·42 | 0.0 | 0 | 0 | 0 |
| none | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| jvm-lock | 1 | 158.6 | 3 | 0.000 | 500.0 [500.0–500.0] | 3 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| jvm-lock | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| jvm-lock | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| jvm-lock-in-tx | 1 | 316.6 | 3 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 7,399·7,399·7,399 | 0.0 | 0 | 0 | 0 |
| jvm-lock-in-tx | 10 | 160.1 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 43·43·43 | 0.0 | 0 | 0 | 0 |
| jvm-lock-in-tx | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| conditional-update | 1 | 158.6 | 3 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 0·0·0 | 0.0 | 0 | 0 | 3 |
| conditional-update | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| conditional-update | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| pessimistic | 1 | 158.6 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 4 |
| pessimistic | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| pessimistic | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| pessimistic-nowait | 1 | 158.6 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| pessimistic-nowait | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| pessimistic-nowait | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.1] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| optimistic | 1 | 158.6 | 3 | 0.000 | 500.0 [500.0–500.0] | 4 [4–5] | 0·0·0 | 0.0 | 1 | 0 | 3 |
| optimistic | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| optimistic | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.1] | 4 [4–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| unique | 1 | 158.6 | 3 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 0·0·0 | 0.0 | 17 | 0 | 2 |
| unique | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.1] | 3 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| unique | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–3] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| advisory | 1 | 158.6 | 4 | 0.000 | 500.0 [500.0–500.0] | 4 [4–4] | 0·0·0 | 0.0 | 0 | 0 | 8 |
| advisory | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| advisory | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| redis-nx | 1 | 158.6 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| redis-nx | 10 | 158.7 | 2 | 0.000 | 500.0 [500.0–500.0] | 3 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| redis-nx | 100 | 160.0 | 2 | 0.000 | 500.0 [500.0–500.0] | 4 [3–4] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| redis-lock | 1 | 158.6 | 10 | 0.000 | 500.0 [500.0–500.0] | 7 [6–71] | 0·0·0 | 0.0 | 0 | 0 | 0 |
| redis-lock | 10 | 158.7 | 47 | 0.000 | 500.0 [500.0–500.0] | 30 [11–74] | 0·0·0 | 0.0 | 1 | 0 | 0 |
| redis-lock | 100 | 160.0 | 20 | 0.000 | 500.0 [500.0–500.0] | 19 [6–303] | 0·0·0 | 0.0 | 0 | 0 | 0 |

- 핫 201/s·핫 p99·핫 에러율·무경합 처리/s·무경합 p99는 3,200건/s 단계 값이다. 중복·획득 대기·Hikari 대기·dropped·락 대기는 회차 전체 값이다(값은 3회 중앙값).
- 지연 0에서는 dropped와 에러가 전 조건 0이다. 지연 20ms 표는 ADR-004 §5.6에 있다.

출처: `X/COMPARISON.md` 'S6 경합 강도 스윕' 절(3,200 단계 행, 조건 행의 회차 단위 열)

#### 7.6.6 판정기 전수와 응답 분류

판정기 위반이 한 번이라도 난 셀(나머지 모든 셀은 위반 회차 0):

| 캠페인 | 조건 | 셀 | 위반 회차 / 정상 회차 | v_* 합 최대 |
|------|------|----|------------------|-----------|
| 본측정 | none-p10 | L2/S1 · L4/S1 | 5/5 · 5/5 | 10 · 10 |
| 본측정 | jvm-lock-in-tx-p10 | L2/S1 · L4/S1 | 4/5 · 5/5 | 2 · 2 |
| 본측정 | none-p10-2apps | L4/S1 | 5/5 | 20 |
| 본측정 | jvm-lock-p10-2apps | L4/S1 | 5/5 | 2 |
| 본측정 | jvm-lock-in-tx-p10-2apps | L4/S1 | 5/5 | 3 |
| S6 | none-s6-d0 | K1 · K10 | 3/3 · 3/3 | 23,803 · 100 |
| S6 | jvm-lock-in-tx-s6-d0 | K1 · K10 | 3/3 · 3/3 | 14,860 · 114 |
| S6 | none-s6-d20 | K1 · K10 · K100 | 3/3 · 3/3 · 3/3 | 20,962 · 4,028 · 4 |
| S6 | jvm-lock-in-tx-s6-d20 | K1 · K10 · K100 | 3/3 · 3/3 · 3/3 | 4,868 · 3,340 · 2 |

응답 분류 합계(회차 전체):

| 캠페인 | 회차 | 2xx | 409 | 0-1050 타임아웃 | 0-1211 연결 수립 실패 | 0-1220 연결 끊김 | 0-1000 기타 | 5xx |
|------|-----|-----|-----|---------------|-------------------|---------------|-----------|-----|
| 본측정 | 434 | 90,587,629 | 74,132,265 | 803,708 | 201,672 | 4,718 | 4 | 0 |
| S6 | 198 | 15,049,213 | 26,739,015 | 231,184 | 99,683 | 5 | 0 | 0 |

- S6의 에러는 지연 20ms 조건에만 있다. 지연 0의 타임아웃·연결 실패 합은 0이다. 방식별 분포는 ADR-004 §5.6.4.
- 본측정 S4의 에러 첫 단계(0부터 센 단계 번호): 12단계 114회, 11단계 20회, 13단계 6회 — 140회 모두 엄격 한계를 넘은 뒤다(§7.5).

출처: `M/COMPARISON.md`·`X/COMPARISON.md` '판정기 전수' 절 · `M/errsplit.json`·`X/errsplit.json`(키별 합산)

#### 7.6.7 보정과 스모크 — 판정에 쓴 것

| 측정 | SHA | 조건 | 결과 | 쓰인 곳 |
|------|-----|------|------|--------|
| `calib-adr002-harness-c01-L4S4` | 0aeebc6 | ADR-002 c01 L4 S4 1회 | 11단계(목표 4,325건/s) 201 4,299.7/s · p99 12.8ms → 12단계(목표 6,487) 201 2,585.9/s · p99 5,857ms · 에러율 2.3%. 12단계 창의 앱 CPU 150~161% · DB 116~119%(구간 최대 222%·159%는 11단계 끝) | 유선 경로 4,300 한계가 ADR-003 변경과 무관하다는 근거(§6.3, §8.1 10-03 행) |
| `smoke2-s4-none` | ee2843f | none L4 S4 | 11단계 201 4,300.3/s · p99 14.8ms → 12단계 2,644.8/s · p99 4,116ms, dropped 1,313,831 | 기준선 변화 의심 → 보정 측정 |
| `smoke2-2apps-jvm` | ee2843f | jvm-lock 앱 2대 S1 | 201 = 2 | H3 첫 신호 |
| `smoke2-redis-lock` · `smoke2-unique` | ee2843f | S1 L4 | 201 = 1 · 1 (redis-lock p50 269 / p99 616ms) | 하네스 동작 확인 |
| `smoke3-pessimistic` | f9eea28 | pessimistic S1 L4 | 201 = 1, 락 대기 최대 8(`timeline-locks.jsonl`) | §2.6 |
| `smoke3-2apps-jvm` | f9eea28 | jvm-lock 앱 2대 S1 | 201 = 2, 앱별 선점 요청 1,308 / 1,661 | 두 앱 모두 요청 처리 확인(리뷰 R2 수정 검증) |
| `smoke3-redisnx-s4` | f9eea28 | redis-nx L4 S4 | 11단계 4,299.9/s · p99 38.1ms → 12단계 3,027.8/s · p99 3,926ms. Redis 키 641,267개 · 사용 메모리 99,993,520 B | Redis 1g로 충분(리뷰 R8) |
| `smoke4-pessimistic` | 96e2e6c | pessimistic S1 L4 | 201 = 1, p50 81 / p99 234ms, 획득 대기 평균 52.25ms · 최대 158.5ms | §2.6 |
| `smoke4-2apps-jvm` | 96e2e6c | jvm-lock 앱 2대 S1 | 201 = 2, 앱별 선점 요청 502 / 498 | 본측정 직전 하네스 확인 |
| `smoke-s6-pess-d20` | 853ef7a | pessimistic 지연 20ms K 1·10·100 | ADR-004 §5.6.5 | S6 하네스 확인 |
| `smoke-2apps-jvm` · `smoke-redis-lock` · `smoke-unique`(1차) | 38af24f | S1 L4 | 무효 — 2앱 compose가 무효(`invalid-app-config`)인데 기동 실패를 넘겨 다른 스모크의 컨테이너를 잼(log 10-03 01:26) | 하네스 수정 |

- log의 처리량 값과 정의가 다르다. log는 `smoke2-s4-none` 12단계를 2,701/s, 보정 12단계를 2,646/s로 적었다. 보정의 2,646은 전체 응답(201 77,578 + 에러 1,801) ÷ 30초와 같다. 위 표는 201만 센 값이다. `smoke2-s4-none`의 2,701은 결과 파일에서 같은 정의를 찾지 못했다(대화 기록 — 결과 파일 미확인).

출처: 각 스모크 폴더의 `MATRIX.log`·`plan.json`·`L4/<셀>/rep1/k6-summary.json`(단계별 `hold_201`·`hold_duration`)·`after-k6.json`·`timeline-*.jsonl`·`SUMMARY.md`

## 8. 결정

**결정(2026-10-06 사용자 확정)** — 선점 경로의 기본 방식을 **3b `pessimistic-nowait`** 로 한다. 차선은 **2 `conditional-update`** 다.

- **사용자 결정 이유**: 속도는 측정값 기준 최선이 아니어도, **기다리는 동안 다른 좌석을 놓치는 클라이언트가 없어야 한다**는 것이 우선이다(이것이 ADR-003을 하는 원래 이유). 그래서 즉시 실패형(3b·7(nowait·redis-nx))을 고르고, 7 redis-nx는 Redis 운영·Redis↔DB 부분 실패(미측정)·키 TTL 관리 때문에 **아직 Redis를 쓰지 않는 방향**으로 두고 3b(FOR UPDATE NOWAIT)를 택한다.

| 기준 | 3b nowait | 2 conditional | 3a pessimistic | 7 redis-nx |
|------|-----------|---------------|----------------|------------|
| ① 정합성(앱 1대) | 1 · 위반 0 | 1 · 위반 0 | 1 · 위반 0 | 1 · 위반 0 |
| ⑦ 앱 2대 | 1 | 1 | 1 | 1 |
| S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) p50 / p99 ms | 222 / 821 | 556 / 1,300 | **181 / 446** | 237 / 680 |
| S1(좌석 1개에 1,000명 동시) L4(앱·DB CPU 4개) p50 / p99 ms | 33 / 288 | 44 / 236 | 97 / 224 | 21 / 251 |
| S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) 커넥션 획득 대기 ms(획득 1회당 — 분모가 다름) | 95 | 217 | 52 | 0.5 |
| S4(도착률 계단, 요청마다 새 사용자·좌석) 엄격 한계 L2(앱·DB CPU 2개) | 2,866 | 2,866 | 2,866 | 2,866 |
| 지연 20ms K=1 무경합 처리/s · p99(ADR-004) | **390 · 5,265**(K=100 최저점 수준) | 249 · 8,801 | 322 · 6,639 | **395 · 5,143**(K=100 최저점 수준) |
| 추가 인프라 | 없음 | 없음 | 없음 | Redis + 부분 실패 처리 |
| 진 쪽의 의미 | 잠겨 있으면 즉시 진다 — **이긴 쪽이 롤백해도 이미 진 요청은 돌아오지 않는다** | 이긴 쪽 커밋·롤백 뒤에 재평가 — 롤백되면 이길 수 있다 | 기다린 뒤 재평가 | 관문 키가 있으면 즉시 진다 |

- **왜 3b(FOR UPDATE NOWAIT)인가**: 추가 인프라 없이(ADR-002 기준선 그대로) 앱 2대에서도 정합하다. S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) p50은 3a(FOR UPDATE 대기) 다음으로 빠르다(p99·L4(앱·DB CPU 4개)는 중위권 — L4 p99 288은 11개 중 아래에서 4번째). 임계 구역이 길어져도(ADR-004) 진 쪽이 커넥션을 쥔 채 기다리지 않아 무경합 영향이 핫 좌석을 100개로 흩은 분산 경합 최저점(K=100)과 같다. 3a(FOR UPDATE 대기)는 S1(좌석 1개에 1,000명 동시)에서 가장 빨랐지만 ADR-004에서 진 쪽이 줄을 서 무경합 처리를 17% 떨어뜨렸다(388 → 322).
- **3b(FOR UPDATE NOWAIT)의 대가**: ① 이긴 쪽이 롤백하면(매수 초과·DB 오류) 그 사이에 진 요청은 409를 받고, 좌석은 AVAILABLE로 남는다. 사용자는 다시 눌러야 한다. 이 랩의 선점 경로에서 이긴 쪽 롤백은 1인 2매 초과뿐이고, 그 빈도는 측정하지 않았다. ② 55P03 판별 코드가 있어야 한다(§2.7). ③ **선점이 아닌 쓰기가 행 락을 쥔 동안에도 즉시 409다** — 만료 배치(`ExpireHoldsService`)가 만료 좌석을 한 트랜잭션에서 되돌리는 동안, 확정 경로가 좌석 행을 갱신하는 동안 들어온 선점은 좌석이 곧 비거나 상관없는데도 진다. S3(입장→선점→확정/이탈 전체 흐름) a20(이탈 20%)에서 nowait의 확정 수 중앙값이 가장 낮았지만(9,715) 범위가 겹쳐 판정하지 못했다¹ — ADR-015에서 잰다.
- **3b(FOR UPDATE NOWAIT)가 3a(FOR UPDATE 대기)보다 S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) p50에서 느린(222 vs 181ms) 이유 — 추정, 미분해**: 진 쪽이 하는 일이 다르다. 3a(FOR UPDATE 대기)의 진 쪽은 DB 안에서 잠든 채 기다린다(CPU를 쓰지 않음). 이긴 쪽이 커밋하면 깨어나 HELD를 읽고 정상 경로로 409를 낸다. 3b(FOR UPDATE NOWAIT)의 진 쪽은 즉시 55P03 오류를 받는다. 그 뒤 JDBC 예외 → Hibernate → Spring 예외 변환(스택 트레이스 생성) → `ROLLBACK` 왕복 → 409 변환을 거친다. 1,000명이 65ms 안에 몰리면 이 오류 경로가 수백 번 반복된다. **CPU 2개(L2(앱·DB CPU 2개))에서는 CPU가 병목이라** 이 비용이 지연으로 나타난다(획득 대기도 95 vs 52ms). CPU가 남는 L4(앱·DB CPU 4개)에서는 반대로 3b(FOR UPDATE NOWAIT)가 3배 빠르다(p50 33 vs 97ms) — 기다리지 않는 이점이 그대로 드러난다. 요청별 CPU 프로파일은 재지 않았다.
- **2(conditional-update)를 차선으로 두는 이유**: 문장 하나로 끝나서 가장 단순하고, 롤백 시 재평가된다. S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0에서는 3b(FOR UPDATE NOWAIT)와 같다(S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개)에서는 556 / 1,300ms로 3b보다 느리다 — 진 쪽도 ⑤(조건부 UPDATE 단계)까지 진행). 하지만 이 구현은 느린 작업을 UPDATE **앞**에 둬서 진 쪽도 그 작업을 다 한다. 그래서 ADR-004에서 K=100 최저점보다 약 35% 낮았다. '판정(UPDATE)을 먼저 하고 느린 작업을 뒤에' 두면 3b(FOR UPDATE NOWAIT)처럼 진 쪽이 바로 빠질 것으로 **추정**한다(미측정 — ADR-004 §6 인계).
- **제외**: 0·1b(정합성 실패), 1a(앱 2대 무효, ⑦), 4·5(느린 작업이 있으면 진 쪽이 작업을 다 쓰고 버린다 — ADR-004 최하위권), 6(3a(FOR UPDATE 대기)와 같은 대기형인데 S1(좌석 1개에 1,000명 동시)·ADR-004 모두 3a보다 나쁘다), 8(S4(도착률 계단, 요청마다 새 사용자·좌석) L2(앱·DB CPU 2개) 한 단계 아래 · Redis 의존).
- 7 redis-nx는 지표상 3b(FOR UPDATE NOWAIT)와 동급이거나 낫다(S1(좌석 1개에 1,000명 동시) L2(앱·DB CPU 2개) p99 680 · L4(앱·DB CPU 4개) p50 21, ADR-004 최저점 수준). 그래도 Redis 운영 비용과 Redis↔DB 부분 실패(미측정)를 감수할 만큼 차이가 크지 않아 기본으로 두지 않는다. DB가 병목이 되는 규모(N7(다중 인스턴스))에서 다시 본다.

> ¹ 정정(2026-10-19): 처음에는 "a20·a50(이탈 20·50%)에서 nowait의 확정 수 중앙값이 가장 낮았다"로 적었다. 결과 파일로 다시 보니 a50에서 가장 낮은 것은 jvm-lock 8,038이고, nowait는 redis-nx와 같은 8,044로 두 번째다. a20만 nowait 9,715가 가장 낮다. 출처: `M/COMPARISON.md` S3-a20·S3-a50 절의 CONFIRMED 열. 범위가 겹쳐 판정하지 못했다는 결론은 그대로다.

### 8.1 의사결정 기록 (2026-10-19 보강)

> 시각은 KST다. 사용자 결정은 대화 기록의 원문(질문 선택지 응답 포함)을 그대로 옮겼다. 근거 지표는 이 문서의 절이나 결과 파일 위치로 적는다. 대화 기록의 시각이 작업 log와 다르면 대화 기록 시각을 쓴다.

| 시각 | 근거 지표(파일:표) | 결정 | 고르지 않은 것 | 누가 |
|------|-----------------|------|-------------|------|
| 10-02 21:27 | ADR-002 인계(§9) | ADR-003을 락 방식 비교로 연다. 성능·안정성 외 기준을 더 찾는다 | — | 사용자: "adr3으로는 그럼 락방식에 대해 실험해봐야겠네 이때 락은 성능 측면과 안정성 측면 2가지를 기준으로 추가적인 기준이 될만한 요소가 있을까?" |
| 10-02 22:12 | ADR-002 L2 S3 두 갈래(ADR-002 §7) | 비교 대상 0~8 전부, 판정 기준 ①~⑦ 전부, S3는 L4만, 1인 2매(당시 ADR-004, 지금 ADR-005)는 따로 | 'DB 계열 전부 + 대조군'(Redis는 별도 ADR — 메인 권장안), '핵심만(0·2·3·4·6)' · S3 '먼저 원인 조사', 'L2·L4 둘 다' · 1인 2매 함께 | 사용자 선택: "Redis까지 전부 (0~8)" · "①정합성 ③경합강도 ④자원점유 ⑤실패모드,② 공정성,⑦ 수평 확장 (앱 2대),⑥ 운영 복잡도 (정성)" · "ADR-003에서 S3는 L4만 (권장)" · "따로 (권장)" |
| 10-02 22:14 | — | 전략 인터페이스 + 기동 설정, S3 이탈 0/20/50 전부, 앱 2대는 S1만, Redis 내부망·CPU 1, 풀 20은 대기형 3개만 | 요청 파라미터 전환 · S3 이탈 0%만(메인 권장, 약 30h)·S1·S4만 · 앱 2대 S1·S4 · 풀 20 전 방식 | 사용자 선택: "전략 인터페이스 + 기동 설정 (권장)" · "S3 이탈 0/20/50 전부 (약 55h)" · "S1만 전 방식 × L4 ×5 (권장, +2h)" · "Redis 내부망·CPU 1 / 대기형 락만 풀 20 추가 (권장)" |
| 10-03 00:45 | — | JVM 락은 올바른 방식(1a)과 함정 변형(1b) 둘 다, Redis NX는 관문이고 DB가 원천, 하네스 보강 4종 전부, 측정 경로는 와이파이 그대로 + 단절 회차 자동 표시 | 올바른 방식만 · 홀드 원천을 Redis로 이전 | 사용자 선택: "올바른 방식 + 함정 변형 둘 다 (권장)" · "Redis는 선점 관문, DB가 원천 (권장)" · "S4 계단 확장 (6,487 위로),배포 재시도 + 단절 회차 판정,락 대기 지표 수집,공정성 측정 (S1 승자의 도착 순위)" · "그대로 + 단절 회차 자동 표시" |
| 10-03 00:49 | 명세 `requirement-spec.md` | 명세 합의, 모드 auto | 수정 후 합의 · lazy | 사용자 선택: "합의 — 이대로 진행" · "auto (권장)" |
| 10-03 01:38 | `smoke2-s4-none`: 12단계(목표 6,487) 201 2,644.8/s · p99 4,116ms(§7.6.7) — ADR-002 c01 L4는 5회 모두 ≥6,294 | 기준선이 바뀌었는지, 이번 변경 탓인지 가르려고 ADR-002 하네스로 보정 1회 | — | 메인 판단(log 10-03 01:38) |
| 10-03 01:46 | `calib-adr002-harness-c01-L4S4`: ADR-002 하네스·앱 그대로도 12단계 201 2,585.9/s · p99 5,857ms, 앱 CPU 150~161%(§7.6.7) | 4,300 한계는 ADR-003 변경과 무관하고 서버도 아니다 — 요청 경로 쪽 | — | 메인 판단(log 10-03 01:46) |
| 10-03 04:04 | 경로 = 유선 eno1로 바뀜(log 10-03 01:48 — 10-01 재부팅 때 링크 업) | 원인을 짧게 조사한다. ADR-002에 정정 섹션을 넣는다 | '유선 그대로' · '와이파이로 되돌림' · ADR-002 본문 수정 | 사용자 선택: "원인부터 짧게 조사 (권장)" · "정정 섹션 추가 (권장)" |
| 10-03 08:51 | 경로 조사: 검사용 nginx(204) 3천~1만 건/s 누락 0 · p99 0.6ms, 커널 'SYN flooding' 경고는 9/30·10/3 모두(log 10-03 01:55 행 — 검사 컨테이너를 지워 결과 파일 없음) | 경로 조사는 보류하고 유선으로 본측정. L4 S4 4,300건/s 위의 차이는 판정하지 않는다(§6.3) | 와이파이로 경로를 돌려 보정 1회 더 재기(sudo 라우트 변경 필요) | 사용자: "진행하자 와이파이랑 유선은 우선 보류하는게 나을 꺼 같은데" |
| 10-03 09:06 ~ 09:25 | 측정 전 리뷰 R1~R9 · 재점검 N1~N6 · 스모크 3·4차(§7.6.7) | 하네스 수정 후 본측정 시작(09:26). 1b가 로컬 테스트(50명 × 5라운드)에서 중복 0 → H2는 본측정이 판정 | — | 메인 판단(리뷰 ledger) · push는 사용자 "진행하자"(09:27) |
| 10-03 10:35 | — (설계 질의, §2.3) | 키별 락·메모리 관문은 재지 않고 설명으로만 남긴다. 이 측정에서 거짓 경합이 사실상 0이라 줄무늬와 결과가 같을 것으로 본다 | 키별 락 실험 추가 | 사용자: "흠 그런데 이실험으 하는게 좋을 지 고민이 되긴하네" → "결국 db에서 위 예약작업을 위한 조회를 할때는 jvm에 대기열을 걸어놓겠다는거잖아? … 위 내용 작성해놓자" |
| 10-04 12:03 | S3 1회차(§7.6.4 끝 줄): 11개 방식 확정 10,000, 409 재시도 746,370~746,960, 일시 중복 none·1b만 1~2건 — 1회차 16.0h 중 S3가 11.0h(69%) | S3 남은 회차를 3회로 줄인다(A). b574543으로 조건 경계에서 전환 | B(none·1b만 5회, 나머지 1회) · C(그대로 5회) | 사용자: "A로 진행하자 그리고 이 락의 차이점이 명확해지는 구간을 찾아볼 필요가 있어 보이는데 어떻게 생각해?" |
| 10-04 12:55 | S1은 2초 버스트, S3·S4는 경합이 흩어져 방식 간 성능 차이가 없음(§5 'S6를 더한 이유') | S6(핫 좌석 K개 × 20명 경합 스윕) 추가 — 임계 구역 지연 0/20ms 포함, 전략 11개 전부, 본측정 뒤 별도 캠페인 | 지연 변형 제외(약 13h) · 대표 묶음 7개 | 사용자: "좋아 지연 변형까지 넣고 전략 11개 전부로 진행하자" |
| 10-04 18:15 | — | 지연 변형은 새 ADR-004로 쓰고 기존 ADR-004~010을 005~011로 옮긴다 | B: ADR-011로 뒤에 붙임 · C: ADR-003 안에 남김 | 사용자: "A로 가보자" |
| 10-06 09:55 | `X/COMPARISON.md`: 지연 0 K=1 1b 중복 7,399, 지연 20ms K=100 무경합 383~390/s(ADR-004 §5.1) | 지연 0은 이 ADR, 0 vs 20ms는 ADR-004에서 K=1 − K=100으로만 판정 | — | 메인 판단 |
| (10-06 이전, 대화 요약) → 10-06 10:22 | ADR-004 §5.2: K=1 무경합 처리 2 conditional 249/s(K=100 최저점 386) vs 3b nowait 390/s | 결정 제안을 '2 conditional 기본'에서 '3b nowait 기본, 2 차선'으로 바꾼다. 지금 구현의 2는 느린 작업이 UPDATE 앞이라 진 쪽도 지연을 다 쓴다(ADR-004 묶음 C) | 2 conditional 기본(처음 제안 — DB 원자 연산, 추가 인프라 없음, 앱 2대 안전) | 메인 판단 — 사용자 확정 10-06 11:32 |
| 10-06 10:41 ~ 10:45 | 결과 문서 리뷰 D1~D14 · post-fix N1·N2(log 리뷰 ledger) | 첫 해석을 고쳤다. ① S6 '판정기가 일시 중복을 놓친다'는 근거 철회 — 7,289는 중복 좌석 수, 16,421은 초과 홀드 수로 단위가 달랐다. 근거는 만료가 끼는 S3만 남김 ② 전수 확인 577 → 523회(미측정 회차를 셈) ③ S6 에러 수는 하네스 상한 3,616에 걸려 방식 비교에서 뺌 ④ 획득 대기 평균은 분모가 달라 방향으로만 읽음 ⑤ 3b 대가 ③(만료·확정이 행 락을 쥔 동안에도 409) 추가 | — | 메인 판단(리뷰 반영) |
| 10-06 11:27 | §8 표(S1 L2·L4 p50/p99, ADR-004 무경합 처리) | 기준을 '측정상 최속'이 아니라 '기다리는 동안 다른 좌석을 놓치는 클라이언트가 없어야 한다'로 둔다 → 즉시 실패형(3b·7) | 대기형 중 S1 최속인 3a pessimistic | 사용자: "속도적으로는 측정값 기준 최선은 아니지만, 이 대기 시간으로 인해 다른 좌석을 놓치는 클라이언트가 있으면 안된다는게 우선되어야할 꺼 같다. 그래서 위와 같은 락을 고려한느게 맞다고 생각한다. 이게 adr3의 원초적인 이유일꺼 같고," |
| 10-06 11:32 | §8 표: 7 redis-nx S1 L2 p99 680 vs 3b 821 · L4 p50 21 vs 33, S4 L2 한계 둘 다 2,866, Redis↔DB 부분 실패 미측정 | 기본 방식 3b nowait 확정(Accepted). 7 redis-nx는 Redis 운영·부분 실패·키 TTL 관리 때문에 아직 쓰지 않는다. 결정을 repo README 맨 위에 기록 | 7 redis-nx 기본 · '원칙만 확정, 방식은 보류' · README 위치로 study-note README·둘 다 | 사용자 선택: "이건 리드미에 명시하자 1번으로 3b로하는 이유는 redis-nx에 대한 위와 가 같은 이유가 있어서 아직은 레디스를 쓰지 않는 방향으로 하고 1번을 선택한다고 적는게 맞고," · "seat-reservation-lab/README.md 맨 위" |
| 10-06 11:35 ~ 11:38 | — (미측정) | `pg_try_advisory_xact_lock`은 DB만으로 즉시 실패하는 후보로 인정하되 후순위로 미룬다(§9 ADR-015 행). 3b 결정은 바꾸지 않는다 | 지금 추가 측정 | 사용자: "이거 try쪽을 활용하면 nowait를 하지 않아도 된다는 거 아니야?" → "푸시하고 그렇게 진행해서 후순위로 미뤄놓고 우선 ADR3과 ADR4에 대한 결정 내용은 리드미 최상단에 넣고 현재 ADR5 진행중이라 넣고 ADR5 진행하자." |

- 다음 ADR로 넘긴 것은 §9에 있다. 이 표에서 새로 넘긴 것은 없다.
- 10-03 04:04 행: 작업 log는 경로 조사를 01:55 행에 적었지만, 대화 기록에서 사용자가 '원인부터 짧게 조사'를 고른 시각은 04:04다. 조사 결과는 그 뒤에 나온 것으로 보인다(log는 수정하지 않았다).

출처: 대화 기록(사용자 메시지·선택지 응답, 10-02 ~ 10-06) · `docs/plans/2026-10-03/adr-003-lock-comparison/log.md` 타임라인·리뷰 ledger · `requirement-spec.md` §0

## 9. 다음 ADR로 넘기는 것 (요약)

| 받는 곳 | 내용 |
|--------|------|
| **ADR-004 임계 구역 길이** | S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0 = 기준(9개 방식 동일, 무경합 p99 3~7ms). 0 vs 20ms 비교와 '판정 먼저(claim-first)' 재배치 측정 |
| ADR-005 1인 2매 | 기본 방식(§8 결정) 위에서 사용자 단위 직렬화. 3b(FOR UPDATE NOWAIT)를 고르면 이긴 쪽 롤백(매수 초과) 시 진 요청이 되살아나지 않는다는 점을 ADR-005가 측정 |
| ADR-015 홀드 만료 · 후순위 후보 | **`pg_try_advisory_xact_lock`(미측정 후보, 2026-10-06 사용자 제기)** — DB만으로 즉시 실패하는 또 하나의 방법. 행이 아니라 숫자 키(좌석 id)를 잠가 만료 배치·확정의 행 락과 부딪히지 않고(3b(FOR UPDATE NOWAIT) 대가 ③(만료·확정이 행 락을 쥔 동안에도 409) 회피), 진 쪽은 오류 대신 `false` → 409(L2(앱·DB CPU 2개)에서 3b가 느렸던 예외 경로 추정 회피). 대신 왕복 하나 더, 모든 선점 경로의 키 규칙 준수, 키 공간 구분(두 정수 형태) 필요. ADR-015에서 3b와 함께 잰다 |
| ADR-015 홀드 만료 | 3b(NOWAIT)는 만료 배치·확정이 좌석 행 락을 쥔 동안 들어온 선점을 즉시 409로 낸다(§8 대가 ③(만료·확정이 행 락을 쥔 동안에도 409)) — 만료 배치 크기·주기와 409 비율 |
| ADR-015 홀드 만료 | 끝 상태 판정기는 만료로 지워진 중복을 놓친다(S3(입장→선점→확정/이탈 전체 흐름): 요청 기록 0~3/회 vs 판정기 0 — 만료가 없는 S6(핫 좌석 K개 × 20명 경합 스윕)에서는 같다). 만료 배치가 증거를 지우는 경로 |
| ADR-016 확정 원자성 | 결제 확인이 트랜잭션 안에 있으면 ADR-004 결과가 그대로 적용된다(느린 작업 × 락 보유) |
| NEXT N9 다중 인스턴스(재번호 전 N7) | 앱 2대 S1(좌석 1개에 1,000명 동시)에서 DB·Redis 8개 방식은 1. 처리량 확장·키 라우팅·Redis 장애 주입은 미측정 |
| 판정기(ADR-001 보강) | 만료가 끼는 시나리오에서는 **요청 기록 기반 일시 중복**(`hold_ok_seat` — 같은 좌석에 만료 전 201이 둘)을 정식 판정 지표로. 공정성은 ms 해상도로 판정 불가 — µs 시각·서버 도착 순서 필요(redis-nx 늦은 순위 반복 확인 포함) |
| 하네스 | S6(핫 좌석 K개 × 20명 경합 스윕) 에러 수가 k6 VU 상한 − Tomcat max-connections(8,192)에 걸림(3,616) — 상한 조정 · 유선 경로 L4(앱·DB CPU 4개) S4(도착률 계단, 요청마다 새 사용자·좌석) 약 4,300건/s 상한(원인 미확정, 보류) · 연결 실패가 앱 앞인지 경로인지 가를 서버 쪽 도착 기록 없음 · S1(좌석 1개에 1,000명 동시) 순간 표본(락 0.5초)은 2초 버스트를 놓친다 — 누적 지표로 대체 |

## 10. 사용한 파일 — 위치와 설명

> 경로는 repo 루트 기준. `M` = `k6/ADR-003/results/20261003-adr003-96e2e6c`, `X` = `k6/ADR-003/results/20261005-adr003-s6-853ef7a`. 앱 패키지 `src/main/kotlin/com/jun/labs/seatreservation/` = `…/`.

### 10.1 코드

| 파일 | 설명 |
|------|------|
| `…/service/HoldStrategyType.kt` | 방식 11개 열거(`seat.hold.strategy` 값) |
| `…/service/SeatHoldProperties.kt` | `strategy`(기본 none), `critical-section-delay`(기본 0 — ADR-004 장치) |
| `…/service/impl/HoldSeatService.kt` | 기동 설정으로 고른 전략 하나에 위임 |
| `…/service/impl/HoldSeatProcess.kt` | 공통 선점 규칙(§2.1) — `Propagation.MANDATORY`, `loadSeat`·`beforeMutation`·`afterFlush` 갈래점, 임계 구역 지연 |
| `…/service/impl/hold/HoldStrategy.kt` | 전략 인터페이스 · `seatTaken()`(409) |
| `…/service/impl/hold/NoLockHoldStrategy.kt` | 0 none |
| `…/service/impl/hold/JvmLockHoldStrategy.kt` | 1a·1b(jvm-lock·jvm-lock-in-tx) — `SeatStripedLocks`(65,536 줄무늬, 비공정) |
| `…/service/impl/hold/DbLockHoldStrategies.kt` | 2 conditional · 3a pessimistic · 3b nowait(55P03) · 4 optimistic · 5 unique(23505 + 인덱스 이름) · 6 advisory |
| `…/service/impl/hold/RedisHoldStrategies.kt` | 7 redis-nx(토큰 Lua 해제, addSuppressed) · 8 redis-lock(Redisson) |
| `…/service/impl/hold/HoldStrategyStartupCheck.kt` | 기동 검사 — unique 인덱스 ↔ 전략 짝, Redis ping |
| `…/domain/repository/ProductSeatRepository.kt` | `findForUpdate`·`findForUpdateNoWait`·`holdIfAvailable`·`findVersion`·`bumpVersion` |
| `src/main/resources/db/migration/V3__add_seat_version.sql` | 4 optimistic의 버전 열(모든 전략에 존재 — 비교 조건 동일) |
| `src/main/resources/db/migration-unique/V4__unique_seat_hold.sql` | 5 unique 전용 부분 유니크 인덱스(별도 Flyway 위치) |
| `…/loadtest/LoadtestDataService.kt` | 초기화 시 Redis 전략이면 flushDb |
| `src/test/kotlin/com/jun/labs/seatreservation/service/hold/` — `HoldStrategyTest`·`UniqueHoldStrategyTest`·`RedisHoldStrategyTest`·`CriticalSectionDelayTest`·`HoldRace`(+ `RedisTestcontainersConfiguration`) | 계약 ×8, 50명 경합, 양성 대조(none 5라운드 안 중복), NOWAIT, 지연 장치 — 68개 green |

### 10.2 하네스

| 파일 | 설명 |
|------|------|
| `k6/ADR-003/compose.yml` | 앱 + postgres:16 + redis(내부망, CPU 1, 1g) — `HOLD_STRATEGY`·`FLYWAY_LOCATIONS`·`CRITICAL_SECTION_DELAY`·`POOL_SIZE` |
| `k6/ADR-003/compose.two-apps.yml` · `nginx.conf` | 앱 2대 + nginx(⑦) — `extends`(`file:` 명시), 앱 포트 비공개 |
| `k6/ADR-003/scenarios/s1-same-seat.js` | S1 — 요청 시각 `t0` 태그(공정성) |
| `k6/ADR-003/scenarios/s4-throughput.js` | S4 — 16단계(50→약 21,900건/s), 좌석 200만 시드 |
| `k6/ADR-003/scenarios/s3-full-flow.js` · `s2-same-user.js` · `warmup.js` | ADR-002 시나리오 복사 |
| `k6/ADR-003/scenarios/s6-contention.js` | S6 — 핫 좌석 K × 경쟁자 M=20, 핫 200→3,200건/s(30초씩) + 무경합 500건/s, `hold_ok_seat` 기록 |
| `k6/ADR-003/scripts/run.sh` | 조건 실행기 — `--strategy`·`--pool`·`--apps`·`--delay-ms`·`--only-rep`, k6 전후 누적 지표 차분, 불일치·수집 실패·path-gap·compose 실패 판정 |
| `k6/ADR-003/scripts/lib.sh` | 배포·compose 재시도, lb 경유 actuator, 락 표본기(별칭 `s`), DB 스칼라 |
| `k6/ADR-003/scripts/campaign.sh` | 회차 우선 순서, `--s3-reps`, `--suite s6`, 비정상 회차 재측정 2바퀴, 인프라 연속 실패 5회 중단 |
| `k6/ADR-003/scripts/summarize.py` · `compare.py` | 조건 요약(`SUMMARY.md`·`summary.json`) · 캠페인 교차표(`COMPARISON.md` — S1(좌석 1개에 1,000명 동시) 공정성·풀, S4(도착률 계단, 요청마다 새 사용자·좌석) 획득 대기, S6(핫 좌석 K개 × 20명 경합 스윕) 단계표) |
| `k6/ADR-003/scripts/errsplit.py` · `stalls.py` · `gapcheck.py` | 응답 분류(error_code 기준) · S4(도착률 계단, 요청마다 새 사용자·좌석) 멈춤(plan.json 조건) · 서버 지표 수집 공백 |
| `k6/ADR-003/README.md` | 하네스 구성·실행법 |

### 10.3 결과

| 파일 | 설명 | 이 문서에서 쓴 곳 |
|------|------|------------------|
| `M/CAMPAIGN.log` · `X/CAMPAIGN.log` | 조건 시작·종료·재측정 시각 | §7.1 |
| `M/COMPARISON.md` · `comparison.json` | 본측정 교차표 + 판정기 전수 + 응답 분류(errsplit 산출 뒤 재생성) | §7.2, §7.3 ①~④·⑦, §7.5, §8 |
| `X/COMPARISON.md` · `comparison.json` | S6(핫 좌석 K개 × 20명 경합 스윕) 교차표(지연 0·20, K 1·10·100, 단계별 — 중복 좌석·초과 홀드·일시, Hikari 대기·dropped) + 판정기 전수 + 응답 분류 | §7.3 ①·③, §7.5, ADR-004 §5 |
| `M/errsplit.json` · `X/errsplit.json` · `requests-sha256.txt` | 응답 분류 원자료 · 요청별 원시 파일 sha256 | §7.5 |
| `M/stalls.json` | S4(도착률 계단, 요청마다 새 사용자·좌석) 멈춤 구간 | §7.5 |
| `<캠페인>/<조건>/SUMMARY.md` · `summary.json` · `plan.json` · `MATRIX.log` | 조건별 요약·회차별 산출(판정기·일시 중복·획득 대기·데드락·dropped) | §7.3 ①(위반 전수), §7.5 |
| `<캠페인>/<조건>/L4/<셀>/rep<k>/` | 회차 원시 — `before-k6.json`·`after-k6.json`(누적 지표), `timeline-{client,db,hikari,locks,server}.jsonl`, `consistency.json`, `k6-summary.json`, `app.log.gz` | §7.3 ④ |
| `…/k6-requests.csv.gz` | 요청별 원시 기록 — 커밋 안 함, sha256만 | §7.3 ②·§7.5 |
| `k6/ADR-003/results/smoke*` · `calib-adr002-harness-c01-L4S4` | 스모크 · 유선 경로 보정(ADR-002 하네스로 4,300 재현) | §6.3 |

### 10.4 문서

| 파일 | 설명 |
|------|------|
| `docs/plans/2026-10-03/adr-003-lock-comparison/requirement-spec.md` | 합의 명세 — 재합의(S3(입장→선점→확정/이탈 전체 흐름) 3회, S6(핫 좌석 K개 × 20명 경합 스윕) 추가, ADR-004 분리) 포함 |
| `docs/plans/2026-10-03/adr-003-lock-comparison/log.md` | 작업 타임라인·리뷰 ledger·이슈 |
| `docs/adr/ADR-004-critical-section-hold-time.md` | S6(핫 좌석 K개 × 20명 경합 스윕) 지연 0 vs 20ms 분석 |
