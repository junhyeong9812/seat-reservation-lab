# log — adr-001-harness

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 09-28 | 인터뷰 4라운드 → 명세 작성 | S1~S4 × 자원 1/2/4 × 5회, .164 서버 · k6 PC 분리, 원시 결과 전부 보존 |
| 09-28 | 사용자 지시: GitHub 경유 대신 직접 전송(git archive → ssh), 결과는 최종에 한 번 커밋 | spec §9.4 변경 후 합의 — SPEC=1, MODE=auto, stakes 중간 |
| 09-28 | 환경 실측: .164 16코어·31G·Docker 29.6.1·8101/15432 비어 있음 / k6 PC 24코어·가용 11G·k6 v2.2.0 / RTT 1.9ms | spec §3 기록 |
| 06:45 | task01: loadtest 프로필(reset·consistency·actuator) + 판정기 고의 위반 테스트 8종(맵 전체 비교 — 거짓 양성도 검출) + 프로필 없으면 404 | 33 green, 커밋 6d3f964 (KDoc `/internal/**`가 중첩 주석으로 파싱돼 1회 컴파일 실패 → 문구 수정) |
| 06:52 | task02 스모크(가정1): git archive→.164 빌드 2분20초, APP/DB cpus=1 적용(NanoCpus 1e9), JVM system.cpu.count=1, 설정 주입 확인, 시드 1만 0.09s·100만 4.9s | 가정1 실증 |
| 06:58 | k6 PC 실측: 10,000 VU 대기 시 RSS 1.19GB | S3 원본 k6 PC에서 가능 |
| 07:02 | 설계 공백 발견: S3 축소의 입장 속도 미정 → 비율 분석 보고 → 사용자 "둘 다"(A: 300/s 유지, B: ×10) | spec §9.2·9.3 갱신, 전체 180회 |
| 06:58 | 스모크 S1(L1 1회): 산출물 전부 생성, 140KB | 관측(비공식): 201 10건·같은 좌석 홀드 10행, p50 3.4s·p99 5.9s — 가정2 일부 실증 |
| 07:08 | 스모크 S4(L1): 단계4(253/s) p99 10ms → 단계5(380/s) 에러 40%·이후 100% 타임아웃, 회복 없음. 붕괴 중 앱 CPU 30~50%·DB 30~70%(CPU 포화 아님). 종료 후 v_stale_held 25,785 | 하네스 문제: 붕괴 후 4분 무의미 부하(dropped 48만), stdout 12MB. 단계1 p99 27s 이상값(원인 미상 — 5회 반복에서 재현 여부 확인) |
| 07:14 | 스모크 S3B(L1): a0 확정 1건·홀드 에러 90%·dropped 18.5만(VU가 타임아웃에 묶임) / a50 확정 4,778 / **a20 seed-failed인데 MATRIX.log 누락**(초기 실패 경로가 기록 없이 return) + reset 500×2(앱 로그는 compose down으로 소실 → 원인 미확정) | 무음 스킵 결함 발견 |
| 07:20 | 하네스 보강: finish_rep 단일 기록 경로 · reset 재시도 6회+재기동 · 앱/DB 로그 회차별 gzip 보존 · S4 단계 에러>50% 중단(exit 99 = 정상) · stdout gzip | 커밋 10cccf2, 스모크 재실행(S3B·S4) 시작 |
| 07:24 | 리뷰 packet(base c5f68b6, results/ 제외 — 런타임 산출물): OUT=/tmp/tmp.gYzKIqkSr4 mirror=/tmp/tmp.2SlX49tQJB · 보안 스캔: compose `seat` 비밀번호 = 격리 실험 컨테이너 일회성 값(오탐) / 192.168.55.164·사용자 jun = 사설 대역·자격증명 아님(허용) | codex ∥ Opus 워커 병렬 시작 — Opus 프롬프트에 경로 자리표시자 미치환 실수 → 즉시 SendMessage로 실제 경로 전달 |
| 07:45 | codex 종합 감사: 판정 오류 R9(엄격 기준 미달≠실패 — 완화·포화 기준 보존 필요)·R16(같은 좌석 반복 201 = 재선점 또는 중복 — 만료시각으로 구분 필요), Q4(S4 조건 한정 확인) / 누락 2건: k6 스크립트가 SHA가 아닌 작업트리에서 실행됨(A1), 결과 ID 재사용 시 덮어쓰기(A2) | R9·R16 판정 수정, A1·A2 채택, Q4 범위 한정으로 정정 |
| 07:47 | 사용자 질문 "반영사항들이 어떤거야?" → 17개 반영 목록 보고 | 수정 착수 |
| 07:58 | 수정 커밋 33af14d → codex 타깃 재점검: 14 해소·5 부분(R3·Q3=ADR 기술 task06, R17=명세 동기화 완료, R8·R12 보강 필요) + 신규 N1(손상 gzip이 요약 전체 중단 — codex가 끝 8바이트 절단으로 재현) | N1·R8·R12 수정, N1 fix verification(손상 rep2 → curve-error 표시·요약 완주) |
| 08:03 | 실수: 스모크 실행 중인 run.sh를 수정(bash는 실행 중 파일을 이어 읽음 — 루프 뒤 몇 줄이 영향 가능) | 본측정은 측정 SHA에서 꺼낸 스크립트 사본으로 실행하기로 |
| 08:05 | 45b9c30 → codex 2차 타깃 재점검: N1·R8·R12 해소, 신규 0 | 中 종료 조건 충족(채택 전부 fixed · 재점검 clean) |
| 07:55 | 스모크(33af14d) S4 rep1 = invalid-consistency.json — 100만 석 최종 판정이 L1 부하 직후 60s curl 상한 초과 | 최종 판정만 상한 600s(폴링은 60s 유지) |
| 07:56 | 사용자 질문 "손상된 gzip은?" → 실제 손상 파일 없음, codex가 절단으로 재현한 가능성 finding(N1)이라고 답변 / "성공 212건은 중복 요청?" → S2는 VU마다 다른 좌석 1회(k6 자동 재시도 없음)·DB 홀드 행 212 일치 | 증명하려다 발견: `name` 태그가 CSV url 열을 덮어 **원시 CSV에 좌석이 남지 않음** → S1·S2·S3에 seat·user 태그(S4는 100만 석 시계열 폭증으로 제외) |
| 07:57 | 실수 반복: 실행 중인 run.sh를 다시 수정 | 스모크 종료 전 스크립트 수정 중지, 종료 후 로그 끝부분 확인 |
| 08:12 | 스모크(33af14d) 9회 모두 기록, 그러나 루프 뒤 줄이 수정된 파일에서 읽혀 문법 오류(exit 2) → 마지막 compose down 미실행 | 서버 seatlab 컨테이너 수동 정리(0개 확인) · 실행 스크립트 동결(시작 시 scripts/ 임시 사본으로 exec)·SHA 불일치 거부 추가, 정리 경로는 mktemp 동결 사본만(작업트리 삭제 방지) |
| 08:14 | aee65f9: 불일치 SHA 지정 → 거부(exit 2, 결과 폴더 미생성) 확인 | 최종 스모크(S1·S2·S4) 시작 |
| 08:16 | 33af14d 스모크 S3 표: L1에서 S3A(300/s)도 선점 에러 61%·확정 252/10,000 — L1 한계(≈250/s) 밖 / **판정 폴링 1회 최대 58s**(Q3 간섭 실측 — 반조인 판정 쿼리가 1 CPU DB와 경합) | S3 폴링을 가벼운 집계(/internal/counts?seatStatus=true)로 교체, 전체 판정은 회차 끝 1회 — 34 green |
| 08:21 | 최종 스모크(aee65f9, 동결 실행) S1·S2·S4 ok + matrix done·compose down 정상 | S2 원시 CSV 증거: 요청 1,000 = 고유 좌석 1,000, 201 212 = 고유 좌석 212, 사용자별 2매 88명·3매 12명(판정 v_over_limit 12) → 사용자 질문 "중복 요청?"에 답 |
| 08:22 | S4(L1): 단계4(253/s) p99 9ms → 단계5(380/s) 성공 205/s·에러 23% → 단계6 중단(partial 제외). 세 기준 한계 모두 ≈252/s. 앞선 단계1 27s 이상값 재현 안 됨 | 한계 산출 규칙 동작 확인 |
| 08:32 | f2a60e6 S3A: 앱 경유 가벼운 폴링 poll_ms 최대 26ms(이전 58s)이나 샘플 4개뿐 — 앱 포화 시 폴링 요청도 10s 안에 응답 못 받아 유실 | 부하 중 폴링을 DB 직접 질의(ssh+psql)로 교체 |
| 08:42 | 9ee71d5 S3A: DB 직접 폴링 — 변형마다 샘플 25개(이전 4), poll_ms 평균 ≈0.5s(ssh 왕복) | 가정2 실증 완료(k6 PC: S4 10,000 VU RSS 3.85GB·CPU 36%, S3A 포함 병목 징후 없음) |
| 08:44 | **본측정 시작**: `run.sh --sha 9ee71d5 --id 20260928-full-9ee71d5` — setsid nohup 분리 실행(동결 사본 /tmp/tmp.ZKGUv0g9Ft), plan 3단계×12셀×5회=180회, 추정 26h+ | runner log = scratchpad/20260928-full-9ee71d5.runner.log (종료 후 results로 복사) |
| 18:13 | 진행 101/180 전부 ok — L1 60회 완료(08:42→16:15), L2 진행 중 | 사용자 요청: 지금까지 커밋·push |
| 18:25 | 범위 합의: "1cpu실험 통계치랑 해당 결과만 커밋" · "2, 4는 끝나는 대로 분석해서 분석내용 작성 후 푸시" | L1 원시 60회(684M, 1,300파일, 최대 <50MB, 전부 ok) + summary-L1(L1만의 계획으로 산출 — 미측정 10칸은 t50/t90 미도달 등 정의 불가 값) 커밋. 진행 중 L2·MATRIX.log 제외 |

