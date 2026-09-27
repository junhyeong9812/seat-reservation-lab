package com.jun.labs.seatreservation.service

interface ExpireHoldsUseCase {
    /** 만료된 홀드를 정리하고 정리한 건수를 돌려준다. */
    fun expire(): Int
}
