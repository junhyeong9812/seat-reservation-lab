# ops/confirm-unique-v12 — V12 확정 유니크 인덱스 배포 절차(ADR-015 §설계-4)

V12(`main-app/src/main/resources/db/migration/V12__reservation_confirmed_seat_unique.sql` — 기본 위치 · 모든 구성)는
`CREATE UNIQUE INDEX uq_reservation_confirmed_seat ON reservation (seat_id) WHERE status = 'CONFIRMED'`다.
기존 데이터에 같은 좌석 CONFIRMED 2건 이상이 있으면 23505로 실패하고 앱이 뜨지 않는다(조용히 건너뛰지 않는다). 그래서 배포 전에 아래 순서를 밟는다.
이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실행하지 않는다).

| 파일 | 하는 일 | 쓰기 |
|---|---|---|
| `r0-precheck.sql` | R0 사전 검사 — 같은 좌석 CONFIRMED ≥ 2인 좌석 · 예약 id(생성 순) | 없음(읽기) |
| `r1-repair-template.sql` | R1 복구 틀 — 사람이 고른 `keep`(남길 예약) · `drop`(취소할 예약 목록)으로 한 좌석 복구 | `reservation.status` · `user_hold_quota.cnt`(+ stmt 방식이면 `updated_at`) |
| `r2-verify.sql` | R2 확인 — R0 = 0 · `v_counter_mismatch` = 0 · `v_reserved_without_confirmed` = 0 · `v_confirmed_on_unreserved_seat` = 0 | 없음(읽기) |

`psql`은 `-X -v ON_ERROR_STOP=1`로 돌린다(어긋나면 exit 3). 비밀번호는 명령줄에 쓰지 않는다(`PGPASSWORD` 환경 · `.pgpass`).

## 순서

