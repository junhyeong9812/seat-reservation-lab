-- ADR-012 증분 대조·안전망 기록 테이블 — spring.flyway.locations에 classpath:db/migration-incremental을 더할 때만 적용된다(선택 위치 — 손잡이를 줄 때만).
-- 기동 검사가 incremental.enabled=true 또는 safety-net.enabled=true이면 이 세 테이블을 요구한다. FK를 두지 않는다(V7과 같은 이유 — 시드 TRUNCATE·정리에 묶이지 않게).

-- 안전망 실행 1회 = 1행(같은 run_id의 counter_reconcile_run 1행과 짝 — §4.2.5 순서 6). 시간(ms)은 앱 쪽 벽시계로 잰 커넥션 블록 합이다.
-- db_ms_total = db_ms_open + db_ms_reread + db_ms_recent + db_ms_full + repair_ms + record_ms(이 행 자기 INSERT는 빼고 지표 safety.self_record로).
-- 표본(id–시각) DB 시간은 sample_db_ms에만 든다. id 범위는 모두 (lo, hi] — 커서 = 처리를 마친 마지막 id.
CREATE TABLE counter_safety_run (
    run_id              BIGINT           PRIMARY KEY,   -- counter_reconcile_run.id
    db_started_at       TIMESTAMPTZ      NULL,          -- 실행 시작 DB 시각(시작 표본 — 실패하면 직전 표본 시각, 없으면 NULL)
    budget_ms           BIGINT           NOT NULL,
    db_ms_total         BIGINT           NOT NULL,
    db_ms_open          BIGINT           NOT NULL,      -- 열린 회차 조회
    db_ms_reread        BIGINT           NOT NULL,      -- 다시 읽기(최근 창 몫 — 앞 절반 = reread + recent)
    db_ms_recent        BIGINT           NOT NULL,
    db_ms_full          BIGINT           NOT NULL,
    repair_ms           BIGINT           NOT NULL,      -- 복구 트랜잭션 합(이월 복구 포함)
    repair_ms_max       BIGINT           NOT NULL,
    record_ms           BIGINT           NOT NULL,      -- skipped 기록 · counter_reconcile_run INSERT
    chunk_ms_max        BIGINT           NOT NULL,      -- 탐지 블록 하나(행 없음 조회 · 쌍 조회 · 탐지 청크)의 최대
    wall_ms             BIGINT           NOT NULL,
    repair_pairs        INT              NOT NULL,
    carry_in            INT              NOT NULL,
    carry_out           INT              NOT NULL,
    carry_dropped       INT              NOT NULL,
    chunks_reread       INT              NOT NULL,
    chunks_recent       INT              NOT NULL,
    chunks_full         INT              NOT NULL,
    reread_left         BIGINT           NOT NULL,      -- 다음 실행으로 넘긴 다시 읽기 id 수
    reread_ids          BIGINT           NOT NULL,      -- 이번 다시 읽기 구역 크기(홀드 + 예약)
    sample_db_ms        BIGINT           NOT NULL,      -- 직전 실행 뒤 표본들(이 실행의 시작 표본 포함)의 DB 시간 합
    sample_count        INT              NOT NULL,
    sample_short        BOOLEAN          NOT NULL,      -- id_at(T)에 맞는 표본이 없어 보수값을 썼다(§4.2.13 #7)
    reset               BOOLEAN          NOT NULL,      -- 시드 초기화(파일 번호 바뀜)로 커서·표본·대기열을 비웠다
    recent_hold_cursor  BIGINT           NOT NULL,
    recent_res_cursor   BIGINT           NOT NULL,
    hold_head           BIGINT           NOT NULL,      -- 실행 시작 표본의 최대 id(적체 = head − cursor)
    res_head            BIGINT           NOT NULL,
    recent_cursor_age_s DOUBLE PRECISION NULL,          -- 홀드 커서 id의 나이(표본 고리로 추정 — 없으면 NULL)
    recent_skip_from    BIGINT           NULL,          -- 건너뛴 범위 lo(제외)
    recent_skip_to      BIGINT           NULL,          -- 건너뛴 범위 hi(포함)
    recent_skip_ids     BIGINT           NOT NULL,      -- hi − lo(홀드·예약 합)
    full_schedule       BIGINT           NULL,          -- 전체 커서 ① 회차
    full_user           BIGINT           NULL,          -- 전체 커서 ① 마지막 사용자
    full_orphan_hold    BIGINT           NULL,          -- 전체 커서 ② 홀드 id(처리를 마친 마지막)
    full_orphan_res     BIGINT           NULL,
    lap_no              INT              NOT NULL,
    lap_completed       BOOLEAN          NOT NULL,
    lap_seconds         DOUBLE PRECISION NULL,          -- 이번에 끝난 바퀴 시간(바퀴 시작 실행 DB 시각 → 끝 실행 DB 시각)
    lap_age_s           DOUBLE PRECISION NOT NULL,      -- 진행 중 바퀴 나이(끝난 행이면 그 바퀴 시간)
    expedited           BOOLEAN          NOT NULL,
    restart             BOOLEAN          NOT NULL       -- 기동 뒤 첫 실행
);

-- 측정 전용 앱 경로 주입(§4.2.7)과 하네스 S3e DB 직접 주입(path = db) 1건 = 1행. 주입과 같은 트랜잭션에서 쓴다(커밋된 주입만 남는다).
CREATE TABLE counter_app_inject (
    id                     BIGSERIAL   PRIMARY KEY,
    at                     TIMESTAMPTZ NOT NULL,   -- clock_timestamp() — 주입 문장 시각
    tx_started_at          TIMESTAMPTZ NOT NULL,   -- now() — 주입 트랜잭션 시작 시각(보장 밖 분류용)
    path                   VARCHAR(10) NOT NULL,   -- hold | expiry | db
    kind                   VARCHAR(5)  NOT NULL,   -- AH | AL | AE | L1 | L2 | H | D
    schedule_id            BIGINT      NOT NULL,
    user_id                BIGINT      NOT NULL,
    delta                  INT         NOT NULL,
    cnt_after              INT         NULL,
    first_candidate_at     TIMESTAMPTZ NULL,       -- 주입 뒤 처음 증분 후보에 오른 틱의 후보 읽기 직전 DB 시각(track-inject)
    first_candidate_source VARCHAR(30) NULL
);

-- 증분 보장 조건 밖으로 나간 사건 1건 = 1행(사용자 결정 2026-10-20 D1 '놓친 사실을 기록한다' — §4.2.6).
CREATE TABLE counter_inc_gap (
    id          BIGSERIAL    PRIMARY KEY,
    at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    source      VARCHAR(30)  NOT NULL,   -- 증분 source
    kind        VARCHAR(30)  NOT NULL,   -- window_clamped | restart | memory_overflow | after_commit_error | hidden_sessions
    from_at     TIMESTAMPTZ  NULL,       -- 보장 밖 구간
    to_at       TIMESTAMPTZ  NULL,
    schedule_id BIGINT       NULL,       -- 첫 쌍(있으면)
    user_id     BIGINT       NULL,
    pairs       INT          NOT NULL DEFAULT 0,
    detail      VARCHAR(200) NULL
);
