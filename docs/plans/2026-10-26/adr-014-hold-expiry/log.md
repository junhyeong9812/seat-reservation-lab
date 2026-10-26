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
| DS14-OQ2 | 1 | opus | §2.3 | [구현 검증] | open | D (가) 확정은 재현 테스트 green 뒤 — 구현 때 해소 |
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

## 생략한 검증

없음.

## 완료 요약
