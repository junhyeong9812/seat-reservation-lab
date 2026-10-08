# 요구사항 명세서 (requirement-spec) — ADR-006 매수 카운터 한 문장 upsert: counter vs advisory-try 재비교

> 작성일: 2026-10-09 · 작업 폴더: `docs/plans/2026-10-09/adr-006-counter-upsert/` · 브랜치: `feat/adr-006-counter-upsert`(main `201aabdd` — ADR-005 PR #5 병합 — 에서 분기)

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문(2026-10-09): "ADR6에서 그럼 카운트를 1방 쿼리 형태로 바꾼다는 거잖아? 그렇게 해서 두 개를 비교하고 기존의 다음 단계들을 한 단계씩 미뤄서…" → 기존 ADR-006~011을 007~012로 이동(완료, 6be4ef0f 계열).
- 비교 방식: 메인이 '다른 캠페인 데이터 재사용은 시간 변화와 방식 차이를 못 가른다'고 보고 → 사용자 선택 **"세 방식을 같은 캠페인에서"** — none(대조군) · advisory-try(ADR-005 결정) · counter-upsert(신규).
- ADR-005 마무리: **advisory-try로 확정, counter는 ADR-006으로**(완료).
- 인터뷰 1차(2026-10-09):
  - 하네스 → **S4 한계 규칙 수정 · 조건 순서 섞기 · 서버 I/O 지표 수집** 전부
  - 시나리오 → **기본 안(S2·S4 L2·L4 + S7-m1 L4 × 5) + S3 이탈 20%만**(L4 × 3)
  - 기존 counter → **그대로 두고 새 방식(counter-upsert) 추가**

---

## 1. 목표·대상 (필수)

`seat-reservation-lab`에 매수 방식 `counter-upsert`(트랜잭션 안 한 문장 `INSERT … ON CONFLICT DO UPDATE … WHERE cnt + 1 <= 상한`)를 추가하고, none · advisory-try와 **같은 캠페인**에서 실측해 "요청당 커넥션 빌림을 1회로 줄이면 counter의 처리량 회귀가 사라지고 장점(S7-m1 억울한 좌석 0·같은 사용자 지연 최소·매수 정합 0)이 유지되는가"를 판정해 `docs/adr/ADR-006-counter-upsert.md`에 기록하면 끝. 결과에 따라 ADR-005의 기본(advisory-try)을 바꿀지 제안한다.

| 방식(설정 `seat.hold.limit-strategy`) | 설명 |
|------|------|
| `none` | 대조군(조회 후 비교) |
| `advisory-try` | ADR-005 결정 — `pg_try_advisory_xact_lock(회차, 사용자)`, 좌석 확인 뒤 거절 |
| `counter-upsert` (신규) | ①.3 사용자 단위 진입에서 한 문장: `INSERT INTO user_hold_quota (schedule_id, user_id, cnt) VALUES (?, ?, 1) ON CONFLICT (schedule_id, user_id) DO UPDATE SET cnt = user_hold_quota.cnt + 1 WHERE user_hold_quota.cnt + 1 <= ?` — 영향 행 1 = 통과, 0 = `HOLD_LIMIT_EXCEEDED`. prepare 없음 |

## 2. 경계·불변식 (필수)

- **좌석 3b·응답 계약·에러 코드는 ADR-005 그대로**. counter-upsert의 에러 순서는 counter와 같다(매수 판정이 좌석보다 먼저 — ADR-005에서 사용자 허용).
- **카운터 정의는 ADR-005 counter와 같다**: 홀드 + 확정 예약 수. 선점 +1(좌석 실패면 같은 트랜잭션 롤백), 확정 변화 없음, 만료 −1(만료 배치가 실제로 지운 홀드 — counter·counter-upsert일 때만). 판정기 `v_counter_mismatch`도 counter-upsert에 적용.
- **기존 매수 방식 10개의 동작은 바꾸지 않는다**(counter-upsert는 새 설정값). 앱 기본값(좌석 3b·매수 none) 그대로.
- **요청당 커넥션 빌림 1회**가 counter-upsert의 설계 목표 — 트랜잭션 밖 단계(prepare) 없음.
- **측정 조건은 ADR-005와 같게**(서버·k6 PC 유선·시드·예열·타임아웃·Tomcat·인덱스 V2·풀 10·좌석 3b). 달라지는 것: 매수 방식, 그리고 하네스 개선 3가지(아래) — 하네스 개선이 측정값 해석을 바꾸는 부분(S4 한계 규칙)은 ADR-005 결과도 새 규칙으로 다시 계산해 나란히 적는다.
- **비교는 같은 캠페인 안에서만**. ADR-005 수치는 참고로만 인용한다(시간 변화 — ADR-005 §7.3 ③).
- 판정·근거 보존 규칙은 ADR-001~005 그대로(DB 사실 기준, 표는 스크립트 산출, 미측정 표기, 요청별 CSV는 sha256만 — **큰 원시 파일은 gzip**, GitHub 100MB 한도).

## 3. 기준소스 (필수)

- `docs/adr/ADR-005-per-user-limit.md`(§2.7 counter, §7.3 ③ prepare·3회 빌림·시간 갈림, §8, §9), `docs/adr/ADR-006-counter-upsert.md`(가설 초안)
- 코드 기준: main `201aabdd` — `service/impl/limit/UserLimitStrategies.kt`(CounterUserLimit) · `UserLimitStrategyType.kt` · `ExpireHoldsService.kt` · `loadtest/LoadtestDataService.kt`
- 하네스 기준: `k6/ADR-005/`(복사해 `k6/ADR-006/`로)
- 환경: ADR-005와 같음(.164 서버, k6 PC 유선)

## 4. 금지영역 (필수)

- 기존 매수 방식 10개·좌석 전략 11개의 동작 변경, 응답 계약 변경
- ADR-001~005의 원시 결과·하네스 수정(읽기만 — 새 하네스는 `k6/ADR-006/`). 단 ADR-005 결과를 새 S4 규칙으로 **다시 계산**하는 것은 허용(원시 결과는 안 바꾸고 산출물만 새 폴더/표에)
- .164의 기존 컨테이너·디렉터리, `~/labs/seat-reservation-lab/` 밖 경로 · 호스트 포트 8101·15432만 · 서버 디스크 사용률 감시(배포 크기 1.3MB 유지)
- 자격증명 탐색 · `main` 직접 커밋 · 사용자 확인 없는 push · 다른 서버(.9·.158) 사용

## 5. 검증 방법 (필수)

- **counter-upsert 테스트(Testcontainers)**: ADR-005 `UserLimitStrategyTest` 계약 그대로(같은 사용자 동시 10좌석 초과 0 · 다른 사용자 비차단 · 같은 좌석 50명 1명 · 에러 순서(매수 먼저) · 카운터 정합(선점·좌석 실패 롤백·확정·만료) · 격리 수준) + **첫 요청 두 개가 동시에 INSERT 경로로 와도 cnt = 성공 수** · prepare 타이머 0건.
- 하네스 스모크: 요청당 빌림 수(Hikari 획득 COUNT ÷ 선점 요청) counter-upsert = 1 · S4 새 한계 규칙이 ADR-005 serializable 회차에서 포화점과 일관 · 순서 섞기 계획(plan) 확인 · I/O 지표가 회차 전후로 기록됨.
- 본측정: 계획 대비 누락 0, 비정상 회차 재측정.
- ADR-006 표는 원시 결과에서 스크립트로 재산출.

## 6. stakes (필수)

- 판정: **중간** — 동시성(upsert 경합), 실험 DB 한정, 응답 계약 불변, 데이터 의미 변경 없음(카운터 정의 동일). → 코드 리뷰 듀얼 1패스, 결과 문서 듀얼 1패스.

---

## 7. 자율성

- [x] auto (기본 권장 — 합의 후 Claude 자율 실행)
- [ ] lazy (매 diff 이해 게이트 — 학습·OSS)

## 8. load-bearing 가정 (1~2개)

1. **한 문장 upsert가 같은 사용자 동시 요청에서 정합하다**(READ COMMITTED에서 `ON CONFLICT DO UPDATE`가 충돌 행을 잠그고 최신 버전으로 `WHERE`를 다시 본다) — 착수 직후 경합 테스트로 실증.
2. **요청당 커넥션 빌림이 1회가 된다** — 스모크에서 Hikari 획득 COUNT ÷ 선점 요청 = 1로 실증. 1이 아니면 설계를 다시 본다.

## 9. 설계

### 9.1 앱

- `UserLimitStrategyType.COUNTER_UPSERT` + `CounterUpsertUserLimit`: `acquire`에서 위 upsert 한 문장(영향 행 0 → `limitExceeded()`), `check` 없음, `onHoldsExpired`는 counter와 같은 감소(공용 함수로). `prepare` 없음.
- 판정기 `v_counter_mismatch`·counter + 배경 행 거부를 counter-upsert에도 적용.

### 9.2 측정 매트릭스 (좌석 3b, 풀 10, 인덱스 V2, 배경 0)

| 대상 | 시나리오 | 단계 | 회차 |
|------|---------|------|------|
| none · advisory-try · counter-upsert | S2 · S4 | L2 · L4 | 5 |
| 같음 | S7-m1 | L4 | 5 |
| 같음 | S3 원본 이탈 20% | L4 | 3 |

→ 약 9시간(추정 — ADR-005 실측: s24 조건 21분, S3 셀 20분, S7 셀 1분).

### 9.3 하네스 (`k6/ADR-006/` — ADR-005 하네스 복사 + 개선 3)

- **S4 한계 규칙**: 목표 미달(포화) 단계에서 멈춘다 — 그 뒤 단계를 '통과'로 집지 않는다. 포화점을 함께 낸다. ADR-005 결과에 새 규칙을 적용한 재계산 표를 만든다.
- **조건 순서 섞기**: 회차마다 조건 순서를 고정 시드로 섞는다(회차 우선 유지). plan·CAMPAIGN.log에 실제 순서 기록.
- **서버 I/O 지표**: 회차 k6 전후로 `pg_stat_wal`(wal_sync·wal_sync_time 등)·`pg_stat_io`와 서버 디스크 통계(`/proc/diskstats` 또는 iostat)를 차분 기록 → 요약기에 fsync 평균 시간·디스크 대기.

### 9.4 판정 기준

| 기준 | 지표 |
|------|------|
| ① 매수 정합성 | S2·S3·S7-m1 매수 초과 0, `v_counter_mismatch` 0 |
| ② 처리량 | S4 엄격 한계(새 규칙)·포화점 — counter-upsert vs none·advisory-try |
| ③ 커넥션 빌림 | 요청당 Hikari 획득 수 |
| ④ 롤백 피해 | S7-m1 억울한 좌석 |
| ⑤ 같은 사용자 지연 | S2 p50·p99·획득 대기 |
| ⑥ 시간 효과 | 순서를 섞은 회차 간 흔들림 vs 방식 간 차이 · I/O 지표와의 관계 |
| ⑦ 실패 모드 | 5xx·데드락·풀 타임아웃·타임아웃 |

### 9.5 문서

- `docs/adr/ADR-006-counter-upsert.md` 가설 → 측정 계획 → 측정 전 검증 → 결과 → 결정. README 결정 기록 갱신(ADR-005 기본 변경 여부 포함).

## 10. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | ADR-006 가설·계획 갱신 | — | 명세와 일치 |
| 02 | counter-upsert + 테스트 | — | 가정 1 실증, 기존 테스트 green |
| 03 | `k6/ADR-006` 하네스(복사 + 개선 3) + ADR-005 S4 재계산 | 02 | 스모크 통과, 가정 2 실증 |
| 04 | 코드·하네스 듀얼 리뷰 1패스 + 수정 | 03 | ledger |
| 05 | 본측정 → 재측정 | 04 | 누락 0 |
| 06 | 결과 분석·ADR-006 결과/결정·README · 결과 문서 듀얼 리뷰 · 커밋·push | 05 | 표 재산출 가능 |

---

## 승인 상태

- [x] 필수 6칸 전부 기입
- [x] 사용자 합의 → SPEC=1 (2026-10-09 — "진행해보자")
- [x] 자율성 선택 → MODE=auto(권장안 그대로 진행 지시)
