# k6/ADR-005 — 1인 2매: 같은 사용자 동시 요청 — 매수 제어 방식 비교 측정

ADR-003 하네스(`k6/ADR-003/`)를 복사하고 **조건 축 `--limit-strategy`**(매수 제어 방식 8개), **S7 이긴 쪽 롤백 시나리오**, **매수 확인 쿼리 DB 벤치**(`limit-bench.sh`), 사용자·좌석 단위 요약을 더했다.
시나리오·판정기·수집·검증 규칙은 ADR-001~003과 같다 — `k6/ADR-003/README.md` 참고. 명세: `docs/plans/2026-10-06/adr-005-user-limit/requirement-spec.md`(§9.2 매트릭스·§9.3 판정 기준).

## 조건 축

| 축 | 값 | 적용 방법 |
|----|----|----------|
| 매수 제어 방식 | none · advisory · advisory-try · quota-lock · quota-nowait · counter · serializable · serializable-retry | `SEAT_HOLD_LIMIT_STRATEGY` → `SPRING_APPLICATION_JSON`의 `seat.hold.limit-strategy`(정확한 속성 이름 — ADR-003 compose 관례) |
| 좌석 전략 | pessimistic-nowait 고정(ADR-003 결정 3b, 앱 기본값과 같다) | `HOLD_STRATEGY`(기본 pessimistic-nowait) |

풀 10 · 앱 1대 · 인덱스 V2 · 배경 0 고정. 회차 판정은 ADR-003 것(`strategy-mismatch`·`pool-mismatch`·`index-mismatch`·`path-gap` …)에 더해
**`limit-strategy-mismatch`**(app-config.json의 configprops `seat.hold.limitStrategy`가 계획 값의 enum 이름과 다름)와, 부하 직후 앱이 계획한 좌석 전략·매수 방식·풀로 떠 있었는지(`apps-mismatch`)를 본다.
`meta.json`에 `limit_strategy`(계획)·`applied.{strategy, limit_strategy}`(configprops 실제 값)·`harness`를 남긴다.

## 셀 · 캠페인 (명세 §9.2)

| 셀 | 내용 | 단계 | 회차 |
|----|------|------|------|
| S2 | 사용자 100명 × 각자 다른 좌석 10개 동시(ADR-003 S2 그대로) | L2 · L4 | 5 |
| S4 | 계단 16단계(50 → 약 2.2만 건/s, ADR-003 그대로) | L2 · L4 | 5 |
| S3-a0/a20/a50 | 선점 → 확정 원본(TTL 5m) × 이탈 0/20/50% | L4 | 3 |
| S7 | 이긴 쪽 롤백(아래) | L4 | 5 |
| S7-m1 | 이긴 쪽 롤백, 일반 사용자 1명(M=1 — 사용자 재합의 2026-10-06) | L4 | 5 |

`campaign.sh`의 조건 = 매수 방식 8개 × {`<방식>-s24`(S2·S4 L2·L4), `<방식>-s3`(S3 L4), `<방식>-s7`(S7·S7-m1 L4)} = 24조건. 회차 우선 순서·비정상 회차 재측정(최대 2바퀴)·연속 기동 실패 중단은 ADR-003 그대로. S3는 `--s3-reps 3`이 기본.
S1·S6 시나리오 파일은 ADR-003에서 복사만 했고 캠페인 조건에는 없다.

## S7 이긴 쪽 롤백 (`scenarios/s7-winner-rollback.js`)

> **M=1 변형 `S7-m1`(2026-10-06 추가)**: 스모크에서 M=20은 none에서도 억울한 좌석 0이었다 — U가 좌석 락을 쥔 창(약 1ms)보다 일반 사용자 도착 흩어짐(−2~3ms)이 커서 늦게 온 일반 사용자가 결국 좌석을 가져갔다. 경쟁자가 많으면 롤백 피해가 흡수된다는 관측이고, 피해 자체(좌석이 비었는데 진 사람)의 비율은 경쟁자 1명일 때 직접 보인다. 셀 이름의 `-m<n>`이 k6 `M`이 된다(`run.sh` `cell_base`·`k6_env_of`).

