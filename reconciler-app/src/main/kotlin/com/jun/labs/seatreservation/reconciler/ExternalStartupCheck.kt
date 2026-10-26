package com.jun.labs.seatreservation.reconciler

import com.jun.labs.seatreservation.service.impl.drift.IncrementalMethod
import com.jun.labs.seatreservation.service.impl.drift.IncrementalSchemaCheck
import com.jun.labs.seatreservation.service.impl.drift.ReconcileProperties
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

/**
 * 외부 앱 기동 검사(ADR-012 §4.2.2 조합표 M3 행 · §4.2.8 · §4.2.13 #14·#18) — 어기면 [IllegalStateException](막은 손잡이 키를 메시지에)으로 뜨지 않는다.
 * ① 전체 대조 C(`reconcile.enabled=true`)는 거부 — 전체 바퀴는 업무 앱만(다른 프로세스로 옮긴 것은 F가 이미 잰다)
 * ② 스키마는 Flyway 없이 업무 앱과 같은 core [IncrementalSchemaCheck]로(V9 종류 · 트리거 유무 · V10 · 준비된 트랜잭션) — 메모리 집합(ⓐ)도 여기서 거부된다
 * ③ 방식은 ⓑ trigger · ⓒ stmt만(none은 할 일이 없다) ④ 증분은 안전망과 함께(rv43 M3 — 보장 밖 쌍을 고칠 전체 훑기가 외부 앱에는 안전망뿐이다).
 * 두 앱의 방식이 같은지는 이 프로세스가 알 수 없다 — 하네스 incremental-config-mismatch가 두 앱 configprops를 비교한다(rv41 Opus M3).
 */
@Component
class ExternalStartupCheck(
    private val jdbc: JdbcTemplate,
    private val reconcile: ReconcileProperties,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        check(!reconcile.enabled) {
            "$RECONCILE_KEY=true — 외부 대조 앱은 전체 대조(C)를 하지 않는다(전체 바퀴 C는 업무 앱만 · ADR-012 §4.2.1). false로 두라"
        }
        IncrementalSchemaCheck.check(jdbc, reconcile, externalApp = true)
        val inc = reconcile.incremental
        val net = reconcile.safetyNet
        val prefix = IncrementalSchemaCheck.PREFIX
        check(inc.method == IncrementalMethod.TRIGGER || inc.method == IncrementalMethod.STMT) {
            "$prefix.method=${inc.method.name.lowercase()} — 외부 대조 앱은 trigger·stmt만 쓴다(memory는 다른 프로세스가 볼 수 없고 none은 할 일이 없다). 업무 앱과 같은 값을 넣어라(§4.2.8)"
        }
        check(inc.enabled || net.enabled) {
            "$prefix.enabled=false · ${IncrementalSchemaCheck.SAFETY_PREFIX}.enabled=false — 외부 대조 앱이 할 일이 없다(증분 또는 안전망을 켜라)"
        }
        check(!inc.enabled || net.enabled) {
            "$prefix.enabled=true인데 ${IncrementalSchemaCheck.SAFETY_PREFIX}.enabled=false — 증분은 조건부 보장이라 보장 밖 쌍을 고칠 안전망이 함께 돌아야 한다(rv43 M3 · 외부 앱에는 전체 대조 C가 없다)"
        }
    }

    companion object {
        const val RECONCILE_KEY = "seat.hold.counter-drift.reconcile.enabled"
    }
}
