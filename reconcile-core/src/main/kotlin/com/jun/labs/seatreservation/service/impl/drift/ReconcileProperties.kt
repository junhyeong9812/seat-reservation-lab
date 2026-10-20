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
)

/**
 * ① 쿼터 행 청크 탐지 모양(ADR-012 §4.1): one-shot(한 문장 — 자동 커밋) · split(쿼터 행 k개 · 그 k명의 홀드 · 확정 예약을 행으로 읽어 앱이 셈) ·
 * group(같은 대상을 테이블별 `GROUP BY user_id` 개수로 읽어 앱이 합침). split·group은 탐지 전용 읽기 전용 REPEATABLE READ 트랜잭션 하나(빌림 1).
 */
enum class DetectShape { ONE_SHOT, SPLIT, GROUP }
