package com.jun.labs.seatreservation.service.impl.drift

import java.time.Duration

/**
 * 대조(C·F) 설정 — 키는 업무 앱 `SeatHoldProperties.counterDrift.reconcile`(`seat.hold.counter-drift.reconcile.*`)에 그대로 묶인다(ADR-012 §4.2.1).
 * core는 앱 설정 클래스를 import하지 않으므로 이 타입만 받는다 — 업무 앱은 `@Bean reconcileProperties`로 그 값을 내준다.
 */
data class ReconcileProperties(
    /** 후보 C — 앱 안 청크 대조(F는 reconciler 프로필이 켠다). 기본 켬(ADR-009 결정 — C는 A가 필수다, 기동 검사). */
    val enabled: Boolean = true,
    val interval: Duration = Duration.ofSeconds(60),
    /** 쌍 복구 트랜잭션의 `SET LOCAL lock_timeout`. */
    val lockTimeout: Duration = Duration.ofSeconds(1),
    /** 쿼터 행 주도 청크 크기(쌍 수 — 설계 E1 k = 1000). */
    val chunkSize: Int = 1_000,
    /** 행 없음 갈래 청크 크기(홀드·예약 id 범위 폭 — 설계 E1 m = 10000). */
    val orphanChunkSize: Int = 10_000,
    /** ① 쿼터 행 청크의 탐지 쿼리 모양(ADR-012 R8 · §4.1). 기본 one-shot = 지금 한 문장(상관 서브쿼리) — ②(행 없음 갈래)·복구는 모양과 무관하다. */
    val detectShape: DetectShape = DetectShape.ONE_SHOT,
    /** 증분 대조(ADR-012 §4.2.2 — `…reconcile.incremental.*`). 기본 = 방식 none · 틱 끔(새 손잡이 미지정이면 이전과 같다 — §3-4). */
    val incremental: IncrementalProperties = IncrementalProperties(),
    /** 안전망(ADR-012 §4.2.5 — `…reconcile.safety-net.*`). 기본 끔. */
    val safetyNet: SafetyNetProperties = SafetyNetProperties(),
)

/**
 * ① 쿼터 행 청크 탐지 모양(ADR-012 §4.1): one-shot(한 문장 — 자동 커밋) · split(쿼터 행 k개 · 그 k명의 홀드 · 확정 예약을 행으로 읽어 앱이 셈) ·
 * group(같은 대상을 테이블별 `GROUP BY user_id` 개수로 읽어 앱이 합침). split·group은 탐지 전용 읽기 전용 REPEATABLE READ 트랜잭션 하나(빌림 1).
 */
enum class DetectShape { ONE_SHOT, SPLIT, GROUP }

/**
 * 바뀐 쌍을 아는 방법(ADR-012 §4.2.2): none(기본 — 표시도 틱도 없음) · memory(ⓐ 앱 메모리 집합 — 업무 앱 안에서만) ·
 * trigger(ⓑ `updated_at` DB 트리거 — V9 트리거 위치) · stmt(ⓒ `updated_at` 앱 문장 — V9 문장 위치).
 */
enum class IncrementalMethod { NONE, MEMORY, TRIGGER, STMT }

/** ADR-012 §4.2.2 손잡이표 — 값·기본값은 측정 전 고정값이다. */
data class IncrementalProperties(
    /** 이 프로세스가 쿼터 쓰기에 남기는 표시(ⓐ 등록 · ⓒ 문장)와 증분 틱이 후보를 읽는 방식 */
    val method: IncrementalMethod = IncrementalMethod.NONE,
    /** 이 프로세스에서 증분 틱을 돌리나(M3 업무 앱 = 표시만 → false) */
    val enabled: Boolean = false,
    /** 틱 주기(고정 지연) — source 꼬리표도 이 값에서 만든다(§4.2.6) */
    val interval: Duration = Duration.ofSeconds(1),
    /** ⓐ 집합 크기 상한(쌍 수) `[구현 검증]` */
    val memoryCap: Int = 200_000,
    /** ⓑⓒ 첫 틱의 창 하한 = 기동 시 DB 시각 − 이 값 */
    val startLookback: Duration = Duration.ofSeconds(60),
    /** ⓑⓒ 창 하한을 이보다 뒤로 끌지 않는다 — 증분 보장의 트랜잭션 상한(사용자 결정 2026-10-20 D1) */
    val maxOverlap: Duration = Duration.ofSeconds(60),
    /** 효과 셀 전용 — 켜면 증분 틱이 `counter_app_inject`의 첫 후보 시각 열을 채운다(§4.2.7) */
    val trackInject: Boolean = false,
)

/** ADR-012 §4.2.5 안전망 손잡이 — 값은 §2.1의 측정 전 고정값(rv41 반영 포함). */
data class SafetyNetProperties(
    val enabled: Boolean = false,
    /** 실행 주기(고정 지연 — 앞당김 요청이면 바로 1회, 10초에 1번까지) */
    val interval: Duration = Duration.ofMinutes(1),
    /** 실행당 탐지 블록 DB 시간 예산(앞 절반 = 다시 읽기 + 최근 창) */
    val budget: Duration = Duration.ofSeconds(2),
    val recentWindow: Duration = Duration.ofHours(2),
    /** 다시 읽기 폭(주기 + 트랜잭션 상한 + 실행 벽시계 여유 — rv41 H3) */
    val reread: Duration = Duration.ofSeconds(130),
    /** 최근 창·다시 읽기 id 청크 폭 */
    val recentIdChunk: Int = 10_000,
    /** 진행 중 바퀴 나이 경고 기준 */
    val lapWarn: Duration = Duration.ofHours(1),
    /** id–시각 표본 주기(고정 지연) */
    val sampleInterval: Duration = Duration.ofSeconds(10),
    /** 실행당 복구 DB 시간 상한(rv41 M5) */
    val repairBudget: Duration = Duration.ofSeconds(1),
    /** 실행당 복구 쌍 수 상한(rv41 M5) */
    val repairCap: Int = 500,
    /** 이월 대기열 상한(rv41 M5) */
    val carryCap: Int = 100_000,
    /** 실행 벽시계 기록 기준(R7 판정용 — 넘어도 멈추지 않는다) */
    val wallCap: Duration = Duration.ofSeconds(6),
)
