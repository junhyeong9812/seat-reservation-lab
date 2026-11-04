# log — ADR-014 홀드 만료와 좌석 상태의 원천

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 10-26 10:20 | 사용자 'ADR-014 진행하자' · 인터뷰 3회(질문 11 — 선택지 A~D 전부 · TTL 짧은 셀 · 이기면 기본값 교체 · #11·#12 병합 뒤 main · DB now() · 선점 안 회수 · 높음 · auto · 좌석 맵 조회 포함 · 만료 판정만 통일 · 금지 ADR-013과 같이) · 메인 코드 확인: `ExpireHoldsService`(10초 sweep · 앱 Clock · 카운터 감소 같은 트랜잭션) · `ProductSeat`(HELD 저장 · 확정 2중 확인) · 좌석 맵 조회 API 없음(새로 필요) | 명세 초안 → 사용자 합의 대기 |
| 10-26 10:35 | 사용자 '응 그대로 진행하자'(적용 범위 = 기본 조합만 · 좌석 맵 조회 API 신설 포함) → set-state SPEC=1 · MODE=auto · 문서는 #11·#12 병합 뒤 브랜치에 커밋(그 전까지 작업 트리) | task 01 설계 초안 워커 |
| 10-26 11:00 | task 01 설계 초안 회수(Opus · 기준 9dc2b7e4 · ADR-014 §설계 40~410줄 +373/−1 · 코드 무변경) · 열린 질문 14(사용자 Q1·Q2·Q3·Q11·Q12) · 새 불변식 N1~N8 · [구현 검증] 12 · 메인 교차 확인: `ProductSeat.expireHolds`(활성 홀드 확인 없이 HELD→AVAILABLE — 혼재 시 이중 판매 위험) · 확정 앱 Clock(`ConfirmReservationService` · `ProductSeat.confirm`) — 일치 · Q1 = 명세 §2 '기존 동작 보존'과 충돌 → 재합의 질문 | 사용자 질문 |
| 10-26 13:50 | 사용자 결정: MQ2 관문 제외·보고만 · MQ3 매진·탐침·A 정합 위반 중 하나 · MQ4 60회 · MQ7 D>B>C · MQ9 ADR-015로 · 메인 MQ1·5·6·8·10 권장안 · [구현 검증] #15–21 중앙 대장 편입 · 상태 줄·§설계-8.1 고침 | task 02 듀얼 1패스 |
| 10-26 14:40 | task 02 듀얼 1패스 회수(미러 ds14.QKdm): codex H 1 · M 3 / Opus H 3 · M 5 · L 1 · OQ 4 → 메인 종합 MR-1~13 전부 채택(MR-8 메인 결정) · codex 종합 감사 지적 2: ① MR-5 'C 배치 잠금 409를 부당 실패에서 뺌'은 실제 사용자 실패를 성공 관문에서 놓침 → 정정: 판정 409(만료·매수)는 P4 문턱 0 그대로 · 배치 잠금 409는 원인 분류해 사용자 실패 지표로 2단(매진 시간과 같은 단)에 넣음 ② MR-8 격하가 P7과 연결 안 됨 → 정정: P7 주장 범위를 'API 계약'으로 좁히고 시각 역전은 동시성 테스트(N2·#12) green을 캠페인 시작 조건으로 연결 | 수정 워커 |
| 10-26 11:10 | 사용자 결정: Q1 A 앱 Clock 유지 · Q2 기존 대조 정의 유지 · Q3 확정 가드 넣음 · Q11·Q12 권장안 → 명세 §2 고침 + §10 R1~R4 재합의 기록 · 메인 Q4~Q10·Q13·Q14 권장안 채택 · ADR §설계-11 결정 줄 | 설계 선검증 듀얼 |
| 10-26 11:25 | 설계 선검증 듀얼 시작 · base 9dc2b7e4 · packet·미러 `/home/jun/.claude/jobs/0961cd42/tmp/ds14.wsKe`(log·NEXT·k6 결과 제외) · 보안 스캔: 시크릿 0 · 테스트용 `password = "app"`(로컬 테스트 컨테이너) · 내부 IP·홈 경로는 이미 공개 저장소에 있는 tracked 문서 → 오탐 판정 · codex(medium) ∥ Opus | 회수 대기 |
| 10-26 13:40 | task 02 초안 회수(Opus · ADR §측정 전 고정 규칙 499–831줄 · 주 셀 16 + 비용 셀 4 × 3회 = 60회 · 총 약 19.8시간 추정(과거 meta.json 기반) · 지표 M1–M14 · 가설 P1–P13 · 교체 관문 G1–G4 · lb 불필요(앱 1대)) · 열린 질문 MQ1–10(사용자 MQ2·3·4·7·9) · 미완료: [구현 검증] #15–21 중앙 대장 편입 · 상태 줄 | 사용자 질문 |
| 10-26 12:05 | 선검증 회수: codex H 1 · M 1 · OQ 1 / Opus M 2 · L 4 · OQ 2 → 메인 종합(채택 6 · 기각 1(명시 강화) · 메인 결정 1 · [구현 검증] 1) → codex 종합 감사 '감사 지적 0'(입력 = Opus 원문을 메인이 줄인 판 — finding 전부 포함) | 수정 워커 |
| 10-26 12:50 | 수정 회수(Opus — 504줄 · 새로 본 문제 8 보고) · 메인: #1 회수 H3 MISSING 구멍 → DS14-8 채택·직접 수정 · #2 C3b·D3 삭제 수 불일치 롤백 규칙 수용 · #4 명세 R4 '모든 방식 사이 ①'로 넓힘(더 엄격 — 사용자 보고) · #5 A·B·C·D 응답 차이(HOLD_EXPIRED vs SEAT_NOT_HELD)는 blind 브리핑에 넣음 · #7 마감 판단 시각 출처 차이 = [구현 검증] · #8 상태 줄 고침 · post-fix 재점검(codex · 미러 ds14.PHhS): 9건 해소 · 신규 1(DS14-9) → 기각(마감 회차 범위 밖 — 근거 문장 추가) · **설계 선검증 마감**(설계 문서 中 변형 — 듀얼 1패스 + 재점검) | task 02 측정 전 고정 규칙 |
| 10-26 13:05 | 사용자 보고(선검증 결과 · DS14-8 · R4 넓힘) → 사용자 '응 그대로 진행해줘'(R4 '모든 방식 사이 ①만' 확인) · task 02 초안 워커 진행 중 | 회수 대기 |

| 10-26 15:30 | task 02 수정 회수(Opus · 73곳 · 886줄) · 보고: D_arr a50-t300 1,800 → 2,220초로 캠페인 19.8 → 21.2시간(추정 — MQ4 재확인) · 배치 잠금 헤더 `X-Seatlab-Reject`는 loadtest 프로필 전용 추가 헤더 → 메인: 계약(상태·본문) 무변경으로 판정 · post-fix 재점검(codex · 미러 ds14.HxcZ): 해소 11 · 부분 2(MR-9·MR-10) · 신규 4(MR-14~17 — 부분 2와 같은 원인 포함) → 메인 직접 수정 · **task 02 리뷰 마감**(설계 문서 中 변형 — 재점검 신규는 수정으로 마감, 반복 없음) | 사용자 보고(MQ4 재확인) |
| 10-26 15:40 | 사용자 MQ4 재확인 '21.2시간 그대로'(60회) · task 01·02 문서 단계 끝 → task 03(blind)·04(구현)는 명세 §3대로 #11·#12 병합 뒤 main | 병합 대기 |
| 10-26 16:05 | 사용자 'PR 병합해줄래?' → #11 squash 병합 53790c81 · #12 대상 main으로 → 충돌(squash 때문) · main 트리 = ADR-012 끝 트리 확인 뒤 `-s ours` 병합 55ccc724(트리 무변경 확인) push · #12 squash 병합 882fa2ea(main 트리 = ADR-013 끝 트리) · 원격 브랜치 삭제 안 함 · 브랜치 feat/adr-014-hold-expiry(origin/main 882fa2ea에서 · push 안 함) · 문서 커밋 | task 03 blind 테스트 |
| 10-26 16:15 | 사용자: 포트폴리오 문서(docs/portfolio · 주제 2개)는 '구현 뒤, 스모크 전' · 주제는 구현 끝나고 함께 고민 → 구현(task 03·04) 계속 · blind 워커는 별도 worktree(구현 diff 미열람) · 구현 워커는 작업 브랜치(blind 테스트 미열람) | task 03 ∥ task 04a |
| 10-26 17:00 | task 03 blind 회수(Opus · worktree 기준 882fa2ea · 커밋 10c29337 · 새 파일 8 · 91 테스트) · 구현 전 6 통과 · 85 실패(키 무시 → A 동작 · 좌석 맵 404 · 대기 간선 없음 · 기동 거부 없음) · 통과 6 중 §설계-10 #2 PostgreSQL 재현 green(DS14-OQ2 입력 — D (가) 전제 실증) · 구현 전 D 스트레스에서 실제 이중 판매 재현 · 계약 가정 E1~E6 · 미포함: MISSING 경로 · 정리 ① 실제 교착 · 일부 전환 방향 | task 04a 회수 대기 |
| 10-26 18:10 | task 04a 회수(Opus · 커밋 d0dea201~df2fb5ff 7개 · 전체 803 · 실패 0 · 건너뜀 15(방식별 assume) · 기존 테스트 무수정) · [구현 검증] #2 green → D (가) 확정 · #12 7시나리오 40P01 0 · #24 green · 설계 밖 결정 8(55P03 JdbcTemplate 분류 · 판정기 새 항목 v_ 접두어 없음 · C·D 청크 실패 계속 · hold-reclaim 출처 main-app 상수 · T1·T2 미구현(운영 절차) 등) · blind cherry-pick 08c02534 → 91 중 89 통과 · 실패 2(C·D N2 스트레스 진행성 단언) = 테스트 시간 설계 결함(첫 만료만 대기 → 대부분 활성 · 만료 좌석은 X K2 잠금과 Y NOWAIT 충돌 = 설계된 409) → 메인 수정(마지막 만료 대기 · NOWAIT 1회 재시도 · 안전성 단언 무변경 — fix verification 분류) → ADR-014 테스트 3회 연속 green | 코드 리뷰 루프 1 |
| 10-26 19:00 | 코드 리뷰 루프 1 회수(base 5c8317a2 · 미러 ds14.9aAM): codex M 3 / Opus L 3 · OQ 4 · H 0 → 메인 종합: 채택 CR14-1(B + cleanup≠schedule 기동 거부) · CR14-2(C·D 틱당 청크 상한) · CR14-3(설계 §8.3 v_* 문장 동기화 — v_* 정의 무변경 · ADR-014 요약기는 방식별 새 키로 판정 = task 04b 요구로 넘김) · CR14-4(blind N2 스트레스 메인 수정이 경계를 없앰 → 첫 만료 대기 복원 · 진행성 단언 'Y:ok ≥ 1' 제거 · 불변식 단언만) · CR14-5(회수 롤백 시 skipped 지표) · CR14-6(repair=false B·C·D 테스트) · CR14-7(전환 정리 T1·T2를 ops SQL 파일로 · 테스트가 그 파일을 읽게) / 기록만: OQ3(B 정리 ① 미룬 쿼터 행 — 회차 재개 극단 경우 수용) · OQ4(V11 위치 없이 재기동 시 Flyway 검증 실패 — 기존 선택 위치 V4·V8·V9와 같은 성질) · codex 종합 감사 지적 1: CR14-3 '04b 범위 밖'은 오류(명세 task 04 = 하네스 포함) → 정정: CR14-3 전부 채택 · 하네스(k6/ADR-014)를 이번 루프 수정으로 구현(병렬 워커) · 루프 2에서 함께 리뷰 | 수정 워커 ∥ 하네스 워커 |
| 10-26 20:30 | task 04b 하네스 회수(Opus · 9ea834d2 · aecb1cf3 · 1449c7a1 · eb50140a · k6/ADR-014/만) · selftest --campaign 전부 통과 · stdin 반증(< /dev/null 제거 → 5회 뒤 exit 6) · k6 inspect rc 0 · dry-run 60자리 약 21.3시간 · 동결본 해시 일치 · shellcheck 없음(미실행) · 해석 14 — 메인 결정: #9(판정 불가 후보가 있으면 사용자 보고 — 보수 쪽 수용) · #10(H4 표지 미관측 = 이름표 · 스모크 #24 뒤 확정) · #3(A의 nowait도 배치 잠금 409 — A 배치도 좌석 행 UPDATE · 수용) · 나머지 기록 | 수정 워커 회수 대기 |
| 10-26 21:10 | 루프 1 수정 회수(Opus · 437ef773 · 4ea07c99 · ca792434(docs) · ef7ee85c · 1485f006 · 15b039d8 · 638b034d) · ADR-014 테스트 232 · 실패 0 · 전체 926 · 실패 0 · 건너뜀 20 · ops/expiry-mode-cutover 로컬 PG16 확인 · CR14-1로 blind 테스트 3개의 **기동 설정만** 고침(lazy에 cleanup=schedule — 채택 지적이 만든 새 규칙 · 단언 무변경 · '메인 수정(CR14-1)' 주석) → 메인 수용 · 새로 본 문제 4 기록(커서 메모리 전용 등) · CR14-1~7 fixed(1) | 루프 2 |
| 10-26 22:10 | 코드 리뷰 루프 2 회수(미러 ds14.jKtA · 누적 base 5c8317a2 · 앱+하네스+ops): codex M 2 / Opus M 2 · L 8 · OQ 4 · H 0 → 메인 종합 CR14-8~20 채택(신규 13 — 앱 결함 0 · 하네스 판정 입력·규칙 실행 가능성 위주) · OQ1 스모크 [구현 검증] · OQ3 메인 결정 유지 · codex 종합 감사 '감사 지적 0' | 수정 워커 → 루프 3 |
| 10-26 23:05 | 루프 2 수정 회수(Opus · 072b2976~ac097a15 15커밋) · selftest --campaign 100 ok · expiry 228 · 전체 922+6 실패 0 · ADR 측정 전 재해석 행 8 · 새로 본 문제 6 중 4(CR14-21~24 — 하네스 판정 입력 무음 같은 종류) 채택 · 2 기록(CR14-13 근사 · 정지 검사 COMMIT 전 새 세션) · **같은 종류(판정 입력 무음) 반복 → 정지 규칙 점검: Inputs 한 곳 검증 밖 경로(probe_metrics·beats3)에서 재발 → 이번 수정은 '모든 판정 입력이 Inputs를 거치는지' 전수 목록화로** | 소수정 → 루프 3 |
| 10-26 23:55 | CR14-21~24 + 판정 입력 전수 점검 회수(Opus · eb6fcad3~a8dd746d 9커밋) · selftest 173 ok(전 100) · 새 시험은 고치기 전 코드에서 실패 확인(생성형 140 주입 중 58 어긋남) · 출처 표 57행(고침 37 · 설명 8) · 메인 결정: 새 사유 2(k6-window-missing · harness-reasons-missing) · 2단·3단 판정 불가 ⓓ · G2 None ⓒ · 표본 비용 분리 불명 처리 — 모두 판정 불가 쪽 보수 → 수용 · ADR-009 gc_summary 무음 = CR14-25 open(ADR-014 쪽에서 감쌈) | 루프 3 |
| 10-27 00:50 | 코드 리뷰 루프 3(마지막 · 미러 ds14.r5H5) 회수: codex finding 0(렌즈별 근거 — 앱·ops 위주) / Opus M 1 · L 2 · OQ 2 · H 0 · 정합 핵심 결함 0 → 신규 채택 5(CR14-26~30) + 열린 CR14-25 → 루프 상한 3 도달 · 신규 채택 ≠ 0이라 종료 조건 미충족 → 수정 뒤 사용자 판단(추가 리뷰 또는 user override) | 수정 워커 |
| 10-27 02:00 | 루프 3 수정 회수(Opus · 6924fd6e~120bb45f 14커밋) · selftest 184 ok · expiry 232 · 전체 926+6 실패 0 · 새 시험은 고치기 전 코드에서 실패 확인(CR14-26 2 · CR14-29 3(권한 없는 롤에서 옛 검사 무음 통과 실증) · 하네스 9) · PG16 권한 동작 로컬 컨테이너 확인 · 메인 직접: CR14-27 ADR 재해석 행 · ops README autovacuum 55P03 한계 · 기록만: r0() broken 미확인(설명 값) · 테스트 도우미 세션 조건 문구 차이 · CR14-25~30 fixed(3) · **리뷰 상태: 3루프 상한 도달 · 마지막 루프 신규 채택 5(전부 수정·테스트) → review unresolved 아님(open 0) · 종료식의 '신규 0' 미충족 → 사용자 판단 대기** | 사용자 보고 |
| 10-27 02:40 | 사용자: '수정분만 재점검 후 마감' · 포트폴리오는 리뷰 마감 뒤 → 루프 3 수정분 post-fix 재점검(codex · 미러 ds14.Nb3g · base a8dd746d): 해소 5 · 부분 1(CR14-29) · 신규 1(CR14-31 — product_schedule 미잠금 교착) → 메인 수정(잠금에 product_schedule 추가 · 테스트 대상 표 4개) · ADR-014 테스트 232 · 실패 0(앱 코드 무변경 — 전체 미실행 · ops·테스트만) · **코드 리뷰 마감**(3루프 + post-fix 재점검 · open 0 · 마지막 재점검 신규 1은 수정·테스트로 마감 — 사용자 선택 경로) | 포트폴리오 주제 논의 → 스모크 |
| 10-28 07:40 | 사용자: '우선 측정부터 하자 서버 켰어'(포트폴리오는 측정 뒤로) · .164 확인(uptime 0분 · 사용자 컨테이너 3개만 · 디스크 55% · 메모리 29G 가용) → 스모크(시작 조건 ①) 워커 · 기준 HEAD 0bf4c827 | 스모크 |
| 10-28 17:10 | 스모크 회수(Opus · 1/6회 뒤 중단 · 이미지 seatlab-app:0bf4c827 sha256:d0747773…): A a50-t30 invalid(db-cpu-missing · dropped-iterations · path-gap · probe-incomplete · probe-missing) — 원인 PC 유선 eno1 끊김 16:45:56~16:46:16 · 결정 필요 2: ① **PC 링크 100Mbps**(메인 확인: /sys speed 100 · ethtool 자기 쪽·상대 쪽 모두 10/100만 광고 · 10-24 17:06부터) + 약 37분 주기 링크 끊김 3회(약 20초) ② CR14-23(PR-3 c ∉ {200,409} → probe-incomplete)이 A의 정상 '만료 뒤 확정 거부' 404 HOLD_NOT_FOUND(sweep이 행을 먼저 지움 · δc>0 탐침 약 7%)를 무효로 만듦 · 메인 추가 확인: ADR-013 캠페인(10-25 16:00~10-26 08:00) 동안 eno1 Link Down 78회 · 17:21:25/31/42 · 17:58:46/53 … 37분 주기 → ADR-013 §6.6-6 '원인 미확인 환경 정지 덩어리(약 12+6+2~3초)'의 유력 원인(가설 — 시각 대조 미완) · green: \gset · 원인 표지 대조 · 앱−DB 시각 · wend_lag · inc/safety 기록 · R₀ 0.438 · c_A 0.348% | 사용자 결정 |
| 10-28 17:40 | 사용자 결정: ② '만료 뒤는 거부, 만료 전은 실패'(404 HOLD_NOT_FOUND) · ① '점검 뒤 1Gbps로 재개' → 메인 수정: summarize c 예상 값에 404 · selftest 2건(δc>0 거부 · δc<0 early_not200) + 기존 CR14-23 시험을 c 400으로 · ADR 측정-9 재해석 행 · README · 스크립트 실행 권한 · selftest 181 ok · --campaign 모두 통과 · 스모크 A 재요약: probe-incomplete 사라짐(unjudgeable 0 · late_200 0 · early_not200 0) · 남은 사유 4 = 링크 끊김 · 스모크는 1Gbps 복구 뒤 6회 처음부터 | 사용자 랜 점검 대기 |
| 10-28 18:39 | 사용자 밖 · 'ethtool -r' 대신 실행 요청 → sudo 암호 필요로 불가(암호 요청 안 함) · 사용자 '그냥 이대로 실험 진행' → 메인: 캠페인은 불가(TTL 5분 셀 약 46분 > 끊김 주기 약 37분 → 늘 무효 → 판정 불가) · **100Mbps 조건 하네스 검증용 스모크 5회만** 진행(판정·시작 조건에 안 씀 — 1Gbps 복구 뒤 정식 6회) · 끊김 18:00:36 · 18:37:51~18:38:09 → 다음 약 19:15 예상 · 끊김 직후 시작 | 스모크(하네스 검증) |
| 10-28 19:45 | 하네스 검증 스모크 회수(Opus · smoke14net-20261028 · --worktree · 이미지 0bf4c827): a50-t30 B·C·D invalid(app-metric-missing · dropped-iterations) · S4c4200 A·D ok · 회차 중 Link Down 0(끊김 19:14:57~19:15:15를 피함) · 하네스 결함 1 고침 f86559cd(gracefulStop이 409 뒤 요청 도중 반복을 끊으면 원인 표지 집계 누락 → 409마다 즉시 flush · 고친 뒤 D k6 rej 합 = HTTP 409) · dropped = 100Mbps 포화(수신 약 74Mbps · 지도 p99 18~19초) · green: c_X A .348 B .375 C .316 D .328(Δc .059%p) · #19 지표 이름 · #18 D d1 = 확정 · d2 0 · FOR UPDATE NOWAIT 미계수 · 확정 404 δc 분포(+1.0만 · late_200/early_not200 0) · clock_timestamp−now ≤ 12.9ms · wend_lag 2.4~3.4초 · CSV 요약 15.8초 · 결정 필요 4: ① 앱이 k6보다 2건 더 셈(끊긴 요청) ② PC 링크 끊김이 env-stall이 아니라 방식 정지로 분류(서버 측정기는 안 멈춤) ③ H4 표지 원리상 0.5초 표본으로 못 봄 ④ PR-1 nowait 0 → M3ᵦ 빈 지표(기록) · .164 사용자 컨테이너 'Up Less than a second' 표시(조회만 · 부팅 직후 시계 9시간 앞 흔적과 관련 추정) | 사용자 결정 |
| 10-29 15:22 | 사용자 랜선 교체(10-28 21:18) → eno1 1Gbps(21:19:08) · 그 뒤 약 18시간 Link Down 0(37분 주기 소멸 — 원인 = 랜선 · 메인의 '장비 쪽' 추정은 틀림) · 교체 직후 .164 ARP 실패 → 사용자 재기동 · .164 up 1분 · 시계 동기 · 사용자 컨테이너 3 healthy · 정식 스모크 6회 시작(--worktree · 이미지 0bf4c827 · HEAD 하네스) · 결정 대기 3(표지 대조 허용 · PC 링크 끊김 분류 · H4)은 요약기만 — 스모크 뒤 재판정 가능 | 정식 스모크 |
| 10-29 15:30 | 사용자 결정(권장안 3): ① 원인 표지 대조 — 0 ≤ 앱 − k6 ≤ k6 응답 없는 요청(코드 0) 수면 일치 ② 회차 창 안 PC 커널 eno1 Link Down = 새 사유 pc-link-down(환경 → 재측정 · 환경 상한) ③ H4 표지 = 이름표 · 회수 대기는 seat.hold.reclaim 타이머 · 경로 보증은 동시성 테스트 · M3ᵦ 빈 지표(PR-1 nowait 0) 기록 → 하네스는 스모크 진행 중이라 스모크 끝난 뒤 수정 · 스모크 재판정 | 정식 스모크 |
| 10-29 18:35 | 정식 스모크 회수(Opus · smoke14-20261029 · 1Gbps · Link Down 0): 최종 6회 모두 ok(a50-t30 A·B·C·D · S4c4200 A·D) · 시도 16회 · 하네스 결함 2종 3커밋: 1ac55ffa(preAllocatedVUs blind 2000→4000 · map 200→400 — 맹목 동시 반복 약 2,244 > 2,000 → 늦은 VU 생성 중 dropped) · 376c0950 + ed5c6767(주 흐름 duration 경계 반복으로 세션 수 ±1 → crn 불일치 · 두 번째 수정에서 계획 세션 수 밖 반복은 요청·crn 기록 없이 끝냄 — 같은 결함 2회 수정 뒤 재발 0) · 시작 조건 ⑤: c_X A .335 B .373 C .311 D .328 · Δc .061%p · R₀ .4382 · 환경 정지 0/6 · VU 최대 약 2,128(TTL 30초 기준) · ⑥ T95 A 216.20 = D_arr 360의 60.1% → 규칙대로 a50-t30 D_arr 420초 · 지도 p99 18~331ms · 원인 표지 앱−k6 = 0 · 정합 위반 0 · 남은 문제: 탐침 첫 선점 k6 코드 0(EOF)이 P12 방식 위반으로 셈(최종 A 2건 · keep-alive 유휴 연결 경합 추정) · 측정-9 처방 'maxVUs'와 실제 원인(preAllocatedVUs) 어긋남 · PC에 다른 프로젝트 부하(ccle-bt-god 컨테이너 · gradle · rev 프로세스 6일째 CPU 100% · load 6~13/24코어) | 사용자 보고 |
| 10-29 18:45 | 사용자: 탐침 EOF = '탐침은 연결 재사용 안 함'(하네스 Connection: close) · PC 다른 부하/캠페인 = '164 내가 하자고 하면 하자' → .164 사용(확인 스모크·캠페인)은 사용자 신호 뒤 · 지금은 하네스 수정만(탐침 Connection: close · 결정 3 규칙 · ⑥ D_arr 420 · 측정-9 처방 문구 preAllocatedVUs) | 하네스 수정 |
| 10-29 20:10 | 하네스 수정 회수(Opus · 679067d1~90e1cb62 16커밋 · 코드 5 · 문서 11 · .164 미접속): 탐침 Connection: close(로컬 흉내 서버로 새 연결 실증) · 원인 표지 허용 = 0 ≤ 앱−k6 ≤ 응답 없는 선점 수 · pc-link-down(pclink14.sh · sudo 없이 journalctl · 다른 사유와 함께여도 환경 — 메인 수용) · M10 확정 · M3ᵦ 빈 지표 기록 · a50-t30 D_arr 420(MAIN 기본값) · 측정-9 처방 preAllocatedVUs · selftest --campaign 203 ok(새 시험 고치기 전 21 실패 확인) · 재판정(사본): 6회 ok 그대로 · A P12 EOF 2 그대로 방식 위반(close는 다음 회차부터) · 캠페인 약 21.5시간 · 결정 필요 2: a50-t300 D_arr 외삽 방식(2,220 그대로 대 2,700 +1.6h) · TTL 5분 칸 preAllocatedVUs 4,000 부족 가능 | 사용자 결정 |
| 10-29 20:30 | 사용자 결정: VU = '미리 늘리고 확인 스모크에 TTL 5분 1회 추가' · a50-t300 D_arr = '확인 스모크에서 실측해 정함' → 메인 수정 6b691bd8(TTL 5분 칸 PRE_VUS blind 12000 · map 1000 · TTL 30초 칸은 4000·400 그대로 · 렌더 env 확인 · dry-run · selftest 통과) · 다음(.164 켜면): 확인 스모크 = a50-t30 1~2회(Connection: close 효과 · 탐침 EOF) + a50-t300 1회(VU 최대 · dropped · T95 → ⑥ 적용) → 캠페인 | .164 대기 |
| 10-30 14:30 | 확인 스모크 회수(Opus · confirm14-20261030 · 1Gbps · Link Down 0 · pc-link 경로 정상): a50-t30 A·B ok — 탐침 코드 0 = 0(새 연결 비율 A pr1 710/710 등 = Connection: close 적용 실증 · 0건 자체는 통계상 약함) · 원인 표지 허용 규칙 정상 · **a50-t300 D invalid**(app-metric-missing · pre-k6-mismatch · probe-*) — 원인 추정 **앱 Tomcat max-connections 기본 8,192 상한**(k6 첫 30초 새 연결 8,153 직후부터 끝까지 초당 약 60건 실패 · 서버 안 측정기도 코드 0 11,714 → 앱이 연결을 못 받음 · 동시 VU 최대 9,254 · 각 VU가 keep-alive 연결 보유 · ADR-003 S6 같은 상한 기록) · TTL 30초 칸(VU 약 4,700)은 무관 · PRE_VUS 12000 < 9,254×1.5 · T95 inf → ⑥ 자료 없음 · k6 RSS 10.6GB · PC MemAvailable 최저 1.98GB · PC load 14~36(다른 프로젝트 부하) · A·B p99 정식 스모크보다 높음(PC 부하 추정) | 사용자 결정 |
| 10-30 14:45 | 사용자 결정 → 명세 §10 R5 재합의: 측정 compose만 Tomcat max-connections 20,000 · 캠페인 동안 PC 다른 부하 멈춤(측정 전 조건 추가) · 메인 확인: 앱 설정·compose 어디에도 tomcat 키 없음(기본 8,192) → 하네스 수정 워커(compose · 파일 기술자 한도 · PRE_VUS · k6 초기화 lead · 시작 조건) | 하네스 수정 |
| 10-30 15:40 | 사용자 '.164 곧 써야 해서 테스트 멈추고 수정만' → 워커 .164 접속 전면 금지 · 수정 회수(Opus · cb7114be~5658e0cd 6커밋): 측정 compose Tomcat max-connections 20000 · 앱 nofile 65536 · 적용값 대조(cond-config-mismatch) · TTL 5분 PRE_VUS blind 14000 · k6 lead = 0.15초 + VU×0.37ms(기존 11회 2.5초 안) · 시작 조건 ④ pccheck14(seatlab 밖 컨테이너 0 · load ≤ 4 · MemAvailable ≥ 16GiB) · 회차 중 MemAvailable < 4GiB = pc-mem-low(환경) · selftest --campaign 232 ok(새 시험 고치기 전 26 실패 확인) · 미확인: .164 nofile·somaxconn·port range(다음 스모크 전) · 새 위험: Tomcat keepAliveTimeout 기본(미지정) < TTL 5분 생각 시간 → 주 흐름 유휴 연결 EOF 경합 가능 · .164 seatlab 측정 컨테이너 3개 잔존(내릴지 사용자 질문) | .164 대기 |
| 10-31 00:26 | 사용자 '.164 켰어 진행' → 사전 점검: .164 up 1분 · 시계 동기 · 사용자 컨테이너 healthy · somaxconn 4096 · port range 32768–60999 · PC pccheck 불통과(ccle-postgres-1 · load 4.77 · MemAvailable 13.7GiB — WebKitWebProcess 9.0GB · idea 2.7GB) → TTL 5분 회차 k6 약 11~12GB 추정 → PC 메모리 고갈·OOM 위험으로 시작 보류 · 사용자 '잠시 대기 · .164 잠시 써야 함' | 대기 |
| 11-02 18:15 | 사용자 '확인 스모크 진행' → 점검: PC eno1 NO-CARRIER(17:20:34부터 · 09:43 · 11:07도 끊김) · .164 경로 와이파이 우회 · PC 다른 실험 컨테이너 9 · load 7~18 → 보류 · 사용자 랜선 재연결 뒤에도 carrier 0 → 메인이 r8169 재로드 권함 → 'D3cold → D0 실패 · probe -5' → 메인이 PCI remove·rescan 권함 → 랜카드 PCI 목록에서 사라짐 → 상위 포트 00:1b.0 runtime suspended 확인 → power/control on · rescan · 포트 reset → 포트 정상 · 랜카드 응답 없음 · **메인 실수: 위험 설명 없이 드라이버 언로드·PCI remove를 권해 상태 악화 · 다른 실험 중 종료 권유 → 사용자 지적** · 지금은 아무것도 끄지 않음 · 유선 복구 = 다른 실험 끝난 뒤 완전 종료·재기동(사용자) | 대기 |
| 11-03 00:24 | 사용자 '실험 진행하자'(PC 재부팅 뒤 · 00:20:02 eno1 1Gbps Up) · 점검: 경로 eno1 · pccheck14 통과(다른 컨테이너 0 · MemAvailable 29.7GiB · load 3.5 — 부팅 직후) · 랜카드 power/control on · 상위 포트 auto(재부팅으로 기본값) · .164 정상 → 확인 스모크 재개(a50-t30 1회 + a50-t300 1회) | 확인 스모크 |
| 11-03 00:30 | 사용자 '스모크 끝나면 알려줘 · 스모크 뒤 바로 본 실험까지 이어서' → 확인 스모크가 통과(결정 필요 문제 없음)하면 시작 조건(⑤ JSON · ⑥ D_arr · 캠페인 스모크 ②) 갖춘 뒤 캠페인 시작 · 결정 필요 문제가 나오면 멈추고 보고 | 확인 스모크 |
| 11-03 01:38 | 확인 스모크 끝(confirm14-20261103 · 워커 Opus 5.5): a50-t30 A **ok**(탐침 EOF 0 · 코드 0 = 0 · T95 221.6s → ⑥ ratio 0.528 연장 없음 · max-connections 20000·nofile 65536 적용 확인) · a50-t300 D **invalid:app-metric-missing**(창 끝 앱 지표 스냅숏이 창 끝보다 1.07s 앞 — lead 비례화 뒤 드러난 하네스 결함 → 5a2ad147 수정 · selftest 통과 · 실회차 미확인) · D의 나머지는 정상(코드 0 = 0 · ESTABLISHED 최대 14,727 < 20000 · VU 최대 blind 5,204 · 정합 위반 0 · T95 1317s → ratio 0.593 연장 없음 · 앱 메모리 최대 1.92GiB/2g) · **D 도중 PC에 다른 프로젝트 ccle 컨테이너·빌드 부하(load 최대 16 · pc-busy-before)** → 시작 조건 ④·R5② 충족 불가 · 사용자 판단 필요로 캠페인 진입 보류(01:38 현재 다른 컨테이너 0 · load 2.0) | 확인 스모크 → 보류 |
| 11-03 08:38 | 사용자 'ccle 멈췄어 · D 한번 더 돌리고 캠페인까지 진행' → 순서: ⑦ 동시성 테스트(HEAD) → HEAD 이미지 고정(앱 코드는 0bf4c827과 같음 — 하네스 고정용) → a50-t300 D 재확인 1회(5a2ad147 실회차 확인) → ⑥·② → 시작 조건 JSON → 캠페인 · 점검: 다른 컨테이너 0 · load 1.3 | 재확인 → 캠페인 |
| 11-03 09:39 | ⑦ 동시성·만료 테스트 green @2bb7355f(expiry.* 232건 · 실패 0 · 건너뜀 20 = 방식별 assumeTrue) · 이미지 고정 images-2bb7355f.json(seatlab-app:2bb7355f sha256:e12fa782…) · D 재확인(confirm14-20261103b · 고정 하네스) **ok** — wend_lag 3.96s(5a2ad147 실회차 확인) · 코드 0 = 0 · 이중 판매·카운터 불일치 0 · T95 1314.8s(⑥ 연장 없음) · Link Down 0 · MemAvailable 최저 13.7GiB · 이름표 pc-busy-before(회차 앞 다른 컨테이너 check-d2-…-jbuild · load 5.98) · 끝 뒤 09:38 점검: **ccle-agent 컨테이너 check-d2-god-alt-…-jcontract2 4개가 1분 전 새로 기동** → pccheck14 막음 · 캠페인 스모크·캠페인 미시작 · 사용자 보고 | 보류 |
| 11-03 10:15 | 사용자 'ccle 실험은 계속해야 함 · 부하를 만들기만 하는데 문제가 있냐' → 재합의 R6(명세 §10): 다른 컨테이너·load는 막지 않고 기록만 · 메모리 하한·dropped·탐침 오차 무효 규칙 유지 · 끝에 깨끗한 회차 대조 → 3f2b3399(pccheck14 busy[] · summarize 이름표 · campaign ④ · selftest 3건 갱신 + 1건 추가 — `selftest.py --campaign` 모두 통과 · 실PC pccheck ok + busy 기록) | 캠페인 준비 |
| 11-03 10:32 | ⑦ green @fd494aea(expiry.* 232 · 실패 0 · 건너뜀 20) · 이미지 images-fd494aea.json(seatlab-app:fd494aea sha256:45eb627f…) · 시작 조건 JSON results/start-conditions-fd494aea.json(smoke 8폴더 · R₀ .4382 · Δc .061%p → 표본 방식 그대로 · env-stall 0/6 · VU 최대) · dry-run 시작 가능(④ busy: check-d2 컨테이너 · load 9.6 — R6 기록만) · ② 캠페인 스모크 campaign14smoke-20261103: rep1 a20-t30 B ok(10:16–10:23) · C ok(10:23–10:31) · 루프가 3번째 자리(D)로 넘어감 확인 뒤 중지(남은 프로세스 0 — 다음 회차 compose_down14가 정리) → 본 캠페인 시작 | 캠페인 |
| 11-04 09:47 | 캠페인 끝(20261103-adr014-fd494aea · 10:31→09:47 · 시도 62 · 모두 ok · 재측정 2 = rep2 a20-t300 D · rep3 a50-t30 A · 유효 3 × 20칸 전부) · 요약기 결론 **A 유지 ① — 성공 후보 없음**: B·D = P12 5xx_or_0:confirm 1건(a50-t300 rep1 — 확정 POST 코드 0 EOF · 연결 재사용(connecting 0) · t0 무렵) · C = P12 gc_pause_ge_10ms 1회(a20-t300 rep1) · 세 회차 모두 pc-busy-before → 원인 분석 워커 06a(Opus · 읽기 전용 — 전 회차 코드 0·5xx 집계 · GC 정지 · 고정 규칙과 구분한 반사실 계산 · 깨끗한 회차 대조) | 결과 분석 |
| 11-04 10:25 | 06a 회수(Opus · 읽기 전용 · 메인 교차 확인: m11.err_window confirm 1 · 반사실 RESULT 결론 줄): 248 CSV 전수에서 코드 0·5xx = 2건뿐(B·D a50-t300 rep1 확정 EOF · 연결 재사용 · 유휴 60.27s/60.15s = Tomcat 기본 60초 keep-alive 종료와 경합 · 노출 확정 수 방식별 ~240 동일 · A 0건 확률 ≈0.6 · 서버 ERROR 없음) · C GC 19.78ms = Leaving safepoint 19.73ms(GC 작업 0.02ms · 부하 시작 1.6s · 힙 20%) · 반사실(고정 규칙 아님 — 3건 무시) 결론 'A 유지 ③': B·C·D 성공 · G1·G2 통과 · 셋 다 G3 a50t300_db_cpu에서 걸림(중앙값 A 24.54 · B 1.295× · C 1.143× · D 1.154× > 1.10) · 3단 비용은 D만 남음 · pc-busy 62/62(깨끗한 회차 없음 · DB CPU 순서는 load 낮은 회차만도 같음) · attempt2 2건 = crn-mismatch(blind 세션 기록 1건 모자람 — 근본 원인 미조사) | 사용자 보고 |
| 11-04 10:40 | 사용자 'A 유지로 결과 문서 · 연결 끊긴 이유는 별도로' · 'A로 확정' → 정식 결론 = 고정 규칙 'A 유지 ①'(기본값 A 그대로 · DB 전환 없음) · 반사실·잡음 분석은 설명으로 · 워커 06b(Opus — ADR-014 §실험 결과·§결정 초안 · 커밋 없음) ∥ 06c(Opus — EOF 근본 원인: Tomcat·k6(Go) keep-alive 기본값 출처 확인 · PC 안 소규모 재현 · 수정안 제안만) | 결과 문서 ∥ 원인 조사 |

## 리뷰 ledger

### 설계 선검증 (설계 문서 — 中 변형: 듀얼 1패스 + post-fix 재점검)

| id | first_seen_loop | source | 근거 | disposition | status | 내용 |
|---|---|---|---|---|---|---|
| DS14-1 | 1 | codex | ADR-014 §3.2 K2·K3(:189-197) | 기각(명시 강화) | — | 확정 가드가 RESERVED 재확정 — K2 FOR UPDATE가 최신 커밋 행을 보고 :197 RESERVED 거부가 이미 있음 · 판정 순서만 명시 |
| DS14-2 | 1 | codex + opus | §3.3·§설계-7 · ProductSeatRepository.kt:18 · ProductSeat.kt:44-48 | 채택 | fixed(1) | 방식 전환 정리 SQL이 좌석 기준 재계산을 안 함(D→B·C 좌석 맵 거짓 AVAILABLE · B·C·D→A 영구 HELD) |
| DS14-3 | 1 | opus | QuotaCleanup.kt:118-121 · §3.4 B | 채택 | fixed(1) | B 정리 ① 홀드 삭제 ↔ 확정 K3 교착 |
| DS14-4 | 1 | opus | §3.1 · §3.2 | 채택 | fixed(1) | 선점 H4 → 확정 K3 대기 간선 미기재 |
| DS14-5 | 1 | opus + codex | §3.4 C1·C3·C4 · ExpiryCounters.kt:73-74 | 채택 | fixed(1) | MISSING 쌍 · 고정 LIMIT 진행 막힘 · D 배치 쌍→행 연결 |
| DS14-6 | 1 | opus | ExpiryCounters.kt:33-35·59-60 | 채택 | fixed(1) | 회수·배치 감소에 증분 방식별 표시 누락 |
| DS14-7 | 1 | opus | PairRepair.kt:101 | 채택 | fixed(1) | 인용 줄 |
| DS14-OQ1 | 1 | opus | application.yml:16 | 메인 결정 | fixed(1) | V11 기본 위치 미편입(기본값 교체 때 함께) |
| DS14-OQ2 | 1 | opus | §2.3 | [구현 검증] | fixed(구현) | D (가) 확정 — #2 재현 테스트 green(blind · 구현자 테스트) |
| DS14-8 | 1 | main-synthesis(수정 워커 보고) | ADR-014 §3.1 H3 | 채택 | fixed(1) | 회수 H3 MISSING 쌍 — 규칙 ① 미성립 → 무음 below 가능 → H4 앞 PairRepair |
| DS14-9 | post-fix | codex | ADR-014 §설계-7 T1(:388·392·403) | 기각 | — | 마감 회차 감소 안 함 → 불일치 — 마감 회차는 카운터 불변식 범위 밖(LoadtestDataService.kt:190·203 · QuotaCleanup.kt:86-87) · 근거 문장 추가 |

### 측정 전 고정 규칙 (설계 문서 — 中 변형)

| id | first_seen_loop | source | 근거 | disposition | status | 내용 |
|---|---|---|---|---|---|---|
| MR-1 | 1 | codex + opus | ADR-014:661 | 채택 | fixed(1) | 표본 질의 비용 방식별 차이 · 사전 기준 |
| MR-2 | 1 | codex | :591·686·813 | 채택 | fixed(1) | 탐침 실제 송신 시각 대조·허용 오차 |
| MR-3 | 1 | codex | :688·748·771 · run.sh:600 | 채택 | fixed(1) | DB CPU 결측 무효 |
| MR-4 | 1 | codex | :513·680·773 | 채택 | fixed(1) | 판정 창 양끝 공백 |
| MR-5 | 1 | opus + 감사 | :682·704·266-268·169·318 | 채택(감사 정정) | fixed(1) | C 배치 잠금 409 — 원인 분류 · 사용자 실패 지표로 2단 · 판정 409 문턱 0 유지 |
| MR-6 | 1 | opus | :778·534 · probe-ping.js | 채택 | fixed(1) | env-stall에 DB 원인 없음 AND |
| MR-7 | 1 | opus | :438-441·719·740·781 | 채택 | fixed(1) | 기대 0 항목 성공 조건 편입 |
| MR-8 | 1 | opus + 감사 | :591·684·708 | 채택(감사 정정) | fixed(1) | PR-3 = API 계약 대조 · 시각 역전 = 동시성 테스트 green을 시작 조건 |
| MR-9 | 1 | opus | :713·720 · ADR-010:166-175 | 채택 | fixed(1) | 문턱 A에도 계산 · A도 어기면 판정 불가 |
| MR-10 | 1 | opus | :778·783 · ADR-013:663 | 채택 | fixed(1) | 환경 정지 빈도 스모크 · 상한 소진 = 판정 불가·사용자 보고 |
| MR-11 | 1 | opus | :576-578 · s3-full-flow.js:33 | 채택 | fixed(1) | dropped_iterations 무효 · crn 해시 = 실행 세션 |
| MR-12 | 1 | opus | :683·706 | 채택 | fixed(1) | P6 n_tup_upd 반영 지연·롤백 |
| MR-13 | 1 | opus | :553·765·689 | 채택 | fixed(1) | D_arr 늘림 식 · M10 표지 |
| MR-14 | post-fix | codex | :595·728 | 채택 | fixed(post) | P9 반증이 '3회 모두 0'이라 만료 전 확정 실패 통과 → 어느 회차든 1건 |
| MR-15 | post-fix | codex | :723·737·744·749 | 채택 | fixed(post) | C 7행에 두 결론 → P5 정합 위반으로만 |
| MR-16 | post-fix | codex | :791·797·816 | 채택 | fixed(post) | 스모크 env-stall이 시작 막아 빈도 항상 0 → env-stall만은 시작 허용·집계 |
| MR-17 | post-fix | codex | :548·700·815 | 채택 | fixed(post) | 409 0 회차의 헤더 결측 무효 → 409 ≥ 1일 때만 |

### 코드 리뷰 (높음 — 루프 ≤3)

| id | first_seen_loop | source | 근거 | disposition | status | 내용 |
|---|---|---|---|---|---|---|
| CR14-1 | 1 | codex | QuotaCleanup.kt:131 · StartupCheck:162 | 채택 | fixed(1) | lazy + cleanup≠schedule 기동 거부 |
| CR14-2 | 1 | codex + opus | LazyExpiryBatch.kt:43-55 | 채택 | fixed(1) | 틱당 청크 상한 |
| CR14-3 | 1 | codex + opus + 감사 | LoadtestDataService.kt:162·213 · ADR §8.3 · spec task 04 | 채택(감사 정정) | fixed(1) | v_* 문장 동기화 + 하네스 k6/ADR-014 구현 |
| CR14-4 | 1 | opus | Adr014ModeContractBase.kt:345-366 | 채택 | fixed(1) | 메인 blind 수정이 경계 재현 제거 |
| CR14-5 | 1 | opus | ExpiryCounters.kt:168-181 | 채택 | fixed(1) | 회수 롤백 흔적 |
| CR14-6 | 1 | opus | ExpiryCounters.kt:125-129·154 | 채택 | fixed(1) | repair=false 테스트 |
| CR14-7 | 1 | opus | Adr014Support.kt:189-204 | 채택 | fixed(1) | 전환 정리 ops SQL |
| CR14-8 | 2 | codex | summarize.py:708·867 | 채택 | fixed(2) | 정합 문턱 열 누락 → or 0 |
| CR14-9 | 2 | codex | summarize.py:144 | 채택 | fixed(2) | 시계 차 rtt 누락 통과 |
| CR14-10 | 2 | opus | summarize.py:261-275·847 | 채택 | fixed(2) | 탐침 첫 선점 4xx → probe-incomplete 아님 |
| CR14-11 | 2 | opus | summarize.py:1197-1201 · ADR P7·P9 | 채택 | fixed(2) | PR-3 실행<계획 규칙 실행 불가 → 측정 전 재해석 |
| CR14-12 | 2 | opus | summarize.py:853-859 | 채택 | fixed(2) | h4 파일 결측 무표시 · 메모 10 확정 |
| CR14-13 | 2 | opus | summarize.py:800-801·893 | 채택 | fixed(2) | 틱 실패 창보다 넓게 |
| CR14-14 | 2 | opus | run.sh:200 | 채택 | fixed(2) | 예열 rc 미확인 |
| CR14-15 | 2 | opus | selftest.py:16·354 | 채택 | fixed(2) | 판정 분기 미실행 |
| CR14-16 | 2 | opus | 01-cleanup.sql:6-21 | 채택 | fixed(2) | 정지 상태 자체 확인 없음 |
| CR14-17 | 2 | opus | StartupCheck:163-186 | 채택 | fixed(2) | C·D + 스케줄러 끔 누적 |
| CR14-18 | 2 | opus | summarize.py:1302-1326 | 채택 | fixed(2) | G3 판정 불가를 A 유지로 |
| CR14-19 | 2 | opus | summarize.py:528-531 | 채택 | fixed(2) | P7 기회 넓게 · 메모 8 확정 |
| CR14-20 | 2 | opus | summarize.py integ | 채택 | fixed(2) | v_reserved_without_confirmed·v_confirmed_on_unreserved_seat 정합 위반 미편입 |
| CR14-21 | 2 | main-synthesis(수정 워커 보고) | summarize.py integ | 채택 | fixed(2) | active_on_reserved_seats(모든 방식 기대 0) 끝 값 integ 미편입 |
| CR14-22 | 2 | main-synthesis | summarize.py probe_metrics | 채택 | fixed(2) | 첫 선점 기록 행 없으면 bad_setup=None → 정상 탐침처럼 판정 |
| CR14-23 | 2 | main-synthesis | summarize.py probe_metrics | 채택 | fixed(2) | h1 응답 201·409 밖(400·404)이 분모·완료·P12 어디에도 없음 |
| CR14-24 | 2 | main-synthesis | summarize.py beats3 | 채택 | fixed(2) | 3단 범위 None을 '≫ 아님'으로 → 3단 판정 불가 무음 |
| CR14-25 | 2 | main-synthesis(수정 워커 보고) | k6/ADR-009/scripts/summarize.py gc_summary | 채택 | fixed(3) | 시각 못 읽는 GC 줄을 창 밖으로 무음 처리 — ADR-014 P12가 이 함수를 씀 |
| CR14-26 | 3 | opus | HoldStrategyStartupCheck.kt:173·202-205 · application-reconciler.yml:25-26 | 채택 | fixed(3) | reconciler 프로필이 lazy 정리 규칙에 걸려 F 기동 실패(기본값 B 교체 시) |
| CR14-27 | 3 | opus | summarize.py:1180-1193 | 채택 | fixed(3) | crn 다수결에 무효 회차 표 → 유효 회차 crn-mismatch |
| CR14-28 | 3 | opus | summarize.py:472-475 | 채택 | fixed(3) | server_rows 깨진 JSON 줄 무음 버림 |
| CR14-29 | 3 | opus OQ1 | 01-cleanup.sql:16-19 | 채택 | fixed(3) | 권한 없는 롤에서 정지 검사 무음 통과 가능 · 검사 뒤 LOCK |
| CR14-30 | 3 | opus OQ2 | summarize.py:1452-1461 | 메인 결정 | fixed(3) | A 판정 불가일 때 X = 판정 불가(보수 · 측정-7 재해석 행) |
| CR14-31 | post-fix(3) | codex | 01-cleanup.sql:37·43 · Adr014ImplSweepTest.kt:223 | 채택 | fixed(post) | 잠금에서 product_schedule이 빠져 검사 뒤 세션과 교착 가능 → 함께 잠금 |

## 생략한 검증

없음.

## 완료 요약
