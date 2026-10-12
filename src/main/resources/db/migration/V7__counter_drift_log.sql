-- ADR-009 카운터 어긋남 탐지·복구·정리 기록 테이블. 쓰는 주체: 후보 A(만료 복구)·C·F(대조)·쿼터 행 정리 — 모두 설정으로 켤 때만 쓴다.
-- FK를 두지 않는다: 부하 시드 초기화(TRUNCATE product_schedule …)와 회차 단위 정리가 이 기록에 묶이지 않게(기록은 사실의 사본).

-- 쌍 단위 기록: 고친 쌍(repaired)·오탐(false-positive — 잠근 뒤 다시 세니 맞음)·건너뛴 쌍(skipped — 복구 트랜잭션 실패, reason = SQLSTATE)
CREATE TABLE counter_drift_log (
    id          BIGSERIAL   PRIMARY KEY,
    at          TIMESTAMPTZ NOT NULL,
    source      VARCHAR(30) NOT NULL,   -- expiry-repair | reconcile-app | reconcile-proc
    schedule_id BIGINT      NOT NULL,
    user_id     BIGINT      NOT NULL,
    kind        VARCHAR(10) NOT NULL,   -- missing(쿼터 행 없음) | below(cnt < 실제) | above(cnt > 실제)
    cnt_before  INT         NULL,       -- 잠근 뒤 읽은 cnt(행이 없어 복구가 만들었으면 NULL). skipped는 탐지 때 본 값
    actual      INT         NOT NULL,   -- 복구 기준 실제 값(A는 이번 만료를 반영한 값). skipped는 탐지 때 본 값
    action      VARCHAR(20) NOT NULL,   -- repaired | false-positive | skipped
    reason      VARCHAR(40) NULL        -- skipped의 SQLSTATE(없으면 예외 종류)
);

-- 대조(C·F) 한 바퀴 기록. 마감 회차는 대조하지 않고 수만 남긴다(skipped_schedules·skipped_pairs — 명세 §10)
CREATE TABLE counter_reconcile_run (
    id                BIGSERIAL   PRIMARY KEY,
    source            VARCHAR(30) NOT NULL,
    started_at        TIMESTAMPTZ NOT NULL,
    finished_at       TIMESTAMPTZ NOT NULL,
    schedules         INT         NOT NULL,   -- 대조한 열린 회차 수
    chunks            INT         NOT NULL,   -- 쿼터 행 주도 청크 문장 수
    pairs_scanned     BIGINT      NOT NULL,
    orphan_chunks     INT         NOT NULL,   -- 행 없음 갈래 청크 문장 수(홀드 + 예약)
    found             INT         NOT NULL,   -- 어긋나 보인 쌍(두 갈래 합, 중복 제거)
    repaired          INT         NOT NULL,
    false_positives   INT         NOT NULL,
    skipped           INT         NOT NULL,   -- 복구 실패로 건너뛴 쌍
    skipped_schedules INT         NOT NULL,   -- 마감 회차 수
    skipped_pairs     BIGINT      NOT NULL,   -- 마감 회차의 쿼터 행 수
    skipped_chunks    INT         NOT NULL,   -- 청크 문장 실패로 건너뛴 청크(실패한 청크 1 + 그 회차·갈래의 남은 청크는 세지 않음)
    errors            INT         NOT NULL,
    chunk_ms_total    BIGINT      NOT NULL,
    chunk_ms_max      BIGINT      NOT NULL,
    orphan_ms_total   BIGINT      NOT NULL,
    borrows           BIGINT      NOT NULL    -- 이 바퀴의 커넥션 빌림 수(이 기록 INSERT는 빼고)
);

-- 쿼터 행 정리 기록. schedule(①) = 회차마다 1행, zero(②) = 실행마다 1행(schedule_id NULL)
CREATE TABLE counter_cleanup_run (
    id           BIGSERIAL   PRIMARY KEY,
    at           TIMESTAMPTZ NOT NULL,
    mode         VARCHAR(10) NOT NULL,   -- schedule | zero
    schedule_id  BIGINT      NULL,
    holds_seen   INT         NOT NULL,   -- ①: 회차 행을 잠근 뒤 본 홀드 존재(0/1 — EXISTS). ②: 0
    rows_deleted INT         NOT NULL,
    waited_ms    BIGINT      NOT NULL,   -- ①: 회차 행 FOR UPDATE 대기. ②: 0
    stmt_ms      BIGINT      NOT NULL    -- 확인·삭제 문장 시간
);