1. **운영 크기 사본에서 먼저**(ADR-013 원칙): R0 → (0행이 아니면 2~4) → R2 → V12 적용 시간 측정(`[구현 검증]` #5 — `reservation` SHARE 락 길이).
2. **R0이 0행이 아니면 배포를 멈춘다 — 자동 복구하지 않는다.** 중복 확정 1건 = 결제한 사용자 둘이다. 어느 예약을 남길지는 사업 결정이고, 취소되는 쪽은 ADR-017 보상 대상이다(결제 uid는 취소한 예약 행에 남는다 — 행을 지우지 않는다).
3. 좌석마다 R1 **미리보기**(기본 — 끝에서 ROLLBACK):
   `psql -X -v ON_ERROR_STOP=1 -v keep=<남길 id> -v drop='{<취소할 id>,…}' -f ops/confirm-unique-v12/r1-repair-template.sql`
   출력(잠근 쌍 · 고른 예약 행 · 다시 센 cnt)을 확인한 뒤 같은 명령에 `-v commit=1`을 더해 적용한다.
4. R0(0행) · R2(`R2 ok`)를 다시 본다. 하나라도 어기면 배포하지 않는다.
5. 배포(앱 기동 → Flyway가 V12 적용). R0과 V12 사이에 새 중복이 생기면 V12가 23505로 실패하고 앱이 뜨지 않는다(무음 아님) → **R0부터 다시**.
   - 실측(로컬 PG 16 · Flyway 11.7.2 · 트랜잭션 안 V12): 23505 실패 뒤 이력에 V12 행이 남지 않고 인덱스도 없다 → R1 · R2 뒤 다시 기동하면 V12가 적용된다(`flyway repair` 불필요).
     같은 migrate 실행에서 V12보다 앞 버전(예: 위치를 더해 함께 적용된 V9)은 각자 트랜잭션이라 **남는다**.
   - **확정 기본값 `conditional-extend`(ADR-016 결정 2026-11-10 · 그 전 기본 `conditional` — ADR-015 결정 2026-11-06 · 둘 다 같은 조건)는 V12 인덱스 · V13 `seat_hold.extended_at` 열이 있어야 기동한다**(기동 검사 규칙 7 · 인덱스 검사 — 마이그레이션 앞에서 거부 · 스키마 무변경). `spring.flyway.target`을 12 이하로 고정한 DB는 target을 풀어 V13까지 적용하거나, 그동안 `seat.confirm.mode=current`(옛 JPA 확정)로 띄운다. R0이 0행이 아니어서 배포를 멈춘 동안 옛 앱을 계속 돌릴 때도 `current`를 명시한다.
6. 배포 뒤 `SELECT indisvalid FROM pg_index WHERE indexrelid = to_regclass('uq_reservation_confirmed_seat')` = `t`(앱 기동 검사도 INVALID면 모든 모드에서 거부한다).

## R1이 하는 일(ADR-015 §설계-4 R1 — 한 트랜잭션)

- ① 취소할 예약의 (회차, 사용자) 쿼터 행을 정렬 순서로 `FOR UPDATE`(ADR-014 §2.4 규칙 ① — 선점 upsert · 배치 감소가 이 행을 먼저 잠그므로 ③의 값이 그 쌍의 정의 값이 된다).
- ⓟ 고른 값 확인(ADR 본문에 없는 앞 확인 — 오입력 방지): `keep`은 CONFIRMED · `drop`은 비지 않고 중복 없음 · `keep` 불포함 · 전부 CONFIRMED · `keep`과 같은 좌석. 예약 행도 `FOR UPDATE`.
- ② `drop`을 CANCELED로 — 영향 행 수 = `cardinality(drop)`이 아니면 중단(그 사이 누가 바꿨다).
- ③ 쌍마다 **다시 센다**(V6 정의: 그 쌍의 `seat_hold` 행 전체 + `reservation` CONFIRMED). 차감(cnt − n)이 아니다 — 이미 어긋난 cnt를 옮기지 않는다. 쿼터 행이 없는 쌍은 건드리지 않는다(새로 만들지 않는다).
  증분 방식이 stmt(V9 문장판 — `updated_at` 열 있음 · 트리거 `user_hold_quota_touch` 없음)면 `updated_at = now()`를 함께 쓴다(DB 사실로 고른다 · trigger면 트리거가 쓴다).
- ④ 그 좌석의 CONFIRMED가 `keep` 1건인지 같은 트랜잭션에서 확인 → `commit`이 있으면 COMMIT · 없으면 ROLLBACK.
- 좌석은 `RESERVED` 그대로(확정 1건이 남는다). 어느 확인이든 어기면 `RAISE` → exit 3 · 바뀐 것 없음.

## R2 메모

- 판정기(`LoadtestDataService.consistency` — LoadtestDataService.kt:140-210) 문장을 DB에서 직접 옮겼다. 이름은 판정기 출력 이름이고 DB 뷰가 아니다.
- `v_counter_mismatch`의 '마감 회차 제외'(`starts_at <= now`)는 판정기가 앱 Clock · 여기는 DB `now()`(시계 차만큼 경계 회차가 갈릴 수 있다 — `[구현 검증]` #12).
- `v_counter_mismatch`는 카운터 방식(counter 계열)일 때만 뜻이 있다. cnt를 유지하지 않는 매수 방식이면 그 칸은 판정에서 뺀다(그 경우 r2 출력 JSON의 나머지 세 칸으로 판단).

## `CONCURRENTLY` 판 실패 뒤 재시도(운영 · 한 단계씩 확인)

지금 V12는 **트랜잭션 안** 판이다. 운영 크기 측정(`[구현 검증]` #5)에서 쓰기 차단이 길면 `CONCURRENTLY` 판(`V12__….sql.conf`의 `executeInTransaction=false`)으로 바꾼다 — 아직 없다. 그 판이 실패하면:

1. 앱을 띄우지 않은 채 `DROP INDEX CONCURRENTLY IF EXISTS uq_reservation_confirmed_seat;`(INVALID 인덱스 제거 — 트랜잭션 밖. `\set AUTOCOMMIT on` 기본 그대로)
   → `SELECT to_regclass('uq_reservation_confirmed_seat')` = NULL 확인.
2. **`flyway repair`**(같은 위치 · 같은 SHA) — 실패로 남은 V12 이력 행을 지운다. 지우지 않으면 다음 migrate가 '실패한 마이그레이션이 있다'로 멈춘다.
   Flyway 11.7.2가 PostgreSQL의 비트랜잭션 실패를 이력에 실패 행으로 남기는지 · repair가 그 행만 지우는지는 `[구현 검증]` #5(그 판을 만들 때 확인).
   **②를 건너뛰고 이력 표를 손으로 고치지 않는다.**
3. R0 다시(실패 원인이 23505 중복이면 R1부터).
4. 앱 기동 → V12 다시 적용 → `indisvalid` = `t` 확인.

## 확인한 것(로컬)

로컬 docker PostgreSQL 16 + Flyway 11.7.2 라이브러리로 두 판(V1~V7 · V1~V7 + V9 문장판)에서: 중복 좌석 1개 · 이미 어긋난 쌍 1개 시드 →
R0 1행 · R2 exit 3(r0_rows 1 · v_counter_mismatch 1) · V12 23505 실패(이력 · 인덱스 없음) · R1 오입력(다른 좌석 · drop 없음) exit 3 무변경 ·
R1 미리보기 무변경 · R1 적용(취소 1 · cnt = 홀드 1 + 확정 0 · stmt판 updated_at 갱신) · R0 0행 · R2 exit 0 · V12 다시 적용 · `indisvalid` t.
`CONCURRENTLY` 재시도 절차는 그 판이 없어 돌려 보지 않았다.
