package com.jun.labs.seatreservation.adr018default

import com.jun.labs.seatreservation.service.HoldGroupMode
import com.jun.labs.seatreservation.service.HoldGroupProperties
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.context.properties.source.ConfigurationPropertySources
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * ADR-018 기본값 교체 계약(blind — 명세만 보고 씀) — 묶음 홀드 키(seat.hold-group.enabled)를 주지 않으면 켜진 채(split) 뜬다.
 * 명세: docs/plans/2026-11-21/adr-018-default-switch/requirement-spec.md(§2 D1 · §5 1 · 2).
 *   D1 코드 기본값(HoldGroupProperties) = application.yml 값 = true · mode 기본 = SPLIT — 한쪽만 바뀌면 띄우는 방식에 따라 기본이 갈린다.
 * Spring 컨텍스트 없이 본다(Adr016 · 017 DefaultModeContractTest와 같은 대조). 실제 기동 계약은 Adr018DefaultBootTest.
 */
class Adr018DefaultContractTest {

    @Test
    fun `1 코드 기본값 — enabled true · mode SPLIT`() {
        val p = HoldGroupProperties()
        assertTrue(p.enabled, "코드 기본값 enabled")
        assertEquals(HoldGroupMode.SPLIT, p.mode, "코드 기본값 mode")
    }

    @Test
    fun `2 application_yml 바인딩 — enabled true · mode split(코드 기본값과 같은 값)`() {
        val sources = YamlPropertySourceLoader().load("application.yml", ClassPathResource("application.yml"))
        val bound = Binder(ConfigurationPropertySources.from(sources))
            .bind("seat.hold-group", HoldGroupProperties::class.java).get()
        assertTrue(bound.enabled, "application.yml enabled")
        assertEquals(HoldGroupMode.SPLIT, bound.mode, "application.yml mode")
        assertEquals(HoldGroupProperties(), bound, "코드 기본값 = application.yml")
    }
}
