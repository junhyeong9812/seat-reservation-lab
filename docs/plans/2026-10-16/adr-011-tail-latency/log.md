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
| HT-1 | 1 | codex 1 | tail.py:311 probe-check 창 경계 | 채택 | open | — |
| HT-2 | 1 | codex 2 | run.sh:1339 plan.json 키 추가 | 기각 — 명세 §2 '계획' 범위 명시(conditions·order·렌더), plan.json 키 추가는 ADR-010과 같은 방식 | — | — |
| HT-3 | 1 | codex 3 | tail.py:245 T4 비주기 서버형 | 채택 | open | — |
| HT-4 | 1 | codex 4 | run.sh:765 서버 측정기 기동 시한 | 채택 | open | — |
| HT-5 | 1 | opus H1 + 스모크 실측(gctrace 353줄/430초) | T3 검정력 상한 | 채택 — 최대 배수·판정 불가·사건 좁힘·시작 시각 보정 + ADR §4 측정 전 정정 | open | — |
| HT-6 | 1 | opus M1 | T1 재배열 귀무 거짓 양성 | 채택 — 위상 무작위 대리 계열 + v2.2.0 20회 거짓 양성률 확인 | open | — |
| HT-7 | 1 | opus M2 | T2 튐 기준·상한 | 채택 — 절대 하한·판정 불가 | open | — |
| HT-8 | 1 | opus M3 | v2.2.0 경로 scratchpad 고정 | 채택 — 안정 경로 + sha256 기록·대조 | open | — |
| HT-9 | 1 | opus L1~L5 | gctrace 부분 실패 · 측정기 고아 · probe compose 묶음 · 구간 상한 · '성분 없음' 표기 | 채택 | open | — |
| HT-10 | 1 | 메인 스모크 | probe-ping.js /actuator/health/ping 404(양쪽 전부) | 채택 — 대상 GET /actuator | open | — |

## 생략한 검증

- 없음
