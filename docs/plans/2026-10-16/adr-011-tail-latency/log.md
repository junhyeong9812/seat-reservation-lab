# log — ADR-011 측정 환경 꼬리 지연 원인

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-16 10:55 | 사용자 요청(ADR-010이 넘긴 ①~④ 중 원인 규명) · 인터뷰 4문항 + "4번은 후순위" | 새 ADR-011(기존 +1) · 범위 ①②③ · 기존 데이터 먼저 → 가설별 실험 · 브랜치 feat/adr-011-tail-latency(feat/adr-010 위) |
| 10-16 10:55 | 명세 작성 — 사용자 합의 대기 | — |
| 10-16 10:59 | 사용자 "진행하자" — 명세 합의, 모드 auto · task 02 읽기 전용 분석 워커(Opus) 시작(합의 전 L0로 착수) | — |
| 10-16 10:59 | task 01 번호 이동 7차: git mv 011~017 → 012~018 · 참조 108곳(병기·범위 뒤 숫자까지 — 6차 실수 재발 방지) · 7차 메모 · 6차 때 잘못 바뀐 5차 이력 줄(NEXT '기존 ADR-011~015' → 원문 010~015) 복원 · '다음 측정 ADR부터' 뜻의 ADR-012부터 → ADR-011부터 → ec7a6286 · 코드 주석 3곳 939839a8 | 링크 대상 MISSING 0(2026-09-29 옛 기록 제외) |
| 10-16 11:34 | task 02 읽기 전용 분석 회수(Opus, 50회 · 요청 약 8,200만 건) — 결과는 아래 '읽기 전용 분석' | 1순위 후보 = PC(k6) 쪽 지연 · 메인 교차 확인: meta.json k6_version 10-14 v2.2.0(go1.26.5) → 10-15 v2.3.0(go1.26.8), `last -x reboot` 10-15 12:43·12:56(커널 7.0.0-34 → 38) |
| 10-16 15:53 | ADR-011 문서(측정 전 8fddcba9) · k6 v2.2.0 gh release 내려받기·체크섬 확인(commit 00a9a1b7f5 = 옛 측정과 같음) · 하네스 구현 워커(Opus) → 4d2c752b · E2 3조건 정정(로컬 4g 컨테이너 실측: AlwaysPreTouch만이면 Initial Capacity 64M) 6b2ef3de · 서버에 grafana/k6:2.2.0 pull(digest 9bd01d69…) · 스모크 smoke-adr011-probes → probe-missing(측정기 대상 404) · 듀얼 리뷰 rv33 | 수정 워커 진행 |
| 10-16 16:51 | 리뷰 반영 a594f27e·문서 3af80c99(E1 3조건 — 기록 켠 스모크에서 느린 초 72·26초 성분 소실·긴 Go GC 겹침 70/72 → 기록이 주 k6를 흔든 정황) · codex 재점검 신규 3 → e4c24c2b(수거 실패 판정·트랩 정리·구간 상한 83) · 스모크 2 smoke-adr011-probes2(v2.3.0·기록 없음·측정기) ok: probe-check 통과 · 26초 성분 재등장 p=0.001(26.7초) · 느린 초 6(주기 6·클라이언트형 5) · PC 측정기 튐 2(겹침 0) · 서버 측정기 튐 0 → 사건 0건 = 겹침 없음 수정 a57f92e0·ddfbe6c3 | E1 시작 |
| 10-16 18:50 | E1 끝(18:30, 9회 전부 ok) · 요약·tail.py → 088f722a(결과) · **T1 기각**: v2.2.0 3회 모두 26초 성분 p=0.001(26.05·25.85·26.15초) — 버전 아님 → 한계 2대로 PC 상태가 후보 · **T2 기각**: v2.3.0 PC 측정기(별도 k6 프로세스) 겹침 7/27(기대 0.8 · ×9.1) · v2.2.0도 10/28(×10.3) — 주 k6 프로세스 밖, PC 전체 · 서버 측정기 튐 v2.2.0 0건(겹침 없음) · v2.3.0 2/27 · 느린 초 클라이언트형 우세(클라이언트 구간 중앙 50~85ms · waiting 2~25ms) · **T3 판정 가능·성립**: 기록 조건 26초 성분 유지(p=0.001 ×3 · 느린 초 7~9 — 기록 없음과 같은 모양) · Go GC 겹침 21/23(기대 1.9 · ×11.2). 긴 Go GC 자체가 약 26초 간격(26.5·27.0·26.2·26.9초 등) · 예: rep1 gc103 wall 306ms·assist CPU 2,021ms. 인과 방향(k6 할당 폭주 → GC → 지연 대 PC 정지 → GC가 길어짐)은 이 자료로 가를 수 없음 · E2 주 k6 = v2.2.0(둘 다 튐 → 규칙대로) | E2 시작 18:50(sha 088f722a · 9회 · 약 97분) |
| 10-16 20:00 | 노트북(k6 PC — HP Victus 16 · 24스레드 · powersave) 읽기 전용 점검(사용자 "확인해보자") · **유력 원인 발견**: 다른 프로젝트 컨테이너 text-es(compose text-server · Elasticsearch · restart unless-stopped)가 재부팅(10-15 12:56:45) 뒤부터 기동 실패 반복 — RestartCount 4,635(× 약 24초 ≈ 31시간 = 재부팅 뒤 전체) · 기동 간격 실측 23.4~27.9초(3분 관찰) · 기동마다 JVM AlwaysPreTouch로 RSS 약 9GB · CPU 400%+ · 실패 사유 failed to obtain node locks(데이터 경로 잠금). 재부팅 시점·주기·PC 전체 영향(측정기 k6 동반 튐) 모두 맞음 — 켜고 끄기 전이라 후보 · 같은 시각 사용자의 다른 실험(ccle-v500 컨테이너, 로컬 postgres 부하)도 E2와 동시 진행 중 | 사용자에게 보고 — text-es 정지·E2 처리 결정 대기 |
| 10-16 20:07 | text-es 출처 확인: compose 라벨 = `~/markCloud/squatting-project/text-server`(6월 12일 생성) — 그 경로에 compose 파일 없음·빈 폴더 4개(root 소유, 10-15 12:43 생성 = 재부팅 때 Docker가 없는 바인드 경로를 새로 만든 것) → ES가 root 폴더에 못 써 기동 실패 반복. 사용자 "es 끄고 저장된 폴더도 깔끔하게 삭제하자" → `docker stop`·`docker rm text-es` 20:06:51(이미지·text-es-init은 그대로) · 폴더는 root 소유라 사용자 sudo 필요 · E2 rep3 첫 회차(none) 도중 text-es 꺼짐 — E2 전체는 간섭(text-es·사용자 다른 실험)이 섞여 참고 자료 | 폴더 삭제 사용자 대기 · E2 끝나면 확인 측정 |
| 10-16 20:45 | E2 끝(20:29, 9회 ok) · 요약·tail.py → 결과 커밋 · text-es 켜진 회차 7회(none 1~3 · xms 1~2 · pt 1~2 — none rep3은 끝 1분만 꺼짐) 모두 26초 성분 p=0.001 · 느린 초 9~14 · **text-es 끈 뒤 2회(xms rep3 · pt rep3) 느린 초 0 · 26초 성분 없음(p=0.630 · 0.416)** — 켜고 끄기 1차 정황. T4 판정은 text-es 간섭으로 보류(주기 없는 서버형 none 0~1 · xms 0 · pt 0 — 차이 작음) · 사용자 "es날렸으니 이제 문제 없는지 확인만" → E1 같은 구성 재측정(text-es 없음) 시작 | id 20261016-adr011-k6ver-noes-<sha> · 9회 약 97분 |
| 10-16 22:35 | E1 재측정(text-es 없음, 20261016-adr011-k6ver-noes-3807c2a2) 끝 22:23 · 9회 ok · **느린 초 9회 모두 0 · 26초 성분 0/9(p 0.147~0.709)** · 1초 p99 최대 4.6~23.0ms(text-es 켜짐 E1: 37.2~112.8ms) · 측정기 튐 PC 0~1·서버 0 · 안정성 4항목 전부 ○ · **켜고 끄기 충족 → 26초 주기 튐 원인 = 노트북의 text-es 기동 실패 반복 확정**(켜짐 E1 9/9 + E2 7/7 → 끔 9/9 + E2 2/2). k6 버전 무관 · .164 seatlab 컨테이너 0 | 사용자 보고 |
| 10-17 08:45 | 사용자 "남은 측정 먼저 돌리자" · 노트북 확인(ccle-v500 끝남 · load 2.2) · E2 재측정(20261017-adr011-pretouch-noes-c5117f4b · 9회) → E3 장시간(20261017-adr011-soak-c5117f4b · 2회) 한 유닛으로 이어 시작 · 주 k6 v2.2.0(E2와 같게) | 약 2.8시간 |
| 10-17 14:20 | E2 재측정 끝 10:23(9회 ok) · **E3 4회 연속 재측정 사유 같음**(invalid-consistency.json · accept-consistency-missing · db-log-fatal, 1회는 borrow-missing도): PG `could not write to file "base/pgsql_tmp/…": No space left on device` — tmpfs 프리셋 PG_TMPFS_SIZE 6g(run.sh:160)에 회차 800 × 1만 석(800만 행) + 30분 예약이 차서, k6 끝 약 25초 뒤 정합성 검사 쿼리 임시 파일이 넘침. 부하 구간(k6 창)은 4회 모두 디스크 오류 전에 끝남 · 확정적 실패라 5번째 회차 도중 `systemctl --user stop` 14:16 → EXIT 트랩이 안 돌아 .164 seatlab 컨테이너 3개 남음 · `docker compose -p seatlab down -v`는 자동 모드 분류기가 거부 → 사용자 확인 필요 · 대기 스크립트가 exit≠0만 보고 재측정 반복은 못 잡음(교훈) | 사용자 결정 대기 |
| 10-17 14:30 | E2 재측정(20261017-adr011-pretouch-noes-c5117f4b) 분석: 9회 ok · 느린 초 none 0·0·0 · xms 0·0·0 · pt 3·0·0(rep1 서버형 2 · 1초 p99 최대 58.6ms) · 26초 성분 0/9 · 1초 p99 최대 none 5.1~23.9 · xms 6.4~8.4 · pt 6.7~58.6ms · 메모리 최대 ÷ 한도 none 76~77% · xms·pt 86~87% · 안정성 4항목 전부 ○ · **T4 기각**(판정 관측: 미리 건드림 서버형 [0–2]가 없음 [0–0]과 범위 겹침) — 대조(none)에 줄일 튐이 처음부터 없음 → ADR-010의 '4g ZGC 추가 튐'은 text-es 간섭(10-15 재부팅 뒤 측정)으로 보는 것이 자연스러움 | 결과 커밋 |

