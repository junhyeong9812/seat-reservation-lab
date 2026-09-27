package com.jun.labs.seatreservation.loadtest

import com.jun.labs.seatreservation.service.SeatHoldProperties
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** loadtest 프로필에서만 등록된다 — 기본 실행에서는 `/internal` 아래 경로가 존재하지 않는다. */
@RestController
@Profile("loadtest")
@RequestMapping("/internal")
class LoadtestController(
    private val loadtestDataService: LoadtestDataService,
    private val properties: SeatHoldProperties,
) {

    @PostMapping("/reset")
    fun reset(
        @RequestParam(defaultValue = "1") schedules: Int,
        @RequestParam(defaultValue = "10000") seatsPerSchedule: Int,
    ): LoadtestDataService.ResetResult {
        val result = loadtestDataService.reset(schedules, seatsPerSchedule)
        loadtestDataService.analyze()
        return result
    }

    @GetMapping("/counts")
    fun counts(@RequestParam(defaultValue = "false") seatStatus: Boolean): Map<String, Any?> =
        loadtestDataService.counts(seatStatus)

    @GetMapping("/consistency")
    fun consistency(@RequestParam graceSeconds: Long?): Map<String, Any?> =
        loadtestDataService.consistency(graceSeconds ?: (properties.expiryInterval.seconds * 2))
}
