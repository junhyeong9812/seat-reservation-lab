# log — ADR-016 측정 (하네스 · 모형 · 측정 전 고정 · 캠페인)

> 명세: `requirement-spec.md`(합의 2026-11-08) · 선행: `docs/plans/2026-11-07/adr-016-payment-start/`(2단계 구현 마감 ea7d5076b) · 설계 정본: `docs/adr/ADR-016-paid-seat-loss.md` 설계-8 · 9

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 11-08 | 사용자 요청 "worktree 지우고 측정 명세로 진행하자" · blind worktree 삭제(이전 작업 log에 기록) | 새 작업 폴더 |
| 11-08 | 사전 탐색(explorer · 읽기 전용): ADR-015 하네스 구조 — 재사용 가능 = 동결 · 해시 · 시작 조건 · 무효 재측정 · 정합 판정기 · much_higher · 회차 순서 / 새로 필요 = T · D 분포(로그정규) · 세션 단위 억울 집계 · payBy 사용 · 실제 눈금 TTL 5분 · PC TIME_WAIT 표본기 · 셀 × 구성 매트릭스 · 새 고르는 규칙(G-정합 · 모형 · 목표 · 비용) · EOF 처방은 이미 커밋(6295ad81 · f865f509) | 인터뷰 질문 작성 |
| 11-08 | 인터뷰 1(범위 · paid-flow 구성 · CAP · 시간 예산) · 2(θ · W · M 규칙 · θ < TTL · stakes · 자율성) · 명세 초안 · 사용자 '질문 줘' → 인터뷰 3(cost 도착률 · race · 세션 수 · ρ) 전부 권장안 | 명세 Q/A 2 반영 · 합의 확인 대기 |
| 11-08 | 사용자 합의 "합의 · 진행" → set-state SPEC=1 · MODE=auto | 설계 선검증(M16-1~6) 착수 |
| 11-08 | task 01 θ < TTL 기동 검사(114502eed) — Adr016ThetaTtlStartupTest 4 · 관련 기동 계약 3클래스 20 통과(전체 테스트는 하네스 커밋 뒤 한 번에) | 완료 |
| 11-08 | 설계 선검증(미러 mirdv16-blKv · OUT dv16-zXpr · 보안 스캔 redact 후 0): codex 높음 5 · 중간 5 · OQ 4 · Opus(토큰 미회수 — 알림 usage 없음) 높음 3 · 중간 8 · 낮음 4 · OQ 6 | 종합 → 합의 규칙과 충돌 4건은 사용자 재합의 · 나머지는 명세 보강 |
| 11-08 | 사용자 재합의 DV-1~4(전부 권장안): W = CAP + M + 여유 · G-모형 세션별 예측 대조 · ⓒ 경계 흐름 · 흐름당 회차 1,100 · 분모 전체 · 5% 점추정 → 명세 §0 Q/A 3 · §1-2~5 · §2 보강(DV-5~14 포함) | 명세 수정 |
| 11-08 | 사용자 요청 "푸시해놓고 다음 순차적으로 진행하자" → `git push -u origin feat/adr-016-paid-seat-loss`(새 원격 브랜치 · 커밋 22 · attribution 0 확인 · PR 없음) | push 완료 |
| 11-08 | 명세 수정 post-fix 재점검(codex · 1회): 신규 3(DV16-R1 ⓒ 마감이 수신 + 상대값이면 서버 payBy 뒤 · R2 ④ extend 지표는 진단 404 · 403 · 만료를 안 셈(PaymentStartService.kt:107 확인) · R3 G-모형 θ 경계 띠 없음) | 셋 다 채택 → 명세 반영 · 설계 문서 재점검 1회 상한이라 메인 대조로 확인 후 종료 |

## 리뷰 ledger

