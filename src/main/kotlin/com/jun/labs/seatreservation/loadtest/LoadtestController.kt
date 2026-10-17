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
// F(reconciler 프로필 — 대조 전용 프로세스)에서는 부하 시드·판정 엔드포인트를 열지 않는다(코드 루프 1 L1-5)
@Profile("loadtest & !reconciler")
@RequestMapping("/internal")
class LoadtestController(
    private val loadtestDataService: LoadtestDataService,
    private val properties: SeatHoldProperties,
) {

    @PostMapping("/reset")
    fun reset(
        @RequestParam(defaultValue = "1") schedules: Int,
        @RequestParam(defaultValue = "10000") seatsPerSchedule: Int,
        @RequestParam(defaultValue = "0") backgroundRows: Int,
    ): LoadtestDataService.ResetResult {
        val result = loadtestDataService.reset(schedules, seatsPerSchedule, backgroundRows)
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
