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
 * ADR-009 기본값 전환(명세 §12 — 앱 기본값 A 켬 · C 켬 · 정리 ① schedule) 전의 기본값 = 후보·정리 끔. 기존 통합 테스트는 이 전제로 쓰였다
 * (기동 검사 조합 · 만료 배치 동작 · `properties.copy(...)`로 만든 검사 입력 · 컨텍스트에 상주하는 대조·정리 스케줄러가 테스트 데이터를 고치지 않음).
 * 그래서 [IntegrationTest]가 셋을 끔으로 고정하고(ADR-008 none 고정과 같은 방식), 켜는 테스트는 `@TestPropertySource`로 덮는다 — 그쪽이 우선한다
 * (Spring Boot 우선순위: @TestPropertySource > @SpringBootTest.properties > 프로필 yml. 확인: Adr009DefaultsTest.kt Adr009PinOverrideTest).
 * 기본값 자체는 Adr009DefaultsTest.kt(설정 키 없이 바인딩·기동)가 본다.
 */
const val PIN_A_OFF = "seat.hold.counter-drift.expiry-repair=false"
const val PIN_C_OFF = "seat.hold.counter-drift.reconcile.enabled=false"
const val PIN_CLEANUP_OFF = "seat.hold.counter-drift.cleanup.mode=none"

/**
 * Testcontainers PostgreSQL + 고정 시각. 만료 스케줄러는 끄고 테스트가 UseCase를 직접 호출한다.
 * 모든 테스트가 같은 설정을 쓰므로 Spring 컨텍스트와 컨테이너는 한 번만 뜬다.
 * ADR-009 후보·정리는 끔 고정(위 [PIN_A_OFF] 머리말) — 이 클래스의 `@SpringBootTest`를 다시 다는 하위 클래스는 세 키도 함께 다시 적어야 한다(애너테이션은 합쳐지지 않는다).
 */
@SpringBootTest(properties = ["seat.hold.expiry-scheduler-enabled=false", PIN_A_OFF, PIN_C_OFF, PIN_CLEANUP_OFF])
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
        // ADR-005 쿼터·카운터 — flyway.target으로 앞 버전 스키마를 띄우는 테스트에는 이 테이블이 없다
        if (jdbcTemplate.queryForObject("SELECT to_regclass('user_hold_quota') IS NOT NULL", Boolean::class.java) == true) {
            jdbcTemplate.update("DELETE FROM user_hold_quota")
        }
        productSeatRepository.deleteAllInBatch()
        productScheduleRepository.deleteAllInBatch()
        productRepository.deleteAllInBatch()
        clock.set(NOW)

        val product = productRepository.save(Product(name = "테스트 공연"))
        schedule = productScheduleRepository.save(
            ProductSchedule(productId = product.id!!, startsAt = SCHEDULE_STARTS_AT),
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

        /**
         * 기본 회차 시작 시각 — 고정 앱 시계(NOW)와 DB 벽시계 **둘 다**보다 먼 미래여야 한다(처음엔 NOW + 1일).
         * ADR-009 B 트리거(V8)는 앱을 거치지 않은 직접 DB 쓰기의 마감을 DB now()로 판정한다(코드 루프 2 L2-4) — NOW + 1일은 이미 지난 벽시계에서는
         * '마감'이라 직접 쓰기 테스트가 카운터에 반영되지 않는다. 마감 경계 테스트는 이 값을 기준으로 시계를 옮기므로(상대값) 의미는 같다.
         */
        val SCHEDULE_STARTS_AT: Instant = Instant.parse("2099-12-31T10:00:00Z")
    }
}
