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
| 10-15 13:17~13:29 | 스모크 smoke-adr010-1g-g1(4b3e64ab, default · S4c4200 tmpfs · 1g · g1) → ok | 가정 1 실증: G1 로그 집계 정상(Young Normal·Concurrent Start·Prepare Mixed·Mixed·Remark·Cleanup 모두 Pause 줄 매칭, unmatched 0) · 정상 구간 Full GC 0 · 정지 최대 5.0ms(k6 7.8ms) · 잡은 힙 최대 153MB · 실행 중 컨테이너에서 메모리 1073741824B·JAVA_TOOL_OPTIONS 앞 -XX:+UseG1GC 확인 |
| 10-15 13:48 | **본측정 시작**: systemd-run --user --unit seatlab-adr010j → campaign.sh --set jvm-baseline --sha 9c898522(4회, 예상 43분) · id 20261015-adr010-jvm-9c898522 | — |
| 10-15 14:33 | 본측정 종료 — 4/4 ok, 비정상 0 · summarize·compare --set jvm-baseline | ① Serial Full GC 8 · 1초 p99 최대 301ms / ② G1 Full GC 0 · 97ms / ③ 1792m 자동 G1 · 90ms / ④ 2g G1 · 96ms · 앱 CPU 140 → 166~175% |
| 10-15 14:50 | 느린 초 대조(아래) — G1 세 회차의 느린 초는 Full GC와 무관, 약 26~27초 간격 | 사용자 재합의: Dockerfile 대신 ADR-011부터 하네스 기본값 · 남은 튐은 후속(명세 §10) |
| 10-15 18:23 | ADR-010 §6~§8 · README · NEXT · 측정로그 · ADR-009 참조 → 결과 문서 듀얼 1패스 rv30(codex 4·Opus 9 → RJ-1~10) + 재점검(RJ-11) 전부 반영 | 판정 좁힘: J4는 1초 p99 최대에서만, ②는 p99·30초 p99가 가장 나쁨(1회), 2g 반복 측정 후보 · 번호 이동 누락 3곳 수정 |

## 느린 초 대조 (ADR-010 §6 — 2026-10-15)

- 정의: 느린 초 = k6 행 타임스탬프(PC 시계, 정수 초) 기준 램프 끝(setup_data.startedAt + ramp_s) 뒤, 코드 0 아닌 hold 요청의 최대 응답 > 50ms인 초. Full GC 시각 = 서버 gc.log 시각 − clock-offset before(회차 앞 1회 — 회차 중 시계 이동 약 10ms). 일치 = ±1초. 출력 튜플 = (k6 시작 기준 초, 최대 응답 ms, 'GC' 일치 여부).
- 실행: `python3 -I slow.py <조건>-jvm/L4/S4c4200/rep1`(캠페인 폴더에서).

```python
import csv,gzip,json,sys,collections,re,datetime
d=sys.argv[1]
off=json.load(open(d+'/clock-offset.json'))['before']['offset_s']
summ=json.load(open(d+'/k6-summary.json')); k0=summ['setup_data']['startedAt']/1000; t0=k0+summ['ramp_s']
mx=collections.defaultdict(float)
with gzip.open(d+'/k6-requests.csv.gz','rt') as f:
    for x in csv.DictReader(f):
        if x['metric_name']!='http_req_duration' or x.get('name')!='hold' or x['status'] in ('0',''): continue
        s=int(x['timestamp']); mx[s]=max(mx[s],float(x['metric_value']))
slow=sorted(s for s in mx if s>=t0 and mx[s]>50)
full=[]
pat=re.compile(r'^\[([^\]]+)\].*GC\((\d+)\) Pause Full')
with gzip.open(d+'/gc.log.gz','rt') as f:
    for l in f:
        m=pat.match(l)
        if m and l.rstrip().endswith('ms'): full.append(datetime.datetime.fromisoformat(m.group(1).replace('+0000','+00:00')).timestamp()-off-k0)
fs=[round(x) for x in full]
print(d.split('/')[0],'slow:',[(s-int(k0), round(mx[s]), 'GC' if any(abs(s-int(k0)-x)<=1 for x in fs) else '') for s in slow])
```

