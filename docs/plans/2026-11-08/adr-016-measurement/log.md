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
| 11-08 | task 02 outcome16.py(분류 · 마감 · decide · predict — 단일 정의) · model16.py → 설계-8.3 표 21칸 재현(허용 4σ · 불일치 0 · 예: ① σ0.94 11.41% 대 설계 11.47%) · 고르기 시험(δ 합성 50ms · err 20ms): M 1 · W 611 · θ_cond 69(①+ⓑ 억울 10.0% — 목표 밖 · 설계 예상과 같음) | 커밋 7b03c549 |
| 11-08 | 사용자 질문 "측정하고 머지하는 게 맞나" → 권고: 측정 · 결정 뒤 한 PR(ADR-014 #13 · ADR-015 #14 선례 · 캠페인은 브랜치 SHA 동결이라 main 불필요 · 기본 모드 공개 API 변경이 결정 전 main에 들어가지 않게) · ADR-015 EOF 수정만 따로 올리는 안 제시 | 답 대기 없이 진행 |
| 11-08 | 인터페이스 계약 고정(`scratchpad/interface16.md` — outcome16 · model16 · summarize16 C절 · plan16 D절) → task 03 구현 워커(Opus · 메인 트리 · 커밋 금지) ∥ task 04 blind 워커(Opus · worktree · 구현 파일 열람 금지) 병렬 | 회수 대기 |
| 11-08 | task 04 blind 회수(토큰 119,983): blind16_test.py 109사례(worktree 커밋 35744d7f) · outcome16 62 · model16 6 통과 · summarize16 41은 모듈 없음 import 오류(의도) · 계약 공백 2(ⓒ 짝 키 · classified 모양 · simulate 입력 모양) → 구현 워커에 계약 보충 전달 | summarize16 뒤 재실행 |
| 11-08 | task 03 구현 워커 회수(토큰 419,987 · 77 도구 · 40분): plan16 · paid-flow.js · lib16.js · run16 · campaign16 · summarize16 · actsnap16 · hhash16 · compose.paid16 · selftest16(111 · --campaign 124 통과) · ADR-015 selftest --campaign 0 · dry-run 21회차 9.33시간 · **가정 1**: 잠자는 VU 0.27~0.30 MiB/VU → cost 300/s 미리 7만 VU(20GiB · 가용 21 − 하한 4 넘음) · 100/s 2.3만(6.5GiB) → **100/s** 권고(스모크에서 RSS 재확인) | 커밋 df2efaf1(하네스) · d714749c(테스트) |
| 11-08 | 메인 대조: 구현 워커 지적 'predict c_send 없음 → (WRONGED, 띠 안)'은 잘못된 결제 전 거부 · 마감 오판을 G-모형이 넘기는 그린 위장 → outcome16에 PAID(4갈래 밖) 반환으로 고침 · selftest16에 'G-모형 띠 밖 불일치' 점검 추가 · blind16_test 109 통과(summarize16 포함) · selftest16 112 통과 | 같은 커밋에 포함 |
| 11-08 | task 05 로컬 회차 착수: 실제 앱(bootTestRun · loadtest · 실제 눈금 TTL 5분 · θ 69 · M 1) + paid-flow.js 2/s × 60초(120세션) · 스크립트 `scratchpad/lr16/lr16.sh` | 실행 중 |
| 11-08 | 코드 리뷰 루프 1 packet: base 218ed2d5a · HEAD d714749c · 16파일 +4005 · untracked 0 · OUT=`scratchpad/rvm16-jNOo` · 미러=`scratchpad/mirm16-pE98` · 보안 스캔 redact 후 0 · codex ∥ Opus | 실행 중 |
| 11-08 | 루프 1 codex 회수: 높음 2(F1 selftest가 FIXED=False를 영구 기대 · F2 G-비용 결측이 '보고 없음'으로) · 중간 4(F3 ss 실패 0 · 포트 미제한 · F4 sid→흐름 배정 미검증 · F5 재선점 행 수 미대조 · F6 pgss가 'UPDATE seat_hold' 시작만 셈) · OQ 2 | Opus 대기 |
| 11-08 | 루프 1 Opus 회수(토큰 미회수 — 알림 대기): 높음 1(F1 ⓒ 흐름 payment-start 5xx면 요약기 TypeError — 재현) · 중간 2(F2 race 양성 대조 없음 · F3 paid 환경 점검 창이 도착 구간뿐) · 낮음 6 · OQ 5 → 종합 L1-1~14(codex F1~6과 합침 · 겹침 2) · 확정 띠 OQ는 코드가 맞음(서버 :now ∈ [송신, 수신] — 명세 식 정리만) · 수정은 구현 워커 재개로 위임 | fixed L1 |
| 11-08 | 루프 1 codex 종합 감사: 이견 5 — 새 구멍 1(ⓒ 끔 흐름 pf='deadline'을 검증 없이 결제 실패로 → 억울 과소) · L1-4 상향 · cost 창 근거 부족 · L1-9 경로 귀속 불가 · 재선점 행 완전성 → L1-15 · L1-16 신설 · L1-4 · 8 · 9 조정 · 워커에 추가 전달 · Opus 토큰 256,455 | fixed L1 |
| 11-08 | task 05 로컬 ① 회차 1차: 내 스크립트 결함(env 파일을 셸로 읽어 FLOWS JSON 따옴표 소실 → k6 시작 실패 rc 107) — 하네스 결함 아님(run16은 배열로 넘김) · shlex 인용으로 고쳐 재실행 | 재실행 |
| 11-08 | task 05 로컬 ① 회차(실제 앱 · 실제 눈금 · 2/s × 60초 · 120세션 · k6 447초 · 커밋 d714749c 요약기 paid_rep로 분류): 무효 사유 0 · 결측 0 · 흐름 n 29(성공 28 · 억울 1) · nc 28(성공 20 · 결제 실패 8) · b 28(성공 22 · 결제 전 거부 2 · 억울 4) · bc 28(성공 25 · 실패 3) · nd 7(마감 전 5 거부 0) · **G-모형 4흐름 모두 일치(띠 안 허용 0)** · 서버 pay_start 56 = k6 200 54 + 409 2 · rejected 5 = k6 비2xx 5 · CONFIRMED 100 = 성공 100 · 판정기 0 · δ̂ p99 15ms · max 192ms | 앱 ↔ 시나리오 계약 확인 |
| 11-08 | task 05 로컬 ④ 회차 시작(같은 방식 · W 611 · 흐름 e 54 · ec 53 · ed 13 · 커밋 사본 `scratchpad/lrsum-8CTq`) | 실행 중(약 30분) |
| 11-08 | 루프 1 수정 회수(구현 워커 · 토큰 누적 507,560): L1-1~16 반영 · outcome16 무변경(L1-1은 요약기에서) · selftest16 --campaign 163 · blind 109 · ADR-015 selftest --campaign 0 · 메인 결정 2(paid TIME_WAIT 결측은 무효 아님 — cost 지표 · ④ cost 창 시작 = 홀드 99% 도달 500초 → 캠페인 9.4시간) · 새 요약기를 로컬 ① 실데이터에 재실행 → 무효 0 · 새 대조(흐름 배정 · pf 재계산 · 확정 송신 · 재선점 행) 불일치 0 | 커밋(fix · test) |
| 11-08 | 루프 2 packet: base 218ed2d5a · HEAD 0ed184f2 · 루프 1 수정 diff 참고 · untracked 1(repo 루트 k6-summary.json — 내 로컬 회차가 cwd에 남긴 산출물 · packet 밖 · 리뷰 프롬프트에 run16의 k6-summary 경로 확인 추가) · OUT=`scratchpad/rvm16b-Gdhr` · 미러=`scratchpad/mirm16b-gL8N` · 스캔 0 | codex ∥ Opus 실행 중 |
| 11-08 | task 05 로컬 ④ 회차(실제 앱 · W 611 · θ 1 · M 1 · 120세션 · k6 813초 · 새 요약기): 무효 0 · e 54 성공 54 · ec 53(성공 51 · 결제 실패 2) · ed 13(마감 전 10 거부 0) · **억울 0(W = CAP + M + 여유 — 구조적)** · G-모형 2흐름 일치 · extend{extended} 120 = k6 200 120 · CONFIRMED 115 = 성공 115 · 판정기 0 · δ̂ p99 26ms · 재선점 never_ratio 1.0(④ 연장이 좌석을 쥔다 — 다른 사용자 비용) | 앱 ↔ 시나리오 계약 확인(④) |
| 11-08 | repo 루트 k6-summary.json = 내 로컬 스크립트가 OUT_DIR을 안 넘겨 cwd에 쓴 산출물(codex 루프 2가 run16 → OUT_DIR=$DIR → common.js:83 → 요약기 회차 폴더 읽기 연결 확인) · 미추적 · 삭제 | 정리 |
| 11-08 | 루프 2 codex: 중간 3(F1 N16-1 경로 쓰기 직접 검사 아님 · F2 campaign16 --id 없이 같은 날 · SHA면 기존 캠페인 재개 · F3 selftest가 자리표시 W 611을 단정) · OQ 1(ⓒ 경계 흐름이 ① ⓑ 없는 경로만 — payment-start 출처 마감은?) | Opus 대기 |
| 11-08 | 루프 2 Opus 회수(토큰 315,722): 높음 1(R2-1 g_model False를 탈락으로 → 결함 증거가 묻힘 · 명세는 '원인 조사 · 판정 불가' — 내 인터페이스 계약 C절이 명세와 어긋났고 blind 사례도 그것을 따름) · 중간 1(R2-2 cost 선점 5xx는 유효+실패 · paid는 무효 · race는 None — 셀마다 다름) · 낮음 4(R2-3 pgss 필터 · 절단 · R2-4 10시간 관문 없음 · R2-5 확정 띠 식 명세 ↔ 구현 · R2-6 OUT_DIR 기본값) · OQ 4 → 종합: 채택 R2-1~6 + codex F2 · F3 · 기각 codex F1(세 겹 판정 — L1-9 감사 결론) · 경계 흐름 OQ 기각(④ ed가 payment-start 출처 마감 · 같은 PayBy · 같은 deadline 식) · 인터페이스 C절 정정(g_model False = 판정 불가 + 원인 조사) | 수정 위임 |
| 11-08 | 루프 2 codex 종합 감사: 이견 2 — L2-9 잘못 기각(경로별 쓰기 증거 없음 — 높음) · L2-10 잘못 기각(①+ⓑ+ⓒ 마감 출처 = payment-start · nd는 선점 201 — 중간) → 둘 다 채택으로 뒤집음: 격리 탐침(예열 뒤 payment-start만 P회 · 앞뒤 pgss · 표 통계 증분 0) · conditional 경계 흐름 bcd 추가 · 명세 문구 정정 2건(확정 띠 식 · 세션 행 파일) | 워커에 추가 전달 |
| 11-08 | 루프 2 수정 회수(구현 워커 · 누적 토큰 561,491): L2-1~4 · 6~10 반영 · selftest16 --campaign 186 · blind 109(test_c_needs_pair_g_model 기대값만 정정된 계약으로) · ADR-015 selftest 0 · bash -n · node --check 0 · dry-run 9.47시간(bcd 흐름 · 탐침 30초 × 3) · 탐침 판정은 쓰기 rows 증분(배치 0행 DELETE 호출로 거짓 실패 없게) · 남은 자리표시: PROBE_HOLDS · ALLOWED_WRITES · 탐침 통계 반영 대기 2초는 스모크 확인 | 커밋(fix · test · docs) |
| 11-08 | 루프 3(마지막) packet: base 218ed2d5a · HEAD 3ac41878 · 루프 2 수정 diff 참고 · 정정된 인터페이스 · untracked 0 · OUT=`scratchpad/rvm16c-mmwd` · 미러=`scratchpad/mirm16c-KUjI` · 스캔 0 · 앱 전체 테스트(θ < TTL 반영) 병행 | codex ∥ Opus 실행 중 |
| 11-08 | 루프 3 회수: codex 높음 1 · 중간 2(F1 ④ 예측이 응답 만료로 덮여 순환 · F2 payment-start 200 holdId 미검사 · F3 choose가 δ̂ 결측을 0으로) · Opus 높음 1 · 중간 2(R3-1 탐침이 PG16 통계 반영 지연으로 거짓 실패 · R3-2 bc 띠 안 거부에서 요약기 TypeError(재현) · R3-3 g_model False + 다른 관문 False = 탈락 → 뒤 pick(재현)) · OQ 5 → L3-1~6 채택 · 수정 위임 | **루프 3에 신규 채택 → 최대 3루프 도달 · 수정 뒤 형식상 review unresolved — 사용자 판단 대상** |
| 11-08 | 루프 3 수정 회수(구현 워커 · 누적 토큰 603,250): L3-1~6 반영 · selftest16 --campaign 198 · blind 109(e_after 사례 3개 그대로 통과) · ADR-015 selftest 0 · dry-run 9.54시간(탐침 60초) · 메인 대조: 로컬 ①·④ 실데이터 재실행 → 무효 0 · 실패 자료 0(extend_value_mismatch 0 = 서버가 정책대로 연장) · G-모형 불일치 0 | 커밋 2cf7b812 · 95e54111 |
| 11-08 | 루프 3 수정 codex 타깃 재점검: 신규 1(L4-1 연장 응답 만료 대조가 경계 흐름 continue 뒤 — ed 미검사) → 메인 수정(검사를 경계 분기 앞으로) + 회귀 사례 · selftest16 --campaign 199 · blind 109 | 커밋 5936dcd5 · 7f3c7b96 |
| 11-08 | 앱 전체 테스트(θ < TTL 반영 · --rerun-tasks · 44분) | 213클래스 · 1165 · 실패 0 · 오류 0 · 건너뜀 24(이전 1161 + Adr016ThetaTtlStartupTest 4) |
| 11-08 | 리뷰 상태: 3루프 + 재점검 — 루프 3에 신규 채택 6 · 재점검 신규 1(고침) → **최대 3루프 도달 · 형식상 review unresolved · 미해소 open 0** · 남은 자리표시(PROBE_HOLDS · ALLOWED_WRITES · 탐침 대기 12초 · θ · W · M · 도착률 · 경계 격자 · 창)는 .164 스모크 뒤 측정 전 고정 커밋 대상 | 사용자 판단 대기 |
| 11-08 | 사용자 판단: '루프 4 더 · 스모크와 병행'(리뷰) · '.164 아직 안 됨' → 루프 4만(상한 초과 · 사용자 승인) · 스모크는 리뷰 뒤 다시 확인 | 루프 4 packet: base 218ed2d5a · HEAD 7f3c7b96 · 루프 3 수정 diff 참고 · OUT `scratchpad/rvm16d-*` · 스캔 0 |
| 11-08 | 루프 4 회수: codex 높음 1 · 중간 1(F1 경계 흐름 — 먼 격자 하나만 성공해도 통과 · F2 재선점이 실제 만료가 아니라 반복 시작 기준) · Opus 중간 1 · 낮음 1(R4-1 결함 사유 세션이 g_model False → L3-4 덮어쓰기로 탈락이 판정 불가로 — 루프 3 수정이 만든 경로(재현) · R4-2 x=0 격자는 늘 늦게 깨어 마감 뒤 송신) · OQ(console-output 형식 — 로컬 실데이터 2회차 깨진 줄 0으로 확인 · 닫음 · 모형 net 0 — 고르기용이라 유지) → L4-2~4 채택 · 인터페이스 C절 g_model 정의 정정 · 수정 위임 | fixed L4 |
| 11-08 | 루프 4 수정 회수(구현 워커 · 누적 토큰 639,795): L4-2~4 반영 · selftest16 --campaign 210 · ADR-015 selftest 0 · blind 1건 실패(test_non_category_observed — 정정 전 계약 고정) → 메인이 정정된 계약으로 사례 수정(판정 밖 → True · 판정 가능 0 → None) · blind 109 통과 · 로컬 실데이터 2회차 paid_rep: 실패 자료 0 · G-모형 · 경계 격자 True · 옛 데이터라 t1 없음 → rehold-schedule-missing(새 하네스는 늘 기록) | 커밋 5feb9fa0 · f0be345f |
| 11-08 | 루프 4 수정 codex 타깃 재점검: L4-4 해소 · 신규 중간 3(L4-R1 경계 송신 검사 시각 ≠ 기록 시각 · L4-R2 송신 표본 0이 False로 탈락 · L4-R3 재선점 이른 시작 미검사) → 메인 직접 수정(작고 범위 명확) + 회귀 사례 3 · selftest16 --campaign 통과 · blind 109 | 커밋(fix · test) · **리뷰 상태: 4루프 + 재점검 — 매 루프 새 실결함(감소 추세 16 → 10 → 6 → 4 → 재점검 3) · 미해소 open 0 · 형식상 review unresolved — 사용자 판단** |
| 11-09 02:19 | 사용자 ".164 쓸 수 있어 · 진행하자" → task 06 착수. FIXED 관문이 스모크도 막아 run16.sh --smoke 추가(b7092642 — FIXED 예외 · smoke16- id만 · 캠페인 안 거부 · --cost-rate는 스모크 cost 전용 · dry-run 5조합 확인 · selftest16 OK). .164 확인: 재부팅 6분 · 사용자 컨테이너 3 정상 · seatlab 없음. cost 스모크 도착률 100/s — 300/s는 미리 만든 VU 70,095 × 0.27~0.30 MiB ≈ 19~21 GB > PC 가용 23 GB의 안전 여유(스왑 3 GB 사용 중) | 스모크 시작: paid ① · paid ④ · race ① · cost ④(100/s) · id smoke16-20261109 · 러너 scratchpad/smoke16.sh |
| 11-09 04:2x | .164 스모크 4회 끝(SMOKE_DONE · 모두 status ok · seatlab만 down -v · 사용자 컨테이너 3 무접촉). paid ①: δ̂ p99.9 8ms(n 4,471) · err 34.8ms · G-모형 4흐름 일치 · TW 368 · 재선점 일정 늦음 0 · 빠짐 0 · paid ④: δ̂ p99.9 8ms(n 2,406) · err 19.7ms · G-모형 2흐름 일치 · TW 237 · race ①: 실패 0 · api_double 0 · cost ④ 100/s: 8만 세션 · 드롭 0 · 정지 0 · TW 220 · PC 가용 최저 15.4GB(기준 약 23.5GB → VU 약 2.3만 개에 약 8GB) → 300/s(VU 약 7만) 불가 확정. model16 --choose(δ̂ 6,877개 · err 0.0348): M 1 · W 611 · θ_ext 1 · θ_cond 69(결제 전 거부 4.3% + 결제 실패 0.3% · 억울 9.9%) — 자리표시와 같은 값. 스모크가 찾은 하네스 오판 3(07에서 수정): S1 N16-1(a) — JPA FK 채우기 `update seat_hold set seat_id=$1 where id=$2`(= 선점 수)를 ① 쓰기로 셈 · S2 격리 탐침 pgss가 배경 작업 표(counter_reconcile_run · counter_safety_run)를 셈(좌석 4표 증분 0 · 양성 대조 통과) · S3 경계 격자 x=0은 마감 전 송신 25~41%(nd · bcd · ed 모두) → BOUNDARY_MIN_FRAC로 늘 판정 불가 | 다음: 측정 전 고정 값 사용자 확인 → task 07 |
| 11-09 11:44 | 사용자 고정 값 확인('이대로 고정') · 수정 방향 확인('제안대로') · '.164 실험 바로 진행' → task 07: 1fc3e22f(요약기 S1~S3) · cc265e4f(사례 S1 · S2 · S3 + 기존 사례 2 정정) · 022b42b8(FIXED = True · BACKGROUND_WRITES · SEAT_HOLD_FK_FILL) · selftest16 --campaign 통과 · blind OK · 스모크 원자료 재요약 3회차 fail 0 · ⑥ 근거: 앱 · 빌드 마지막 변경 114502ee 뒤 전체 테스트 1165/0(11-08 14:38) · diff 0. **순서 변경(사용자 '바로 진행')**: 고정 커밋 듀얼 1패스를 캠페인과 병행(codex ∥ Opus · 미러 mirm16e · 보안 스캔 0) — 사유: 수정 3건이 요약기 판정이라 결함이면 원자료 재요약으로 고침 · run16 실행 경로 결함이면 재측정 | ② 캠페인 스모크 csmoke16-20261109 시작(첫 자리 끝 · 둘째 진입 뒤 중지) → 본 캠페인 |
| 11-09 12:0x | 고정 커밋 듀얼 1패스 회수(codex EXIT 0 · Opus 워커) — 실행 경로(run16) 재측정 결함 0(Opus 확인 · 수집 원자료로 전부 재요약 가능) → 캠페인 그대로 진행. 종합: FX-1 채택(codex F2 · Opus 중간) fk_fill_rows = seat_hold n_tup_ins 증분 요구 · FX-2 채택(codex F4) x = 0 마감 전 송신 ≥ 1이어야 판정(아니면 None) · FX-3 채택(Opus 낮음) 표 캡처에 스키마 한정 · 따옴표 · MERGE · FX-4 채택(codex F3 · Opus 낮음) rep_summary에서 plan.smoke 또는 not fixed = invalid · FX-5 채택(Opus 낮음) 자리표시 주석 · RESULT 문구 N16-1 정의(FK 채우기 뺀 증분) 정정 · FX-R 기각(codex F1 — payment-start는 readOnly라 쓰기 시도 = 5xx(N16-1 (c)) · 새 별도 트랜잭션 회귀 가정 필요). 사용자 '그렇게 진행'(11-09) → FX-1~5는 캠페인 뒤 요약기 수정 + 원자료 재요약 · 결과 문서에 수정 전 · 뒤 판정 병기 · F3 운영 방어: 캠페인 id campaign16-20261109 | 다음: 캠페인 스모크 둘째 자리 진입 → 중지 → 본 캠페인 |
| 11-09 12:14 | ② 캠페인 스모크 csmoke16-20261109: 첫 자리 paid ext rep1 ok(12:13:58) · 둘째 자리 진입 확인 뒤 세션 그룹 TERM(남은 프로세스 0 · .164 seatlab down -v · 사용자 컨테이너 3 무접촉) → task 08 본 캠페인 시작(campaign16-20261109 · 022b42b8 · 시작 조건 ⓪~⑥ 통과 · 21회차 예상 9.54시간 · 러너 scratchpad/campaign16-run.sh) | 진행 중 — 재개는 같은 명령(--id campaign16-20261109) |
| 11-09 13:0x | FX-1~5 구현(캠페인 무간섭 — 별도 worktree scratchpad/wt16fx · 로컬 브랜치 fix/adr-016-postfix-summarizer · 4e428358 · 1683c8e1 · d481d98a): selftest16 --campaign 통과(FX-1~4 사례 + 기존 2 정정) · blind OK · 실데이터 재요약 시험: 캠페인 paid ext rep1 ok · fail 0 · ed grid_ok True(x = 0 송신 7) · 스모크 paid ① 사본(fixed · smoke 표지 바꿔) fk 5,319 = ins 5,319 · fail 0 · 원 스모크 폴더는 FX-4로 invalid:plan-not-fixed(의도) | 캠페인 진행 중 · 끝나면 이 요약기로 원자료 재요약 · 결과 문서에 고정 요약기 판정과 병기 |
| 11-09 17:45 | 사용자 '.164 써야 함 · 저녁에 이어서' → 캠페인 campaign16-20261109 중지: 12/21 ok(마지막 cost cond+b rep2 17:21:49) · 13번째 cost ext rep2 진행 21분에 세션 그룹 TERM(남은 프로세스 0) · .164 seatlab down -v(probe 포함 · 사용자 컨테이너 3 무접촉) · 끊긴 cost ext rep2는 status 없음 → 재개 시 하네스 시도 1회로 세고 attempt2로 다시 돎(하네스 재시도 한도 2 안) | 재개: scratchpad/campaign16-run.sh 그대로(--id campaign16-20261109) · 남은 9자리 약 4.3시간 |
| 11-10 00:53 | 사용자 '다시 진행' → 캠페인 재개(같은 러너 · --id campaign16-20261109). .164 재부팅 직후(가동 0분) · 시계 동기화 양쪽 yes · 시작 조건 ⓪~⑥ 통과 · cost ext rep2 attempt2부터 · 러너 출력 로그는 덮어써짐(이력은 결과 폴더 CAMPAIGN.log에 남음) | 진행 중 — 남은 9자리 |
| 11-10 05:15 | 캠페인 campaign16-20261109 끝(CAMPAIGN_EXIT=0): 21/21 ok(무효 0 · 재측정은 중지로 끊긴 cost ext rep2 attempt2 1회) · 결론 **pick · ext+c**(고정 요약기 022b42b8). 마지막 cost 회차 seatlab 스택이 .164에 남아 down -v(probe 포함 · 사용자 컨테이너 3 무접촉). 보강 요약기(fix/adr-016-postfix-summarizer 4e428358 · 1683c8e1 · d481d98a) 재요약 — 사본 scratchpad/resum16-final(원본 무변경): paid · cost 15회차 전부 ok 유지 · 캠페인 결론 동일(pick · ext+c) · RESULT.md 차이는 N16-1 (a) 문구 1줄뿐 · paid ① 3회차 외래키 채우기 rows = seat_hold n_tup_ins 증분(5318 · 5293 · 5313) · 나머지 n_tup_upd 0 · 경계 grid_ok 전부 True | 다음: 보강 브랜치 합치기 → task 09 결과 · 결정 문서 |
| 11-10 05:3x | 보강 브랜치 ff 합침(022b42b8..d481d98a · 합친 뒤 selftest16 --campaign 통과 · blind OK) · worktree · 로컬 브랜치 정리 | task 09 시작 |
| 11-10 06:xx | task 09: ADR-016 §실험 결과(결과-1~6) · §결정 작성 · 상태 줄 갱신 · 결과 요약 파일 커밋 fca11c27c(87파일 · 원자료 gz · 로그 제외 · 누출 스캔 0) · 결과 문서 듀얼 1패스 packet: base fca11c27c · 대상 ADR-016 문서 diff · OUT=`scratchpad/rv16r-OLNQ` · 미러=`scratchpad/rvm16r-fHti`(보안 스캔 — 비밀값 후보 2,960 미러 안 redact 후 0 · 키패턴 0) · codex ∥ Opus | 실행 중 |
| 11-10 07:xx | 결과 문서 듀얼 1패스 회수: codex(EXIT 0) F1~F4 · Opus 워커(185,487 토큰) F1~F9 + OQ 4 → 종합 RR16-1~8 전부 채택 · 고침(G-모형 = 세션 대조 19,800 bad 0 · 재선점 해석 = 만료 홀드 전부의 몫 · ④+ⓒ 짝 nc는 ⓒ 결제 실패 좌석 · δ̂ = 응답 시간 상한 · 최대 수명 T + W ≤ 851초 · PC TIME_WAIT · 비용 수용은 사용자 결정 대기 · 7.3만~8만 · cost 열 2개) · codex 종합 감사 '없음' + post-fix 재점검 clean(한 호출 — A · B 분리 출력) · NEXT.md 동기화 + 러너 로그 덮어씀 원인(campaign16.sh:109 `tee /dev/stderr` O_TRUNC) | docs 커밋 → 사용자 보고(비용 수용 · 기본값 교체 결정) |
| 11-10 07:xx | 사이클 마감: NEXT.md 갱신 · 측정 로그 1행 · CS 이슈 아카이브는 보류(study-note = 명세 금지영역 — 후보: JPA 단방향 @OneToMany 외래키 채우기 UPDATE · `tee /dev/stderr` O_TRUNC) · 미러 rvm16r 삭제 | 사용자 결정 대기: G-비용 수용 · 앱 기본값 교체 · push · PR |
| 11-10 12:35 | 사용자 결정: ④ + ⓒ 채택 · G-비용(홀드 수명 최대 약 14분 · 재선점) 수용 · 앱 기본값 교체를 이번 작업에 포함(별도 커밋 — 명세 '앱 기본값 변경은 사용자 확인 뒤 별도') · 끝나면 push만(PR 없음) | 기본값 교체 착수 — 영향 조사 = 코드 기본값 + application.yml 교체 후 전체 테스트 |
| 11-10 13:02 | 기본값 교체 진행 중 중단(사용자 이동): 코드 기본값 · application.yml · ops README 2개 · 기동 검사 주석 교체 · 새 계약 테스트 Adr016DefaultModeContractTest · 기본 θ/M = 0 가정 테스트 5파일 명시화(Adr015DefaultMode · Adr015Startup · Adr016ConditionalZeroTheta · Adr016Startup · Adr016ThetaTtl). 전체 테스트(수정 전 코드로 시작)는 사용자 요청으로 중지 — 그때까지 실패 = 위 5파일 해당 칸뿐 | 미커밋 · 재개 = 수정 후 전체 테스트 다시 → 나머지 실패 고침 → 듀얼 1패스 → 커밋 → push |
| 11-13 14:14 | 기본값 교체 전체 테스트(수정본 · 32분 20초) | 214클래스 · 1166 · 실패 0 · 오류 0 · 건너뜀 24(이전 213 · 1165 · 건너뜀 24 — +새 계약 클래스 2 · Adr015 기본값 값 칸 1 이전) · EXIT 0 |
| 11-13 14:15 | 기본값 교체 듀얼 1패스 착수: base 6198f0455 + 작업트리 · OUT = scratchpad/rv16d-mYXY · 미러 = scratchpad/rvm16d-HKX0(k6/*/results 제외 · 9.1M) · 보안 스캔: 키 패턴 0 · password/token 값 = 코드 식별자 · 사설망 IP(192.168.x — 기존 하네스 문서) 오탐 판정 · codex ∥ Opus 워커 동시 시작 | 회수 대기 |
| 11-13 14:24 | 듀얼 1패스 회수: codex(EXIT 0) finding 0 · verified 7 · OQ 1 / Opus 워커(128,316 토큰 · 224초) F1~F4 + OQ 3 → 종합 RD-1~4 전부 채택(RD-1 문서 처방 · RD-2~4 고침) → 영향 4클래스 재실행 green(26 테스트) → codex 감사 '없음' · post-fix 재점검 clean · 미러 삭제 | 커밋 단계 |
| 11-13 14:25 | 기본값 교체 커밋 9d2bf21b5(main-app · ops 13파일) · ADR-016 상태 줄 · §결정(사용자 채택 · 기본값 교체 · 클라이언트 계약 · 되돌림 · 옛 하네스 주의 RD-1) · NEXT N00b 갱신 → docs 커밋 | push 대기(사용자 지시: push만) |

## 리뷰 ledger

| ID | 출처 | 위치 | 처리 | 상태 |
|---|---|---|---|---|
| RR16-1 | codex F1 · Opus F1 | ADR-016 결과-2 G-모형 | 채택(중간) — 대체된 이항 구간 규칙 → 세션 대조 결과 | fixed(메인) |
| RR16-2 | codex F2 · Opus F2 · F7 · F8 | 결과-5 G-비용 재선점 | 채택(중간) — 만료 홀드 전부의 몫 · 짝 nc = ⓒ 결제 실패 · 창 한정 · 짝 흐름 값 | fixed(메인) |
| RR16-3 | codex F3 · Opus F5 | 결과-2 δ̂ 정의 | 채택(중간) — 응답 시간(상한) | fixed(메인) |
| RR16-4 | codex F4 | 결과-5 · 결정 최대 수명 | 채택(낮음) — T + W ≤ 851초 | fixed(메인) |
| RR16-5 | Opus F3 | 결과-4 TIME_WAIT 열 | 채택(중간) — PC 쪽(서버 대상) | fixed(메인) |
| RR16-6 | Opus F4 | 결과-5 · 결정 · 상태 줄 | 채택(중간) — 후보 선택 자동 · 비용 수용 사용자 결정 대기 | fixed(메인) |
| RR16-7 | Opus F6 | 결과-4 제목 | 채택(낮음) — 7.3만~8만 세션 | fixed(메인) |
| RR16-8 | Opus F9 | 결과-4 표 | 채택(낮음) — payment-start p99 · seat_hold 행 열 | fixed(메인) |
| RR16-OQ | Opus OQ | 결과-1 · 결과-6 | 끊긴 시도 폴더 명시 · 나머지는 메인 실행 기록(log 11-09 17:45 · 11-10 00:53 · 05:15)으로 확인 | closed |
| L4-R1 | 재점검 · codex | paid-flow.js:143-145 | 채택(중간) — 송신 시각 한 번 재서 검사 · 기록 | fixed(메인) |
| L4-R2 | 재점검 · codex | summarize16.py:1101-1103 | 채택(중간) — 송신 표본 0 = None | fixed(메인) |
| L4-R3 | 재점검 · codex | summarize16.py:842-849 | 채택(중간) — 재선점 일정 양방향 | fixed(메인) |
| L4-2 | codex F1 · opus R4-2 | summarize16.py:173 · paid-flow.js:130-145 | 채택(높음) — 경계 격자별 유효 표본 · 늦게 깨면 안 보냄(lw) · 부족하면 None | fixed L4 |
| L4-3 | codex F2 | paid-flow.js:148-165 | 채택(중간) — 재선점 첫 시도 vs 실제 만료 대조 · rehold-schedule-late | fixed L4 |
| L4-4 | opus R4-1 | summarize16.py:766-768 · :146-149 · :227-238 | 채택(중간) — 판정 밖 행은 g_model False 아님 · 원인 조사는 띠 밖만 | fixed L4 |
| L4-1 | 재점검 · codex | summarize16.py:750 | 채택(높음) — 연장 응답 만료 대조를 경계 분기 앞으로 | fixed(5936dcd5) |
| L3-1 | codex F1 | outcome16.py:142 · summarize16.py:757-761 | 채택(높음) — ④ 예측은 정책 구간 · 응답 만료는 별도 대조(extend_value_mismatch) | fixed L3 |
| L3-2 | opus R3-2 | summarize16.py:754-761 · outcome16.py:150 | 채택(중간) — 마감 입력 없으면 PAID · 요약기 예외 방지 | fixed L3 |
| L3-3 | opus R3-1 | run16.sh:166 · :172 · summarize16.py:61-67 | 채택(높음) — 대기 ≥ 12초 · 양성 대조 n_tup_ins == P · pgss rows 1차 | fixed L3 |
| L3-4 | opus R3-3 · OQ-3 | summarize16.py:223-232 · selftest16.py:293 | 채택(중간) — g_model False면 다른 관문과 무관하게 undetermined · 행 0건 = None | fixed L3 |
| L3-5 | codex F2 | paid-flow.js:119-122 | 채택(중간) — 200 본문 holdId 존재 · 일치 | fixed L3 |
| L3-6 | codex F3 | model16.py:48 · :87-98 · :183 | 채택(중간) — 측정용 choose는 δ̂ · err 필수 | fixed L3 |
| L3-OQ | opus OQ-2 · codex OQ | summarize16.py:953 · :1033 | 기록 — 회차 단위 G-정합은 의도(같은 서버) · cost payment-start p99 절대 문턱은 측정 전 고정에서 | 고정 커밋 |
| L2-1 | opus R2-1 | summarize16.py:170-190 · :947 · interface C절 · blind16_test.py:735-744 | 채택(높음) — g_model False = 판정 불가 + 원인 조사(명세 §1-3) · 인터페이스 정정 · blind 사례는 정정된 계약으로 수정(사례 결함 아님 — 계약 결함) | fixed L2 |
| L2-2 | opus R2-2 | summarize16.py:567-571 · :798-829 · :887-905 | 채택(중간) — cost 선점 5xx · 시간 초과 = hold-fail 무효 · 재선점 5xx · 시간 초과 = None(race 규칙) | fixed L2 |
| L2-3 | opus R2-3 | lib15.sh:62-65 ↔ summarize16.py:45-56 · :552-558 | 채택(낮음) — ADR-016 전용 pgss 덤프(필터 · 절단 없음) · selftest 입력을 실제 덤프 모양으로 | fixed L2 |
| L2-4 | opus R2-4 | plan16.py:432-443 | 채택(낮음) — est_hours > 10이면 dry-run problem | fixed L2 |
| L2-5 | opus R2-5 · L1-OQa | 명세 δ̂ 절 | 채택(문서) — 명세 식을 구현 식 [송신 − err, 수신 + err]로 정정 | fixed(명세) |
| L2-6 | opus R2-6 | paid-flow.js:374-377 · common.js:8 | 채택(낮음) — OUT_DIR 필수(없으면 throw) | fixed L2 |
| L2-7 | codex F2 | campaign16.sh:74 | 채택(중간) — 자동 ID 결과 폴더가 있으면 중단 · --id 요구 | fixed L2 |
| L2-8 | codex F3 | selftest16.py:67 | 채택(중간) — W 고정값 단정 → W = CAP + M + 배치 + 여유 관계식 | fixed L2 |
| L2-9 | codex F1 | summarize16.py:548 | 채택(높음 · 감사로 뒤집음) — 격리 탐침(payment-start만 P회 · 앞뒤 pgss · 표 통계 증분 0) · 세 겹은 보조 | fixed L2 |
| L2-10 | codex OQ · opus OQ2 | plan16.py:76 · summarize16.py:959 | 채택(중간 · 감사로 뒤집음) — conditional 경계 흐름 bcd 추가(cond+b+c 판정용) | fixed L2 |
| L2-11 | opus OQ1 · OQ3 · OQ4 | summarize16.py:209 · :42 · paid-flow.js:9 | 기록 — none = 판정 불가 문구(L2-1과 함께) · ④ 재선점 ≫는 의도(사용자 '비용 보고') · 세션 행 별도 파일(시계열 폭증 방지 — 명세 문구 정정) | fixed L2 · 명세 |
| L1-1 | opus F1 | summarize16.py:633-640 | 채택(높음) — ⓒ 흐름 payment-start 결함이면 요약기 예외 → G-F 증거가 재측정으로 버려짐 | fixed L1 |
| L1-2 | codex F1 | selftest16.py:45 · :616 | 채택(높음) — FIXED=True면 selftest 깨짐 | fixed L1 |
| L1-3 | codex F2 · opus F6 | summarize16.py:825-835 | 채택(높음) — G-비용 결측 → 조용한 '보고 없음' | fixed L1 |
| L1-4 | opus F2 | summarize16.py:754-807 | 채택(높음 · 감사 상향) — race 양성 대조 · A 규칙 → None | fixed L1 |
| L1-5 | opus F3 | summarize16.py:259-394 · plan16.py:269 | 채택(중간) — paid 환경 점검 창 k6 끝까지 | fixed L1 |
| L1-6 | codex F3 · opus F4 | run16.sh:150-155 · summarize16.py:490 | 채택(중간) — ss 실패 0 · 포트 필터 | fixed L1 |
| L1-7 | codex F4 | summarize16.py:583 · :643 | 채택(중간) — sid → 흐름 배정 검증 | fixed L1 |
| L1-8 | codex F5 | summarize16.py:687 · paid-flow.js:145 | 채택(중간 · 감사 확장) — 재선점 대상 sid마다 결과 행 정확히 하나 | fixed L1 |
| L1-9 | codex F6 · opus OQ1 | summarize16.py:519-521 | 채택(중간 · 감사 조정) — 경로 귀속 불가 → n_tup_upd 0 + 허용 밖 쓰기 0 + payment-start 5xx 0(readOnly가 쓰기를 오류로) | fixed L1 |
| L1-10 | opus F5 | summarize16.py:707-729 | 채택(낮음~중간) — cost 결정표 갈래 · CONFIRMED 대조 | fixed L1 |
| L1-11 | opus F7 | summarize16.py:596-601 · :688-691 | 채택(낮음) — 경계 흐름 제외 · 같은 흐름 비교 | fixed L1 |
| L1-12 | opus F8 · codex OQ2 | model16.py:145-147 · summarize16.py:788 · :845 | 채택(낮음) — 결측 0 채움 제거 | fixed L1 |
| L1-13 | opus OQ4 | run16.sh:238 | 채택(낮음) — offset 실패 0 채움 → abort | fixed L1 |
| L1-14 | opus F9 | selftest16.py:453-527 | 채택(테스트) — G-정합 음성 사례 · 결정표 빠진 갈래 | fixed L1 |
| L1-15 | 감사 · codex | summarize16.py:569 | 채택(높음) — pf 재계산 대조 · ⓒ 끔 확정 송신 수 = 결제 성공 수 | fixed L1 |
| L1-16 | 감사 · codex | plan16.py:54 · :229 | 채택(중간) — cost 창 시작을 홀드 수명 기준 잔여 비율로 보고 · 창 길이는 고정 커밋에서 | fixed L1 |
| L1-OQa | codex OQ1 | outcome16.py:158 | 기각(코드 유지) — 확정 띠 [송신 − err, 수신 + err]가 맞다 · 명세 식 정리 | closed |
| L1-OQb | opus OQ2 · OQ3 · OQ5 | k6 console-output · err 드리프트 · cost 창 시작 | 기록 — 스모크에서 확인(OQ2) · M 산정이 덮음(OQ3) · 영향은 L1-16으로 재계산(감사 — 0.3% 근거 부족) | 스모크 확인 |
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


### 기본값 교체(2026-11-13 · 듀얼 1패스 · loop 1)

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|---|---|---|---|---|---|---|
| RD-1 | 1 | opus | k6/ADR-015/compose.confirm.yml:33 · plan15.py:167 · :208 · campaign16.sh:132-134 — 새 기본 M 1초로 ADR-015 하네스 ① 기동 거부 · 나머지 config 불일치(ADR-016 race 재측정 포함) | 채택(문서 처방 — ADR-015 하네스는 범위 밖) | fixed(docs 커밋) | 1 |
| RD-2 | 1 | opus | PaymentStartController.kt:53 · HoldStrategyStartupCheck.kt:112 · BootSupport.kt:121 · Adr015StartupContractTest.kt:86 — '기본 conditional' 잔존 | 채택 | fixed | 1 |
| RD-3 | 1 | opus | application.yml:34 — 되돌림 안내가 θ · M 0 복원을 빠뜨림 | 채택 | fixed | 1 |
| RD-4 | 1 | opus | Adr016StartupContractTest.kt:112 — 모드 키 없는 ① 사례(검사 순서 의존) | 채택 | fixed | 1 |
| RD-OQ | 1 | opus · codex | 전체 테스트(해소 — 실행 결과) · 홀드 수명 운영 메모 · payBy 강제(ADR §결정에 기술) | open question | 해소/문서 | 1 |

## 생략한 검증

- (없음)

## 완료 요약

- (미완)
