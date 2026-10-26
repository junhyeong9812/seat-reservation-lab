package com.jun.labs.seatreservation.incremental

import com.jun.labs.seatreservation.support.countOf
import io.micrometer.core.instrument.MeterRegistry
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/*
 * 테스트 21 — 숨은 세션·권한(§4.2.3 '숨은 세션 검사' · §4.2.2 rv41 M4): 앱 접속 역할이 superuser도 pg_read_all_stats도 아니면 다른 역할 세션의
 * state·xact_start가 NULL로 보인다. 그 세션이 연 트랜잭션이 틱 사이에 커밋한 변경도 max-overlap 안이면 잡혀야 하고, 그 틱에 hidden_sessions 기록이 남는다.
 * 판별력: 다른 역할 트랜잭션을 '틱 k보다 1.5초 넘게 앞'에서 시작한다 — 숨은 세션을 무시하고 o 없이 L = t − 1초로 두는 구현은 그 변경을 놓친다.
 * 역할: app(앱 — NOSUPERUSER · DB 소유자) · other(다른 역할 — 쿼터 UPDATE 권한만).
 */
class HiddenSessionTest : BootTestBase() {

    @Test
    fun `21 — 다른 역할의 트랜잭션(xact_start 안 보임) 변경도 max-overlap 안이면 잡힌다 · hidden_sessions 지표 · counter_inc_gap hidden_sessions 행`() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { c ->
            c.createStatement().use { st ->
                st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'app') THEN CREATE ROLE app LOGIN PASSWORD 'app' NOSUPERUSER; END IF; END $$")
                st.execute("DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'other') THEN CREATE ROLE other LOGIN PASSWORD 'other' NOSUPERUSER; END IF; END $$")
            }
        }
        val url = freshDbUrl(owner = "app")
        val app = jdbcFor(url, "app", "app")
        val props = incrementalProps("trigger", LOCS_TRG)

        boot(url, props, user = "app", password = "app").use { ctx ->
            assertEquals(false, app.queryForObject("SELECT rolsuper FROM pg_roles WHERE rolname = current_user", Boolean::class.java), "전제: 앱 역할은 superuser가 아니다")
            app.execute("GRANT SELECT, UPDATE ON user_hold_quota TO other")
            val s = app.seedSchedule()
            app.quota(s, 2101, 1); app.hold(s, 2101)
            val meters = ctx.getBean(MeterRegistry::class.java)
            val inc = ctx.getBean(IncrementalReconciler::class.java)
            val hidden0 = meters.countOf(M_INC_HIDDEN)

            DriverManager.getConnection(url, "other", "other").use { other ->
                other.autoCommit = false
                other.exec("UPDATE user_hold_quota SET cnt = cnt + 1 WHERE schedule_id = ? AND user_id = ?", s, 2101L)   // 트리거 표시 = 이 트랜잭션 시작
                val start = other.txNow()
                assertEquals(
                    1,
                    app.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE usename = 'other' AND state IS NULL AND xact_start IS NULL", Int::class.java),
                    "전제: 앱 역할에게 다른 역할 세션의 state·xact_start가 NULL로 보인다",
                )
                inc.tick(SRC_TRG)                  // 정렬용 틱
                app.awaitDbTimeAfter(start.plusMillis(1_500))
                inc.tick(SRC_TRG)                  // 틱 k — 숨은 세션 h > 0 → 하한 = t − max-overlap
                other.commit()
            }
            inc.tick(SRC_TRG)                      // 틱 k+1

            assertEquals(1, app.cnt(s, 2101), "숨은 세션의 커밋 변경을 놓쳤다")
            assertEquals(listOf(SRC_TRG), app.logsOf(s, 2101).filter { it.action == "repaired" }.map { it.source })
            assertTrue(meters.countOf(M_INC_HIDDEN) - hidden0 >= 1, "hidden_sessions 지표")
            assertTrue(app.gapRows("hidden_sessions").any { it["source"] == SRC_TRG }, "counter_inc_gap hidden_sessions 행(보수 하한을 쓴 틱): ${app.gapRows()}")
        }
    }
}
