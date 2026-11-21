package com.jun.labs.seatreservation.api

import com.jun.labs.seatreservation.service.impl.holdgroup.HoldGroupStartupCheck
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/**
 * 묶음 API 등록 조건 = `seat.hold-group.enabled` 바인딩 값(명세 D8) — [com.jun.labs.seatreservation.service.HoldGroupProperties.enabled]와 같은 변환 규칙
 * (Boolean 바인딩 — true · on · yes · 1 등)과 같은 기본값(키 없으면 true). `@ConditionalOnProperty(havingValue = "true")`는 문자열로 비교해 on · yes를 놓친다.
 */
class HoldGroupEnabledCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean =
        Binder.get(context.environment).bind(HoldGroupStartupCheck.ENABLED_KEY, Boolean::class.javaObjectType).orElse(true)
}
