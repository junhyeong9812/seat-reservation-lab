# k6/ADR-008 — 매수 제어 기본값(counter-upsert-precheck) 확정 측정: 기본값 그대로 기동한 앱

ADR-007 하네스(`k6/ADR-007/`)를 복사하고 **매수 방식 주입을 뺐다** — 앱은 `application.yml`·코드 기본값(ADR-008 결정 `counter-upsert-precheck`)으로 뜬다.
그 위에 **기본값 출처 증거**(앱 컨테이너에 매수 방식 키가 없는가)와 **회차 합격 판정**(명세 §5)을 회차마다 더했다.
시나리오·판정기·수집·검증 규칙은 ADR-001~007과 같다 — `k6/ADR-007/README.md`(셀·DB 설정·벽 분석)·`k6/ADR-006/README.md`(S4 한계 규칙·I/O 지표)·`k6/ADR-005/README.md`(지표 정의) 참고.
명세: `docs/plans/2026-10-11/adr-008-precheck-default/requirement-spec.md`(§2 경계 · §3 기준소스 · §5 검증 방법).

## 구성

| 파일 | 역할 |
|------|------|
| `compose.yml` | ADR-007 compose에서 `SPRING_APPLICATION_JSON`의 `"limit-strategy"` 키만 뺐다. `SEAT_HOLD_LIMIT_STRATEGY` 변수도 읽지 않는다(셸에 있어도 앱에 닿지 않는다) |
| `scripts/run.sh` | 조건 하나 실행기. ADR-007 + 매수 방식 축 없음(`--limit-strategy`·`--inject-limit-strategy` 거부) · 기대 기본값 대조 · `limit-key.json` · 회차 합격 사유 |
| `scripts/campaign.sh` | 확정 측정 캠페인 — 13회(아래), 회차 우선 + 짧은 셀 회전(`order.txt`), `--dry-run`. 합격 사유 회차는 재측정하지 않는다 |
| `scripts/lib.sh` | ADR-007 + `limit_key_check`(앱 컨테이너 환경의 매수 방식 키 **개수만** — 서버에서 세고 숫자만 받는다) |
| `scripts/summarize.py` | ADR-007 + `--accept-check <회차> <셀>`(회차 합격 판정) · 빌림 식을 `borrow_per_hold()`로 뺐다(값은 같다 — 오프라인 확인) |
| `scripts/compare.py` | **새로 씀** — 셀 5개 × (ADR-008 기본값 · ADR-007 precheck-default) 비교 · 합격 항목 · 같은 자릿수 |
| `scripts/wall.py`·`wdrop.py`·`gapcheck.py`·`host-sampler.sh` | ADR-007 그대로 — run.sh·lib.sh가 회차마다 부른다(S4 벽 판정 · 호스트 표본 판정 `run.sh` `wdrop.py --coverage` · 표본 공백 · 호스트 표본) |
| `scripts/errsplit.py`·`stalls.py` | ADR-007 그대로(분석용) |
| `scenarios/`·`nginx.conf`·`compose.two-apps.yml`·`compose.tmpfs.yml` | ADR-007 그대로(앱 2대·tmpfs는 ADR-008 캠페인이 쓰지 않는다 — run.sh 옵션 호환용) |

ADR-007의 `fio-disk.sh`(디스크 원인 후속 전용)와 ADR-007 `campaign.sh`·`compare.py`(117회 매트릭스 전용)는 가져오지 않았다.

## ADR-007 대비 바뀐 점

1. **매수 방식을 주입하지 않는다.** compose에 키가 없고, `run.sh`의 `config_env`도 `SEAT_HOLD_LIMIT_STRATEGY`를 넘기지 않는다(meta `app_env`에 거짓 값이 남지 않게).
   `LIMIT_STRATEGY=counter-upsert-precheck`는 **기대 기본값**이다 — 축이 아니라 대조 기준. plan·meta에 `limit_source: "default"`.
2. **기본값 출처 증거(`limit-key.json`)** — `app_config` 직후 앱 컨테이너(앱 2대면 둘 다)의 환경에서
   `SEAT_HOLD_LIMIT…`로 시작하는 변수 수(`env`)와 `SPRING_APPLICATION_JSON` 안의 `limit-strategy`·`limitStrategy`·`limit_strategy` 등장 수(`json`)만 센다.
   **환경 전체는 기록하지 않는다**(`DB_PASSWORD`가 들어 있다). 세는 일은 서버에서 하고 숫자만 받는다.
   적용 값은 ADR-007과 같이 configprops(`app-config.json` `seat.hold.limitStrategy`)와 meta `applied.limit_strategy`.
3. **회차 합격(명세 §5)** — `rep_status`가 `summarize.py --accept-check`를 부른다. 사유가 하나라도 있으면 그 회차는 ok가 아니다.