```
1g-auto-jvm slow: [(65, 163, 'GC'), (75, 58, ''), (89, 138, 'GC'), (100, 82, ''), (137, 135, 'GC'), (151, 64, ''), (183, 314, 'GC'), (223, 99, 'GC'), (272, 127, 'GC'), (301, 57, ''), (322, 117, 'GC'), (372, 112, 'GC')]
1g-g1-jvm slow: [(64, 149, ''), (81, 55, ''), (92, 135, ''), (121, 51, ''), (148, 108, ''), (203, 81, ''), (230, 131, ''), (257, 56, ''), (284, 72, ''), (338, 84, ''), (364, 114, ''), (371, 52, ''), (391, 91, '')]
1792m-auto-jvm slow: [(62, 105, ''), (88, 58, ''), (115, 65, ''), (169, 137, ''), (196, 62, ''), (248, 143, ''), (274, 100, ''), (301, 58, '')]
2g-auto-jvm slow: [(105, 68, ''), (158, 68, ''), (185, 106, ''), (212, 67, ''), (265, 52, ''), (317, 96, ''), (318, 69, ''), (344, 75, ''), (370, 134, '')]
```

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
| HJ-10 | p | codex post-fix | run.sh gc 정지 0 검사 범위 | 채택 — S4c 셀로 한정 | fixed | post-fix |
| HJ-4 재점검 partial | p | codex post-fix | 응답 0건인 초는 per_sec에 없음 | 범위 밖(표의 '집계 초/계획 초'가 빈 초를 드러냄) — 추가 수정 없음 | — | — |
- post-fix 재점검 종료: 신규 1(HJ-10) 반영 — 한 줄 범위 한정이라 추가 재점검 없음(中 듀얼 1패스).
- review packet(결과 문서): rv30 — `scratchpad/rv30`(ADR-010·ADR-009·README + 결과 폴더 요약 파일 + GC 발췌·느린 초 대조). Opus(opus) ∥ codex(medium).
| RJ-1 | r | codex 1 + opus 1 | ADR-010 J4·§8 · README | 채택 — '꼬리 이득 없음'을 1초 p99 최대로 한정, ③·④가 p99·30초 p99에서 낮았음 병기 · 반복 측정 후보 | fixed | doc |
| RJ-2 | r | codex 2 + opus 2 | §6 표 | 채택 — 30초 구간 p99·램프 p99·앱 메모리 추가, ②의 p99 +12%·30초 p99·느린 초 수 반증 관측 J2에 | fixed | doc |
| RJ-3 | r | codex 3 | §6·§7 'GC와 무관' | 채택 — 'Full GC와 일치하지 않고 관측된 GC 정지만으로 설명되지 않음' | fixed | doc |
| RJ-4 | r | codex 4 + opus 3·7 | 주기 26.5초 · 57ms · 51ms · README 47초 값 | 채택 — ③ 8/8·④ 9/9·② 27.2초 10/13·① 25초 4/4, 위상 차, 빠진 자리 근거 없음 명시, 범위 정정 | fixed | doc |
| RJ-5 | r | opus 4 | 대조 바퀴·앱 예약 작업 | 채택 — 대조 바퀴 시각과 겹친 초 기록, 후보에 만료 배치·주기 병기 | fixed | doc |
| RJ-6 | r | opus 5 | '300ms급' | 채택 — Full GC 일치 튐(99~314ms)으로, 크기로 못 가름 | fixed | doc |
| RJ-7 | r | opus 6 | README:5·17, ADR-001:274 | 채택 — 번호 이동 누락(범위·병기 뒤 숫자 — 'ADR-' 접두 없는 숫자를 정규식이 못 봄) 3곳 수정 | fixed | doc |
| RJ-8 | r | opus 7 | J3 'Using G1' 인용 | 채택 — gc.json collector로, 경계 자체는 미확인 | fixed | doc |
| RJ-9 | r | opus 8 | J5 CPU 원인 | 채택 — 추정 표시, 잡은 힙 150~170MB | fixed | doc |
| RJ-10 | r | opus 9 | 대조 스크립트 재현 경로 | 채택 — log.md '느린 초 대조'에 스크립트·정의·결과 원문 | fixed | doc |
| RJ-11 | p | codex post-fix | ② 주기 10/13 서술 · J3 경계 출처 · §8.1 '회차당 8~13초' | 채택 — 허용 ±1.5초·64초 어긋남 명시, 경계 출처(OpenJDK is_server_class_machine), G1 회차와 ① 4초 구분 | fixed | post-fix |
- post-fix 재점검(결과 문서) 종료: partial 2·신규 1 반영 — 문구 수정만이라 추가 재점검 없음(中 듀얼 1패스).
- Opus 워커가 미러 안에 `__pycache__`를 만들었다(py_compile) — 재점검 전 메인이 삭제.

## 아카이브 (사이클 마감 ② — 사용자 확인 2026-10-15: ADR-009·010 함께)

- 카드에 나오면 안 되는 원 식별자: seat-reservation-lab · seatlab · backend-labs-commerce · jun · 192.168.55.164 · .164 · seat_hold · user_hold_quota · counter_reconcile_run · product_schedule · SeatHoldProperties · LoadtestDataService · ExpireHoldsService · ConfirmReservationService · UserLimitStrategies · counter-upsert-precheck · counter-trigger · seatlab-app-1 · ADR-0xx 번호 · 커밋 SHA

## 생략한 검증

- 없음
