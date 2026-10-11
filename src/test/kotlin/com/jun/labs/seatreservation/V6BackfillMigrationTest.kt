package com.jun.labs.seatreservation

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.service.HoldSeatCommand
import com.jun.labs.seatreservation.service.HoldSeatUseCase
import com.jun.labs.seatreservation.service.limit.counterMismatch
import com.jun.labs.seatreservation.support.IntegrationTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.ClassPathResource
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * ADR-008 V6 백필(명세 §2 카운터 불변식 · §5 ①~⑤). 컨텍스트 기동 때 Flyway가 빈 DB에 V6를 이미 적용했으므로,
 * 데이터를 넣은 뒤 같은 SQL 파일을 직접 다시 실행해 "기존 데이터가 있는 DB에 V6가 적용되는" 상황을 만든다.
 * 판정은 판정기(v_counter_mismatch)와 같은 정의로 DB 행을 직접 센다.
 */
class V6BackfillMigrationTest : IntegrationTest() {

    @Autowired lateinit var holdSeat: HoldSeatUseCase
    @Autowired lateinit var tx: TransactionTemplate

    /** 파일이 없으면 여기서 실패한다(구현 전 빨강). */
    private fun runV6() {
        val resource = ClassPathResource(V6_RESOURCE)
        assertTrue(resource.exists(), "$V6_RESOURCE 가 클래스패스에 없다")
        val sql = resource.inputStream.use { String(it.readAllBytes(), Charsets.UTF_8) }
        // Flyway처럼 트랜잭션 하나로 실행한다 — V6의 LOCK TABLE은 트랜잭션 안에서만 된다(pgJDBC는 여러 문장을 따로 보내 자동 커밋이면 거부된다, ADR-008 task 02)
        tx.executeWithoutResult { jdbcTemplate.execute(sql) }
    }

    private fun newSchedule(): ProductSchedule =
        productScheduleRepository.save(ProductSchedule(productId = schedule.productId, startsAt = NOW.plusSeconds(172_800)))

    private var seatNo = 0

    private fun seat(scheduleId: Long, status: SeatStatus = SeatStatus.HELD): Long = jdbcTemplate.queryForObject(
        "INSERT INTO product_seat (schedule_id, section, row_no, seat_no, status) VALUES (?, 'A', 1, ?, ?) RETURNING id",
        Long::class.java, scheduleId, ++seatNo, status.name,
    )!!

    private fun hold(scheduleId: Long, userId: Long, expiresAt: Instant = NOW.plusSeconds(300)): Long {
        val seatId = seat(scheduleId, SeatStatus.HELD)
        jdbcTemplate.update(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
            seatId, scheduleId, userId, Timestamp.from(expiresAt.minusSeconds(300)), Timestamp.from(expiresAt),
        )
        return seatId
    }

    private fun reservation(scheduleId: Long, userId: Long, status: String) {
        val seatId = seat(scheduleId, if (status == "CONFIRMED") SeatStatus.RESERVED else SeatStatus.AVAILABLE)
        jdbcTemplate.update(
            "INSERT INTO reservation (schedule_id, seat_id, user_id, payment_uid, status, created_at) VALUES (?, ?, ?, ?, ?, now())",
            scheduleId, seatId, userId, "pay-$seatId", status,
        )
    }

    private fun quota(scheduleId: Long, userId: Long, cnt: Int) {
        jdbcTemplate.update("INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, ?)", scheduleId, userId, cnt)
    }

