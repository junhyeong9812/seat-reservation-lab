# 요구사항 명세서 (requirement-spec)

> 작성일: 2026-10-11 · 작업 폴더: `docs/plans/2026-10-11/adr-008-precheck-default/`
> 선행: ADR-007 §8 결정(매수 제어 기본 = counter-upsert-precheck, 사용자 2026-10-11) · `docs/plans/NEXT.md` N1

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "전환및 테스트까지 진행해보자" · "이거 adr8로 진행하자" · "이테스트는 구래서 종말 괜찮은지 보고 이걸로 확정하는과정까지"
- Q/A (2026-10-11):
  - 전환 범위 = **설정 + 코드 기본값 둘 다**(`application.yml` `seat.hold.limit-strategy` · `SeatHoldProperties.limitStrategy`)
  - 기존 데이터 = **Flyway 백필 마이그레이션 추가**
  - 테스트 = **기존 테스트 전체 + 기본값 테스트 · 서버 스모크(기본값 기동) · 부하 하네스 무영향 확인**
  - 확정 측정 = **기본값으로 기능 셀 재측정**(S2·S3-a20·S7-m1·S7-m1-late 각 3회 · S4 1회)
  - 번호 = 기존 ADR-008~013을 009~014로 옮기고 새 ADR-008(완료 — fb181a30)
- 메인이 실코드에서 확인한 사실(명세 근거):
  - 지금 코드 기본값은 `none`이다(`application.yml:36` · `SeatHoldProperties.kt:14`). ADR-005의 advisory-try 결정도 코드에 반영된 적이 없다 → 실제 전환은 **none → counter-upsert-precheck**.
  - 카운터 정의(판정기 `LoadtestDataService.kt:165-176` `v_counter_mismatch`): `user_hold_quota.cnt` = 그 (회차, 사용자)의 **`seat_hold` 행 전체 수 + `reservation` CONFIRMED 수**. 만료 배치는 실제로 지운 홀드 수만큼 내린다(`UserLimitStrategies.kt:214-223` — 행이 없으면 `check` 실패). 그래서 백필은 "만료 전 홀드"가 아니라 **`seat_hold` 행 전체**를 센다(ADR-007 §8·NEXT의 '유효(만료 전 HELD)' 표현은 틀림 — 이 작업에서 정정).
  - 기동 검사(`HoldStrategyStartupCheck.kt:36`): 매수 방식이 none이 아니면 좌석 전략은 `PESSIMISTIC_NOWAIT`이어야 한다 → 기본값이 바뀌면 **좌석 전략만 바꾸는 설정(ADR-001~003 하네스, 다른 락 전략 테스트)은 기동 실패**한다.
  - counter 계열은 배경 행 시드를 거부한다(`LoadtestDataService.kt:38`).
  - ADR-005~007 하네스 compose는 `SEAT_HOLD_LIMIT_STRATEGY:-none`을 앱 설정 JSON에 넣는다 → 기본값을 덮어 none으로 뜬다(영향 없음 — 단 "기본값으로" 재측정하려면 이 키를 빼는 경로가 필요). ADR-001~003 compose에는 이 키가 없다 → 새 기본값을 받는다.
  - Flyway: `db/migration` V1·V2·V3·V5 + `db/migration-unique` V4 → 새 마이그레이션은 V6.

## 1. 목표·대상 (필수)

`seat-reservation-lab`의 매수 제어 기본값을 counter-upsert-precheck로 바꾸고(설정·코드), 기존 데이터가 있는 DB에서도 카운터가 맞도록 V6 백필을 넣고, 기존 하네스·테스트가 의도대로(방식 명시) 계속 돌게 한 뒤, **기본값 그대로 기동한 앱**으로 기능 셀을 재측정해 ADR-007 precheck 결과와 같은 정합·동작을 확인하고 새 ADR-008에 확정을 기록한다.

## 2. 경계·불변식 (필수)

- **카운터 불변식**: V6 적용 직후, 모든 (회차, 사용자)에 대해 `cnt = seat_hold 행 수 + CONFIRMED 예약 수`(`v_counter_mismatch` = 0). 홀드·예약이 없는 쌍은 행이 없거나 cnt = 0. 빈 DB에서는 아무 행도 만들지 않는다. 이미 쿼터 행이 있으면 실제 값으로 **덮어쓴다**(멱등 — 다시 돌려도 같은 결과).
- 백필은 기존 홀드·예약을 **바꾸지 않는다**(쿼터 테이블만 쓴다). cnt가 상한(2)을 넘는 쌍(none 시절 초과분)도 실제 값 그대로 둔다 — 다음 요청부터 upsert의 `cnt + 1 <= 최대`가 막는다(초과분을 지우지 않는다).
- 기본값 외 동작 불변: 다른 방식을 명시하면 지금과 같다. 응답 계약(에러 코드)·API 불변.
- 기존 하네스(ADR-001~007)는 **전과 같은 방식으로 돈다** — 방식을 명시하지 않는 곳은 none을 명시한다(측정 의미 보존). 기존 결과 폴더는 건드리지 않는다.
- 기존 테스트의 의미 보존 — 좌석 전략만 바꾸는 테스트는 none을 명시해 그대로 돈다(테스트를 지우거나 약화하지 않는다).
- 서버(.164) 경계: `~/labs/seat-reservation-lab` · compose project seatlab · 포트 8101/15432만. sudo 없음.

## 3. 기준소스 (필수)