좌석마다 **이미 2매를 가진 사용자 U 1명 + 일반 사용자 M명(기본 20)** 이 같은 좌석을 거의 동시에 선점한다. 대상 좌석 100석(1..100), 좌석 i의 U = 700000 + i, 일반 사용자 = 710000 + (i − 1)·M + j.

- **U의 2매는 setup()에서 앱 API로** 미리 선점한다(좌석 101..300 — counter 방식의 카운터가 실제 경로로 2가 되게. DB 직접 삽입 금지). 같은 사용자의 두 요청을 동시에 보내면 즉시 실패형이 하나를 거절하므로, 모든 U의 첫 좌석을 끝낸 뒤 둘째 좌석을 보낸다. 하나라도 201이 아니면 k6가 실패(회차 `k6-exit-*`).
- **시차**: 좌석마다 경주 시각을 `GAP_MS`(50ms)씩 떼고, 그 시각에 U를, `LEAD_MS`(1ms) 뒤에 일반 사용자 M명을 보낸다(공통 시작 시각 = setup 끝 + 3초에 각 VU가 `sleep`으로 맞춘다).
  - 근거: 현상은 "U가 좌석 락을 먼저 잡고 → 매수 판정에서 져 롤백하는 동안 → 일반 사용자가 NOWAIT로 진다"이다. 경로(네트워크·Tomcat·트랜잭션 시작·사용자 단위 진입·좌석 `FOR UPDATE NOWAIT`)는 U와 일반 사용자가 같으므로, U를 `LEAD_MS`만큼 먼저 보내면 좌석 락에도 대략 그만큼 먼저 닿는다.
    U가 락을 쥐는 시간은 매수 판정(COUNT 2개) + 롤백이다 — 같은 하네스의 앱 타이머 `seat.hold.limit.check` 평균은 smoke S2 none L4에서 1.06ms였다(아래 스모크). `LEAD_MS`가 이 창보다 길면 U가 끝난 뒤에 일반 사용자가 도착해 현상이 사라지고, 0이면 U가 락을 먼저 잡을 기회가 경쟁자 21명 중 하나로 줄어든다. 그래서 창보다 짧은 1ms(k6 `sleep`의 실용 해상도)로 둔다.
  - 좌석 사이 50ms 간격은 한 좌석의 경주(21건)가 다음 좌석과 겹치지 않게 — 동시 부하가 아니라 롤백 현상만 보도록(100석 × 50ms ≈ 5초).
  - 실제 시차는 요청 기록으로 검증한다: 요청마다 `t0`(보낸 시각 ms)·`lag`(예정 시각 대비 늦음 ms)를 남기고, 요약에 좌석별 "일반 − U 보낸 시각" 분포를 낸다.
- 스모크 관측(2026-10-06, L4 1회 — 본측정 아님): none에서 U가 매수 판정까지 간 좌석 21/100(U 응답 HOLD_LIMIT_EXCEEDED), 나머지 79석은 U가 좌석 락에서 짐(SEAT_NOT_AVAILABLE). 그래도 **억울한 좌석 0** — 100석 모두 일반 사용자 1명이 가져갔다. 일반 − U 보낸 시각은 −2~3ms(177건은 일반이 U보다 먼저 나감)로, `LEAD_MS` 1ms보다 k6 쪽 흩어짐이 크고 U의 락 창(≈1ms) 뒤에 도착한 일반 사용자가 좌석을 가져간다. counter는 U 100/100이 좌석 전에 HOLD_LIMIT_EXCEEDED.
- 방식별 기대: none·advisory·advisory-try·quota-*·serializable* 은 U가 사용자 단위 진입 → 좌석 락 → 매수 판정 순서라 현상이 생길 수 있다. counter는 매수 판정(카운터 UPDATE 0행)이 좌석 락보다 먼저라 U가 좌석 락을 잡지 않는다(명세 §2 에러 우선순위 예외).

