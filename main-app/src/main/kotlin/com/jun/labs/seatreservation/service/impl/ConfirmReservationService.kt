package com.jun.labs.seatreservation.service.impl

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.Reservation
import com.jun.labs.seatreservation.domain.ReservationStatus
import com.jun.labs.seatreservation.domain.SeatReservationException
import com.jun.labs.seatreservation.domain.repository.ProductSeatRepository
import com.jun.labs.seatreservation.domain.repository.ReservationRepository
import com.jun.labs.seatreservation.service.ConfirmReservationCommand
import com.jun.labs.seatreservation.service.ConfirmReservationResult
import com.jun.labs.seatreservation.service.ConfirmMode
import com.jun.labs.seatreservation.service.ConfirmReservationUseCase
import com.jun.labs.seatreservation.service.ExpiryMode
import com.jun.labs.seatreservation.service.SeatConfirmProperties
import com.jun.labs.seatreservation.service.SeatHoldProperties
import com.jun.labs.seatreservation.service.impl.drift.MemoryChangedPairs
import com.jun.labs.seatreservation.service.impl.expiry.LazyConfirmProcess
import com.jun.labs.seatreservation.service.impl.hold.HoldStrategyStartupCheck
import com.jun.labs.seatreservation.service.impl.limit.ActiveUserLimit
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.postgresql.util.PSQLException
import java.time.Clock
import java.time.Duration

/**
 * ADR-000 기준선: 좌석 애그리거트의 확정(2중 확인 포함) + 예약 생성을 같은 트랜잭션에서 한다
 * — 애그리거트 2개를 한 트랜잭션에서 바꾸는 의도된 예외(ADR-000 §2.1).
 * 확인과 전환 사이에 상태가 바뀔 수 있다 — ADR-015(확정 원자성)에서 관측(번호 이동 전 ADR-005).
 * ADR-014: 만료 방식이 sweep(A)이 아니면 B·C·D 확정([LazyConfirmProcess] — 좌석 잠금 뒤 DB now()로 판정)으로 간다. A 확정은 바꾸지 않는다(Q3).
 * ADR-015(§설계-3 공통 규칙 · §설계-4): A 확정 `current`(손잡이 기본값)의 SQL · 판정 순서는 그대로 두고 두 가지만 더한다 —
 * ① 예약 INSERT가 V12 인덱스에 걸리면(23505 · 서버 오류의 제약 이름 일치) 409 SEAT_NOT_HELD + ERROR 로그 + 지표 `seat.confirm.unique_violation`(다른 무결성 오류는 그대로)
 * ② 관측 지표 `seat.confirm.converted_after_expiry{clock=app}` — 커밋 뒤 앱 Clock이 그 홀드의 expires_at 뒤인 성공(판정은 바꾸지 않는다 · SQL 없음).
 * 새 모드(①~④ — `seat.confirm.mode ≠ current`)는 [AtomicConfirmProcess](JDBC 문장 · 영향 행 수로 판정 — §설계-3.1~3.4)로 간다. current 경로는 손대지 않는다.
 */
