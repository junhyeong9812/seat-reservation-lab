package com.jun.labs.seatreservation.adr016

import com.jun.labs.seatreservation.TestcontainersConfiguration
import com.jun.labs.seatreservation.adr015.Adr015Base
import com.jun.labs.seatreservation.adr015.Adr015TapConfig
import com.jun.labs.seatreservation.adr015.M_CONDITIONAL
import com.jun.labs.seatreservation.adr015.P_A_ON
import com.jun.labs.seatreservation.adr015.P_LOC_BASE
import com.jun.labs.seatreservation.adr015.P_SCHED_OFF
import com.jun.labs.seatreservation.support.IntegrationTest
import com.jun.labs.seatreservation.support.PIN_CLEANUP_OFF
import com.jun.labs.seatreservation.support.PIN_C_OFF
import com.jun.labs.seatreservation.support.PIN_INC_OFF
import com.jun.labs.seatreservation.support.PIN_METHOD_NONE
import com.jun.labs.seatreservation.support.PIN_SAFETY_OFF
import com.jun.labs.seatreservation.support.PIN_SHAPE_ONE_SHOT
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment.RANDOM_PORT
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessException
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals

/**
 * ADR-016 2단계 명세 §8 가정 2(N16-1 장치): 앱의 트랜잭션 관리자 아래 읽기 전용 트랜잭션에서 JdbcTemplate 쓰기는 PostgreSQL 오류가 된다
 * (① payment-start [com.jun.labs.seatreservation.service.impl.PaymentStartService.check]의 `@Transactional(readOnly = true)`가 실제 방어선인지).
 * 대조: 같은 쓰기를 읽기 전용이 아닌 트랜잭션에서는 받는다.
 */
@SpringBootTest(
    webEnvironment = RANDOM_PORT,
    properties = [P_SCHED_OFF, P_A_ON, PIN_C_OFF, PIN_CLEANUP_OFF, PIN_INC_OFF, PIN_METHOD_NONE, PIN_SAFETY_OFF, PIN_SHAPE_ONE_SHOT, P_LOC_BASE, M_CONDITIONAL],
)
@Import(TestcontainersConfiguration::class, IntegrationTest.ClockConfig::class, Adr015TapConfig::class)
class Adr016ReadOnlyProbeTest : Adr015Base() {

    @Autowired lateinit var txManager: PlatformTransactionManager

    private fun write(hold: Long) = jdbcTemplate.update("UPDATE seat_hold SET expires_at = expires_at + interval '1 second' WHERE id = ?", hold)

    @Test
    fun `읽기 전용 트랜잭션의 쓰기는 오류 · 대조 쓰기는 받는다`() {
        val hold = holdOk(createSeat(1).id!!, x)
        val readOnly = TransactionTemplate(txManager).apply { isReadOnly = true }
        assertThrows<DataAccessException> { readOnly.executeWithoutResult { write(hold) } }
        assertEquals(1, TransactionTemplate(txManager).execute { write(hold) })
    }
}
