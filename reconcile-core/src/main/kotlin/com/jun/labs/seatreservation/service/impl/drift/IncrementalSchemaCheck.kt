package com.jun.labs.seatreservation.service.impl.drift

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 증분·안전망 방식과 스키마가 맞는지 기동 때 본다(ADR-012 §4.2.2 기동 검사 · Flyway 위치 조합표). 어기면 [IllegalStateException] —
 * 메시지에 막은 손잡이 키·객체 이름을 넣는다(§4.2.13 #18). 업무 앱과 외부 앱의 기동 검사가 함께 부른다(외부 앱은 Flyway를 끄고 같은 스키마를 확인한다).
 * 매수 방식과의 조합(stmt + counter-trigger 등)·A 필수는 매수 방식을 아는 업무 앱 검사가 따로 본다.
 */
object IncrementalSchemaCheck {
    private val log = LoggerFactory.getLogger(IncrementalSchemaCheck::class.java)

    const val PREFIX = "seat.hold.counter-drift.reconcile.incremental"
    const val SAFETY_PREFIX = "seat.hold.counter-drift.reconcile.safety-net"
    const val TOUCH_TRIGGER = "user_hold_quota_touch"
    const val UPDATED_AT_INDEX = "idx_user_hold_quota_updated_at"
    /** V10(선택 위치 `db/migration-incremental`) 테이블 3개 */
    val V10_TABLES = listOf("counter_safety_run", "counter_app_inject", "counter_inc_gap")

    /** @param externalApp 외부 대조 앱이면 true — 메모리 집합(ⓐ)은 다른 프로세스가 볼 수 없어 거부한다 */
    fun check(jdbc: JdbcTemplate, reconcile: ReconcileProperties, externalApp: Boolean) {
        val inc = reconcile.incremental
        val method = inc.method
        val m = method.name.lowercase()
        if (method == IncrementalMethod.MEMORY) {
            check(inc.enabled) {
                "$PREFIX.method=memory인데 $PREFIX.enabled=false — 표시만 하는 메모리 집합은 뜻이 없다(다른 프로세스가 볼 수 없다). enabled=true로 켜거나 방식을 바꿔라"
            }
            check(!externalApp) { "$PREFIX.method=memory는 외부 대조 앱에서 쓸 수 없다 — 메모리 집합은 업무 앱 프로세스 안에서만 성립한다(trigger·stmt만)" }
        }
        val column = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'user_hold_quota' AND column_name = 'updated_at'",
            Int::class.java,
        )!! > 0
        val index = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?", Int::class.java, UPDATED_AT_INDEX,
        )!! > 0
        val trigger = jdbc.queryForObject(
            "SELECT count(*) FROM pg_trigger WHERE tgname = ? AND NOT tgisinternal", Int::class.java, TOUCH_TRIGGER,
        )!! > 0
        val updatedAtMethod = method == IncrementalMethod.TRIGGER || method == IncrementalMethod.STMT
        if (updatedAtMethod) {
            check(column && index) {
                "$PREFIX.method=$m 는 user_hold_quota.updated_at 열과 인덱스($UPDATED_AT_INDEX)가 있어야 한다(열=$column · 인덱스=$index) — " +
                    "spring.flyway.locations에 classpath:db/migration-updated-at${if (method == IncrementalMethod.TRIGGER) "-trigger" else ""}를 더하라"
            }
        }
        if (method == IncrementalMethod.TRIGGER) {
            check(trigger) {
                "$PREFIX.method=trigger인데 트리거 $TOUCH_TRIGGER 가 없다 — spring.flyway.locations에 classpath:db/migration-updated-at-trigger를 더하라(문장 위치가 아니라)"
            }
        } else {
            check(!trigger) {
                "$PREFIX.method=$m 인데 트리거 $TOUCH_TRIGGER(V9 트리거 위치)가 있다 — 트리거 비용이 다른 셀에 샌다. " +
                    "classpath:db/migration-updated-at-trigger는 method=trigger에서만 더하라"
            }
        }
        val safety = reconcile.safetyNet.enabled
        if (inc.enabled || safety) {
            val missing = V10_TABLES.filterNot { t -> jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, t) == true }
            check(missing.isEmpty()) {
                "증분($PREFIX.enabled=${inc.enabled}) 또는 안전망($SAFETY_PREFIX.enabled=$safety)을 켰는데 V10 기록 테이블이 없다: $missing — " +
                    "spring.flyway.locations에 classpath:db/migration-incremental을 더하라"
            }
        }
        if (updatedAtMethod && inc.enabled) {
            // 준비된 트랜잭션은 세션이 끝난 뒤에도 남아 pg_stat_activity의 xact_start로 보이지 않는다 — 창 하한 근거(§4.2.3)가 깨진다(rv41 Opus M4 · codex H1)
            val prepared = jdbc.queryForObject("SHOW max_prepared_transactions", String::class.java)!!.trim().toInt()
            check(prepared == 0) {
                "$PREFIX.method=$m 증분 틱(enabled=true)은 max_prepared_transactions = 0인 DB에서만 돈다(지금 $prepared) — 준비된 트랜잭션은 창 하한 검사에서 보이지 않는다"
            }
            val privileged = jdbc.queryForObject(
                "SELECT rolsuper OR pg_has_role(current_user, 'pg_read_all_stats', 'MEMBER') FROM pg_roles WHERE rolname = current_user",
                Boolean::class.java,
            ) == true
            if (!privileged) {
                log.warn(
                    "증분 기동 — 접속 역할이 superuser도 pg_read_all_stats도 아니다: 다른 역할 세션의 xact_start가 NULL로 보일 수 있음 — 숨은 세션 검사로 보수 하한(틱마다 t − max-overlap)",
                )
            }
        }
    }
}