| ID | 출처 | 위치 | 처리 | 상태 |
|---|---|---|---|---|
| DV16-R1 | 재점검 · codex | 명세 §1-2 ⓒ 마감 | 채택(높음) — 마감 = min(수신 + payWithinSeconds, 절대 payBy − err_ms) | fixed |
| DV16-R2 | 재점검 · codex | 명세 G-정합 ④ 교차 | 채택(중간) — extend 지표가 세는 결과만 대조 | fixed |
| DV16-R3 | 재점검 · codex | 명세 G-모형 | 채택(중간) — payment-start θ 띠 추가 | fixed |
| DV-1 | opus F1 | 명세 §0 W 규칙 · §1 G-목표 | 채택(높음) — 모형 최소 W는 관측 상한 판정에서 구조적으로 탈락 | fixed(재합의 반영) |
| DV-2 | opus F2 · codex OQ3 | G-모형 | 채택(높음) — 공통 난수로 재측정 무력 · 다중 검정 · 세션 단위 예측 대조로 | fixed(재합의 반영) |
| DV-3 | opus F3 | M16-3 · ⓒ 흐름 | 채택(높음) — 무작위 D로는 M 덮음 검출력 ≈ 0 · 경계 겨냥 흐름 필요 | fixed(재합의 반영) |
| DV-4 | opus F1 · F9(codex) | 흐름당 n · 통계 | 채택(중간) — 0/3000 상한 0.0998% 칼날 · 결제 전 거부 + 실패 통계 미정 | fixed(재합의 반영) |
| DV-5 | codex F1 · F2 · opus F4 · F15 | 세션 분류 | 채택 — 끝 상태 DB 기준 · 결정표 · 억울 허용 목록(404 · 409 HOLD_EXPIRED · SEAT_NOT_HELD) · payment-start 계약 거부 = 결제 전 거부 · predict / classify 분리 | fixed(명세) |
| DV-6 | codex F3 · opus OQ3 | 흐름 배정 | 채택 — 세션 번호 mod 흐름 수 교차 배정 · 흐름별 계획 = 분류 대조(무효 사유) | fixed(명세) |
| DV-7 | codex F4 · opus F6 | δ | 채택 — 직접 측정 불가 → δ̂ = 확정 응답 시간(상한) · 시계 차 = err_ms 상한 · 없으면 0 대체 금지(결측 = 무효) | fixed(명세) |
| DV-8 | codex F5 · opus F5 · OQ4(codex) | ⓒ 마감 | 채택 — 계약 그대로 상대값(수신 시각 + payWithinSeconds · 내림) · payBy 출처 응답 고정(① 선점 201 · ①+ⓑ · ④ payment-start 200) · 모형도 같은 내림 | fixed(명세) |
| DV-9 | codex F6 · opus F7 · F9 | N16-1 · 서버 교차 | 채택 — 1차 강제 = readOnly + 계약 테스트 · 부하 증거 = 수렴 뒤 n_tup_upd 0 + pgss 쓰기 문장 0 + pay_start{result} = k6 결과 · extend{result} · 잘못된 결제 전 거부 0 | fixed(명세) |
| DV-10 | codex F7 · opus F8 | N16-5 | 채택 — rejected{code} · failed{status} 각자 대조 · k6 비2xx ≤ 서버 ≤ k6 비2xx + 확정 status 0 · 실행 전체 누적 | fixed(명세) |
| DV-11 | codex F8 · opus F11 · F12 | ρ · 재선점 지표 | 채택 — cost ρ = 0(ADR-015 M15-20) · 재선점 지표 = 좌석을 결국 못 얻은 비율 · 풀리기까지 시간 | fixed(명세) |
| DV-12 | codex F10 · opus F10 · F11 | 실제 눈금 상수 · 예산 | 채택 — grace · converge · pre-VU · cost 창 재계산 · ⓑ 없는 흐름도 긴 간격 앞 close · plan16 --dry-run으로 시간 · VU 산정 → 예산 넘치면 줄이는 안 보고 · 세션 행은 paid-flow만 | fixed(명세 · 예산은 dry-run 뒤 보고) |
| DV-13 | opus F13 · F14 | ⓒ 가정 · CAP 30 | 채택(낮음) — 결정 문서에 'PG가 상한 강제' 조건 · ⓒ 끔 짝 흐름 일치 조건 · CAP 30분 결과 병기 | fixed(명세) |
| DV-14 | opus θ<TTL 주의 | plan16 렌더 | 채택 — 모든 ① · ④ 렌더 M ≤ θ < min(TTL, W) dry-run 점검 | fixed(명세) |

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