@Service
class ConfirmReservationService(
    private val productSeatRepository: ProductSeatRepository,
    private val reservationRepository: ReservationRepository,
    private val clock: Clock,
    private val userLimit: ActiveUserLimit,
    private val changes: MemoryChangedPairs,
    private val properties: SeatHoldProperties,
    private val lazyConfirm: LazyConfirmProcess,
    private val confirmProperties: SeatConfirmProperties,
    private val meters: MeterRegistry,
    private val atomicConfirm: AtomicConfirmProcess,
) : ConfirmReservationUseCase {

    @Transactional
    override fun confirm(command: ConfirmReservationCommand): ConfirmReservationResult {
        if (properties.expiryMode != ExpiryMode.SWEEP) return lazyConfirm.confirm(command)
        // ADR-015 새 모드(①~④) — 같은 트랜잭션에서 JDBC 문장으로(기동 검사 규칙 2가 기본 조합을 보장하므로 beforeConfirm은 부르지 않는다 — §설계-3 공통 규칙)
        if (confirmProperties.mode != ConfirmMode.CURRENT) return atomicConfirm.confirm(confirmProperties.mode, command)
        // ADR-009 L2-4: counter-trigger만 트리거 기준 시각(앱 Clock)을 이 트랜잭션에 넣는다 — 다른 매수 방식은 아무 문장도 보내지 않는다
        userLimit.strategy.beforeConfirm()
        val now = clock.instant()
        val seat = productSeatRepository.findByHoldsId(command.holdId)
            ?: throw SeatReservationException(ErrorCode.HOLD_NOT_FOUND)
        // ADR-015 관측: 확정 앞에 찾은 홀드의 expires_at을 들고 있다가 커밋 뒤 비교한다(seat.confirm이 같은 컬렉션을 곧바로 읽는다 — 문장 · 순서 무변경)
        val expiresAt = seat.holdExpiresAt(command.holdId)

        seat.confirm(command.holdId, command.userId, now)
        // ADR-015 §설계-8.2 측정 지연 주입(current 자리 = ProductSeat.confirm 뒤 · save 앞 — loadtest 프로필 전용 · 0이면 아무것도 안 함)
        loadtestDelay(confirmProperties)

        val reservation = try {
            reservationRepository.save(
                Reservation(
                    scheduleId = seat.scheduleId,
                    seatId = seat.id!!,
                    userId = command.userId,
                    paymentUid = command.paymentUid,
                    status = ReservationStatus.CONFIRMED,
                    createdAt = now,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // IDENTITY라 save 자리에서 INSERT가 나간다
            throw uniqueViolationOrRethrow(meters, e, command, seat.id)
        }
        if (expiresAt != null) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = convertedAfterExpiry(meters, "app", Duration.between(expiresAt, clock.instant()))
            })
        }
        // ADR-012 ⓐ: 확정은 실제 값 쪽만 바꾼다(쿼터 행 그대로) — 앱 경로라 그 쌍을 등록한다(§2.3 '앱 경로면 봄'). method ≠ memory면 아무것도 안 함
        changes.changed(seat.scheduleId, command.userId)
        return ConfirmReservationResult(
            reservationId = reservation.id!!,
            seatId = seat.id!!,
            status = reservation.status,
        )
    }

    companion object {
        private val log = LoggerFactory.getLogger(ConfirmReservationService::class.java)
        private const val UNIQUE_VIOLATION = "23505"
        /** ADR-015 §설계-4 — 최후 방어선 위반(기대 0 · 태그 없음 — T4) */
        const val UNIQUE_VIOLATION_METRIC = "seat.confirm.unique_violation"
        /** ADR-015 §설계-3 — 만료 뒤 전환된 성공(관측만 · 기대값 없음) */
        const val CONVERTED_AFTER_EXPIRY_METRIC = "seat.confirm.converted_after_expiry"
        /** 같은 성공의 지연량(전환 시각 − expires_at) 분포 — 건수 · 최대(§설계-8.2 '건수 · 최대' · R15-9). 태그 clock = db | app */
        const val CONVERTED_AFTER_EXPIRY_LATENESS_METRIC = "seat.confirm.converted_after_expiry.lateness"

        /**
         * 커밋 뒤([TransactionSynchronization.afterCommit])에서만 부른다 — 롤백된 확정은 세지 않는다(R15-3). [lateness] = 전환 시각 − expires_at.
         * 0 이하(만료 전 전환)는 아무것도 기록하지 않는다. 그 밖이면 카운터 +1 · 지연량 Timer에 기록.
         */
        internal fun convertedAfterExpiry(meters: MeterRegistry, clockTag: String, lateness: Duration) {
            if (lateness <= Duration.ZERO) return
            meters.counter(CONVERTED_AFTER_EXPIRY_METRIC, "clock", clockTag).increment()
            meters.timer(CONVERTED_AFTER_EXPIRY_LATENESS_METRIC, "clock", clockTag).record(lateness)
        }

        /** ADR-015 §설계-8.2 확정 지연 주입(`seat.confirm.loadtest.delay` — loadtest 프로필 전용 · 규칙 3). 0(기본)이면 아무 호출도 하지 않는다(current SQL · 순서 무변경) */
        internal fun loadtestDelay(confirm: SeatConfirmProperties) {
            val delay = confirm.loadtest.delay
            if (!delay.isZero) Thread.sleep(delay)
        }

        /**
         * A 확정 공용(current · 새 모드 넷 — §설계-4 N6): 예약 INSERT의 V12(같은 좌석 CONFIRMED ≤ 1) 위반(23505 · 인덱스 이름 일치)만 409 SEAT_NOT_HELD + ERROR 로그 + 지표로 바꾼다.
         * 그 밖의 무결성 오류는 [e] 그대로 돌려준다(무음 변환 금지). 호출자가 돌려받은 예외를 던진다.
         */
        internal fun uniqueViolationOrRethrow(meters: MeterRegistry, e: DataIntegrityViolationException, command: ConfirmReservationCommand, seatId: Long?): RuntimeException {
            // R15-8: 제약 이름은 서버 오류 필드(constraint)로 본다 — 메시지 부분 일치는 다른 이름 · 번역된 문구에 걸릴 수 있다
            val cause = e.mostSpecificCause
            if (!(cause is PSQLException && cause.sqlState == UNIQUE_VIOLATION && cause.serverErrorMessage?.constraint == HoldStrategyStartupCheck.CONFIRMED_SEAT_INDEX)) return e
            meters.counter(UNIQUE_VIOLATION_METRIC).increment()
            log.error(
                "확정 예약 INSERT가 {}(V12)에 걸렸다 — 같은 좌석의 CONFIRMED가 이미 있다(앱 가드가 뚫림 · 409 SEAT_NOT_HELD로 거부) hold={} seat={} user={}",
                HoldStrategyStartupCheck.CONFIRMED_SEAT_INDEX, command.holdId, seatId, command.userId, e,
            )
            return SeatReservationException(ErrorCode.SEAT_NOT_HELD)
        }
    }
}
