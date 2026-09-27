package com.jun.labs.seatreservation.domain

class SeatReservationException(val errorCode: ErrorCode) : RuntimeException(errorCode.message)