| 사유 | 조건 | 대상 셀 |
|------|------|---------|
| `over-limit` | 판정기 `v_over_limit_users` > 0, 또는 S2 사용자별 응답·끝 상태 매수 초과(`s2_users`) | 전부 |
| `counter-mismatch` · `counter-mismatch-missing` | 판정기 `v_counter_mismatch` ≠ 0 · 항목 없음(카운터 방식이 아니게 떴다) | 전부 |
| `accept-consistency-missing` | consistency.json에 `v_over_limit_users`가 없음 | 전부 |
| `s7-wronged-seats` | 억울한 좌석(`s7_seats` — 끝 상태 AVAILABLE인데 일반 사용자가 409) > 0 | S7-m1 |
| `s7late-order-violation` | 명세 순서 위반(`s7_order` `u_hle_after_r_201` — R 201 뒤에 보낸 U가 HLE) > 0. 기회 0은 기존 `s7late-no-opportunity` | S7-m1-late |
| `s7-check-failed` | S7 판정 입력(hold_code 행·end-state) 없음 | S7-m1 · S7-m1-late |
| `borrow-not-one` · `borrow-missing` | 요청당 빌림이 [0.98, 1.02] 밖 · 계산 불가. 폭 = ADR-005 실측 1커넥션 방식 1.00~1.02(run.sh `invalid-borrow-ratio` 주석). ADR-007 precheck 실측 S2 1.000 · S4 1.0001 · S7 1.0025 | S3 제외(확정 요청도 빌린다 — 기존 `invalid-borrow-ratio`와 같은 제외) |
| `accept-check-failed` | 판정 자체가 안 됨(입력 읽기 실패 등 — exit 2) | 전부 |
| `limit-strategy-mismatch` | configprops `limitStrategy` ≠ `COUNTER_UPSERT_PRECHECK`(기존 판정식, 기대값만 기본값) | 전부 |
| `limit-key-present` · `limit-key-check-failed` | 앱 컨테이너에 매수 방식 키가 있음 · limit-key.json 없음/앱 수와 다름 | 전부 |

## 캠페인 (명세 §5 확정 측정)

- 셀: `S2`·`S3-a20`·`S7-m1`·`S7-m1-late` 각 3회 + `S4` 1회 = **13회 호출**. 조건 이름 `default-<셀 이름표>`. L4 · 좌석 3b · 풀 10 · 앱 1대 · DB 설정 default.
- 순서: 회차 우선 — 회차 k는 짧은 셀 4개를 (k−1)칸 회전, S4는 1회차 끝. `order.txt`가 정본, 이어서 실행 때 다시 계산해 같아야 한다.
- 재측정: 끝나면 비정상 회차를 다시 잰다(최대 2바퀴). **합격 사유(위 표의 결과 사유)가 있는 회차는 다시 재지 않는다** — 측정 결과이지 하네스 실패가 아니다(다시 재서 ok가 나오면 실패가 가려진다). 남으면 exit 1.
- 비교: `compare.py` — ADR-008 `default-<셀>` ↔ ADR-007 `results/20261009-adr007-ea8b85cc/counter-upsert-precheck-default-<셀>`(명세 §3 기준). 합격 항목이 하나라도 어긋나면 exit 1. 지표 자릿수(S3-a20 WAL · S2 p99 등)가 다르면 '자릿수 다름' 행(원인을 ADR-008 문서에 기록).

## 실행

```bash
# 계획만(측정 없음) — conditions.txt·order.txt 13행
k6/ADR-008/scripts/campaign.sh --sha <SHA> --id <id> --dry-run
# 확정 측정(하네스 = 커밋된 SHA여야 한다)
k6/ADR-008/scripts/campaign.sh --sha <SHA> [--id <id>]
# 조건 하나(스모크)
k6/ADR-008/scripts/run.sh --sha <SHA> --levels 4 --cells S2 --reps 1 --id <id>
# 분석
for d in k6/ADR-008/results/<id>/default-*/; do python3 k6/ADR-008/scripts/summarize.py "$d"; done
python3 k6/ADR-008/scripts/compare.py k6/ADR-008/results/<id>
```

- **음성 대조(서버 스모크 — 명세 task 05)**: 기본값이 아직 `none`인 앱 SHA(`fb181a30`)에 이 하네스를 작업트리로 붙여(`run.sh --sha fb181a30 --worktree …`) 돌리면
  회차가 `limit-strategy-mismatch`로 끝나야 한다(키를 주입하지 않으므로 `limit-key-present`는 없어야 한다).

## 재측정 판정식 확인 (오프라인, 2026-10-11)

ADR-007 방식(`dc/rs/run-tests.sh`)대로 실제 `run.sh`에서 `rep_status`를 떼어 ADR-007 precheck 본측정 회차의 **복사본**에 돌렸다(원본 변경 0건 확인) — 46/46 통과.
- ADR-007 precheck-default S2·S3-a20·S4·S7-m1·S7-m1-late 회차는 ADR-008 판정으로도 `ok`(합성: 호스트 표본·저장 위치·limit-key.json — 그 회차들은 그 도입 전).
- 새 사유마다 양방향: `over-limit`(판정기·S2 응답) · `counter-mismatch`·`-missing` · `s7-wronged-seats`(ADR-007 none S7-m1 실측 = 있음, precheck = 없음, late 셀에서는 보지 않음) ·
  `s7late-order-violation`(ADR-007 counter-upsert S7-m1-late 양성 대조 = 있음, precheck = 없음, m1 셀에서는 보지 않음) · `borrow-not-one`(1.03 = 있음 — 기존 0.9~4 범위 안, 1.019 = 없음, S3 제외) ·
  `borrow-missing` · `limit-key-present`(env·json) · `limit-key-check-failed`(파일 없음·앱 수 다름) · `limit-strategy-mismatch`(NONE) · `accept-check-failed`(깨진 csv).
- `summarize.py` 회귀: S2·S7-m1 조건을 ADR-007·ADR-008 요약기로 각각 요약 → `summary.json` 같음.
- `lib.sh limit_key_check`: 로컬 `docker create` 컨테이너 3개(키 없음 · `SEAT_HOLD_LIMIT_STRATEGY` · JSON 키)와 없는 컨테이너 — 4/4, 출력에 비밀번호 없음.
