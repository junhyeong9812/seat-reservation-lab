package com.jun.labs.seatreservation.service.impl.drift

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate

/**
 * 증분·안전망 방식과 스키마가 맞는지 기동 때 본다(ADR-012 §4.2.2 기동 검사 · Flyway 위치 조합표). 어기면 [IllegalStateException] —
 * 메시지에 막은 손잡이 키·객체 이름을 넣는다(§4.2.13 #18). 업무 앱과 외부 앱의 기동 검사가 함께 부른다(외부 앱은 Flyway를 끄고 같은 스키마를 확인한다).
 * 방식 ↔ 스키마 규칙은 [checkSchema] 한 곳 — 업무 앱은 마이그레이션 앞에서 Flyway 위치로 내다본 [Schema]로, 마이그레이션 뒤에는 DB에서 읽은 [Schema]로 같은 함수를 부른다.
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

    /**
     * 설정만으로 판정되는 쪽(DB를 보지 않는다) — 업무 앱은 Flyway 마이그레이션 앞에서도 부른다(거부된 기동이 스키마를 바꾸지 않게). [check]가 첫머리에서 다시 부른다.
     * @param externalApp 외부 대조 앱이면 true — 메모리 집합(ⓐ)은 다른 프로세스가 볼 수 없어 거부한다
     */
    fun checkConfig(reconcile: ReconcileProperties, externalApp: Boolean) {
        val inc = reconcile.incremental
        if (inc.method == IncrementalMethod.MEMORY) {
            check(inc.enabled) {
                "$PREFIX.method=memory인데 $PREFIX.enabled=false — 표시만 하는 메모리 집합은 뜻이 없다(다른 프로세스가 볼 수 없다). enabled=true로 켜거나 방식을 바꿔라"
            }
            check(!externalApp) { "$PREFIX.method=memory는 외부 대조 앱에서 쓸 수 없다 — 메모리 집합은 업무 앱 프로세스 안에서만 성립한다(trigger·stmt만)" }
        }
    }

    /** V9 트리거판(ⓑ) 위치 — Flyway location 경로(`classpath:` 뒤) */
    const val LOCATION_V9_TRIGGER = "db/migration-updated-at-trigger"
    /** V9 문장판(ⓒ — 열·인덱스만) 위치 */
    const val LOCATION_V9_STMT = "db/migration-updated-at"
    /** V10(안전망·보장 밖·주입 기록 테이블) 위치 */
    const val LOCATION_V10 = "db/migration-incremental"

    /**
     * 방식 판정에 쓰는 스키마 사실(ADR-012 §4.2.2). 마이그레이션 뒤에는 DB에서 읽고([readSchema]), 업무 앱은 마이그레이션 앞에서
     * 지금 DB + Flyway가 더할 위치로 내다본 값([afterLocations])을 같은 규칙([checkSchema])에 넣는다 — 앞뒤 판정이 갈리지 않게.
     * @param triggerStates 이 스키마 user_hold_quota의 [TOUCH_TRIGGER] `pg_trigger.tgenabled` 값들(없으면 빈 목록)
     * @param missingV10 없는 [V10_TABLES]
     */
    data class Schema(
        val updatedAtColumn: Boolean,
        val updatedAtIndex: Boolean,
        val triggerStates: List<String>,
        val missingV10: List<String>,
        val maxPreparedTransactions: Int,
    ) {
        /**
         * 이 스키마에 Flyway [locationPaths](location 경로 — `db/migration-incremental` 꼴)를 적용한 뒤의 모양(조합표의 위치 → 객체).
         * 위치가 목록에 있고 target이 닿으면 그 위치의 객체가 **있다고 본다**(이미 적용됐는지·이번에 적용될지를 가르지 않는다 — 적용된 V9 객체를 손으로 지운 DB ·
         * validate-on-migrate=false로 다른 V9판이 적용된 DB · baselineVersion ≥ 9는 예측이 실제와 어긋날 수 있다: 그때는 마이그레이션 뒤 검사가 거부한다 · ADR-012 §7-13 ⑧). 두 V9 위치를 함께 준 경우(조합표 '두 V9' 행)는 여기서 보지 않는다 —
         * Flyway가 마이그레이션 전에 '같은 버전 2개'로 막는다(호출 쪽이 Flyway 검증을 먼저 돌린다).
         * @param reachesV9 Flyway target이 V9까지 가는가(false면 V9 위치는 객체를 더하지 않는다) · [reachesV10] 같은 뜻
         */
        fun afterLocations(locationPaths: Collection<String>, reachesV9: Boolean = true, reachesV10: Boolean = true): Schema {
            fun has(location: String) = locationPaths.any { it.trim('/') == location || it.trimEnd('/').endsWith("/$location") }
            val v9Trigger = reachesV9 && has(LOCATION_V9_TRIGGER)
            val v9Stmt = reachesV9 && has(LOCATION_V9_STMT)
            return copy(
                updatedAtColumn = updatedAtColumn || v9Trigger || v9Stmt,
                updatedAtIndex = updatedAtIndex || v9Trigger || v9Stmt,
                triggerStates = if (v9Trigger && triggerStates.isEmpty()) listOf("O") else triggerStates,
                missingV10 = if (reachesV10 && has(LOCATION_V10)) emptyList() else missingV10,
            )
        }
    }

    /** 지금 DB의 스키마 사실 — 읽기만 한다(빈 DB에서도 돈다) */
    fun readSchema(jdbc: JdbcTemplate): Schema {
        val column = jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'user_hold_quota' AND column_name = 'updated_at'",
            Int::class.java,
        )!! > 0
        val index = jdbc.queryForObject(
            "SELECT count(*) FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?", Int::class.java, UPDATED_AT_INDEX,
        )!! > 0
        // 이 스키마의 user_hold_quota에 걸린 트리거만(rv43 — 다른 스키마·테이블의 같은 이름 트리거로 판정하지 않는다)
        val states = jdbc.queryForList(
            "SELECT t.tgenabled::text FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid JOIN pg_namespace n ON n.oid = c.relnamespace " +
                "WHERE t.tgname = ? AND NOT t.tgisinternal AND c.relname = 'user_hold_quota' AND n.nspname = current_schema()",
            String::class.java, TOUCH_TRIGGER,
        )
        val missing = V10_TABLES.filterNot { t -> jdbc.queryForObject("SELECT to_regclass(?) IS NOT NULL", Boolean::class.java, t) == true }
        val prepared = jdbc.queryForObject("SHOW max_prepared_transactions", String::class.java)!!.trim().toInt()
        return Schema(column, index, states, missing, prepared)
    }

    /** 설정 쪽([checkConfig]) + 지금 DB 스키마([readSchema] → [checkSchema]). @param externalApp 외부 대조 앱이면 true */
    fun check(jdbc: JdbcTemplate, reconcile: ReconcileProperties, externalApp: Boolean) {
        checkConfig(reconcile, externalApp)
        checkSchema(reconcile, readSchema(jdbc))
        val inc = reconcile.incremental
        if ((inc.method == IncrementalMethod.TRIGGER || inc.method == IncrementalMethod.STMT) && inc.enabled) {
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

    /**
     * 방식 ↔ 스키마 규칙(ADR-012 §4.2.2 Flyway 위치 조합표) — 단일 출처. 마이그레이션 뒤([check])와 업무 앱의 마이그레이션 앞(내다본 [Schema])이 함께 부른다.
     * DB를 보지 않는다(사실은 [schema]로 받는다).
     */
    fun checkSchema(reconcile: ReconcileProperties, schema: Schema) {
        val inc = reconcile.incremental
        val method = inc.method
        val m = method.name.lowercase()
        val column = schema.updatedAtColumn
        val index = schema.updatedAtIndex
        val states = schema.triggerStates
        val trigger = states.isNotEmpty()
        // tgenabled: O(보통 세션에서 발화)·A(늘 발화)만 '활성' — D(DISABLE TRIGGER)·R(복제 세션에서만)은 업무 세션에서 updated_at을 남기지 않는다
        val triggerActive = states.any { it == "O" || it == "A" }
        val updatedAtMethod = method == IncrementalMethod.TRIGGER || method == IncrementalMethod.STMT
        if (updatedAtMethod) {
            check(column && index) {
                "$PREFIX.method=$m 는 user_hold_quota.updated_at 열과 인덱스($UPDATED_AT_INDEX)가 있어야 한다(열=$column · 인덱스=$index) — " +
                    "spring.flyway.locations에 classpath:${if (method == IncrementalMethod.TRIGGER) LOCATION_V9_TRIGGER else LOCATION_V9_STMT}를 더하라"
            }
        }
        if (method == IncrementalMethod.TRIGGER) {
            check(trigger) {
                "$PREFIX.method=trigger인데 트리거 $TOUCH_TRIGGER 가 없다 — spring.flyway.locations에 classpath:$LOCATION_V9_TRIGGER 를 더하라(문장 위치가 아니라)"
            }
            // 트리거가 꺼져 있으면 쿼터 변경이 updated_at을 남기지 않아 증분이 그 쌍을 못 본다(유실 — 명세 §2) — 이름만 있는 트리거로 통과하지 않는다
            check(triggerActive) {
                "$PREFIX.method=trigger인데 트리거 $TOUCH_TRIGGER 가 비활성이다(pg_trigger.tgenabled=$states — O·A만 활성) — " +
                    "ALTER TABLE user_hold_quota ENABLE TRIGGER $TOUCH_TRIGGER 로 켜거나 방식을 바꿔라"
            }
        } else {
            // 비활성(D)이어도 '있음'으로 본다(보수적 — 다시 켜지면 비용이 다른 셀에 샌다). 지우려면 새 마이그레이션 또는 수동 DROP
            check(!trigger) {
                "$PREFIX.method=$m 인데 트리거 $TOUCH_TRIGGER(V9 트리거 위치)가 있다 — 트리거 비용이 다른 셀에 샌다. " +
                    "classpath:$LOCATION_V9_TRIGGER 는 method=trigger에서만 더하라. " +
                    "이미 V9 트리거를 적용한 DB를 옛 방식(C)으로 되돌리는 것이면 $PREFIX.method=trigger · spring.flyway.locations 기본값을 그대로 둬라" +
                    "(명세 §10 K3 — 증분은 끈 채 표시만) — 트리거를 지우려면 앞으로 가는 새 마이그레이션이 필요하다"
            }
        }
        val safety = reconcile.safetyNet.enabled
        if (inc.enabled || safety) {
            check(schema.missingV10.isEmpty()) {
                "증분($PREFIX.enabled=${inc.enabled}) 또는 안전망($SAFETY_PREFIX.enabled=$safety)을 켰는데 V10 기록 테이블이 없다: ${schema.missingV10} — " +
                    "spring.flyway.locations에 classpath:$LOCATION_V10 을 더하라"
            }
        }
        if (updatedAtMethod && inc.enabled) {
            // 준비된 트랜잭션은 세션이 끝난 뒤에도 남아 pg_stat_activity의 xact_start로 보이지 않는다 — 창 하한 근거(§4.2.3)가 깨진다(rv41 Opus M4 · codex H1)
            val prepared = schema.maxPreparedTransactions
            check(prepared == 0) {
                "$PREFIX.method=$m 증분 틱(enabled=true)은 max_prepared_transactions = 0인 DB에서만 돈다(지금 $prepared) — 준비된 트랜잭션은 창 하한 검사에서 보이지 않는다"
            }
        }
    }
}