## 읽기 전용 분석 (task 02 — 워커 Opus, 2026-10-16)

- 대상: jvm2 18회 · jvm 4회 · remeasure tmpfs 12회 · 10-13 tmpfs 4회 · 스모크 6회(k6 v2.2.0 20회 · v2.3.0 24회). 스크립트·중간 산출물: `scratchpad/adr011-p1/bin`·`out`(임시).
- 주기: v2.3.0 24회 모두 26초 안팎(25.2~30.4초, 회차마다 다름, 회차 안에서는 잔차 0.3~1.3초로 일정) 성분 유의(10ms 넘는 요청 수 계열 p ≤ 0.04). v2.2.0 20회는 26초 성분 없음(p ≤ 0.05 2회 — 16.6·20.1초). 위상은 k6 시작·앱 기동·DB ready·수집기 시작 어디에도 묶이지 않음(Rayleigh p ≈ 0.5~0.6).
- 신호 대조(v2.3.0 Full GC 아닌 느린 초 236개, ±1초, 우연 기대 대비 배수): VU ≥15 13.4(결과 신호) · Hikari pending>0 4.2(결과일 수 있음) · Hikari active≥5 2.3 · DB 활성 ≥5 2.1 · safepoint >1ms 0.99 · GC 아닌 safepoint 0 · DB 대기 0.7 · lock 0 · autovacuum 0.5 · checkpoint 0.5 · 대조 바퀴 0.9 · 디스크 1.0 · docker stats 수집 1.07 · db_counts 1.02 · ps 0.98. 수집기 주기 1.16·5.2·10.5·12.55·0.57·1.0초 — 26초 근처 없음.
- 지연 분해: 서버 멈춤 대조군(Serial Full GC 초 138개)은 느린 요청 지연 100%가 waiting(TTFB)·클라이언트 구간 작음(blocked·sending·receiving 최대 중앙값 0.9·1.3·2.7ms). 26초 주기 초는 blocked·sending·receiving 최대 중앙값 30·48·57ms(p90 84·100·116ms), 64% 초에서 클라이언트 구간이 더 큼, 50ms 넘는 요청 1~50건(전역 멈춤 아님). 정상 초 클라이언트 구간 최대 p95: v2.2.0 10.2ms 대 v2.3.0 25.0ms.
- 후보 순위: ① PC 쪽 변화 묶음(k6 v2.3.0 내부 주기 동작 또는 재부팅 뒤 PC 상태 — 커널·패키지 약 120개 동시 갱신) ② 앱 조건 변화(같은 날 control 계열 → default) — 서버 신호 불일치·지연 모양이 반대 근거 ③ 서버 JVM·DB·호스트·수집기 — 일치가 우연 수준. 4g ZGC의 주기 없는 추가 튐은 waiting(서버형)이 많다 — 첫 접근 페이지 폴트 후보와 모순 없음.
- 한계: CSV 시각 정수 초 · k6 CPU 순간값·Go GC 미관측 · k6 버전·PC 재부팅·앱 조건·하네스 SHA가 같은 날 바뀌어 이 자료만으로 분리 불가.