## 리뷰 ledger (中↑)

- review packet: base c5f68b6 / OUT /tmp/tmp.gYzKIqkSr4 / mirror /tmp/tmp.2SlX49tQJB

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R1 | 1 | opus·codex | lib.sh:40,46-51 curl 타임아웃 없음 → 무인 실행 무음 정지 | 채택 | fixed | 1 |
| R2 | 1 | opus·codex | summarize.py:100 S3 t0에 예열·시드 포함 | 채택 | fixed | 1 |
| R3 | 1 | opus | run.sh:32-35 S4 중 만료 배치 개입·누적 행 | 채택(부분) | fixed(코드) — ADR 해석 한계는 task06 | 1 |
| R4 | 1 | opus·codex | k6 성공 vs DB 소유 대조 누락 | 채택 | fixed | 1 |
| R5 | 1 | opus·codex | summarize.py:130 계획 대조 없음(미실행 셀 소실) | 채택 | fixed | 1 |
| R6 | 1 | opus | S3 표에 dropped·에러율 없음 | 채택 | fixed | 1 |
| R7 | 1 | opus·codex | run.sh:136 S3/S4 원시 CSV 없음 | 채택(사용자: 전부 git) | fixed | 1 |
| R8 | 1 | opus·codex | run.sh:157 산출물 검증 없음 | 채택 | fixed | 1 |
| R9 | 1 | opus | run.sh:158 S4 exit 99 무조건 ok | 채택 | fixed | 1 |
| R10 | 1 | opus·codex | s3-full-flow.js:48 비-409 재시도 | 채택 | fixed | 1 |
| R11 | 1 | opus·codex | summarize.py:80-91 한계 산출 규칙·부분 단계 | 채택 | fixed | 1 |
| R12 | 1 | opus | run.sh:188,196 fail-fast 없음 | 채택 | fixed | 1 |
| R13 | 1 | opus | summarize.py:162 S2 상한 하드코딩 | 채택 | fixed | 1 |
| R14 | 1 | opus | warmup.js:1 주석 | 채택 | fixed | 1 |
| R15 | 1 | codex·opus(q) | run.sh:145 S3 만료 꼬리 관측 전 판정 | 채택 | fixed | 1 |
| R16 | 1 | codex·opus(q) | summarize.py:107 누적 확정·재선점 곡선 | 채택 | fixed | 1 |
| R17 | 1 | codex(q) | spec §10·§9.4 불일치(135/180, 셀별 down) | 채택 | fixed | 1 |
| Q1 | 1 | opus | actuator 계측 오버헤드 | 범위 밖 | 기록 | |
| Q2 | 1 | opus | /internal 인증 없는 LAN 노출 | 범위 밖 | 기록 | |
| Q3 | 1 | opus·codex | S3 판정 폴링 간섭 | 채택(기록) | fixed(poll_ms 기록) — ADR 기술은 task06 | 1 |
| A1 | 1 | 감사 | lib.sh:22·run.sh:141 k6가 SHA 아닌 작업트리 스크립트 실행 | 채택 | fixed | 1 |
| A2 | 1 | 감사 | run.sh:25 결과 ID 재사용 시 원시 덮어쓰기 | 채택 | fixed | 1 |
| N1 | 1(재점검) | codex | summarize.py:110 손상 gzip이 요약 전체 중단 | 채택 | fixed | 1 |

## 생략한 검증

- (없음)

## 완료 요약

