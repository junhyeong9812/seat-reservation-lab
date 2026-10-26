# log — ADR-013 운영 중 매수 방식 전환 (기존 데이터가 있을 때)

> 작업 폴더의 두 번째 파일(명세서와 한 쌍). **메인이 단일 writer** — 워커 산출은 packet 원시각을 명시해 메인이 append(미회수 시 "미회수" 행 필수).
> **발생 시점에 append** — 사후 재구성 금지.

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-23 17:48 | 사용자 "진행해보자"(ADR-012 PR #11 뒤 ADR-013 착수) · 새 작업 폴더 · 입력: docs/adr/ADR-013-limit-strategy-cutover.md(선택지 ①~④ 후보) · NEXT N0(ADR-012에서 넘어온 것 — 대조 전환 도구 · V9 운영 중 적용) | 인터뷰 1차 시작(L0 — 게이트 리셋) |
| 10-23 17:55 | 인터뷰 1차 답: 범위 = 매수 방식 전환 · 증분 스키마 운영 중 적용 · 대조 방식 전환(C → 증분) 모두 + 사용자 제안 '이전 카운터 계열을 이번 업데이트 형태로 바꾸는 작업으로 주제를 바꾸자' · 선택지 ①~④ 전부 · 롤링 재현 = 앱 2대 동시 기동 · 데이터 규모 = 두 규모 모두 → 주제 확인 질문: **ADR-009 구성(counter-upsert-precheck + C · updated_at 없음) → ADR-012 구성(V9 트리거 + 증분 + 안전망) 운영 중 전환만**(사용자 선택 — 원래 질문 advisory/none → counter는 NEXT 후보로) | 인터뷰 2차 |
| 10-23 21:42 | 인터뷰 2·3차 답: 전환 중 부하 = S3e + S4c · 앱 배치 = .164 둘 다 · 브랜치 = feat/adr-012 끝에서 · 고르는 기준 = 정합 > 다운타임 > 단계 수 · 측정 = 조합 둘(S4c 큰 규모 · S3e 작은 규모) × ①~④ × 3 = 24회 · V9·V10 파일 무변경(운영 중 적용판은 별도) · stakes 높음 · auto → 명세 초안(필수 6칸 · 가정 2 · task 01~07) | 사용자 합의 대기 |
| 10-23 22:08 | 사용자 '가보자' → set-state spec-approved · mode auto(SPEC=1 · MODE=auto) · 브랜치 feat/adr-013-config-cutover(feat/adr-012 끝 4b266e86에서) · task 01 시작 | 진행 |
| 10-23 22:10 | task 01 끝 9d59a1ea(ADR-013 주제 변경 — 파일 이름 counter-config-cutover · 문제·선택지 ①~④·고른 것 표 · NEXT N0 · README) · task 02 가정 스모크 워커(Opus — .164 · 프로젝트 seatlab-smoke13 · 정리 포함) 시작 · .164 가동 18시간 · 컨테이너 3(사용자 것) · 디스크 45G 여유 | 진행 |
| 10-23 22:20 | task 02 회수(Opus · .164 13:12~13:19 UTC · 프로젝트 seatlab-smoke13 · 정리 확인 — 컨테이너·볼륨·네트워크·이미지 0 · 사용자 컨테이너 무변경 · 배포 폴더 93dd610a·9d59a1ea는 남김): **가정 1 성립** — 옛 앱(93dd610a)이 V10 DB에서 Flyway WARN 1줄('version 10 newer than latest 7')만 남기고 기동 · 기동 검사 무사 · 선점 UPDATE·만료·C 복구 UPDATE마다 V9 트리거가 updated_at 갱신 · 확정은 쿼터 미변경 · C 4바퀴 오류 0 · 주입 어긋남 복구 1 · updated_at NULL 0 · 옛 reset은 counter_app_inject를 비우지 않음 · **가정 2 띄우기 성립 · 하네스 재현 부분** — 옛·새 동시 기동 · 앱 간 한도 공유(409) · 한 대 stop/start · 앱별 actuator 분리 가능 · **stop/start 뒤 컨테이너 IP 교환 실측**(nginx 정적 upstream 오경로 위험 — 동적 재해석 필요) · k6/ADR-009 하네스는 앱 1대 고정(run.sh:114 --apps 거부 · seatlab-app-1 고정 등) → 혼재 오버레이(다른 SHA 이미지) · lb 재해석 · 회차 중 내리기 오케스트레이션 · 앱별 수집 · ApplicationName 분리 · reset 소유 고정 필요 · 한계: 부하 없는 스모크 · 옛 C와 새 증분 동시 복구 경합 미재현 · 운영 중 적용판(V7 DB에 V9 적용) 범위 밖 | task 03 설계 |
| 10-23 22:39 | task 03 설계 초안 회수(Opus): ADR-013 §4.0~4.10(기존 데이터 = S4c SQL 시드 약 499만 쌍 · S3e 회차 안 · 시각표 공통 T1·T2 · ② 그림자 = 기존 손잡이 · ④ 적용판 = Flyway 밖 열→트리거→인덱스 CONCURRENTLY + 동치 확인 뒤 V9 이력 기록(skipExecutingMigrations 1순위 [구현 검증]) · Flyway V9는 ACCESS EXCLUSIVE를 끝까지 쥐어 읽기도 막음 · 한도 탐침 · 하네스 k6/ADR-013 새 폴더 · 불변식 N1~N10 · [구현 검증] 9) · 열린 질문 8 → 사용자 결정 4(S4c 1,200초 · ⑤ 추가 → 30회 · 앱 자원 각자 그대로 · 탐침 수용) + 메인 4(§1 정정 · ops/online-v9/ · N8 채택 · 메시지 추가 안 함) → 명세 재합의 기록 §10 · ADR §4.2 ⑤ 행 · §4.10 정한 질문 표 | 설계 선검증 듀얼 리뷰 |
| 10-23 23:04 | 설계 선검증 듀얼 회수: codex H 5 · M 3 · Opus H 2 · M 6 · L 4 → 종합 DS-1~16(겹침 병합) 전부 채택 · DS-1(첫 증분 틱이 기존 610만 행 — ADR-012 기본값 결함) → 사용자 결정 '이번에 고침'(명세 §2 예외 · §10 재합의) · 설계 수정 워커(Opus) | 진행 |
| 10-23 23:22 | 설계 수정 회수(Opus — DS-1~16 반영 · §4.11 첫 창 m 제외 + 키셋 페이지) · 메인 판단: DS-1 규칙 형태 = 'm 값 행 제외'(엄격 하한보다 보장을 좁히지 않음 — 사용자 결정의 뜻 유지) · N4 강제/기록·고정값 3개(보정 [0.8,1.25] · N6b 3초 · 탐침 2건/s)는 task 04 · post-fix 재점검(codex): DS-2~16 해소 · DS-1 부분 → NF-1(H — 이어 읽기 뒤 하한을 다 읽은 시각 기준으로 잘라 짧은 트랜잭션 유실) · NF-2(M — m과 같은 시작 시각의 정상 변경 제외) → 메인 수정: t₀(이어 읽기 시작 시각) 기준 클램프 · NF-2 예외 명시 + 측정 계수 | 설계 끝 → task 04 |
| 10-23 23:43 | §5 측정 전 고정 듀얼 회수: codex H 1 · M 3 · Opus H 1 · M 7 · L 4 → S5-1~14 채택 · 정합 기준 해석 → 사용자 결정 '전환이 만든 손해 0'(명세 §10) · §5 수정 워커(Opus) | 진행 |
| 10-23 23:46 | 사용자 확인 질문 '8 이전 데이터를 12 상태로?' → 출발 = ADR-009~011 반영 main 93dd610a(쿼터·카운터 있음) · 도착 = ADR-012 · ADR-008 이전(카운터 없음)은 범위 밖으로 설명 → 사용자 '지금 범위 그대로 진행 — 트래픽을 받기 위해 추가사항을 처리할 때 확장 시 어떻게 안전하게 데이터나 구조를 전환하는지에 대한 주제' | 범위 유지 · ADR §1 주제 문장에 반영 예정 |
| 10-23 23:56 | §5 수정 회수(Opus — S5-1~14 · 메인 판단 §5.7 · §1 주제 일반형 54f88ebc) · post-fix 재점검(codex): 해소 10 · 부분 4(S5-2·3·4·10) · 신규 NF-1(H — min(T)>max(O)가 관측 손해 통과) · NF-2(H — d·종류 구성 차) · NF-3(H — 결과 보고 재측정 선택 여지) · NF-4(M — 탐침 일정 미고정 · N=1) → 메인 수정 §5.8(탐침 일정 고정 · 짝 제외 · 칸별 T ≤ O · N H·L 각 1 · 절차 지연 probe-short = 실패 · P8 반증 = 멈춤 · 하네스 수정 = 30회 전체) — 설계 문서 리뷰 상한(듀얼 1패스 + 재점검 1회) 도달 · 추가 재점검 없이 확정(구현 blind 테스트·dry-run이 검증) | 측정 전 고정 확정 → task 05 |
| 10-23 23:58 | task 05 착수 — 워커 3(Opus) 병렬: 앱 코드(§4.11 첫 창 m 제외·키셋 페이지 · N8 · 지표 — reconcile-core·main-app) ∥ blind 테스트(worktree test/adr013-blind · 16fb81df 기준 계약만) ∥ 하네스·적용판(k6/ADR-013 · ops/online-v9 — 접속 없이 dry-run) · 같은 작업트리 두 워커는 경로 분리 · 커밋 각자 경로만 | 진행 |
| 10-24 00:10 | blind 회수(Opus · worktree test/adr013-blind b7e05822): Adr013FirstWindowContractTest 7 + Adr013InvalidIndexContractTest 1 · 구현 전(16fb81df) 8/8 기대 단언에서 실패(그린 위장 1건 발견·전제 추가로 고침) · 해석 H1~H11(지표 이름 page_full·carry_ticks · page-size 0 = 기동 검사 거부 · carry_ticks DistributionSummary 등) · PG16 실측: VACUUM FULL → atthasmissing false · 빈 표 ADD COLUMN도 atthasmissing true | 앱 구현 회수 뒤 cherry-pick·실행 |
| 10-24 00:32 | 앱 코드 회수(Opus bb7ecbd2): m 제외(attmissingval · 두 범위) · 키셋 페이지(page-size 기본 100,000 — ADR-012 결과 최대 틱 후보 26,591 × 2 이하 근거) · t₀ 기준 하한 · page_full·carry_ticks · N8(Schema.updatedAtIndexValid · afterLocations가 기존 INVALID 유지 · 게이트 migrate 전 거부) · 구현자 테스트 7 · 워커 전체 테스트 692/0(18m50s) · §4.9-16 PG16 확인 · blind cherry-pick → 구현 위에서 Adr013* 8/8 통과(1m27s) · 남은 §4.9-17(대량 시드 EXPLAIN)은 스모크 몫 | 하네스 회수 대기 |
| 10-24 00:43 | 하네스·적용판 회수(Opus b0a01e6e — k6/ADR-013 19파일 · ops/online-v9 · 로컬 PG로 apply.sh·SQL·탐침 실행기 확인 · selftest 17) · 위험: 사건 실제 길이에서 ②⑤ 단독(새) 탐침 전부 경계(N=0 → 매 회차 실패) → §5.8-1 규정대로 측정 전 일정 정정(칸별 첫 오프셋 30·60·120초 — a8190c9b · 기동 40초 가정 dry-run 경계 0 · selftest 고정값 갱신 17/17) · 해석 수용: ① J 짝 = lb 편입 뒤 전환 칸 + 단독(새) · 오탐 = 주입·탐침 밖 repaired | 코드 듀얼 리뷰 루프 1 |
| 10-24 00:55 | 코드 리뷰 루프 1(base 16fb81df · 미러 rv13c — k6 결과 제외 · 측정 DB 더미 비밀번호 redact): codex 6(H 4 · M 2) · Opus 10(H 1 · M 4 · L 5) — 앱 코드 결함 0(두 리뷰 공통) → 종합 CR13-1~14 채택 · 수정 워커 | 진행 |
| 10-24 01:19 | 루프 1 수정 회수(Opus): 3289c3d5(적용판) · 5048ab39(하네스) · 09acde2d(README·ADR §4.9-17) · bbba7264(앱 WARN 문구) · selftest 29/29(새 13 — 수정 전 13 실패 확인) · 로컬 PG apply.sh 경우 7 · incremental 테스트 131/0 · 메인 판단: anomaly = P9 (바) 정합 · 결측 무효는 transition-failed 없을 때 · final-app-mismatch = 절차 · inflight_unknown = 하네스(옛 앱 active 지표 유무는 스모크 확인) | 전체 테스트 + 루프 2 |
| 10-24 01:30 | 코드 리뷰 루프 2 회수: codex 3(H 2 · M 1) · Opus 12(H 1 · M 4 · L 7) · 앱 결함 0 → CR13-15~29 채택 · **정지 규칙 주의**: 비움 inflight는 CR13-6 → CR13-16(2회째) — 함수 하나로 모으고 조회 실패 = 모름 전파 · 또 깨지면 비움 방식 재설계 · 메인 결정: J 모집단 = §5.8-3 문자(전환 칸만 — 사용자 기준 '전환이 만든 손해') · ① J = 0(섞임 없음) → 측정 전 정정 행 · 수정 워커 | 진행 |
| 10-24 01:56 | 전체 테스트(bbba7264 코드 · 17m46s rc=0): 700건 실패 0 · 루프 2 수정 회수(Opus): a0703a60(비움 함수 하나 — app_inflight·drain_verdict) · 5f8970cd(요약기 — 절차 우선 · J 전환 칸만 · ① J 0) · f0a613ef(시작 조건 — 스모크 events.jsonl 실측 · stmt-timeout max(3×,600) · git archive 동결 + hhash) · 7fa9cbe8(적용판 — ② 같은 트랜잭션 확인 · test-local.sh) · f43e0e25(README) · selftest 51/51 · selftest-drain 0 실패(수정 전 13 실패) · test-local 0 실패(수정 전 1 실패) · 판단 지점: inflight 필터 = method POST(스모크 확인) · 동결 대조는 이 커밋 이후 SHA만 | 루프 3(마지막) |
| 10-24 02:42 | 코드 리뷰 루프 3(마지막) 회수: codex 2(H 1 · M 1) · Opus 5(M 2 · L 3) · 앱 결함 0 → 사용자 질문 '실제 발생할 문제들이 맞아?' → 메인 분류(발생 조건·가능성·영향) 보고 → 사용자 '수정해 그리고 커밋 후 문제없으면 스모크 진행 · 164 켜놨어' → 메인 수정(결론을 기울일 수 있는 3건): 탐침 오류 = 최소 수와 무관하게 invalid(probe-error) · 비움 판정 입력 IP 형식 검증(lb_started_after — 같은 종류 3회째 → 판정 입력 검증을 한 곳으로 · do_lb_add ip-unknown) · 끝 검사 실패 = curl 28만 판정 불가 · 그 밖 end-check-app-failed(하네스) · 기록만(고치지 않음): 옛 C 바퀴 0(T2 전 300초 넘게 60초 주기 — 거의 없음) · 멈춤 메시지 15회 문구 · test-local 1ms DROP · 스모크 폴더 출처 대조 · selftest 51/51 · selftest-drain 0 실패 · 빈 IP·주입 문자 rc 1 확인 · **리뷰 마감 = user override**(루프 3 신규 채택 3 — 재리뷰 없이 .164 스모크가 실서버 검증) | task 05 끝 → .164 스모크 |
| 10-24 11:54 | .164 첫 실서버 스모크 회수(Opus · 02:44~05:46 KST · 회차 5 모두 ok): ④ S4c · ③ S4c · 보정(load 7,687,012쌍 C 24.12초 · CREATE INDEX 0.944초 / seed 0.985 · 0.990 — 범위 안) · ② S3e · ① S4c · [구현 검증] Flyway OSS 11.7.2 skipExecutingMigrations 동작 · nginx -t 자리표시 통과 · reload 효력 0.25~0.38초 · 비움 지표(옛·새 앱 method POST 있음) · 기동→UP 6~9.5초 · CONCURRENTLY 1.23초 · 트랜잭션 안 V9 0.8초 · N6b 0 · 옛 앱 GC Full 0·stall 0 · 고친 하네스 버그: 1e56c2b4(정지 초 격자 · pre-apply-late) · 1bc8752e(nginx worker_rlimit_nofile — 500 203건) · c6a91ebf(락 표본 한 줄 — 무음 0) · 남은 위험 → 사용자 결정: ① reload 연결 리셋(빼기 1회 약 420~450건) = lb 아티팩트로 따로 세고 D1·D2에서 뺌 ② nginx max_fails=0(앱 하나 구간 시한 1회 → 10초 502 두 번) · 그대로 둠: 차가운 JVM p99 5~12초 40초(절차 성질) · ④ 첫 창 page_full 2(P7 자료) | 하네스 수정 → 짧은 재스모크 |
| 10-24 13:54 | §5.8-3b 구현 회수(Opus b3f59b75 — lb_members_conf max_fails=0 · lb_windows/lb_hit · D1_raw·D2_raw 보존 · lb-artifact-window-missing · selftest ㉔ 9 — 수정 전 8 실패) + 메인 보정(보낸 시각 기준 · 아티팩트 초는 그 초 코드 0 전부일 때만) · 스모크 재집계: p4 448 · p3 500 · p2 45 · p1 23(남은 코드 0 = 0) · D1·D2 변화 없음 · .164 꺼짐(사용자) — 다시 켜면 짧은 재스모크(① S4c · ② 1회) → 본측정 30회 | .164 대기 |
| 10-25 16:10 | 재스모크 회수(Opus · 하네스 0b870178 · 동결 해시 00d3eeb3): ① S4c ok — 새 앱 구간 502 덩어리 없어짐(5xx 119,442 → 12,888 · 502 116,315 → 28) · D1 35 → 21(중단 21만) · ② S4c ok — reload 4번 lb_artifact 614(창 missing 0) · D1 0 · 탐침 경계 0 · 하네스 버그 0 · 남은 것: 차가운 새 JVM 첫 40~47초 504(proxy_connect_timeout 5초 · 주 부하 약 8%) → 가용성 탐침 그 구간 100% 실패(D2 157 · 연속 55.5초) — §5.8-3b ⓒ(절차의 성질)로 그대로 · ⑤ 미스모크(사건 길이는 다른 절차 최대로) · 시작 조건 results/start-conditions-20261025.json(보정 0.985·0.990 · smoke_dirs 5) · dry-run ⓪~⑥ 통과 · 사용자 '스모크 끝나고 문제 없으면 바로 진행' → 캠페인 20261025-adr013-0b870178 시작 | 본측정 30회 |
| 10-25 16:45 | 캠페인 20261025-adr013-0b870178: 16:10~16:42 **1회(S4c ① rep1 ok)만 실행하고 끝 집계로 감** → P8 판정 불가 → §5.8-5 멈춤 · 원인(재현 확인): 회차 루프 `while read … done <<< "$ORDER"` 안의 run.sh(ssh·docker)가 stdin(here-string)을 읽어 남은 29줄을 소비 — 스모크는 run.sh 직접 · dry-run은 run.sh 미실행이라 못 잡음 · 계획 대 실행 대조 없어 무음 → 수정 ed2af264(run.sh < /dev/null · 끝 집계 전 미실행 자리 있으면 멈춤 exit 6) · §5.8-5: 캠페인 시작 뒤 하네스 수정 = 30회 전체 새 id로(사용자 확인) · 앞 1회 superseded | 사용자 확인 대기 |
| 10-25 17:08 | 새 캠페인 20261025-adr013-ed2af264 시작(사용자 '다시 30회') · 이전 0b870178 SUPERSEDED.txt | 진행 |
| 10-25 17:40 | 2번째 회차 시작 확인(루프 결함 해소) | 진행 |
| 10-26 07:22 | 캠페인 끝: 30/30 유효(재측정 1 — rep2 S4c ③ baseline-unstable [60,90)초 정지 12) · P7·P8 성립 · 다섯 절차 모두 성공 · 고르는 규칙 → ③(정합 단 ③④ · 멈춤·꼬리 동률 · K 2 대 6) | 결과 커밋 a992b60a(원시 요청 CSV·lb 접근 로그 gitignore · 남는 94MB) |
| 10-26 07:56 | ADR §6·§7 초안 회수(Opus): P1·P6·P11 문자 반증 + 측정 뒤 재해석(설명 가설 — 고르기 무영향) · P2 판정 불가(V9 0.78~0.85초) · P12 반증(⑤ rep1 3) · 결정 = ③ · 일반화 원칙 6(측정 범위/가설 구분) · 새로 본 것: S4c 31회 중 9회 환경 정지 덩어리(약 12초 + 6초 + 2~3초 · 앱 지표 표본 13.5초 공백 — 원인 미확인) · m_rows_post_v9 18~22(V9 시작 뒤 락 전 커밋 추정 — NF-2보다 넓은 예외로 기록) | 결과 문서 듀얼 1패스 |
| 10-26 08:07 | 결과 문서 듀얼 1패스 회수: codex 2(M 1 · L 1) · Opus 7(H 1 · M 3 · L 3) — 수치·규칙 적용 일치 · 결정 ③ 무변경 · 과장 서술 정정 RD-1~9 채택 · 수정 워커 | 진행 |
| 10-26 08:18 | 결과 문서 RD-1~9 수정(Opus — 원자료 재확인: 겹침 구간 옛 C 복구 0 · ② rep3 504 T1 4,252/T2 14,257 · 소유 공백·쓰기 수 · 적용판 발동 0) · post-fix 재점검(codex): 해소 8 · RD-4 부분('상한' 일반형) → 메인 정정(관측 = 같은 급 · 일반형 = 가설) · 신규 0 · 결정 ③ 무변경 · **검증 완료**(stakes 높음 — 설계 선검증·측정 전 고정 듀얼·blind·코드 루프 3(user override)·스모크·30회·결과 듀얼 1패스+재점검) → 사이클 마감 | README·NEXT·측정로그·아카이브·완료 요약 |
| 10-26 09:10 | 이슈 아카이브 회수(Opus · study-note archive/2026-10-26 · main 0d514551에서 · push 안 함): 아카이브: cross-cutting/database/add-column-default-shared-value ⓐ a6966045 · 아카이브: cross-cutting/database/ddl-lock-held-to-transaction-end ⓐ b49830bd · 아카이브: cross-cutting/infra/load-balancer-defaults-in-backend-switch ⓐ 52c3f0e8 · 아카이브: cross-cutting/os/subprocess-lifecycle-and-pipes ⓒ 4f00fe6c · 아카이브: cross-cutting/data/in-band-signaling-collision ⓒ dba7ca62 · 아카이브: cross-cutting/data/absent-vs-empty ⓒ 1d3f959f · 기존 카드 cross-cutting/database/catalog-presence-vs-enabled 동일 — 무변경(INVALID 인덱스 이름만 보고 통과 · ⓑ) · 원 식별자(함수·클래스·파일명·프로젝트명) 일반 이름으로 치환 · 노출 스캔 0(오탐 1: issue/README 기존 행 127.0.0.1 루프백) · 메인 교차 확인: 브랜치 추가 행에 프로젝트명·클래스명·경로·IP·attribution 0 | study-note push·main ff = 사용자 확인 대기 |
| 10-26 09:30 | 사용자 확인('ADR-012 위에 쌓기' · 'main ff 병합 + push') → study-note main ff 1d3f959f push · feat/adr-013-config-cutover push(dd2f8a23) · PR #12 생성(base feat/adr-012-incremental-reconcile — #11 병합 뒤 main으로 변경) · 사용자 지적: 결과·내용 보고 없이 push·PR까지 진행 — 이 기록 커밋은 push하지 않음 | 결과 보고 |

## 리뷰 ledger (中↑ — 듀얼 리뷰가 돈 작업 필수)

> 스키마 정본 = `playbooks/review.md §2`.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| DS-1 | 1 | opus F1(H) | V9:3-4 DEFAULT now() · IncrementalReconciler.kt:388-393·454·141-147 첫 창 무상한 | 채택 — 사용자 결정 '이번에 고침'(첫 하한 ≥ V9 적용 시각 · 후보 상한) + 지표 | fixed | 재점검 |
| DS-2 | 1 | opus F2(H) | ADR-013 N4(:137·259) 소유 공백 < 60초는 필요조건 아님 · SafetyNetReconciler.kt:426-428 | 채택 — '옛 C 마지막 바퀴 시작 ≥ 새 앱 첫 하한' · 못 넘긴 구간 기록 | fixed | 재점검 |
| DS-3 | 1 | codex F2(H) + opus F3(M) | N8 INVALID 검사를 afterLocations가 덮음(IncrementalSchemaCheck.kt:63-71) → V10 적용 뒤 거부 | 채택 — 기존 INVALID는 예측이 덮지 않음 · 테스트 DB 상태 고정 | fixed | 재점검 |
| DS-4 | 1 | codex F1(H) + opus F4(M) | ADR §4.4:171 V9만 든 위치 → V1~V7 미해석 | 채택 — 전체 위치 + target=9 · 실패 원인 분류 [구현 검증] | fixed | 재점검 |
| DS-5 | 1 | codex F3(H) + opus F7(M) | S3e 탐침 끝 대조 불성립(TTL 5분) | 채택 — 탐침 직후 스냅숏 대조 | fixed | 재점검 |
| DS-6 | 1 | codex F4(H) | S3e 전환 구간 정지 초 = 좌석 소진 구간 | 채택 — 전용 좌석 일정 도착률 탐침 · 201 지표는 S4c | fixed | 재점검 |
| DS-7 | 1 | codex F5(H) | '한 가지씩만 다름' 불성립(JVM 기동 · 적용 시각 등) | 채택 — 절차 전체 운영 결과로 판정 · 원인 비교는 교란 명시 참고 | fixed | 재점검 |
| DS-8 | 1 | opus F5(M) + codex F6(M) | lock_timeout 상속 → CONCURRENTLY 끊김·INVALID · N6 범위 | 채택 — 3단계 RESET lock_timeout · statement_timeout · N6 1·2단계 획득 대기로 좁힘 · 업무 차단 별도 관측 | fixed | 재점검 |
| DS-9 | 1 | opus F6(M) | lb 빈 upstream · reload 무음 | 채택 — 자리표시 서버 · nginx -t · $upstream_addr 확인 | fixed | 재점검 |
| DS-10 | 1 | opus F8(M) | 탐침 경계 이름표 | 채택 — 주입·판정 시각·소유 변경 이름표 · 경계 탐침 별도 | fixed | 재점검 |
| DS-11 | 1 | codex F7(M) | N1 옛 이미지 동일성(기반 이미지 미고정) | 채택 — 사전 확보 digest 고정 | fixed | 재점검 |
| DS-12 | 1 | codex F8(M) | 계획 사건 누락·시드 보정 문턱 없음 | 채택 — 계획 대 실제 대조 transition-failed · 보정 허용 범위 고정 | fixed | 재점검 |
| DS-13 | 1 | opus F9(L) | 가정 스모크가 부하 중 옛 앱 + V9 적용을 안 봄 | 채택 — 스모크 1회 추가(task 05 앞) | fixed | 재점검 |
| DS-14 | 1 | opus F10(L) | N5 attmissingval 비교 제외 | 채택 | fixed | 재점검 |
| DS-15 | 1 | opus F11(L) | S4c 끝 불일치는 약한 자료 | 채택 — task 04 고르는 규칙에 명시 | fixed | 재점검 |
| DS-16 | 1 | opus F12(L) | 시드 '열린 회차 100%' 조건 명시 | 채택 | fixed | 재점검 |
| S5-1 | 1 | opus F1(H) | ADR-013 :412 P9(마) · :245 S3e 탐침 끝 대조 — TTL 5분 만료로 다섯 절차 모두 반증 | 채택 — 스냅숏 기준 · 끝 검사는 주 부하 쌍만 | fixed | 54f88ebc · §5.8 |
| S5-2 | 1 | codex F1(H) + opus F3·F4(M) | 명세 '전환 구간 0'을 순위로만 · ②⑤ 구조 탈락 | 채택 — 사용자 결정 '전환이 만든 손해 0'(문턱 + 순위 = 단독(옛) 대비 초과) | fixed | 54f88ebc · §5.8 |
| S5-3 | 1 | opus F2(M) | 소유 공백 손해가 경계 탐침 제외로 판정 밖 · :477 근거 ① 불성립 | 채택 — [S, L*) 경계 탐침 미복구 > 0 → 반증 | fixed | 54f88ebc · §5.8 |
| S5-4 | 1 | codex F2(M) | 회차·지표별 최소 판정 탐침 수 없음 | 채택 | fixed | 54f88ebc · §5.8 |
| S5-5 | 1 | codex F3(M) | J3 첫 복구 기록 없는 쌍 미정의 | 채택 — 필수 · 없으면 실패 | fixed | 54f88ebc · §5.8 |
| S5-6 | 1 | codex F4(M) | 보정 비 범위 밖 = 사용자 결정 | 채택 — 범위 밖이면 측정 안 함(재시드 1회 · 그래도 밖이면 task 05로) | fixed | 54f88ebc · §5.8 |
| S5-7 | 1 | opus F5(M) | 단독(옛) 무효 창이 절차마다 달라 ④⑤ 불리 | 채택 — 공통 창 | fixed | 54f88ebc · §5.8 |
| S5-8 | 1 | opus F6(M) | health 시한·탐침 시한·maxVUs 미고정 · dropped_iterations | 채택 — §5.1 고정 · 버린 반복 = 실패 | fixed | 54f88ebc · §5.8 |
| S5-9 | 1 | opus F7(M) | P10 GC 기준 창 미정 | 채택 — [UP+60초, 내림 직전] · 옛 앱 단독 GC 스모크 [구현 검증] | fixed | 54f88ebc · §5.8 |
| S5-10 | 1 | opus F8(M) | '하네스로 확인' 사후 판단 · 재실행 범위 | 채택 — 기계적 기준 · 재실행 = 그 부하 15회 전체 | fixed | 54f88ebc · §5.8 |
| S5-11 | 1 | opus F9(L) | ① 구간 경계 | 채택 | fixed | 54f88ebc · §5.8 |
| S5-12 | 1 | opus F10(L) | 순환 처리 | 채택 — 순환 후보만 남김 | fixed | 54f88ebc · §5.8 |
| S5-13 | 1 | opus F11(L) | P12 문턱 4 | 채택 — 설명 가설 문턱 재검토 | fixed | 54f88ebc · §5.8 |
| S5-14 | 1 | opus F12(L) | P9(마) 첫 항 구조상 0 | 채택 — 판정 자료 아님 주석 | fixed | 54f88ebc · §5.8 |
| CR13-1 | 1 | codex F1(H) | k6/ADR-013/scripts/summarize.py:547 J가 단독(새) 칸 제외 | 채택 | fixed | 루프 1 수정 |
| CR13-2 | 1 | codex F2(H) | summarize.py:1048 칸별 T≤O가 짝 남은 칸만 | 채택 — 6칸 고정 · 짝 0 칸 = 판정 불가 | fixed | 루프 1 수정 |
| CR13-3 | 1 | codex F3(H) | summarize.py:477·689 예상 밖 응답 anomaly가 사유 아님 | 채택 | fixed | 루프 1 수정 |
| CR13-4 | 1 | opus F1(H) | summarize.py:805-818·1069 · lib13.sh:120-122 GC 자료 없으면 P10 통과 | 채택 — gc-log-missing 무효 | fixed | 루프 1 수정 |
| CR13-5 | 1 | codex F5(M) | run.sh:257 앱별 수집 실패에도 collect ok | 채택 — H4 필수 산출물 결측 = 무효 | fixed | 루프 1 수정 |
| CR13-6 | 1 | codex F4(M) + opus F7(L) | run.sh:245-253 inflight 결측 = 0 · 원인 불명 = harness | 채택 — inflight_unknown · 원인 불명 = 절차 | fixed | 루프 1 수정 |
| CR13-7 | 1 | opus F2(M) | ops/online-v9/apply.sh:198·228 'Edition' 배너로 판 제한 오분류 · V9 이력 중복 | 채택 — 고정 문구로만 · 직접 기록 전 이력 0 재확인 · 실패 경로 이력 기록 | fixed | 루프 1 수정 |
| CR13-8 | 1 | opus F3(M) | campaign.sh:68 탐침 dry-run이 사건 길이 0 | 채택 — 스모크 실측 길이 필수 · 없으면 막음 | fixed | 루프 1 수정 |
| CR13-9 | 1 | opus F4(M) | summarize.py:571 P9(가)가 계획 칸 'new'만 | 채택 — 실제 구간 new | fixed | 루프 1 수정 |
| CR13-10 | 1 | opus F5(M) | IncrementalReconciler.kt:504-507 · V9 인덱스 단일 열 — 'm 없음'에서 같은 값 묶음 비용 무상한 | 채택(기록) — WARN 문구 + ADR §4.9-17 한계 · 코드 무변경(V9 파일 금지) | fixed | 루프 1 수정 |
| CR13-11 | 1 | opus F6(L) | summarize.py:520·586 (마) 두 수 집합 다름 | 채택 — 판정 탐침으로 한정 | fixed | 루프 1 수정 |
| CR13-12 | 1 | opus F8(L) | apply.sh:150·172 INVALID DROP 결과 미확인 | 채택 | fixed | 루프 1 수정 |
| CR13-13 | 1 | opus F9(L) | k6/ADR-013/README.md:87-91 정정 전 서술 | 채택 | fixed | 루프 1 수정 |
| CR13-14 | 1 | codex F6(H) + opus F10(L) | apply.sh:35·192 · lib13.sh:208 비밀번호 명령줄 · 기본값 박힘 | 채택 — 환경변수 전달 · 값은 측정 DB 고정 더미(기존 compose와 같음 — ADR-012 RX-9 선례 잔여 위험) | fixed | 루프 1 수정 |
| CR13-참고 | 1 | opus 참고 | summarize.py:750 P7 이어 읽기 판정 · run.sh:468 끝 검사 앱 대체 | 채택 — page_full 기준 · final_app ≠ 계획 = 사유 | fixed | 루프 1 수정 |
| CR13-15 | 2 | codex(H) | summarize.py:445·721 탐침 실행기 done 누락이 최소 수 충족 시 ok | 채택 — 완료 행 누락·계획 대 완료 불일치 = invalid | fixed | 루프 2 수정 |
| CR13-16 | 2 | codex(H) + opus L2 | lib13.sh:92 · run.sh:247 URI별 조회 실패 → v=0 · 주석 불일치 | 채택 — 함수 하나·실패 전파·inflight_unknown(2회째 — 정지 규칙) | fixed | 루프 2 수정 |
| CR13-17 | 2 | codex(M) | run.sh:34·287 statement_timeout 600 고정 | 채택 — max(실측 × 3, 600) · 시작 전 검증 | fixed | 루프 2 수정 |
| CR13-18 | 2 | opus H1(H) | summarize.py:776-778·830·984 절차 원인 전환 실패가 하네스 무효로 덮임 | 채택 — 절차 사유 우선 · 전환 실패 뒤 결측은 묻지 않음 · selftest | fixed | 루프 2 수정 |
| CR13-19 | 2 | opus M1(M) | ADR §5.8-3 대 summarize.py:546-576 J 모집단 | 채택 — 메인 결정 §5.8-3 문자(전환 칸만) · ① J = 0 · ADR 정정 행 | fixed | 루프 2 수정 |
| CR13-20 | 2 | opus M2(M) | campaign.sh:71-72 · plan13.py:211-216 사건 길이 0·손값 · op 누락 | 채택 — 스모크 events.jsonl에서 op별 최대 dur_s 계산 · 모든 op | fixed | 루프 2 수정 |
| CR13-21 | 2 | opus M3(M) | run.sh:16-24 · campaign.sh:26·133 하네스 SHA 미고정 | 채택 — git diff --quiet 또는 git archive 동결 · 해시 기록·대조 | fixed | 루프 2 수정 |
| CR13-22 | 2 | opus M4(M) | §4.8 N5·N7 blind 테스트 없음 | 채택 — 로컬 PG 테스트(ops/online-v9) | fixed | 루프 2 수정 |
| CR13-23 | 2 | opus L1 | summarize.py:938 v_counter_mismatch 미사용 | 채택 | fixed | 루프 2 수정 |
| CR13-24 | 2 | opus L3 | lib13.sh:107-110 막은 쪽 판정 시간 창 없음 | 채택 | fixed | 루프 2 수정 |
| CR13-25 | 2 | opus L4 | apply.sh:98 · 04-catalog.sql:13 트리거 표 미한정 | 채택 | fixed | 루프 2 수정 |
| CR13-26 | 2 | opus L5 | apply.sh:279-282 기록 뒤 확인 실패 시 이력 남음 | 채택 — ② 같은 트랜잭션 검증 후 COMMIT · ①은 사유에 명시 | fixed | 루프 2 수정 |
| CR13-27 | 2 | opus L6 | plan13.py:323-324 pageSize 양수만 | 채택 — new = 100000 | fixed | 루프 2 수정 |
| CR13-28 | 2 | opus L7 | summarize.py:744-746 오프셋 파싱 | 채택 | fixed | 루프 2 수정 |
| CR13-29c | 3 | codex(H) | summarize.py:483·769 탐침 SQL 오류가 최소 수 충족 시 무시 | 채택 — probe-error invalid | fixed | 루프 3 메인 |
| CR13-30c | 3 | codex(M) | summarize.py:699 옛 C 바퀴 0이면 N4 생략 | 기록만(발생 거의 없음 — 사용자 확인) | user-deferred | — |
| CR13-29o | 3 | opus(M) | run.sh:247 · lib13.sh:48·93 빈 IP → 비움 통과 | 채택 — 입력 형식 검증 · ip-unknown | fixed | 루프 3 메인 |
| CR13-30o | 3 | opus(M) | run.sh:500 · summarize.py:980 끝 검사 실패 = 판정 불가 | 채택 — curl 28만 · end-check-app-failed | fixed | 루프 3 메인 |
| CR13-31 | 3 | opus(L) | campaign.sh:25·152 · README:73 15회 문구 | 기록만 | user-deferred | — |
| CR13-32 | 3 | opus(L) | test-local.sh 1ms DROP 비결정 | 기록만(측정 무관) | user-deferred | — |
| CR13-33 | 3 | opus(L) | campaign.sh:75-86 스모크 폴더 출처 미대조 | 기록만(메인 통제 입력) | user-deferred | — |
| RD-1 | 1 | opus F1(H) | ADR §7.1 단계 2 · §7.3 혼재 정합 — 겹침 구간 옛 C 복구 0 · 같은 쌍 동시 복구 0인데 PairRepair 직렬화 검증처럼 | 채택 — '실행되지 않음(근거 = ADR-012 §3-2 설계)' · §7.3 가설 칸으로 | fixed | 결과 리뷰 수정 |
| RD-2 | 1 | opus F2(M) | ② rep3 504 18,509 중 4,252가 T1 그림자 앱(B1) | 채택 — T1·T2 몫 분리 · 예열 원칙 반례 1/12 | fixed | 결과 리뷰 수정 |
| RD-3 | 1 | opus F3(M) | §7.3 소유권 넘김 근거 혼합(J3 51초는 소유 설계) | 채택 — 공백 길이·쓰기 수만 · 손해 미관측 · J3 인용 삭제 | fixed | 결과 리뷰 수정 |
| RD-4 | 1 | opus F4(M) | §7.3 락 '상한' · lock_timeout 효과(발동 0) | 채택 — 조건부 · 효과 미측정 | fixed | 결과 리뷰 수정 |
| RD-5 | 1 | codex F1(M) | §6 옛 앱 새 스키마 호환 '30회' — ①은 미시험 · T2 뒤 재기동 미측정 | 채택 — 24회 · 되돌리기 미측정 | fixed | 결과 리뷰 수정 |
| RD-6 | 1 | opus F5(L) | ③④ 전환 정지 초 0 단정 | 채택 — 원인 미확인 | fixed | 결과 리뷰 수정 |
| RD-7 | 1 | codex F2(L) | N6b '0초' 단정 | 채택 — 0.2초 표본 관측 0 | fixed | 결과 리뷰 수정 |
| RD-8 | 1 | opus F6(L) | J1 식 −6/15 | 채택 — −2/5 | fixed | 결과 리뷰 수정 |
| RD-9 | 1 | opus F7(L) | 되돌리기 경로 차가운 JVM 위험 누락 | 채택 — 주의 1줄(측정 밖) | fixed | 결과 리뷰 수정 |

## 생략한 검증 (긴급 경로 빚 — DEBT=1인 동안 정본)

- (없음)

## 완료 요약 (2026-10-26 — 스니펫은 git 출력 그대로)

**무엇이 됐나**
- 주제: 운영 중 카운터 대조 구성 전환(ADR-009 구성 main 93dd610a → ADR-012 구성 · 기존 데이터 · 롤링 배포 · 앱 2대 혼재) — 질문의 일반형 = 트래픽을 받기 위해 구조를 더할 때 운영 중 데이터·스키마를 안전하게 전환하는 법(사용자 2026-10-23).
- 측정: 캠페인 20261025-adr013-ed2af264 — 5절차(① 멈추고 바꾸기 · ② 그림자 · ③ 대조로 따라잡기 · ④ 트리거 먼저 · ⑤ 적용판 + 그림자) × S4c·S3e × 3 = 30회 유효(재측정 1) · 앞 캠페인 0b870178은 루프 결함(1회만 실행)으로 대체.
- 결정: 기본 전환 절차 = **③**(정합 단 ③④ · 멈춤·꼬리 동률 · K 2 대 6) · ADR-012 기본값 유지 · 일반화 원칙 6(측정 범위/가설 구분).
- 함께 고친 ADR-012 결함(사용자 결정 — 명세 §2 예외): 기존 데이터 위 첫 증분 창이 기존 행 전부를 읽음 → V9 값 m(attmissingval)인 행 제외 + 키셋 페이지(page-size 100,000) · INVALID 인덱스 마이그레이션 전 거부(bb7ecbd2 · blind ea6cd0b9 류 b7e05822).
- 하네스 k6/ADR-013(새) · 운영 중 적용판 ops/online-v9(Flyway 밖 열→트리거→인덱스 CONCURRENTLY + 동치 확인 뒤 V9 이력).

**핵심 diff**

① `reconcile-core/.../IncrementalReconciler.kt` — 첫 창 m 제외(두 범위)·키셋
```kotlin
356: * - 페이지가 덜 찼다(다 읽음): `L = max(min(pending, next_k), t₀ − max-overlap − 1초)` — t₀ = 이 이어 읽기를 시작한 틱의 문장 1 시각(이어 읽기가 없으면 t₀ = t · pending 없음 → `L = next_k` — ADR-012 규칙 그대로).
372:    /** 이어 읽기 상태(§4.11 ② — 틱 스케줄러 한 스레드만 take를 부른다): 키셋 위치 p · 최솟값 pending · t₀ · 이어진 틱 수 · 겹침 상한 넘김 WARN 여부 */
475:                log.warn("증분 이어 읽기가 max-overlap({})보다 오래 이어진다 — 시작 {} · {}틱째 · page-size {} — 다 읽은 뒤 하한은 t₀ − max-overlap 앞으로 가지 않는다", inc.maxOverlap, t0, ticks, pageSize)
479:            // 다 읽었다 — L = max(min(pending, next_k), t₀ − max-overlap − 1초)(재점검 NF-1)
480:            val guard = t0.minus(inc.maxOverlap).minus(MARGIN)
481:            if (pending.isBefore(guard)) {
```

② `ops/online-v9/` 파일 목록
```
ops/online-v9/01-column.sql
ops/online-v9/02-function.sql
ops/online-v9/02-trigger.sql
ops/online-v9/03-index.sql
ops/online-v9/04-catalog.sql
ops/online-v9/05-history.sql
ops/online-v9/apply.sh
ops/online-v9/test-local.sh
```

③ `k6/ADR-013/scripts/campaign.sh` — 루프 결함 수정
```bash
173:# 계획 대 실행 대조 — 모든 자리에 status가 있어야 끝 집계로 간다(무음 금지 — 2026-10-25 루프가 1회 뒤 끝났는데도 집계로 갔다)
```

**검증**: 설계 선검증 듀얼(DS-1~16) + 재점검(NF-1·2) · 측정 전 고정 듀얼(S5-1~14) + 재점검(NF-1~4) · blind 8(구현 전 8 실패 → 구현 뒤 8/8) · 전체 테스트 700/0 · 코드 루프 3회(CR13-1~33 — 루프 3 신규 채택 3 → user override · L 4 user-deferred) · .164 스모크 6 + 재스모크 2(하네스 버그 3 수정) · 본측정 30회 · 결과 문서 듀얼(RD-1~9) + 재점검(신규 0 · RD-4 메인 정정).
**배운 것**: ① 측정 전 고정 규칙도 실서버 사건 길이를 넣은 dry-run을 거쳐야 한다(탐침 경계 · 루프 stdin — 둘 다 dry-run이 사건 길이 0 · run.sh 미실행이라 못 잡음) ② lb·JVM 같은 측정 도구의 동작(reload 리셋 · max_fails · 차가운 JVM)이 절차 손해를 부풀린다 — 측정 대상과 도구 아티팩트를 측정 전에 갈라 둔다 ③ 리뷰가 조용한 실패 경로를 계속 찾으면 판정 입력을 한 곳에서 검증하는 구조로 바꾼다(비움 판정 3회째).
**남은 빚**: 없음(DEBT=0). PR은 사용자 확인 대기(PR #11 미병합 — 대상 브랜치 결정 필요). 후속 = NEXT N0c.
