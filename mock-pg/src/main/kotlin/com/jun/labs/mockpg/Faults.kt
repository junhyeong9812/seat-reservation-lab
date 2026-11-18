package com.jun.labs.mockpg

import org.springframework.stereotype.Component
import java.util.Random
import java.util.concurrent.ConcurrentHashMap

/**
 * ADR-017 설계-3 주입(호출 종류별 · 시드 재현) — 처리 앞에서 정한다:
 * - fail: 처리하지 않고 503(원장에 남지 않는다 · 재시도가 다시 처리한다 — RD17-13)
 * - unknown: 처리하고 [Rule.unknownDelayMs]만큼 늦게 응답(호출자 타임아웃 → 결과 모름)
 * - delayMs: 처리 앞 지연(고정)
 * 종류별 난수열은 (seed, 종류)로 고정된다 — 같은 순서의 호출이면 같은 결과(동시 호출의 순서는 재현 범위 밖).
 */
@Component
class Faults {
    data class Rule(val delayMs: Long = 0, val failRate: Double = 0.0, val unknownRate: Double = 0.0, val unknownDelayMs: Long = 5_000)
    data class Config(val seed: Long = 0, val rules: Map<String, Rule> = emptyMap())

    enum class Decision { PROCEED, FAIL, UNKNOWN }

    @Volatile private var config = Config()
    private val randoms = ConcurrentHashMap<String, Random>()

    fun set(c: Config) {
        config = c
        randoms.clear()
    }

    fun get(): Config = config

    fun rule(op: String): Rule = config.rules[op] ?: Rule()

    fun decide(op: String): Decision {
        val r = rule(op)
        if (r.failRate <= 0.0 && r.unknownRate <= 0.0) return Decision.PROCEED
        val x = synchronized(this) { randoms.computeIfAbsent(op) { Random(config.seed * 31 + it.hashCode()) }.nextDouble() }
        return when {
            x < r.failRate -> Decision.FAIL
            x < r.failRate + r.unknownRate -> Decision.UNKNOWN
            else -> Decision.PROCEED
        }
    }
}