## 리뷰 ledger

- review packet(하네스 4d2c752b·6b2ef3de): rv33 — `scratchpad/rv33`(미러 tracked − results·ledger, harness.diff·spec). Opus ∥ codex.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| HT-1 | 1 | codex 1 | tail.py:311 probe-check 창 경계 | 채택 | fixed | a594f27e |
| HT-2 | 1 | codex 2 | run.sh:1339 plan.json 키 추가 | 기각 — 명세 §2 '계획' 범위 명시(conditions·order·렌더), plan.json 키 추가는 ADR-010과 같은 방식 | — | — |
| HT-3 | 1 | codex 3 | tail.py:245 T4 비주기 서버형 | 채택 | fixed | a594f27e |
| HT-4 | 1 | codex 4 | run.sh:765 서버 측정기 기동 시한 | 채택 | fixed | a594f27e |
| HT-5 | 1 | opus H1 + 스모크 실측(gctrace 353줄/430초) | T3 검정력 상한 | 채택 — 최대 배수·판정 불가·사건 좁힘·시작 시각 보정 + ADR §4 측정 전 정정 | fixed | a594f27e |
| HT-6 | 1 | opus M1 | T1 재배열 귀무 거짓 양성 | 채택 — 위상 무작위 대리 계열 + v2.2.0 20회 거짓 양성률 확인 | fixed | a594f27e |
| HT-7 | 1 | opus M2 | T2 튐 기준·상한 | 채택 — 절대 하한·판정 불가 | fixed | a594f27e |
| HT-8 | 1 | opus M3 | v2.2.0 경로 scratchpad 고정 | 채택 — 안정 경로 + sha256 기록·대조 | fixed | a594f27e |
| HT-9 | 1 | opus L1~L5 | gctrace 부분 실패 · 측정기 고아 · probe compose 묶음 · 구간 상한 · '성분 없음' 표기 | 채택 | fixed | a594f27e |
| HT-10 | 1 | 메인 스모크 | probe-ping.js /actuator/health/ping 404(양쪽 전부) | 채택 — 대상 GET /actuator | fixed | a594f27e |
| HT-11 | p | codex post-fix | 수거 stop·rm 실패 무시 · EXIT 트랩 정리 · 구간 상한 여유 | 채택 | fixed | e4c24c2b |
| HT-12 | p | 메인 스모크 2 | 사건 0건을 판정 불가로 표시 | 채택 — 겹침 없음 | fixed | a57f92e0 |
- post-fix 재점검 종료(中 듀얼 1패스) — 신규 3 반영 · 추가 1(메인) — 수정 범위 작아 추가 재점검 없음.

## 생략한 검증

- 없음
