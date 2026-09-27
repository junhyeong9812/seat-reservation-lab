package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.domain.ErrorCode
import com.jun.labs.seatreservation.domain.SeatReservationException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

const val USER_ID_HEADER = "X-User-Id"

@RestControllerAdvice
class ApiExceptionHandler {

    @ExceptionHandler(SeatReservationException::class)
    fun handle(e: SeatReservationException): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(e.errorCode.httpStatus())
            .body(ErrorResponse(code = e.errorCode.name, message = e.errorCode.message))

    private fun ErrorCode.httpStatus(): HttpStatus = when (this) {
        ErrorCode.SEAT_NOT_FOUND, ErrorCode.HOLD_NOT_FOUND -> HttpStatus.NOT_FOUND
        ErrorCode.HOLD_NOT_OWNED -> HttpStatus.FORBIDDEN
        ErrorCode.SEAT_NOT_AVAILABLE,
        ErrorCode.HOLD_LIMIT_EXCEEDED,
        ErrorCode.HOLD_EXPIRED,
        ErrorCode.SEAT_NOT_HELD,
        -> HttpStatus.CONFLICT
    }
}

data class ErrorResponse(
    val code: String,
    val message: String,
)