- 결정: ADR-007 §8(2026-10-11) · 비교 기준: ADR-007 §7.1 precheck 결과(`k6/ADR-007/results/20261009-adr007-ea8b85cc/` — S2·S3-a20·S7-m1·S7-m1-late·S4, 방식 명시 `counter-upsert-precheck`).
- 카운터 정의: `LoadtestDataService.kt` `v_counter_mismatch` · `UserLimitStrategies.kt` `upsertQuota`·`decrementCounters`.
- 기동 검사: `HoldStrategyStartupCheck.kt`.

## 4. 금지영역 (필수)

- 다른 서버(.9/.158) · study-note 원본 checkout · `deploy-study-note/docs/secrets.md` · credential 파일시스템 grep · main 직접 커밋 · push는 사용자 확인 후.
- 기존 결과 폴더(`k6/*/results/**`) 변경 금지. 과거 작업 log 변경 금지.
- 기존 마이그레이션(V1~V5) 수정 금지 — 새 V6만.
- 다른 방식의 구현 코드 변경 금지(기본값·주석 번호 정정 외).

## 5. 검증 방법 (필수)

- **테스트**(blind 테스트 워커 — 구현 diff 미열람, 이 명세에서 출발):
  - 기본값: 설정 없이 기동하면 `COUNTER_UPSERT_PRECHECK`가 고르인다(`application.yml` 경로와 코드 기본값 경로 둘 다).
  - 기본값으로 같은 사용자 동시 10요청 → 성공 정확히 2, 카운터 = 실제.
  - V6 백필: ① 빈 DB → 행 0 ② 홀드·확정 예약·만료 지난 미정리 홀드·취소 예약·다른 회차가 섞인 DB → `v_counter_mismatch` 0 ③ 어긋난 기존 쿼터 행 → 실제 값으로 덮음 ④ 상한 초과 사용자 → 실제 값 유지, 다음 요청 거절 ⑤ 두 번 적용해도 같은 결과(멱등 — SQL 재실행으로 확인).
  - 기존 테스트 전체 green(좌석 전략만 바꾸는 테스트는 none 명시 후).
- **하네스 무영향**: ADR-001~007 하네스 전수 grep으로 방식 미지정 경로를 찾아 none 명시 · 각 하네스 dry-run/설정 렌더링이 변경 전과 같은 앱 설정(`limit-strategy` 값)을 낸다.
- **서버 스모크**: 기본값으로 기동(`limit-strategy` 미지정) → `configprops`로 precheck 확인 → S2·S7-m1 1회씩 ok.
- **확정 측정**(기본값 기동, k6/ADR-008 하네스): S2·S3-a20·S7-m1·S7-m1-late × 3 · S4 × 1. 판정: 매수 초과 0 · `v_counter_mismatch` 0 · 억울한 좌석 0(S7-m1) · 명세 순서 위반 0(S7-m1-late) · 요청당 커넥션 빌림 1.0 · 지표가 ADR-007 precheck 범위와 같은 자릿수(S3-a20 WAL, S2 p99 — 다르면 원인 기록). 앱 설정 기록(`app-config.json`)에 precheck가 찍혀야 회차 ok.
- **리뷰**: stakes 높음 → 설계 선검증(백필 SQL·불변식) + 듀얼 리뷰 루프(≤3) + blind 테스트 워커. 문서(ADR-008)는 듀얼 1패스.

## 6. stakes (필수)

- 판정: **높음** — DB 마이그레이션(데이터 쓰기, 실제 환경에서 되돌리기 어려움) · 기본 동작(1인 매수 제어)의 의미 변경 · 기동 검사로 기존 설정이 깨질 수 있음.

---

## 7. 자율성 (모드 — stakes와 별개 축)

- [x] auto (기본 권장 — 합의 후 Claude 자율 실행)
- [ ] lazy (매 diff 이해 게이트 — 학습·OSS 기여)

## 8. load-bearing 가정 (1~2개)

1. **V6 백필 한 문장(집계 → upsert로 덮어쓰기)이 카운터 불변식을 정확히 만든다** — 섞인 데이터 테스트로 착수 직후 실증.
2. **하네스가 `limit-strategy` 키를 빼면 앱이 코드·설정 기본값(precheck)으로 뜬다** — 서버 스모크의 `configprops`로 실증.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 설계 선검증: V6 SQL·불변식·하네스 영향 목록(전수 grep) | — | 검증 워커 ok |
| 02 | 구현: 기본값 2곳 · V6 · ADR-001~003 하네스와 테스트에 none 명시 · 코드 주석 ADR 번호 정정 · k6/ADR-008 하네스(기본값 모드 = 키 생략) | 01 | 컴파일·기존 테스트 green |
| 03 | blind 테스트 워커: §5 테스트 | 01 | 테스트 green |
| 04 | 리뷰 루프(≤3) | 02·03 | ledger 종료 |
| 05 | 서버 스모크 | 04 | 가정 2 성립 |
| 06 | 확정 측정 13회 + 분석 | 05 | 회차 전부 ok, 판정 |
| 07 | ADR-008 문서 · ADR-007·NEXT 정정 · 듀얼 문서 리뷰 · 측정로그 · 아카이브 | 06 | 확정 기록 |

- 예상: 01~05 약 3~4시간 · 06 약 2.5시간 · 07 약 1.5시간.

---

## 승인 상태

- [x] 필수 6칸 전부 기입 (빈 칸 없음)
- [x] 사용자 합의 → SPEC=1 (2026-10-11 "합의, auto로 진행" — set-state 기록)
- [x] 자율성 선택 → MODE=auto
