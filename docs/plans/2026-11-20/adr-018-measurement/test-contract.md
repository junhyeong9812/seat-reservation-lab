# 시험 계약 — ADR-018 2단계 task 01 묶음 경로 최소 지표 (구현 워커 · blind 워커 공통)

> 정본: 명세 `requirement-spec.md` §2 M18-6(관측만 — 응답 · 상태 전이 · 잠금 무변경 · 1석 기존 지표 이름 · 태그 무변경)과 이 문서. 지표 이름 · 태그 · 값은 아래 그대로 쓴다.

## 1. 지표 3개

### 1.1 `seat.group.request{op, outcome}` — 묶음 · 해제 API 응답 결과(모든 프로필)

- `op`
  - `hold`: `POST /api/schedules/{scheduleId}/hold-groups`
  - `release_group`: `DELETE /api/hold-groups/{groupId}`
  - `release_hold`: `DELETE /api/holds/{holdId}`
  - `pay_start`: `POST /api/hold-groups/{groupId}/payment-start`
  - `confirm`: `POST /api/hold-groups/{groupId}/confirm`
- `outcome`
  - 2xx = `ok`
  - 도메인 거부(`ErrorCode`로 나간 응답) = 그 `ErrorCode` 이름(예: `SEAT_NOT_AVAILABLE` · `HOLD_EXPIRED` · `PAYMENT_STARTED`)
  - 그 밖의 비2xx = `status_<코드>`(예: 본문 결함 `status_400` · 처리되지 않은 예외 `status_500`)
- 요청 하나에 정확히 1 증가한다. 응답 상태 기준이고, 커밋 · 롤백과 무관하다. 겹치지 않는다.
- 핸들러를 찾지 못한 요청(묶음 API를 꺼서 경로가 없는 404 등)은 세지 않는다.
- 기존 `ConfirmOutcomeMetrics`(1석 확정 `seat.confirm.rejected` · `seat.confirm.failed`)의 동작 · 값은 바뀌지 않는다.

### 1.2 `seat.group.hold.rejected{mode, cause}` — 묶음 선점 409 원인(loadtest 프로필에서만)

- `mode`: `split` | `one_shot`(그 요청을 처리한 방식).
- `cause`
  - `nowait`: split의 좌석 행 NOWAIT 패배(55P03)
  - `short`: one-shot의 바뀐 행 수 < N
  - `rule`: 그 밖의 선점 409(`SEAT_NOT_AVAILABLE` 중 위 둘이 아닌 것 · `HOLD_LIMIT_EXCEEDED` · `SCHEDULE_CLOSED`)
- 같은 응답에 헤더 `X-Seatlab-Reject: group-<cause>`(예: `group-nowait`)를 붙인다. 상태 코드 · 본문은 그대로다.
- **묶음 선점의 409는 1석 지표 `seat.hold.rejected`를 올리지 않는다.** 1석 선점의 `seat.hold.rejected{cause=nowait|rule}`와 헤더 `nowait` · `rule`은 그대로다(ADR-015~017 하네스가 헤더 합과 지표를 대조한다).
- loadtest가 아닌 프로필에서는 이 지표와 헤더가 없다(1석과 같은 규칙).

### 1.3 `seat.db.deadlock{path}` — 교착(SQLSTATE 40P01) 관측(모든 프로필)

- `path`
  - `group_hold` · `group_pay_start` · `group_confirm` · `release`: 해당 API가 40P01로 실패한 요청. 그 요청의 응답은 지금과 같고(바꾸지 않는다), `seat.group.request`에는 그 응답대로 센다.
  - `expiry_tick`: 만료 배치(sweep — `seat.hold.expiry-mode=sweep`, 묶음을 켜면 기동 검사가 강제) 틱이 40P01로 롤백된 경우, 틱당 1. 기존 `seat.hold.expiry.tick{outcome=failed}`는 그대로 함께 오른다. lazy 계열(C · D) 청크 배치는 세지 않는다(측정 구성에서 닿지 않음 — 2026-11-20 리뷰 반영).
  - `group_confirm`에는 묶음 확정이 거부를 기록하는 두 번째 트랜잭션에서 40P01이 나 기록을 못 하고 도메인 거부로 답한 경우도 넣는다(응답은 그대로 — 2026-11-20 리뷰 반영, 경계 셀이 이 경로를 친다).
- 원인 판정은 예외 사슬 **전체**에서 `SQLException.sqlState == "40P01"`인 것이 하나라도 있는지다(첫 SQLException만 보지 않는다 — 2026-11-20 리뷰 반영).
- 참고: `seat.group.hold.rejected`의 `cause`는 원인 관측용이며 방식끼리 '경합 거절 수'를 비교하는 지표가 아니다. split은 잠금을 얻은 뒤 상태를 다시 보고 내는 409를 `rule`로, one-shot은 같은 경합을 `short`로 센다. 방식 비교는 하네스의 2석 성공률로 한다.

## 2. 시험에서 확인할 것

- 1.1: op마다 2xx · 도메인 거부 · 본문 결함 400에서 정확히 1 증가, outcome 값이 맞음. 같은 요청이 두 번 세지지 않음.
- 1.2: split 경합(NOWAIT 패배) → `nowait` · one-shot 경합 → `short` · 한도 초과 → `rule`. 헤더 값 · 상태 · 본문 확인. `seat.hold.rejected`는 묶음 선점에서 변화 없음. loadtest가 아닌 프로필에서는 헤더 없음.
- 1.3: 40P01을 실제로 만들기 어려우면, 예외 사슬 판정 단위(40P01 → 증가 · 다른 SQLSTATE → 무변화)와 만료 틱의 연결(40P01로 실패하는 틱 → `expiry_tick` 1)을 확인한다. 실제 교착 재현은 선택이다.
- 1석 경로 무변화: 1석 선점 · 결제 시작 · 확정의 기존 지표(`seat.hold.rejected` · `seat.confirm.*`)가 묶음 지표 추가 전과 같은 값으로 오름.
- 기존 시험 전부 green(단언 무변경).

## 3. 파일 경계

- **구현 워커**: `main-app/src/main/**` · `main-app/src/test/kotlin/com/jun/labs/seatreservation/adr018metricsimpl/**`. 기존 시험 단언은 바꾸지 않는다(바꿔야 하면 멈추고 보고). `adr018metrics/`(blind)는 열지 않는다.
- **blind 워커**: `main-app/src/test/kotlin/com/jun/labs/seatreservation/adr018metrics/**`만 쓴다. `main-app/src/main/**`(`src/main/resources/application.yml`은 읽어도 됨) · `adr018metricsimpl/**`는 열지 않는다. 기존 시험 코드(`src/test/**` — `adr018/` · `adr018impl/` 포함)는 읽어도 된다.
- 공통(읽기만): 명세 · ADR-018 · 이 문서 · `build.gradle.kts`.
- Gradle: 두 워커는 각자 scratchpad 복사본에서만 돌린다(원 저장소의 build 폴더를 함께 쓰면 cleanTest가 서로의 결과를 지운다 — 1단계 실측).
