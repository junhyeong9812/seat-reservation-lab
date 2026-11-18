# 작업 기록 — ADR-017 2단계 측정

> 명세: `requirement-spec.md` · 브랜치 `feat/adr-017-measurement`(기준 c807ce683 — PR #16 브랜치 위) · stakes 높음 · MODE auto

## 타임라인 (시각 | 사건 | 결과)

| 시각 | 사건 | 결과 |
|---|---|---|
| 2026-11-14 10:2x | 2단계 인터뷰(질문 11 — 전부 권장안) · 명세 작성 | requirement-spec.md |
| 2026-11-14 10:40 | 사용자 합의 "응 합의, 진행해줘" · set-state spec-approved · mode auto · 브랜치 feat/adr-017-measurement 생성(c807ce683) | SPEC=1 · MODE=auto |
| 2026-11-14 10:40 | 사전 확인 — 회차 리셋 TRUNCATE에 V15 표 3개 포함(LoadtestDataService.kt:49-53) · mock-pg 엔드포인트(intents · pay · authorize · capture · close · payments · ledger · admin/faults · admin/reset) · Faults는 고정 delayMs만 | 하네스 쪽 앱 변경 불필요로 보임 |
| 2026-11-14 10:45 | task 01·02 + INTERFACE.md 워커 위임(Opus · 백그라운드 · 기준 c807ce683) | 진행 중 |
| 2026-11-14 11:04 | task 01·02 워커 회수(10:41~11:04 · 337k 토큰) — compose.pay17 · lib17.sh · hhash17 · plan17.py · lib17.js · paid-flow17.js · INTERFACE.md(519줄) · 로컬 20세션 k6(S · pg 주입) 분류 일치 · down -v | 메인 재확인: 기존 추적 파일 변경 0 · dry-run 재실행 = 예상 8.46시간 · 최악 12.51시간(배수 상한 600초 매번) · seatlab 컨테이너 0 |
| 2026-11-14 11:05 | 워커 관찰 2건 — ① mock-pg GET /payments/{uid}는 주입 지점 밖(주입 = capture · close만 가능 · 명세 §0 '조회' 주입은 앱 변경 필요 → 앱 변경 없음 원칙대로 capture · close만 · 측정 전 고정 때 사용자 확인) ② 시드 좌석은 전부 section A(S '같은 section'은 자명) · S 대체가 낮은 id부터 집어 세션 좌석을 여유 좌석 뒤에 둠 | 기록 · 측정 전 고정 확인 항목 |
| 2026-11-14 11:06 | task 05 blind 워커(Opus) · task 03·04 구현 워커 이어서 — 병렬 | 진행 |
| 2026-11-14 11:16 | task 05 회수(11:05~11:16) — blind17_test.py 136 테스트 · 구현 미열람 · 도중 생긴 outcome17 · summarize17에 136/136 OK(테스트 쪽 결함 3 고침 — 기대값을 구현에 맞춘 수정 없음) · 계약 모호점 13 | 모호점 → 구현 워커에 전달(INTERFACE 명확화) · 구현 확정 뒤 재실행 |
| 2026-11-14 11:37 | task 03·04 회수(11:05~11:37 · 누적 518k 토큰) — run17 · campaign17 · actsnap17 · outcome17 · summarize17 · selftest17 · dump17.sql · compose.local17 · INTERFACE 명확화 13(구현 고침 #1 shape · #7 X·Y 출처 = plan.json · #9 상위 insufficient면 choice null) · 로컬 D/kill 20세션 run17 끝까지 status ok · record 18 = 18 | 메인 재확인: selftest17 --campaign rc 0(238/0) · blind17 136/136 OK · 추적 파일 변경 0 · seatlab 컨테이너 0 |
| 2026-11-14 11:40 | 남은 틈 — pg 셀 로컬 회차 없음 · base 셀 G3 입력 실제 회차 없음 · blind 쪽 '바탕 회차 ok' 사례 없음 | task 06에서 메움 |
| 2026-11-14 11:39 | 남은 틈 메우기 — 구현 워커: 로컬 R/pg · S/base 회차 · blind 워커: 바탕 ok 사례 · 명확화 문구 직접 단언 | 병렬 진행 |
| 2026-11-14 11:45 | blind 2차 회수 — 164/164 OK · 바탕 회차 ok 9조합(후보 3 × 셀 3) · 무효 사유 '바탕 → 결함 하나 → 정확히 그 사유' · 구현 결함 0 · 테스트 결함 3 고침 · 남은 모호 7(연쇄 사유 — 포함만 단언) | 연쇄 사유는 판정(무효 여부)에 영향 없음 → 수용 · 기록 |
| 2026-11-14 12:07 | 구현 워커 회수 — 로컬 R/pg · S/base 60세션 run17 끝까지 · 둘 다 status ok · 주입 증거(CLOSE attempts 2 × 3 · retry 지표 3) · DEAD 0 · G3 입력(DB CPU 19.3% · confirm p99 10.4ms · payment-start p99 404.7ms — 로컬 값) · record 54 = 54 · 코드 변경 없음 | 메인 재확인: summary17 두 건 status ok · g1 true · record 일치 · seatlab 0 |
| 2026-11-14 12:10 | 하네스 code 커밋 7c6a1acef(16파일 +5720) | — |
| 2026-11-14 12:12 | 리뷰 루프 1 — BASE c807ce683 · OUT=$SP/rv17-loop1(packet · mirror · results) · 보안 스캔 0건 · Opus 리뷰 A ∥ codex(medium · read-only · cwd 미러) | 진행 |
| 2026-11-14 12:32 | 루프 1 회수 — codex 6(high 3 · medium 3) · Opus 4(high 1 · low 3) + OQ · 메인 종합(results/synthesis-loop1.md) · codex 종합 감사 rc 0 — 이의 4(L1-2 키 존재 · L1-4 Σattempts 증거 기각 · L1-9 계약 모순 · L1-10 재검토) | 채택 9 · 사용자 확인 2(L1-10 · OQ-a) |
| 2026-11-14 12:50 | L1-1~9 수정 회수(구현 워커 · 미커밋 +326/−50 · 5파일) — L1-4 Java Random 재현이 실제 mock-pg 결정열과 6/6 일치(처리 1건 = ops 1 증가 코드·실측 확인) · 새 사유 inject-undecidable · app-restarted · g2-time-missing · KILL_TOL_MS 5000 | 메인 재확인: selftest17 289/0 rc 0 · blind 67 실패(계약 변경 연쇄 — 바탕 고정물 필수 필드) → blind 워커에 INTERFACE diff 전달 |
| 2026-11-14 12:58 | blind 3차 회수 — 193 중 192 통과 · 남은 1 = inject.processed 정의 모호(구현 = 원장 ops · 문서 해석 = 걸어서 만난 처리 수) | 메인 확정: processed = 걸어서 만난 처리 수(summarize17.py:178 · INTERFACE §9.2) → selftest17 289/0 · blind 193/193 OK |
| 2026-11-14 13:00 | 루프 1 수정 code 커밋 ce41d1bcf · 리뷰 루프 2 — OUT=$SP/rv17-loop2 · 보안 스캔 0 · Opus ∥ codex | 진행 |
| 2026-11-14 13:20 | 루프 2 회수 — codex 3(1은 OQ-a 중복) · Opus 4 + OQ 4 · 종합(results/synthesis-loop2.md) 채택 9 · codex 감사 이의 2(L2-2 미완료 CAPTURE도 G1 · L2-3 kind_of가 사유 목록에서 env 판별) — 수정 범위 보완 | 루프 2 신규 채택 9 → 수정 뒤 루프 3 |
| 2026-11-14 13:06 | L2-1~9 수정 회수(구현 워커 · 미커밋 +264/−60 · 6파일) — cap = 판정(unfinished_tasks G1) · drain-poll-failed · 환경 사유 이식(pclink14 · pc-mem · dropped · env-send-lag 2000ms · env-stall 측정기 미이식) · 회차 사이 values · sha · hash 대조 · 로컬 R/base ok(pc-link 기록 확인) | 메인 재확인: selftest17 327/0 · blind 214 실패(계약 연쇄) → blind 워커에 interface-l2.diff 전달 |
| 2026-11-14 13:06 | **절단 계약 사건** — 구현 워커가 grep 출력으로 blind17_test.py의 drain-timeout 단언 7줄을 우연히 봄(의도 아님 · 그 내용으로 맞추지 않았다고 보고). 방향은 구현→테스트 열람(blind 쪽은 구현 미열람 유지) | 기록 · 영향 작음(해당 사유는 이번 계약에서 폐지) · 이후 grep에서 제외 지시 |
| 2026-11-14 13:12 | blind 4차 회수 — 213/213 OK · 바탕 9조합 ok · 테스트 결함 2 고침 · 구현 결함 0 | 메인 재확인: selftest17 327/0 · blind OK |
| 2026-11-14 13:14 | 루프 2 수정 code 커밋 91c31f9a6 · 리뷰 루프 3(마지막) — OUT=$SP/rv17-loop3 · 보안 스캔 0 · Opus ∥ codex | 진행 |
| 2026-11-14 13:40 | 루프 3 회수 — codex 4(F1 조회 주입 중복 · F2 시간 관문 · F3 CSV 짧은 행 · F4 원장 ops) · Opus 4(A1 medium cap 덤프 경합 · low 3) · 종합(results/synthesis-loop3.md) 신규 채택 7 · codex 감사 이의 3(조회 주입 = 미해결 high로 명시 · 12시간 = 상한 기준 · L3-1 재시도 소진 사유) | **review unresolved**(최대 3루프 · 신규 채택 > 0) → 사용자 결정 요청 |
| 2026-11-14 13:45 | 사용자 결정(AskUserQuestion — 전부 권장안): 리뷰 = 고친 뒤 타깃 재점검 1회(user override) · 조회 주입 = capture · close만(명세 정정) · 12시간 = 최악 기준 · 배수 상한 300초 · section 하나(명세 정정) | 명세 §0 Q/A 2 · 문구 정정 · OQ-a · L1-10 · OQ-c2 해소 |
| 2026-11-14 18:06 | L3-1~7 + 사용자 결정 반영 회수(구현 워커 · 미커밋 +147/−55 · 6파일) — 덤프 반복(cap 5회 · dump-unstable) · cap 회차 app-metric 보고만 · CSV 행 손상 · 원장 ops 하한 · 주입 = 재현 AND 관측 · kill valid 분리 · DRAIN_CAP_S 300 · 시작 관문 최악 기준 · 로컬 D/pg 60세션 ok | 메인 재확인: selftest17 349/0 · dry-run 예상 8.46 · 최악 10.26시간 · blind 9 실패(계약 연쇄) → blind 워커에 interface-l3.diff |
| 2026-11-14 18:12 | blind 5차 회수 — 225/225 OK · 바탕 9조합 ok · 구현 결함 0 | 메인 재확인: selftest17 349/0 · blind OK |
| 2026-11-14 18:14 | 루프 3 수정 code 커밋 5614874fe · post-fix 타깃 재점검(codex · OUT=$SP/rv17-postfix · fix-diff + 인접 + 새 테스트) | **신규 finding 0** · ①~⑦ verified(run17.sh:356 · summarize17.py:80,344,409,420,428 · plan17.py:65,173,370 · campaign17.sh:89 · Ledger.kt:119) |
| 2026-11-14 18:30 | 리뷰 마감 — **user override**(최대 3루프 뒤 사용자 결정 '고친 뒤 재점검 1회') · open 0 · 잔여 리스크: 루프 3 수정분은 전체 듀얼 리뷰가 아니라 codex 타깃 재점검 1회만 거침 · .164 스모크가 다음 방어선 | task 06 완료 · 다음 task 07 .164 스모크(사용자 확인 대기) |
| 2026-11-16 20:57 | task 07 착수(사용자 확인 — "164 스모크 진행해줘") · .164 점검(재부팅 직후 · 사용자 컨테이너 3 · 디스크 여유 39G) · --prepare-image 5614874f(app fc67b34a · mockpg bb6f0c53) · 결과 폴더 k6/ADR-017/results/smoke17-164a · R/base 1,100세션 실눈금 시작 | 진행 |
| 2026-11-16 21:15 | R/base 1회(17.5분) → invalid:session-invalid — 세션 6개 pg-0-NOCODE(k6→mock-pg `network is unreachable` 21:05:19~24) · G1 0 · record 984=984 · G2 p99 0.98초(REJECTION 52 · SWEEP 337) · G3 DB CPU 9.4% · confirm p99 15.9ms · payment-start p99 21.6ms · 배수 empty 11.8초 | 원인 조사: PC 유선 eno1이 2026-11-10 18:37부터 PHY 실패(r8169_apply_firmware -110 · phy_poll_reset -110 → Link is Down)로 죽어 있고 PC→.164는 무선 wlo1(192.168.45.x 경유 라우팅)로만 간다 · 끊김 = wlo1 재연결(21:05:21~25) · **pclink14는 eno1의 'Link is Down'만 봐서 무선 끊김을 못 잡음(down 0 — 환경 사유가 하네스 사유 session-invalid로 분류됨)** · .164 seatlab 컨테이너 0 · 나머지 스모크 보류 · 사용자 결정 대기 |
| 2026-11-17 15:55 | 사용자 "진행하자" — PC 재부팅(15:43)으로 eno1 복구 확인(carrier 1 · 192.168.55.114 · .164 경로 eno1 · rtt 0.3ms) → 선택지 1(유선 복구 뒤 재개) · .164 이미지 ID 4개 고정값과 일치 · smoke17-164b 5회차 연쇄 시작(R/base · D/base · S/base · D/pg · S/kill) | 진행 · 남은 과제: pclink14가 eno1 외 경로 끊김을 못 보는 한계(캠페인 전 판단) |
| 2026-11-17 17:19 | smoke17-164b 5회차 회수 — 모두 status ok · 회차당 17.5분 · pc-link down 0 · eno1 carrier 1 유지 · .164 seatlab 0 | G1 전부 0 · record 990=990(kill 959=959) · M17-6 late 거부 220(kill 209) · noconf 종결 55(54) · G2 p99: R/base 0.99 · D/base 1.01 · S/base 0.57 · D/pg 15.3(max 21.6) · S/kill 1.03초 · G3(base): DB CPU R 9.25 · D 9.27 · S 9.22% / confirm p99 R 9.9 · D 12.4 · S 10.0ms / payment-start p99 R 16.7 · D 15.9 · S 17.0ms · 출처: R · D REJECTION 57~58 · SWEEP 327~328 / S REJECTION 0 · 대체 57(비율 0.26) · 주입 D/pg capture 715(fail 75 · unknown 31) · close 385(fail 43 · unknown 16) · attempts 2 = 143 · 3 = 11 · kill 이벤트 2 · 재기동 · 시각 ok · app_dead 57 · left_intents 0 · send_lag max 2.6ms · 배수 empty 11.8~17.6초 | 초안 값 그대로 고정 제안 → 사용자 확인 대기 |
| 2026-11-17 17:40 | 사용자 "그 값으로 고정 커밋 진행해줘" — task 08: plan17 FIXED = True · 숫자 변경 0 · INTERFACE §5 · §7 · §8 [초안] 해제 · §11 문구 · selftest17 --campaign 349/0 · blind17 225/225 · --fixed rc 0 · dry-run rc 0 | 진행 |
| 2026-11-17 18:00 | 듀얼 1패스(task 08): Opus(84k 토큰 · 64초) low 2(INTERFACE:631 L 분포 '정할 값' 문구 · plan17.py:361 '자리표시' 주석) → 채택 · 수정 / codex 1차 rc 124(stdin 대기 — 호출 오류) · 2차 rc 1(--skip-git-repo-check 누락) · 3차 수정본 대상 finding 0(= post-fix 재점검) | 고정 커밋 757c17f88 · 전체 테스트(시작 조건 ⑥) 로컬 시작 |
| 2026-11-17 18:25 | 전체 테스트 1차 = 21초 · 17 up-to-date(실행 안 함 — 증거 불인정) → cleanTest test 강제 재실행 | 진행 |
| 2026-11-17 23:5x | 사용자 ".164 다시 띄워뒀어. 진행해줘" — .164 재기동 확인(사용자 컨테이너 3 · 이미지 유지) · eno1 carrier 1 · --prepare-image 757c17f88(app c40521d2 · mockpg cbed2765 · pg · redis 같음) · campaign17 dry-run: ⓪ ③ ⑤ 통과 | 진행 |
| 2026-11-18 00:07 | 전체 테스트 @757c17f88 강제 재실행 22.5분: main-app 1243/0(건너뜀 24) · mock-pg 24/0 · reconciler 6/0 · 낡은 XML 0 · pccheck14 ok(load1 4.72 · alert-system 11개 기록만) · start-conditions-csmoke 작성 · ② 캠페인 스모크 csmoke17-20261118 시작(첫 자리 끝 · 둘째 자리 진입 뒤 중지) | 진행 |
| 2026-11-18 00:25 | ② 캠페인 스모크 csmoke17-20261118: 첫 자리 D base rep1 ok(00:07:52–00:25:20 · G1 0 · G2 p99 1.0초 · record 990) · 둘째 자리(S base) 진입 확인 뒤 세션 그룹 TERM(남은 프로세스 0 · .164 seatlab down -v · 사용자 컨테이너 3 무접촉) | 통과 |
| 2026-11-18 00:26 | task 09 캠페인 campaign17-20261118 시작(setsid · --id) — 시작 조건 ⓪~⑥ 8줄 모두 통과 · pccheck14 ok(load1 1.8) · eno1 carrier 1 · start-conditions-757c17f8.json | 진행(예상 8.46 · 최악 10.26시간) |
| 2026-11-18 08:18 | 캠페인 campaign17-20261118 종료(00:26–08:18 · 27회차 모두 attempt1 ok · 재측정 0) · plan_fixed · runs_consistent true · G1 R · D · S 실패 0 · G2 모두 통과(base p99 ≤ 1.17초 · pg 최대 20.4초 ≤ 120) · G3 D 실패(확정 p99 범위 [11.92, 12.56]ms vs R [9.57, 10.17] 비겹침 · 중앙값 비 1.22 > 1.10) · S 통과 | pass R · S true, D false → choice **R** |
| 2026-11-18 09:1x | task 10 결과 문서: RESULT.md(campaign17-20261118 · 27회차 summary17 집계) · ADR-017 실험 결과(결과-1~6) · 결정(R · 사용자 채택 · 기본값 교체 대기) · 상태 줄 · 집계 중 확인: S REJECTION 0 = 안전망보다 먼저 온 지연 확정은 전부 대체 성공(PaymentConfirmService.kt:66-84) · 약 10분 = 연장 홀드 끝까지의 대기(paid-flow17.js:123 late = 연장 만료 + L) · 듀얼 1패스 착수 — 미러 scratchpad/rv17/mirror(ADR-017 · INTERFACE · scripts · scenarios · payment 코드 · 결과 요약 · spec) · 보안 스캔 0 | 리뷰 진행 |
| 2026-11-18 09:4x | task 10 듀얼 1패스 회수: codex(medium) 수치 불일치 0 · finding 4 / Opus 워커(145,982 토큰 · 253초) 수치 · 판정 불일치 0 · finding 11 → 겹침 합쳐 T10-1~T10-11 전부 채택 · RESULT.md · ADR 결과 절 수정 · post-fix 재점검 codex 신규 finding 0 · 미러 삭제 | task 10 문서 리뷰 마감 |

## 리뷰 ledger

| id | first_seen_loop | source | 근거 | disposition | status |
|---|---|---|---|---|---|
| L1-1 | 1 | opus | outcome17.py:207,:246-249 · INTERFACE §5 B·D | 채택 — retry c1 미확정 + c2 정상 → integrity(G1 거짓 실패) | fixed |
| L1-2 | 1 | codex(+감사) | summarize17.py:308 | 채택 — v_* 숫자 아님 · 필수 키 없음 무음 0 | fixed |
| L1-3 | 1 | codex · opus | summarize17.py:325-331 | 채택 — G2 시각 결측 무음 제외 · 대체 | fixed |
| L1-4 | 1 | codex · opus · 감사 | summarize17.py:280-284 | 채택 — 주입 증거. 감사: Σattempts > ops는 연결 실패와 구분 못 함 → 메인 처방: Faults 결정열 재현(Java Random(seed·31 + op.hashCode()) · 원장 처리 수까지 걸으며 FAIL 수 = 결정 수 하한) · 재현 불가면 판정 불가 | fixed |
| L1-5 | 1 | codex | summarize17.py:232-237 | 채택 — kill 계획 시각 · k6 창 대조 | fixed |
| L1-6 | 1 | codex · opus | summarize17.py:175-176 · INTERFACE §6.4 | 채택 — act 필드 null · failed_names · process_start_s | fixed |
| L1-7 | 1 | opus | summarize17.py:266-268 | 채택 — kill 셀 pg-not-reset 거짓 무효 | fixed |
| L1-8 | 1 | opus | summarize17.py:360-371 | 채택 — G-비용 보고 누락 2 | fixed |
| L1-9 | 1 | codex OQ · 감사 | INTERFACE §8 · summarize17.py:442-444 | 채택 — G2 표본 0 상태 모순 → 판정 불가(insufficient) 하나로 | fixed |
| L1-10 | 1 | codex · 감사 | plan17.py:167-175 · LoadtestDataService 시드 | 사용자 결정 2026-11-14 — section 하나로 명세 정정 | resolved |
| OQ-a | 1 | opus | MockPgController.kt:35-37 | 사용자 결정 2026-11-14 — capture · close만 | resolved |
| OQ-b | 1 | opus | lib17.sh:62-63 | 기각 — 로컬 D/kill kill.json 2 이벤트 ready · started_at 상이 · L1-6으로 보강 | — |
| L2-1 | 2 | opus | summarize17.py:360-372 · PaymentStartController.kt:42-48 | 채택 — pay_start 503/유실 uid pg-not-reset 거짓 무효 | fixed |
| L2-2 | 2 | opus(+감사) | run17.sh:333-348 · summarize17.py:337-338 | 채택 — 배수 cap도 덤프 · 판정(남은 OPEN · 미완료 작업 = G1) | fixed |
| L2-3 | 2 | opus(+감사) | campaign17.sh:126-127 · run17.sh:274 | 채택 — 환경 사유 이식 · kind_of 목록 판별 | fixed |
| L2-4 | 2 | opus | paid-flow17.js:123-127 | 채택 — 계획 ↔ 실제 송신 지연 | fixed |
| L2-5 | 2 | codex | summarize17.py:78,448 | 채택 — G3 CSV 표본 수 대조 | fixed |
| L2-6 | 2 | codex | summarize17.py:537 | 채택 — 캠페인 고정 값 · SHA · 하네스 해시 대조 | fixed |
| L2-7 | 2 | opus OQ1 | summarize17.py:591 | 채택 — R insufficient 규칙 | fixed |
| L2-8 | 2 | opus OQ3 | summarize17.py:380 | 채택 — 안전망 종결 = DONE | fixed |
| L2-9 | 2 | opus OQ4 | run17.sh:354-357 | 채택 — 원장 재조회 실패와 경합 구분 | fixed |
| OQ-c2 | 2 | codex | plan17 --dry-run | 사용자 결정 2026-11-14 — 최악 기준 · 배수 상한 300초 | resolved(L3-7로 구현) |
| L3-1 | 3 | opus(+감사) | run17.sh:356-361 · summarize17.py:435-440 | 채택 — cap 회차 덤프 중 워커 PG 호출 → 거짓 무효(L2-2가 만든 충돌) · 재시도 소진 사유 명시 | fixed(post-fix clean) |
| L3-2 | 3 | codex | summarize17.py:92 | 채택 — CSV 짧은 행 무음 | fixed(post-fix clean) |
| L3-3 | 3 | codex | summarize17.py:287 | 채택 — 원장 ops 필수 | fixed(post-fix clean) |
| L3-4 | 3 | opus | summarize17.py:428-434 | 채택 — 주입 관측 증거 병행 · 계약 before 문장 | fixed(post-fix clean) |
| L3-5 | 3 | opus | summarize17.py:370 | 채택 — kill.restarted 이름 | fixed(post-fix clean) |
| L3-6 | 3 | opus | summarize17.py:117-133 | 채택 — 죽은 코드 | fixed(post-fix clean) |
| L3-7 | 3 | codex(+감사) | plan17.py:369 · campaign17.sh:88 | 채택 — 시간 관문 상한 기준(감사: 예상 기준이면 명세 변경 필요) | fixed(post-fix clean) |
| OQ-a | 1·3 | opus · codex · 감사 | spec §0 · plan17.py:65 | 사용자 결정 2026-11-14 — capture · close만 · 명세 정정 | resolved |
| F8-1 | task08 | opus | INTERFACE.md:631 | 채택 — L 분포 문구 고정으로 | fixed |
| F8-2 | task08 | opus | plan17.py:361 | 채택 — '자리표시' 주석 | fixed |
| T10-1 | task10 | codex · opus | ADR-017:14 | 채택 — 상태 줄 '사용자 채택 대기'로 결정 절과 일치 | fixed |
| T10-2 | task10 | codex | RESULT · ADR 결과-5 | 채택 — 지연 확정 결과에 app_dead 26 · 28 · 28 | fixed |
| T10-3 | task10 | codex · opus | RESULT · ADR 결과-5 | 채택 — §8 G-비용 누락(attempts 분포 · app_dead shape · 시간 분포 n · p50 · max · 주입 관측) | fixed |
| T10-4 | task10 | codex · opus | RESULT · ADR 결과-2 · 5 | 채택 — G2 = DONE CLOSE 표본 명시 · '약 1초'는 base · kill 한정 | fixed |
| T10-5 | task10 | codex · opus | RESULT · ADR 결과-5 | 채택 — S 대체 비율 해석을 이번 구성으로 한정 · 회차 범위 0.218 ~ 0.301 | fixed |
| T10-6 | task10 | opus | ADR 결과-5 · RESULT | 채택 — uid_conflict · legacy는 base · pg 6회(kill null) | fixed |
| T10-7 | task10 | opus | ADR 결과-5 · 결정 | 채택 — 시간 행 = 회차 p99 범위 · 표본 = REFUNDED · VOIDED 전체 | fixed |
| T10-8 | task10 | opus | ADR 결과-3 · RESULT G3 | 채택 — '원인은 설계 그대로' → 방향 일치 · 요인 미분리 | fixed |
| T10-9 | task10 | opus | ADR 결과-2 · RESULT | 채택 — pg 셀 14.5 ~ 20.4초 · 읽기 타임아웃 3초 · 추정 명시 | fixed |
| T10-10 | task10 | opus | RESULT · ADR 결과-1 | 채택 — 진행 08:17 · 7시간 51분 · 메모리 GiB · 이미지 · 스모크 경로 | fixed |
| T10-11 | task10 | opus | ADR 결과-5 | 채택 — app_dead 수 ADR에도 | fixed |

## 생략한 검증

- (없음)

## 완료 요약 (2026-11-18)

- 캠페인 campaign17-20261118: 27/27 유효(재측정 0) · G1 세 후보 0 · G2 전부 통과 · G3 D 실패(확정 p99 [11.92, 12.56] vs R [9.57, 10.17]ms · 중앙값 비 1.218) → 고정 규칙 **choice R**.
- 산출물: `k6/ADR-017/results/campaign17-20261118/RESULT.md`(신규) · ADR-017 '실험 결과' 결과-1~6 · '결정'(before: `## 실험 결과\n\n미측정.\n\n## 결정\n\n미정.` → after: 결과 절 + 결정 'R · 사용자 채택 대기 · 기본값 교체는 채택 뒤') · NEXT N00c · measurement-log 1행.
- 남은 것: 사용자 채택 → 기본값 `seat.compensation.mode` none → refund 교체 커밋(별도 L1) · push · PR(사용자 확인).
- CS 이슈 아카이브: 0건(이번 task 10은 문서 정리 — 새 CS 이슈 없음).

