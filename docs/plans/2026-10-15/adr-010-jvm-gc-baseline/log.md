# log — ADR-010 앱 JVM GC·메모리 기준

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-15 12:41 | 사용자 요청(GC 설명 문서화 + G1 최소 자원 기준 ADR-010) · 인터뷰 4문항 | 새 ADR-010(기존 +1), 4조건 사다리 1회씩, G1 명시 + 꼬리 지연 기준, ADR-010 이후 측정부터 |
| 10-15 12:41 | 명세 작성 — 사용자 합의 대기 | — |
| 10-15 13:04 | 사용자 "진행해보자" — 명세 합의, 모드 auto(set-state) · 브랜치 feat/adr-010-jvm-gc-baseline(3a2048b4에서) | — |
| 10-15 13:04 | task 01 번호 이동: ADR-010~016 → 011~017(git mv) + 참조 90곳 갱신(옛 번호 이동 메모 줄은 보존, 6차 메모 추가) → 766fd3f7 · 코드 주석 3곳 → e14ebad4 | 링크 대상 확인 — 새로 깨진 링크 0(2026-09-29 명세의 옛 파일명 2건은 기존 기록) |
| 10-15 13:04 | task 02 ADR-010 문서(§1 GC 설명 · §2 선택지 · §3 가설 J1~J5 · §4 계획·판정 · §5 한계) — 측정 전 고정 | 커밋 |
| 10-15 13:17 | task 03 하네스: run.sh --app-mem·--app-gc(+ 계획·이어서 실행 대조, gc-collector-mismatch), compose APP_JVM_OPTS, campaign --set jvm-baseline, summarize GC full·k6_steady·힙, compare --set jvm-baseline → 4b3e64ab | dry-run 4회 계획 · main·cost-remeasure 계획 기존과 동일 · compose 렌더(tmpfs 오버레이 포함) 손잡이 없음·APP_MEM=1g 모두 diff 0 · 새 GC 집계를 재측정 control tmpfs rep1에 돌려 정상 구간 Full 8(gcwall 대조와 일치) |
| 10-15 13:17 | 발견: 하네스 "가장 나쁜 구간 p99"는 30초 구간이라 Full GC 멈춤이 묻힘(재측정 control tmpfs 1회차 4.2ms) → 1초 꼬리 지표 추가(1초 p99 최대 283ms·> 50ms 초 8·최대 295ms — gcwall과 일치) · ADR-010 §3 J2·J4·§4 판정 지표를 측정 전에 1초 p99 최대로 정정 | docs 커밋 |

## 리뷰 ledger

- review packet(하네스 4b3e64ab): rv29 — `scratchpad/rv29`(미러 tracked − results·ledger, `_packet/harness.diff`·spec·related-raw). Opus(opus) ∥ codex(medium). post-fix: `_packet/fix.diff`(4b3e64ab..6ddb9358)

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| HJ-1 | 1 | codex 1 | run.sh:255 · compose.yml:77 | 채택 — auto도 APP_JVM_OPTS 빈 값 명시 | fixed | 6ddb9358 |
| HJ-2 | 1 | codex 2 | summarize.py:805–846 · run.sh:1100 | 채택 — k6 구간 GC 정지 0이면 gc-log-missing | fixed | 6ddb9358 |
| HJ-3 | 1 | codex 3 | run.sh:1159 · compare.py:663 | 채택 — docker inspect 메모리 한도·JAVA_TOOL_OPTIONS 한 변수만 app-jvm.json, app-jvm-mismatch, 표에 실제 한도 | fixed | 6ddb9358 |
| HJ-4 | 1 | codex 4 + opus 5 | summarize.py:238–255 | 채택 — 얇은 초(계획 도착률 절반 미만) 1초 p99 제외·secs_thin, 표에 집계 초/계획 초·코드 0 비율 | fixed | 6ddb9358 |
| HJ-5 | 1 | opus 1 | summarize.py:831 | 채택 — GC 정상 구간 시작 = startedAt + ramp_s(지연 지표와 같은 기준), steady_from 기록 | fixed | 6ddb9358 |
| HJ-6 | 1 | opus 2 | compare.py:122·660 | 채택 — ok 아닌 회차도 상태와 함께 표에 | fixed | 6ddb9358 |
| HJ-7 | 1 | opus 3 | compare.py:687 | 채택 — None → 누락 | fixed | 6ddb9358 |
| HJ-8 | 1 | opus 4 | compare.py:710 | 채택 — jvm 세트는 벽·반감 누락 알림 생략 | fixed | 6ddb9358 |
| HJ-9 | 1 | opus 5 | summarize.py:822 | 채택 — 잡은 힙 최대 = 로그 전체 범위 명시(표 머리·docstring) | fixed | 6ddb9358 |
- Opus 워커가 미러 안에 `__pycache__`를 만들었다(py_compile) — 재점검 전 메인이 삭제.

## 생략한 검증

- 없음