    private fun quotaRows(): List<Triple<Long, Long, Int>> = jdbcTemplate.query(
        "SELECT schedule_id, user_id, cnt FROM user_hold_quota ORDER BY 1, 2",
    ) { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), rs.getInt(3)) }

    private fun cntOf(scheduleId: Long, userId: Long): Int? = jdbcTemplate.queryForList(
        "SELECT cnt FROM user_hold_quota WHERE schedule_id = ? AND user_id = ?", Int::class.java, scheduleId, userId,
    ).singleOrNull()

    /** 백필은 홀드·예약을 바꾸지 않는다(명세 §2) — 전후 비교용 전체 스냅샷 */
    private fun holdsAndReservations(): Pair<List<Map<String, Any?>>, List<Map<String, Any?>>> =
        jdbcTemplate.queryForList("SELECT * FROM seat_hold ORDER BY id") to
            jdbcTemplate.queryForList("SELECT * FROM reservation ORDER BY id")

    /**
     * 섞인 데이터: 회차 2개 · 사용자 여럿 · 유효 홀드 · 확정 예약 · 만료 지났지만 배치가 안 지운 홀드 · 취소 예약.
     * 반환 = (회차, 사용자) → 기대 cnt(seat_hold 행 전체 + CONFIRMED). 실제 0인 쌍은 넣지 않는다.
     */
    private fun seedMixed(): Map<Pair<Long, Long>, Int> {
        val a = schedule.id!!
        val b = newSchedule().id!!
        val past = Instant.parse("2020-01-01T00:00:00Z") // DB now()·테스트 시계 어느 쪽으로 봐도 만료
        hold(a, 1); reservation(a, 1, "CONFIRMED")                          // A·1 = 2
        hold(a, 2, expiresAt = past); reservation(a, 2, "CANCELED")         // A·2 = 1 (만료 미정리 홀드도 센다, 취소는 안 센다)
        reservation(a, 3, "CANCELED")                                       // A·3 = 0
        reservation(a, 4, "CONFIRMED"); reservation(a, 4, "CONFIRMED")      // A·4 = 2
        hold(b, 1); hold(b, 1, expiresAt = past)                            // B·1 = 2
        reservation(b, 5, "CONFIRMED")                                      // B·5 = 1
        hold(b, 2)                                                          // B·2 = 1
        return mapOf(
            (a to 1L) to 2, (a to 2L) to 1, (a to 4L) to 2,
            (b to 1L) to 2, (b to 5L) to 1, (b to 2L) to 1,
        )
    }

    /** 실제 > 0인 쌍은 정확히 그 값의 행이 있어야 한다. 실제 0인 쌍은 행이 없거나 cnt = 0(명세 §2 둘 다 허용). */
    private fun assertQuotaEquals(expected: Map<Pair<Long, Long>, Int>) {
        val positive = quotaRows().filter { it.third != 0 }.associate { (it.first to it.second) to it.third }
        assertEquals(expected, positive)
        assertEquals(0L, counterMismatch(jdbcTemplate), "v_counter_mismatch")
    }

    @Test
    fun `① 빈 DB(홀드·예약·쿼터 없음) — V6 뒤에도 쿼터 행 0`() {
        seat(schedule.id!!, SeatStatus.AVAILABLE) // 좌석만 있고 홀드·예약은 없다
        runV6()
        assertEquals(0, jdbcTemplate.queryForObject("SELECT count(*) FROM user_hold_quota", Int::class.java))
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }

    @Test
    fun `② 섞인 데이터 — 만료 미정리 홀드 포함·취소 제외, 회차·사용자별로 v_counter_mismatch 0, 홀드·예약은 그대로`() {
        val expected = seedMixed()
        val before = holdsAndReservations()
        runV6()
        assertQuotaEquals(expected)
        assertEquals(before, holdsAndReservations(), "백필이 홀드·예약을 바꿨다")
    }

    @Test
    fun `③ 어긋난 기존 쿼터 행 — 너무 높은 값·너무 낮은 값 모두 실제 값으로 덮는다`() {
        val expected = seedMixed()
        val a = schedule.id!!
        quota(a, 1, 0)  // 실제 2 — 너무 낮음
        quota(a, 2, 2)  // 실제 1 — 너무 높음
        quota(a, 4, 1)  // 실제 2 — 너무 낮음
        runV6()
        assertQuotaEquals(expected)
        assertEquals(2, cntOf(a, 1))
        assertEquals(1, cntOf(a, 2))
        assertEquals(2, cntOf(a, 4))
    }

    @Test
    fun `③-0 실제 0인 쌍의 기존 쿼터 행 — v_counter_mismatch 0이면 된다`() {
        val expected = seedMixed()
        val a = schedule.id!!
        quota(a, 3, 1)   // 취소 예약만 있음 — 실제 0
        quota(a, 99, 2)  // 홀드·예약이 아예 없음 — 실제 0
        runV6()
        // 설계가 둘 중 하나를 고른다: 행을 cnt = 0으로 덮거나, 행을 지우거나. 명세 §2는 둘 다 허용하므로 판정기 정의(행 없음 = 0)만 단언한다.
        assertEquals(0L, counterMismatch(jdbcTemplate))
        assertTrue(cntOf(a, 3).let { it == null || it == 0 }, "A·3: ${cntOf(a, 3)}")
        assertTrue(cntOf(a, 99).let { it == null || it == 0 }, "A·99: ${cntOf(a, 99)}")
        assertQuotaEquals(expected)
    }

    @Test
    fun `④ 상한 초과 사용자(none 시절 3매) — cnt 3 유지·홀드 안 지움, 기본값에서 다음 요청은 거절(팔린 좌석은 좌석 불가가 먼저)`() {
        val a = schedule.id!!
        val user = 501L
        repeat(3) { hold(a, user) }
        quota(a, user, 1) // 어긋난 기존 행
        runV6()
        assertEquals(3, cntOf(a, user), "실제 값 그대로(상한 2로 자르지 않는다)")
        assertEquals(0L, counterMismatch(jdbcTemplate))

        val free = createSeat(900)
        val e = assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(a, free.id!!, user)) }
        assertEquals(ErrorCode.HOLD_LIMIT_EXCEEDED, e.errorCode)
        val sold = createSeat(901, SeatStatus.HELD).also {
            jdbcTemplate.update(
                "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?)",
                it.id, a, 502L, Timestamp.from(NOW), Timestamp.from(NOW.plusSeconds(300)),
            )
        }
        quota(a, 502L, 1)
        val e2 = assertThrows<SeatReservationException> { holdSeat.hold(HoldSeatCommand(a, sold.id!!, user)) }
        assertEquals(ErrorCode.SEAT_NOT_AVAILABLE, e2.errorCode, "명세 순서: 좌석 불가가 매수 초과보다 먼저")

        assertEquals(3, cntOf(a, user), "거절된 요청이 카운터를 바꿨다")
        assertEquals(3, jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE user_id = ?", Int::class.java, user))
        assertEquals(SeatStatus.AVAILABLE, seatStatus(free))
        assertEquals(0L, counterMismatch(jdbcTemplate))
    }

    @Test
    fun `⑤ 멱등 — 두 번 적용해도 쿼터·홀드·예약 테이블 내용이 같다`() {
        val expected = seedMixed()
        quota(schedule.id!!, 1, 7)
        runV6()
        val first = quotaRows() to holdsAndReservations()
        runV6()
        assertEquals(first, quotaRows() to holdsAndReservations())
        assertQuotaEquals(expected)
    }

    companion object {
        const val V6_RESOURCE = "db/migration/V6__backfill_user_hold_quota.sql"
    }
}
