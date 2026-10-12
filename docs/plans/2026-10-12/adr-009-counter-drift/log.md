# log — ADR-009 카운터 어긋남 탐지·격리·복구 + 판매 마감 + 쿼터 행 정리

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-12 07:xx | 인터뷰 3차(범위·후보·주입·브랜치 / 번호·추가 범위·측정·결정 기준 / 복구 정책·행 정리·모드 / 시작 후 선점 / 마감 시각) | 명세 초안 작성 — 사용자 합의 대기 |
| 10-12 07:xx | 실코드 확인: startsAt을 읽는 코드 없음(판매 기간 미정의 — ADR-000 설계 누락, 사용자 확인) · 부하 시드 회차 시각 2026-12-31 19:00+09(이후 하네스 전부 거절될 위험 — 기록) · 만료 배치 단일 트랜잭션 재조회 | 명세 §0 근거 |
| 10-12 07:xx | 사용자 합의 "진행하자" → SPEC=1 · MODE=auto(set-state) · 브랜치 feat/adr-009-counter-drift(feat/adr-007-throughput-wall 4b137670 위 — PR #7 병합 후 main으로 rebase, push 전) · task 00(번호 이동, Opus 워커) ∥ task 01(설계 선검증, Opus 워커) 위임 | 진행 |
| 10-12 08:xx | task 00 회수 cd05733d(번호 이동 009~014 → 010~015 · ADR-000 판매 기간 정정 메모 · NEXT N1b/N1c) — 메인 교차 확인: 파일 목록·ADR-008:7 이름 정정 · 애매 1건(ADR-007 괄호 '2026-10-11 … ADR-010') 메인 정리 커밋 · 코드 주석 옛 번호 2곳(ConfirmReservationService:19 → 011, ExpireHoldsService:11 → 010)은 구현 단계에서 | task 00 완료 |
| 10-12 08:xx | task 01 설계 선검증 회수(Opus, scratchpad/adr009/design.md 596줄 + SQL·실험 E1~E10·B1~B4, 임시 PG16 — 컨테이너 정리 확인 보고) · 메인 교차: exp-out-2 E7(한 문장 DELETE … NOT EXISTS가 진행 중 선점을 무시하고 행 삭제)·E8(잠금 후 재확인 안전) 원문 확인 · 사용자 결정 D1~D8 · codex 교차 검증 시작(미러 scratchpad/rv15, 결과 폴더 제외, 보안 스캔 0건) | 진행 |
| 10-12 09:xx | codex 설계 교차 검증 회수: 지적 7(C1 마감 회차 대조 제외 → 무음 · C2 정리① 회차 조건 재확인 누락(E8이 다른 사용자 홀드 남은 채 삭제) · C3 정리①이 확정 쌍 삭제 → 판정기 불일치 · E1 E10이 실제 reconcile.sql 미실행 · E2 B2 끝 상태 정합 미검증 · B1 트리거 무변화 UPDATE · O1 lock_timeout 뒤 skipped 기록 경계) + 메인 발견 M1(B2 끝 상태 = 확정 + 좌석 AVAILABLE — 확정·만료 둘 다 좌석 행 무잠금·@Version 미매핑, 기존 위험 → ADR-011) · 사용자 결정 8건 → 명세 §10 재합의 | 설계 수정 위임 |
| 10-12 09:xx | 사용자 "병합 진행하고 가자" → PR #7 merge commit fb59ecbb(MERGEABLE·CLEAN, 체크 0) · feat/adr-009-counter-drift를 origin/main 위로 rebase(push 전 — 원격 브랜치 없음 확인): cd05733d → 9b8be68d · 8f4740ae → 6a15678d · 7102ba6b → 4baf138e · 로컬 main ff → fb59ecbb | 구현 브랜치 준비 |
| 10-12 10:xx | 설계 v2 회수(Opus): v1 청크 대조 오류 발견(162만 쌍 155초 → 두 갈래 15초) · C2a 회차 FOR SHARE/FOR UPDATE 직렬화 실증 · M1 끝 상태는 DELETE 행 수 확인 생략 탓(행 수 확인 넣으면 6경우 모두 정합) · 매트릭스 84회 ≈ 18.7시간 | codex 재점검 |
| 10-12 10:xx | codex 재점검: 기존 7 중 6 해소·E1 부분 · 신규 N1(행 없음 SQL 열린 회차 필터·예약 갈래 미실측) · N2(마감 판정 순서가 실제 호출 경로·전략별로 미보장) · N3(무관한 열린 회차 하나로 판정기 공허 통과) → 설계 재루프 없이 구현 합격 항목으로(§11) · 사용자 R1 FOR SHARE · R2 두 갈래 · R3 60초 | 설계 선검증 종료 |
| 10-12 10:xx | 구현 검증 대장 신설 `docs/implementation-verification.md`(IV-009-1~7) · 설계 v2·SQL을 작업 폴더에 보존(design-v2.md·design-sql/ — scratchpad 유실 방지, 2파일 규칙의 편차) · 계약 표면 contract.md(blind 테스트용 이름 발췌) | task 02 ∥ 03 위임 |
| 10-12 10:xx | 사용자 제기: 기존 데이터가 있을 때의 방식 전환 → 별도 ADR(사용자 결정), 번호 = 009 어긋남 → 010 전환(사용자 결정 — 의존: 전환이 009 대조·복구·트리거 사용) · NEXT N1b2 등재 · 문서 번호 이동(010~015 → 011~016)은 구현 워커 종료 뒤(작업트리 동시 커밋 충돌 회피) · 서버 디스크 읽기 전용 확인: nvme0n1 = Windows 전체 설치(EFI·MSR·NTFS·복구), 펌웨어 부팅 순서 Ubuntu → Windows Boot Manager, Linux에 미마운트 | 기록 |
| 10-12 12:15 | task 03 blind 테스트 회수(Opus, worktree agent-a73d6e3de90a34655, 브랜치 worktree-agent-a73d6e3de90a34655, 커밋 9a3aa01a — 새 파일 9개, design 미열람): A·C·F·오탐·B·마감·마감 순서(허용 조합 23개)·정리①②·판정기·기동 검사 · 구현 전 컴파일 실패는 계약 이름 미구현뿐 · 스텁 복사본에서 새 동작 단언만 실패(공허성 근거) · 약점: 기동 실패 단언이 원인 무관(양성 대조로 보완) · 계약 밖 이름 4건 → 구현 워커에 전달(패키지 impl.drift·skipped_schedules·hikaricp.connections.acquire·reconciler 프로필 yml) | 구현 회수 대기 |
| 10-12 12:2x | task 02 구현 회수(Opus): d2153431(V7·V8) · b3157ab5(main 전체) · 63ebe0f4(구현 확인 테스트 6파일 + IndexMigrationTest 1곳) · 231/0/0 · 계약 이름 변경 0(+ 추가 키·지표) · N2: HoldSeatProcess 트랜잭션 안 limit.acquire 전 SalesCloseGate FOR SHARE, SERIALIZABLE은 acquireFirst, Redis NX 진 경로는 잠금 없는 판정 · 순서 바꾼 코드로 4건 실패 확인(테스트 실효) · 해석 3건(holds_seen 0/1 · run.borrows에 기록 INSERT 포함 · 판정기는 회차 행 없는 쌍은 센다) · IV-009-5·6 해소 · blind 커밋 9a3aa01a를 cherry-pick → d11bee68, 전체 테스트 실행 | 진행 |
| 10-12 12:5x | blind 포함 전체 `./gradlew test` rc=0 · 465/0/0/0(XML 집계 — blind 클래스 전부 실행 확인, 마감 순서 23조합 각 7) · blind 첫 실행 전부 통과 | 리뷰 루프 1 시작 |
| 10-12 13:xx | 5차 번호 이동(사용자 결정: 새 ADR-010 = 운영 중 전환): 파일 010~015 → 011~016 · 참조 +1(머리 메모 줄·'새 ADR-010'·N1b2 줄 제외) · 정규식이 놓친 'ADR-012·012' 2곳(README·ADR-001) 수동 정정 · 링크 BROKEN 0 · 코드 주석 3곳(ExpireHolds 011·Confirm 012·UserLimitStrategies 012) 별도 커밋 · 대장 IV-009-5·6 해소 표시 | 리뷰 루프 1 진행 중 |
| 10-12 13:xx | 리뷰 루프 1 회수: codex R1~R4 · Opus F1~F13(P1 0·P2 2·P3 11) → 종합 L1-1~L1-15 · 사용자 결정 3: B 증가 경로도 엄격(행 없음 → 오류, 확정 실패 감수) · 트리거는 마감 회차 건너뜀(DB now() — 앱 Clock과 출처 차이 기록) · C·F 켜면 A 필수(단 counter-trigger는 예외 — 트리거가 마감 회차를 건너뛰어 마감 회차 만료 정지 경로가 없음) | 수정 위임 ∥ codex 종합 감사 |
| 10-12 18:3x | 루프 1 수정 회수(Opus): e81b4197·dc23f1bc·0b38a3f8·dd747077·21d6da45 · 483/0/0/0 보고 · 메인 확인 필요 2: 트리거 기준 시각 이음새 `seatlab.counter_now`(세션 설정, IntegrationTest connection-init-sql — 운영엔 비어 있음) · blind CounterReconcileTest 설정에 expiry-repair=true(L1-10 따른 설정 맞춤, 단언 무변경) · 새 위험 6(③ 마감 끔 + B → 한도 우회 = 결함, 루프 2로) · 메인 전체 테스트 재실행 + 리뷰 루프 2 시작 | 진행 |
| 10-12 18:3x | 리뷰 루프 2 첫 시도 무효: scratchpad/rv17이 이전 세션의 읽기 전용 미러로 남아 있어 rm 실패 → packet 생성 실패(무음 — 명령 체인이 실패를 끝까지 전파하지 않음), codex가 '_packet에 packet-diff 없음'으로 판정 보류 → 결과 폐기 · rv18에 새로 생성(존재 확인 후, 각 단계 실패 표시) · Opus 리뷰어에 새 경로로 재지시 | 재시작 |
| 10-12 19:xx | 리뷰 루프 2 회수(codex 4 · Opus P2 1·P3 9) → L2-0~L2-12(채택 10·기각 1·기록 2) · 메인 판정 오류 인정: 루프 1에서 §10 문구(FOR UPDATE)와 다른 수정을 재합의 없이 채택 | 수정 위임 |
| 10-12 19:xx | 루프 2 종합 감사(codex): 메인 판정 오류 2(L2-3 기각 근거 부족 → 채택 · L2-1 '비용 없음' 과장 → 측정 대상) + 방향 보강 2(L2-4 확정 경로 포함 · L2-2 회차 행 잠금으로 직렬화) → 구현 워커에 추가 전달 | 수정 진행 |
| 10-12 19:0x | 루프 2 수정 회수(Opus): 002292e3·772c7787·f35a50af · 492/0/0/0 보고 · 테스트 픽스처 기본 회차 NOW+1일 → 2099-12-31(이음새 제거로 직접 DB 쓰기가 DB now() 판정 — 단언 무변경, 주석) · 새 위험: B 경로 set_config 1문장(왕복 +1) · beforeConfirm 공용 확장점 · 대조 복구 회차 FOR SHARE | 루프 3(codex ∥ Opus, rv19) + 메인 전체 테스트 |
| 10-12 19:3x | 리뷰 루프 3(상한) 회수: codex 신규 1(N3-1 높음) · Opus 신규 P3 3 → L3-1~L3-4 채택 · N3-1은 두 리뷰어 판정이 갈림(메인: codex 채택 — 경계 동시 선점의 한도 초과 가능) · 루프 상한 도달 → ADR-008 선례대로 수정 + codex 타깃 재점검으로 종료(사용자 보고) | 수정 위임 |
| 10-12 19:2x | 루프 3 수정 회수(Opus): 0392b17d(L3-1 판정 시각 한 번 채집 — ThreadLocal, hold finally에서 clear) · 5cd9fcce(L3-4 V7 skipped_closed 열·run 필드 / L3-3 주석) · c696b459(테스트: Loop3ImplTest · ReconcileCloseRace 재작성 — FOR SHARE 제거 시 실패 확인 · MutableClock.setForOneRead) · 493/0/0/0 보고 | codex 타깃 재점검 ∥ 메인 전체 테스트 |
| 10-12 19:4x | 메인 전체 테스트 493/0/0/0 · codex 타깃 재점검(rv20): L3-1 fixed · L3-3 fixed · L3-2 partial(700ms 수면이 FOR SHARE 도달 보장 안 함) · L3-4 partial(drift 지표 action=skipped에 마감 제외 섞임, 하네스·ADR 분리는 이후) · 신규 N(MutableClock 스레드 간 가시성) → 구현 워커에 마감 수정 3건(이후 메인이 diff·테스트로 확인하고 리뷰 종료 — 루프 상한) | 진행 |
| 10-12 19:3x | 재점검 마감 수정(Opus): 4b4ba691(마감 제외는 excluded{pairs}, drift skipped는 실패만) · 370a5166(MutableClock AtomicReference · 경합 테스트 pg_stat_activity 대기 확인 — FOR SHARE 제거 시 실패 재확인) · 메인 diff 확인 + 전체 테스트 493/0/0/0 → **코드 리뷰 종료**(루프 상한 3 + 타깃 재점검, ADR-008 선례). 남은 기록 항목: L2-10(B 엄격 범위 ADR) · L1-16/L2-11(하네스 WAL·MultiXact) · L3-4 하네스 분리 집계 · L2-9 ADR 정의 | task 05 하네스로 |
| 10-12 20:4x | task 05 하네스 회수(Opus): 728c236c(k6/ADR-009 20파일) · 매트릭스 84회 ≈ 19.2시간 · 서버 스모크 5/5 ok(control S2 빌림 1.001 · A+F S2 F 8102 첫 바퀴 40초 · B S2 트리거 2 · A+C S3e 주입 60쌍 100% 복구·오탐 0·틱 실패 0·빌림 1.000117 · S3c control+schedule 마감 뒤 선점 79,951 전부 SCHEDULE_CLOSED·확정 140 성공·정리① 홀드 남은 동안 0 → 마지막 12,031행) · 서버 flyway V7 success(읽기 전용) · 서버 정리 · 명세 편차: 주입 시각 +420s → +270s(설계 조건으로 +420s 후보 0) · IV-009-7 C 세션은 문장 표지 근사(앱 풀 공유) · 오프라인 rep_status 39/39 · summarize 41/41 | 하네스 듀얼 1패스 |
| 10-12 20:5x | 하네스 듀얼 1패스 회수: codex R1~R7 · Opus F1~F3·P3-1~10 → H-1~H-18(채택 17·기록 1) · H-10 B+C 면제 범위는 L2-5 사용자 보고 범위로 판정(새 결정 아님) | 하네스 수정 위임 |
| 10-12 22:5x | 하네스 수정 회수(Opus): b3351b59(H-1~H-17·H-18 README) · 5e4c68b0(틱 실패 원인 분류 — V8 SR001은 로그에 'counter row missing'만) · 오프라인 rs 50/50 · py 69/69 · 로컬 PG 주입 SQL · 서버 스모크: control S3e ok(틱 실패 70 = 주입 원인뿐, 오래된 HELD 2,120 = 정지 재현) · B+C S3e 1회 effect-unexpected(분류기 결함 — 하네스 결함 1건 실측 발견) → 재실행 ok(60/60, 틱 실패 3 전부 주입 설명) · S3c zero ok · A+C S3e 회귀 ok · 해석 확인 필요 2(H-2 마감 전 성공 창 330초 · H-3 A+C·A+F expiry-repair 최소 1건 미요구) · 본측정 SHA 5e4c68b0 | codex 타깃 재점검 + 사용자 확인 |
| 10-13 00:xx | 사용자 확인 3: H-2 마감 전 성공 창 330초 승인 · H-3 A+C·A+F expiry-repair 최소 1건 미요구 승인 · 본측정 = 재점검 clean이면 바로 시작 / codex 하네스 타깃 재점검(rv22): fixed 13 · partial 4(H-5·7·10·13) · 신규 N-1~N-5 → N-1 기각(정적 추론 '마감 뒤 확정 성공 불가' — S3c zero 스모크 실측 101건 OK가 반례) · N-2(기대 바퀴 수가 fixedDelay·실행 시간 무시)·N-3(B+C 면제 초 반올림·빈 파서)·N-4(잠금 후 재확인 조건 일부)·N-5(잘림을 결과 사유로) 채택 → clean 아님, 수정 후 시작 | 하네스 수정 위임 |
| 10-13 00:xx | 사용자 지적: "원래 문서 먼저 쓰고 그에 맞게 테스트하는 거 아니야?" — 맞다(ADR-007 선례 7377fec9: 명세·가설·측정 계획 문서를 측정 전 커밋). ADR-009 ADR 문서를 측정 뒤로 미룬 것은 메인 누락 → 측정 전 고정 문서 ADR-009-counter-drift.md(문제·선택지·가설 H1~H8·측정 계획 84회·판정 기준·한계) + ADR-010 가설 문서 작성 · 본측정은 이 문서 커밋 뒤 시작 · 문서 듀얼 1패스(rv23) | 진행 |
| 10-13 01:xx | 하네스 마감 수정 회수 285ba256(N-2~N-5, N-1 기각 근거 README) · 메인 오프라인 재실행 py 81/81 · rs 50/50 · 측정 전 문서 듀얼 1패스(codex R1~R4 · Opus F1~F9) → DOC-1~9 반영(사용자 결정: 비용 판정에 p99·벽 시각 추가) · 사용자 질문 '디스크 문제를 메모리에 모아 한 번에 flush로 우회?' → 아님(tmpfs = 측정용 디스크 제거, 그룹 커밋·synchronous_commit은 N1a 미시험 후보) | 문서 커밋 → 본측정 |

## 리뷰 ledger

- review packet(설계): rv15 — 미러(fb181a30 전 HEAD tracked, 결과 폴더 제외) + design·SQL·실험 · 보안 스캔 0건

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|----|-----------------|--------|------|-------------|--------|---------------|
| DC1 | 0 | codex | design §4 마감 회차 제외 | 채택 — 사용자 §10(카운터 안 씀, 제외 기록) | fixed | v2 |
| DC2 | 0 | codex | reconcile.sql 정리① 사용자 단위 재확인 | 채택 — 회차 행 FOR SHARE/FOR UPDATE 직렬화(C2a 실증) | fixed | v2 |
| DC3 | 0 | codex | 정리① 확정 쌍 삭제 → 판정기 | 채택 — 판정기 마감 회차 제외(C3 실증) | fixed | v2 |
| DE1 | 0 | codex | E10이 실제 SQL 아님 | 채택 — 실측으로 v1 오류 발견, 두 갈래 | partial → N1 | v2 |
| DE2 | 0 | codex + main M1 | B2 끝 상태 | 채택 — 선잠금 = 교착만, 정합은 ADR-011(IV-009-1) | fixed(범위 밖 이연) | v2 |
| DB1 | 0 | codex | V8 트리거 무변화 UPDATE | 채택 | fixed | v2 |
| DO1 | 0 | codex | lock_timeout 뒤 skipped | 채택 — 롤백 뒤 독립 트랜잭션 기록 | fixed | v2 |
| N1 | 1 | codex | orphan-v2.sql:2 | 채택 — 구현 합격 항목(§11) | open | — |
| N2 | 1 | codex | HoldSeatService.kt:27 · HoldSeatProcess.kt:45 | 채택 — 구현 합격 항목(§11) | open | — |
| N3 | 1 | codex | design §5a 판정기 | 채택 — 하네스 미판정 사유(§11) | open | — |

- review packet(코드 루프 1): rv16 — 미러(2269f25e..d11bee68 src diff + spec·contract) · 보안 스캔 0건

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| L1-1 | 1 | opus F1 | V8__counter_trigger.sql:11-13 · UserLimitStrategies.kt:316-320 | 채택 — 사용자: 증가 경로도 엄격(행 없음 → SR001) | fixed | e81b4197..21d6da45 |
| L1-2 | 1 | codex R1 | V8:15 · QuotaCleanup.kt:130 | 채택 — 사용자: 트리거가 마감 회차(starts_at ≤ now()) 변경은 건너뜀 | fixed | e81b4197..21d6da45 |
| L1-3 | 1 | codex R2 + opus F6 | HoldSeatService.kt:27-29 · UserLimitStrategies.kt:116-124 | 채택 — prepare가 마감 회차에 행을 만들지 않게(같은 문장 안 조건) · 거절 전후 쿼터 행 전체 비교 테스트 | fixed | e81b4197..21d6da45 |
| L1-4 | 1 | codex R3 | CounterReconciler.kt:239 · PairRepair.kt:135 | 채택 — skipped 기록마저 실패하면 바퀴 결과를 실패로(run errors·지표), 성공 위장 금지 | fixed | e81b4197..21d6da45 |
| L1-5 | 1 | codex R4 | application-reconciler.yml:3 | 채택 — F 프로필에서 업무 컨트롤러(선점·확정·부하 시드) 비활성 + 검증 | fixed | e81b4197..21d6da45 |
| L1-6 | 1 | opus F2 | drift 테스트 | 채택 — 가정 1 동시성 테스트(A·C 각각: 커밋 전 선점 중 복구 → cnt = 실제) | fixed | e81b4197..21d6da45 |
| L1-7 | 1 | opus F3 | CounterReconciler.kt:97-107,178-187 | 채택 — 회차·bounds 조회 실패도 청크 실패처럼 처리, errors·skipped_chunks 지표 | fixed | e81b4197..21d6da45 |
| L1-8 | 1 | opus F4 | PairRepair.kt:64-75 | 채택 — 행 없음 + 실제 0이면 INSERT 없이 false-positive | fixed | e81b4197..21d6da45 |
| L1-9 | 1 | opus F5 | QuotaCleanup.kt:117-118 | 채택 — 회차 행 FOR NO KEY UPDATE(선점 FOR SHARE와는 계속 충돌, FK KEY SHARE는 안 막음) | fixed | e81b4197..21d6da45 |
| L1-10 | 1 | opus F7 | HoldStrategyStartupCheck.kt:78-90 | 채택 — 사용자: C·F 켜면 A 필수(counter-trigger 예외) | fixed | e81b4197..21d6da45 |
| L1-11 | 1 | opus F8 | CounterDriftStartupCheckTest.kt:66-80 | 채택 — 기동 실패 원인 메시지 단언(fix verification — blind 파일 수정은 이 단언 강화만) | fixed | e81b4197..21d6da45 |
| L1-12 | 1 | opus F9 | V7:39 vs CounterReconciler.kt:44,124 | 채택 — 정의는 '기록 INSERT 포함'으로, 코드 주석·ADR에 명시(V7 수정 금지) | fixed | e81b4197..21d6da45 |
| L1-13 | 1 | opus F10 | CounterDriftScheduling.kt:36 | 채택 — F 프로필 source 선택 테스트 + F 프로필인데 reconcile.enabled=false면 기동 실패 | fixed | e81b4197..21d6da45 |
| L1-14 | 1 | opus F11 | LoadtestDataService.kt:14-18 · HoldStrategyStartupCheck.kt:109 | 채택 — 카운터 계열 집합 단일 출처 · 트리거 2개 모두 존재 검사 | fixed | e81b4197..21d6da45 |
| L1-15 | 1 | opus F12 | CounterReconciler.kt:267 | 채택 — 지표 `…reconcile.excluded{unit}`로 이름 변경(계약 밖 지표) | fixed | e81b4197..21d6da45 |
| L1-16 | 1 | opus F13 | SalesCloseGate.kt:38 | 기록 — 결함 아님(§11 R1 측정으로 판정), 하네스에 MultiXact·WAL 레코드 지표 추가 | deferred(하네스) | — |
| L1-17 | 1 | 감사 A1 | V8:15-19,29 · ExpireHoldsService.kt:30-43 | 기록 — B 엄격의 결과(열린 회차 행 없음 → 복구까지 틱 롤백). B+C 복구 뒤 정상화 테스트 · 효과 셀에서 B 틱 실패는 결과로 판정 | fixed | e81b4197..21d6da45 |
| L1-18 | 1 | 감사 A2 | HoldStrategyStartupCheck.kt:76-85 · application-reconciler.yml | 채택 — F 프로세스의 A 검사 제거, 업무 앱 `external-reconciler` 키(true + 비trigger → A 필수) · 명세 측정 조건 C·F = A+C·A+F | fixed | e81b4197..21d6da45 |
| L1-19 | 1 | 감사 A3 | V8:30-35,46-51 | 채택 — 트리거 마감 건너뜀은 기여 쪽별 판정 | fixed | e81b4197..21d6da45 |
| L1-20 | 1 | 감사 A4 | HoldSeatService.kt:27-30 · UserLimitStrategies.kt:116-125 | 채택 — prepare 판정은 앱 Clock 파라미터, 경계 잔여 cnt 0 행 무해 테스트 | fixed | e81b4197..21d6da45 |

- review packet(코드 루프 2): rv18 — 미러(2269f25e..21d6da45 src 누적 + 루프 1 수정분 + spec·contract) · 보안 스캔 0건 · 첫 시도(rv17) 무효 기록 참고

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| L2-0 | 2 | main-synthesis | HoldStrategyStartupCheck · V8 마감 건너뜀 | 채택 — counter-trigger + sales-close.enabled=false → 기동 거부(앱은 마감 회차 선점을 받는데 트리거는 증가를 건너뛰어 한도 우회) | fixed | 002292e3..f35a50af |
| L2-1 | 2 | codex P1 + opus F3 | QuotaCleanup.kt:117-120 | 채택 — 명세 §10대로 FOR UPDATE 복귀(루프 1 L1-9의 FOR NO KEY UPDATE는 재합의 없는 명세 이탈이었다 — 메인 판정 오류). 정리 ①은 홀드 0 회차만이라 확정(홀드 필요)은 그 사이 오지 않음 · 감사: 앱 밖 직접 INSERT의 FK 검사와는 경합 가능 → '비용 없음' 철회, 측정 대상 | fixed | 002292e3..f35a50af |
| L2-2 | 2 | codex + opus F5 + 감사 | CounterReconciler.kt:128,242-255 · PairRepair · QuotaCleanup.kt:115 | 채택 — 복구 트랜잭션에서 회차 행 FOR SHARE 후 마감 재확인(정리 ① FOR UPDATE와 직렬화), 마감이면 skipped(reason=closed) | fixed | 002292e3..f35a50af |
| L2-3 | 2 | codex + 감사 | PairRepair.kt:65 · spec §2 | 채택(메인 기각 철회 — 감사: '행 없음 = 0'은 값 표현이고 §2 복구 절차(행 생성·잠금·덮어쓰기)를 면제하지 않음) — A 경로도 행 생성·잠금 후 0 확정 | fixed | 002292e3..f35a50af |
| L2-4 | 2 | codex + opus F2 | V8:16-19 · IntegrationTest.kt:29-34 | 채택 — 트리거 기준 시각을 앱이 B 트랜잭션마다 앱 Clock으로 SET LOCAL(같은 빌림 안 문장) → 시각 출처 단일화, connection-init-sql 이음새 제거, 직접 DB 쓰기는 now() 폴백 + 폴백 테스트 · 감사: 확정 경로(IDENTITY 즉시 INSERT) 포함, 첫 트리거 전 실행 | fixed | 002292e3..f35a50af |
| L2-5 | 2 | opus F1 | UserLimitStrategies.kt:330-344 · ExpiryCounters.kt:254-259 | 채택 — B 만료 열린 회차 cnt < n도 시도 목록(kind=below)에 올려 롤백 시 skipped 기록 · §11 B 예외는 '행 없음·작음' 모두(사용자 'B 엄격' 결정의 같은 결과 — 사용자 보고) | fixed | 002292e3..f35a50af |
| L2-6 | 2 | opus F4 | V7:115 | 채택 — V7은 이번 작업 신규·서버 미적용 → 주석 정정(정의 = 기록 INSERT 포함) | fixed | 002292e3..f35a50af |
| L2-7 | 2 | opus F6 | ExpireHoldsService.kt:37-48 | 채택 — 커밋 뒤 단계 예외를 롤백으로 처리하지 않게 catch 범위 축소 | fixed | 002292e3..f35a50af |
| L2-8 | 2 | opus F7 | QuotaCleanup.kt:141-161 | 채택 — 정리 ② 실패도 errors 지표·부분 기록 | fixed | 002292e3..f35a50af |
| L2-9 | 2 | opus F8 | CounterReconciler.kt:125 · LoadtestDataService.kt:186 | 채택 — 두 정의를 이름으로 구분(코드 주석) + ADR 명시 | fixed | 002292e3..f35a50af |
| L2-10 | 2 | opus F9 | UserLimitStrategies.kt:346-353 | 기록 — B 엄격 범위 = 트리거 경로(선점 lockQuotaRow는 precheck와 같은 기존 의미), ADR-009 명시 | deferred(문서) | — |
| L2-11 | 2 | opus F10 | SalesCloseGate.kt:37-41 | 기록 — 하네스에 WAL·MultiXact(pg_stat_slru·wait_event) 지표(L1-16과 합침) | deferred(하네스) | — |
| L2-12 | 2 | opus 보조 | CounterReconciler.kt:248 | 채택 — probe 호출을 try 안으로 | fixed | 002292e3..f35a50af |

- review packet(코드 루프 3): rv19 — 미러(2269f25e..f35a50af src 누적 + 루프 2 수정분 + 루프 2 항목 요지) · 보안 스캔 0건 · 루프 2 항목 판정: codex fixed 9·partial 2(L2-4·L2-9)·not 2(L2-10·11 문서·하네스 몫) / Opus 해결 9·부분 2(L2-1·L2-4 낡은 주석)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| L3-1 | 3 | codex N3-1(높음) — Opus는 결함 아님으로 봄 | UserLimitStrategies.kt:330 · HoldSeatProcess.kt:54 | 채택 — 메인 판정: 마감 경계에서 같은 사용자의 동시 선점 둘이 게이트를 통과하고 트리거가 증가를 건너뛰면 한도 판정이 상대를 못 봐 한도 초과가 가능(창은 작지만 실제 홀드). 수정 비용 작음: B 선점은 시각을 한 번 채집해 마감 판정과 set_config에 같은 값 | fixed | 0392b17d..370a5166 |
| L3-2 | 3 | opus N1 | Loop2ImplTest.kt:203-213 | 채택 — ReconcileCloseRaceImplTest 공허(finally 순서로 대기 단언 항상 참, 이음새가 시계를 먼저 옮겨 FOR SHARE 없이도 통과) → 정리 스레드가 DELETE 뒤 커밋 직전에 시계를 옮기게, FOR SHARE 제거 시 빨강 확인 | fixed | 0392b17d..370a5166 |
| L3-3 | 3 | opus N2 | QuotaCleanup.kt:118-119 · SalesCloseGate.kt:31 · TriggerLoop1ImplTest.kt:25 · ExpiryRepairTest.kt:98 | 채택 — 낡은 주석 정정(대기 비용 있음·FOR UPDATE·이음새 제거·2099) | fixed | 0392b17d..370a5166 |
| L3-4 | 3 | opus N3 | CounterReconciler.kt:263-264 · V7:4,15,31 | 채택 — skipped(reason=closed)는 실패가 아니라 마감 제외: V7 주석에 정의 + 바퀴 요약에서 따로 셈(run 필드 또는 지표 태그) · 하네스·ADR에서 분리 집계 | fixed | 0392b17d..370a5166 |

- review packet(하네스 듀얼 1패스): rv21 — 미러(728c236c tracked + ADR-008 대비 diff + spec·design-v2 + 스모크 산출 요약) · 보안 스캔 0건

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| H-1 | h | opus F1(P1) | run.sh:177 · lib.sh:466-480 | 채택 — control(정지 조건)은 둘째 주입의 D·L1을 조건별 계획에서 빼고 plan.json에 기록 · 캠페인 전 control·B+C S3e 스모크 | fixed | b3351b59·5e4c68b0 |
| H-2 | h | codex R1(P1) | summarize.py:850 | 채택 — S3c 마감 뒤 선점은 SCHEDULE_CLOSED 요구 + 마감 직전 성공 표본 요구 | fixed | b3351b59·5e4c68b0 |
| H-3 | h | codex R3(P1) | summarize.py:823 | 채택 — 틱 실행 수 > 0 · 끝 오래된 HELD 0을 합격 조건에 · A 경로 복구 출처 확인 | fixed | b3351b59·5e4c68b0 |
| H-4 | h | codex R5(P1) | lib.sh:437 · summarize.py:801 | 채택 — drift 기록 잘림이면 회차 실패(또는 전체 수집) | fixed | b3351b59·5e4c68b0 |
| H-5 | h | codex R2 | summarize.py:859 | 채택 — 마감 뒤 유효 홀드 확정 성공 ≥ 1 요구 | fixed | b3351b59·5e4c68b0 |
| H-6 | h | codex R4 + opus P3-4 | summarize.py:814,776,785,826 | 채택 — 주입 쌍마다 끝 cnt = 실제 요구, 끝 상태 누락은 effect-check-failed | fixed | b3351b59·5e4c68b0 |
| H-7 | h | codex R6 + opus P3-5 | lib.sh:466,498-500 | 채택 — 주입 대상 정렬 잠금 후 조건 재확인·영향 행 수 대조 · 틱 실패 SQLSTATE 기록 | fixed | b3351b59·5e4c68b0 |
| H-8 | h | codex R7 | summarize.py:866 | 채택 — 마감 전 표본 시각 허용 창(starts_at − 5초 ± 오차) | fixed | b3351b59·5e4c68b0 |
| H-9 | h | opus F2 | summarize.py:875-880 · lib.sh:567-568 | 채택 — schedule 모드: 끝 holds 0 · 틱 실패 증가 0 · rows_deleted > 0 기록 ≥ 1 요구 | fixed | b3351b59·5e4c68b0 |
| H-10 | h | opus F3 | summarize.py:830-831 | 채택 — B+C 틱 실패 면제는 주입 → 복구 구간 안·주입 원인(SR001·23514 — L2-5에서 사용자 보고한 B 예외 범위)만, 그 밖은 unexpected | fixed | b3351b59·5e4c68b0 |
| H-11 | h | opus P3-1 | run.sh:656-661 | 채택 — 빌림 지표를 acquire 앞뒤로 두 번 읽어 일치할 때 사용 | fixed | b3351b59·5e4c68b0 |
| H-12 | h | opus P3-2 | run.sh:526-528 | 채택 — docker logs 파이프 실패·빈 로그 감지 | fixed | b3351b59·5e4c68b0 |
| H-13 | h | opus P3-3 | run.sh:955 | 채택 — reconcile.errors 증가 > 0 사유 · 구간 안 기대 바퀴 수 대조 | fixed | b3351b59·5e4c68b0 |
| H-14 | h | opus P3-6 | lib.sh:425-434 | 채택 — 시드 시각 이후 바퀴만 · S2 C·F 비용 한계는 ADR 기록 | fixed | b3351b59·5e4c68b0 |
| H-15 | h | opus P3-8 | run.sh:475,566 · lib.sh:458 | 채택 — 주입·마감 환경변수를 plan.json에 기록·이어하기 대조 | fixed | b3351b59·5e4c68b0 |
| H-16 | h | opus P3-9 | campaign.sh:174 | 채택 — reconcile-missing은 재측정 대상에서 빼고 결과 사유로 | fixed | b3351b59·5e4c68b0 |
| H-17 | h | opus P3-10 | summarize.py:767,783 | 채택 — k0 이후 주입 쌍의 주입 전 repaired도 오탐에 포함 | fixed | b3351b59·5e4c68b0 |
| H-18 | h | opus P3-7 · +270초 · C 문장 표지 | compose.yml:63 · README | 기록 — control DB_URL ApplicationName 차이, 첫 물결 D·L1은 A가 먼저 고침(C·F 탐지 시간 비교는 L2·H만), by_src는 근사 → README·ADR 명시 | deferred(문서) | — |

- review packet(측정 전 문서 듀얼 1패스): rv23 — ADR-009(측정 전 고정)·ADR-010(가설) + spec + 하네스·코드·스모크 산출 · 보안 스캔 0건

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|----|-----------------|--------|------|-------------|--------|---------------|
| DOC-1 | d | opus F1 | ADR-007:292-293 · campaign.sh:57,65 · s4-throughput.js:41-46 | 채택 — 고정 도착률 셀의 처리량은 디스크 벽·도착률에 묶여 둔감 → 사용자 결정(2026-10-13): 처리량 유지 + 선점 p99·디스크 벽 시작 시각(+마감 판정 시간·MultiXact 기록) 추가, H4·H6·H7·§5 반영 | fixed | doc |
| DOC-2 | d | codex R2 + opus F2 | CounterReconciler.kt:176-186 · summarize.py:739 | 채택 — H5 반증을 DB 쪽 대기 이벤트로, 청크 시간(풀 대기 포함)·처리량은 해석 지표 | fixed | doc |
| DOC-3 | d | opus F3 | run.sh:235 · lib.sh:564-583 · summarize.py:1003-1066 | 채택 — H8 반증을 ① 끝 홀드 0인데 행 남음 · ② cnt 0 행 남음 · 마감 5초 전 표본으로, 마감 뒤 판정기 불가는 §6 ⑩ | fixed | doc |
| DOC-4 | d | codex R1 + opus F4 | campaign.sh:61 · spec §5 | 채택 — B 단독 주장은 측정 밖(코드·테스트 추론) §6 ⑨ | fixed | doc |
| DOC-5 | d | codex R3 + opus F5 | smoke2-BC-s3e-1 · summarize.py:918-945 | 채택 — §5.1에 B+C 첫 회 실패·원인(분류기 결함)·재판정 결과 기록 · 메인 확인: 285ba256 오프라인 py 81/81(실측 재판정 regress-n3 포함) · rs 50/50 | fixed | doc |
| DOC-6 | d | opus F6 | smoke-AC-s3e-1 effect.json · README:66 | 채택 — H3 탐지 시간은 L2·H로, §6 ⑪(A가 D·L1을 먼저 고침) · 미스모크 셀 §5.1 | fixed | doc |
| DOC-7 | d | opus F7 | summarize.py:888-904 | 채택 — H2 반증에 D·L1 복구 수·끝 일치·오래된 HELD 추가 | fixed | doc |
| DOC-8 | d | codex R4 + opus F8 | V8:26-39 · HoldStrategyStartupCheck.kt:76 | 채택 — ADR-010 ④에 'B 그대로는 불가(엄격·기동 검사) → 비엄격 변형·순서 재설계' | fixed | doc |
| DOC-9 | d | opus F9 | HoldStrategyStartupCheck.kt:92-105 | 채택 — ADR-010 ②에 조합 규칙 확장 필요 | fixed | doc |

## 생략한 검증

- 없음