## 지표 정의

| 지표 | 정의 | 출처 |
|------|------|------|
| 매수 초과 사용자(판정기) | 판정기 `v_over_limit_users` — (회차, 사용자)별 홀드 + 확정 예약 > 2 | `consistency.json` |
| 매수 초과 사용자(응답) | S2에서 201을 3건 이상 받은 사용자 수 | `hold_code` 행 |
| 가짜 거절 사용자 | S2에서 **끝 상태 매수(DB: 회차 1의 홀드 + 확정 예약) < 2인데 409 `HOLD_LIMIT_EXCEEDED`를 1번 이상 받은 사용자** — 매수가 남았는데 즉시 실패형·SERIALIZABLE이 거절한 것(명세 §9.3 ②). 응답 기준(201 < 2 & HLE)도 함께 내고, 둘이 다르면 `응답≠끝 상태 사용자`가 0이 아니다 | `end-state.json` × `hold_code` |
| 409 코드별 수 | 409 응답 본문의 `code`(SEAT_NOT_AVAILABLE · HOLD_LIMIT_EXCEEDED · 그 밖) | `hold_code` 행 |
| 억울한 좌석 수 / 억울한 409 수 | S7에서 **끝 상태가 AVAILABLE(아무도 못 가짐)인데 일반 사용자가 409 SEAT_NOT_AVAILABLE을 받은 좌석 수 / 그 좌석들에서의 그런 409 수**(명세 §9.3 ④) | `end-state.json`(좌석별 상태·보유자) × `hold_code` |
| U 코드 분포 | S7 U의 응답: HOLD_LIMIT_EXCEEDED(매수 판정까지 감) · SEAT_NOT_AVAILABLE(좌석 락에서 짐) · OK(매수 위반) | `hold_code` |
| 201 − 홀드 행 | S2: 201 수 − 홀드 행. S7: 측정 201 + setup 201(`setup_hold_201`) − 홀드 행. 0이 아니면 응답과 DB가 어긋남 | k6 요약 × 판정기 |
| 매수 acquire / check | 앱 Micrometer 타이머 `seat.hold.limit.acquire`(트랜잭션 시작 직후 사용자 단위 진입 — 락·카운터·격리 수준 설정) · `seat.hold.limit.check`(좌석 확인 뒤 매수 판정). k6 직전·직후 `metrics` 엔드포인트 값의 차분 — 건수(COUNT), 평균 ms(ΔTOTAL_TIME ÷ ΔCOUNT), 최대 ms(MAX = 누적이 아니라 최근 약 2분 창). S7은 setup 선점 200건 포함 | `before-k6.json`·`after-k6.json` |
| 40001 수 / 재시도 수 | 카운터 `seat.hold.limit.serialization_failure`(직렬화 충돌, 재시도 중 난 것 포함) · `seat.hold.limit.retry`(L7이 다시 한 수). 아직 기록 없는 지표(지표 목록에 이름 없음)는 0, 목록 조회 실패는 미측정 | 같음 |
| 카운터 불일치 사용자 | 판정기 `v_counter_mismatch` — counter 방식에서만 판정기가 낸다(쿼터 행 cnt ≠ 홀드 + 확정). 다른 방식은 '해당 없음' | `consistency.json` |

요청 기록 `hold_code`: 시나리오 lib의 `hold(…, {code: true})`가 요청 1건당 1행을 남긴다(태그 `code` = OK · 409 본문 code · `HTTP<상태>`, + 호출부 태그 user·seat·role 등). S2·S7만 켠다(S4는 요청 수가 커서 원시 기록을 불리지 않게).
끝 상태 `end-state.json`: k6 직후 DB에서 직접 — S2·S7의 회차 1 사용자별 매수, S7의 대상 좌석별 상태·홀드 보유자.

## 매수 확인 쿼리 DB 벤치 (`scripts/limit-bench.sh`)

ADR-002 `s5-bench.sh` 방식: DB 컨테이너 안에서 pgbench로 Hibernate가 실제로 내는 매수 확인 SQL 2개(ADR-003 §2.1 ④ 원문)를 잰다.

