package com.jun.labs.seatreservation.support

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.domain.Product
import com.jun.labs.seatreservation.domain.ProductSchedule
import com.jun.labs.seatreservation.domain.ProductSeat
import com.jun.labs.seatreservation.domain.SeatStatus
import com.jun.labs.seatreservation.domain.repository.ProductRepository
import com.jun.labs.seatreservation.domain.repository.ProductScheduleRepository
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.domain.repository.ReservationRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.Timestamp
import java.time.Instant

/**
 * Testcontainers PostgreSQL + 고정 시각. 만료 스케줄러는 끄고 테스트가 UseCase를 직접 호출한다.
 * 모든 테스트가 같은 설정을 쓰므로 Spring 컨텍스트와 컨테이너는 한 번만 뜬다.
 */
@SpringBootTest(properties = ["seat.hold.expiry-scheduler-enabled=false"])
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class)
abstract class IntegrationTest {

    @Autowired lateinit var clock: MutableClock
    @Autowired lateinit var productRepository: ProductRepository
    @Autowired lateinit var productScheduleRepository: ProductScheduleRepository
    @Autowired lateinit var productSeatRepository: ProductSeatRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var reservationRepository: ReservationRepository

    lateinit var schedule: ProductSchedule

    @BeforeEach
    fun resetData() {
        reservationRepository.deleteAllInBatch()
        jdbcTemplate.update("DELETE FROM seat_hold")
        productSeatRepository.deleteAllInBatch()
        productScheduleRepository.deleteAllInBatch()
        productRepository.deleteAllInBatch()
        clock.set(NOW)

        val product = productRepository.save(Product(name = "테스트 공연"))
        schedule = productScheduleRepository.save(
            ProductSchedule(productId = product.id!!, startsAt = NOW.plusSeconds(86_400)),
        )
    }

    fun createSeat(seatNo: Int, status: SeatStatus = SeatStatus.AVAILABLE): ProductSeat =
        productSeatRepository.save(
            ProductSeat(scheduleId = schedule.id!!, section = "A", rowNo = 1, seatNo = seatNo, status = status),
        )

    fun seatStatus(seat: ProductSeat): SeatStatus = productSeatRepository.findById(seat.id!!).orElseThrow().status

    // 홀드는 DB 행으로 직접 확인한다 — 도메인 내부 구조(홀드를 누가 소유하는가)와 무관한 특성 단언.
    fun holdCount(): Int = jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold", Int::class.java)!!

    fun holdExists(holdId: Long): Boolean =
        jdbcTemplate.queryForObject("SELECT count(*) FROM seat_hold WHERE id = ?", Int::class.java, holdId)!! > 0

    fun holdRow(holdId: Long): HoldRow = jdbcTemplate.queryForObject(
        "SELECT seat_id, user_id, held_at, expires_at FROM seat_hold WHERE id = ?",
        { rs, _ ->
            HoldRow(
                seatId = rs.getLong("seat_id"),
                userId = rs.getLong("user_id"),
                heldAt = rs.getTimestamp("held_at").toInstant(),
                expiresAt = rs.getTimestamp("expires_at").toInstant(),
            )
        },
        holdId,
    )!!

    fun insertHold(seat: ProductSeat, userId: Long, heldAt: Instant, expiresAt: Instant): Long =
        jdbcTemplate.queryForObject(
            "INSERT INTO seat_hold (seat_id, schedule_id, user_id, held_at, expires_at) VALUES (?, ?, ?, ?, ?) RETURNING id",
            Long::class.java,
            seat.id, schedule.id, userId, Timestamp.from(heldAt), Timestamp.from(expiresAt),
        )!!

    data class HoldRow(val seatId: Long, val userId: Long, val heldAt: Instant, val expiresAt: Instant)

    @TestConfiguration(proxyBeanMethods = false)
    class ClockConfig {
        @Bean
        @Primary
        fun mutableClock(): MutableClock = MutableClock(NOW)
    }

    companion object {
        val NOW: Instant = Instant.parse("2026-09-27T10:00:00Z")
    }
}