- qh 홀드 수: `select count(ps1_0.id) from product_seat ps1_0 left join seat_hold h1_0 on ps1_0.id=h1_0.seat_id where ps1_0.schedule_id=? and h1_0.user_id=?`
- qr 확정 예약 수: `select count(r1_0.id) from reservation r1_0 where r1_0.schedule_id=? and r1_0.user_id=? and r1_0.status=?`
- 배경 규모 0 / 10만 / 100만(`/internal/reset?backgroundRows=N` — 배경 회차에 홀드 N·확정 N, 사용자 대역 9e9·8e9), L4(앱·DB CPU 4), 만료 배치 사실상 끔(1h·TTL 60m).
- **측정 사용자는 배경 대역이 아니라 S2 측정 사용자와 같은 대역**: has = 200001(앱 API로 좌석 1·2 선점 후 좌석 2 확정 → 홀드 1 + 확정 1, 한도에 닿은 사용자) · none = 200002(아무것도 없음, 첫 선점). 준비 뒤 `ANALYZE`(reset의 통계는 준비 전 것)하고, DB에서 두 사용자의 행 수를 확인해 다르면 `rows-mismatch`. 배경 0에서는 표가 1행이라 Seq Scan이 정상(스모크 관측).
- 조합마다 `EXPLAIN (ANALYZE, BUFFERS)`(리터럴 값) + 1연결 pgbench(`-M prepared -c 1 -T 15`, 거래 로그). 회차·사용자는 바인드 변수, `status`는 pgbench 변수가 문자열을 못 담아 리터럴 `'CONFIRMED'`(앱은 바인드 — 편향).
- 결과: `<out>/N<규모>/{explain,qh|qr-has|none-c1.txt, …-latency.log.gz, rows.json, status}`. 캠페인 폴더 아래 `limit-bench/`에 두면 `compare.py`가 표로 낸다.

## 실행

```bash
k6/ADR-005/scripts/campaign.sh --sha <SHA> --id <campaign-id>                    # 본측정 — 24조건, 회차 우선, 비정상 회차 재측정
k6/ADR-005/scripts/run.sh --sha <SHA> --limit-strategy counter --levels 4 --cells 'S7' --reps 5 --id <id>   # 조건 하나
k6/ADR-005/scripts/limit-bench.sh --sha <SHA> --out k6/ADR-005/results/<campaign-id>/limit-bench   # DB 벤치(규모 0/10만/100만)
for d in k6/ADR-005/results/<campaign-id>/*/; do [ -f "$d/plan.json" ] && k6/ADR-005/scripts/summarize.py "$d"; done
k6/ADR-005/scripts/errsplit.py k6/ADR-005/results/<campaign-id>                   # 응답 코드 분류·원시 sha256 (compare 전에)
k6/ADR-005/scripts/compare.py k6/ADR-005/results/<campaign-id>                    # 조건 교차표 + 판정기 전수
```

- 실행하는 하네스 = 기록되는 SHA: `run.sh`·`campaign.sh`·`limit-bench.sh`는 작업트리의 `k6/ADR-005`(results 제외)가 SHA와 다르면 거부한다.
- **스모크 전용 `--worktree`**(run.sh·limit-bench.sh): 앱은 `<SHA>` 그대로, 하네스는 작업트리에서(서버 배포 폴더 `<SHA>-wt-<하네스 해시 8자리>`). plan·meta의 `harness`가 `worktree:<해시>`가 된다. 캠페인·`--resume`과 함께 쓸 수 없다.
  `--inject-limit-strategy <값>`(--worktree에서만): 앱에 계획과 다른 매수 방식을 주입해 `limit-strategy-mismatch` 판정을 실증한다(meta `injected_limit_strategy`).
- 요청별 원시 기록(`k6-requests.csv.gz`)은 커밋하지 않는다(`.gitignore`) — `errsplit.py`의 sha256 목록과 요약만.
