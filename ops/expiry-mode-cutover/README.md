# 만료 방식 전환 정리 (ADR-014 §설계-7)

`seat.hold.expiry-mode`(sweep · lazy · lazy-sweep · derived)를 바꿀 때 쓰는 운영 SQL이다. 방식 사이 전환은 모두 **멈추고 바꾸기**만 허용한다(ADR-014 §설계-7 · 선검증 DS14-2 — 저장 `HELD`의 뜻이 방식마다 달라 한 DB에서 섞이면 판정·좌석 맵이 틀린다). 방향(A ↔ B·C·D · B·C ↔ D · B ↔ C)마다 SQL을 따로 두지 않는다 — 같은 두 단계를 돌린다.

이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실행할 수 없다). 테스트 `Adr014CutoverContractTest`가 이 파일들을 읽어 돌린다(`Adr014Support.kt` — 문장이 둘로 갈라지지 않게).

| 파일 | 내용 |
|---|---|
| `01-cleanup.sql` | 한 트랜잭션: 정지 상태 검사(실행 롤 권한 확인 → 이 DB에 다른 클라이언트 세션이 있으면 멈춤 — 코드 리뷰 CR14-16 · CR14-29) · 대상 테이블 잠금(`seat_hold` · `user_hold_quota` · `product_seat` — `ACCESS EXCLUSIVE NOWAIT` · CR14-29) · T1 남은 만료 홀드 삭제 + 열린 회차 쌍별 감소(지운 행 수만큼) · T2 좌석 기준 재계산(`RESERVED`가 아닌 좌석 = 활성 홀드 있음 → `HELD` / 없음 → `AVAILABLE`) |
| `02-verify.sql` | 확인 3항목(`v_held_without_hold` · `v_duplicate_hold_seats` · `v_counter_mismatch`) — 판정기 정의 그대로 |

## 사용 순서

1. **업무 앱 · 대조 전용 프로세스를 모두 내린 뒤** 세션이 남지 않았는지 본다. 순서:
   1. 업무 앱을 모두 내린다(인스턴스 전부 — 롤링으로 하나씩 바꾸지 않는다).
   2. 별도 대조 프로세스(F — `reconciler` 프로필)를 내린다. 업무 앱이 내려가도 F는 같은 DB의 쿼터 행을 고친다.
   3. 이 DB에 붙은 다른 클라이언트 세션이 0인지 본다(자기 세션 · autovacuum 같은 백그라운드 프로세스는 빼고 센다 — `01-cleanup.sql`의 검사와 같은 조건):
      ```sql
      SELECT application_name, client_addr, count(*) FROM pg_stat_activity
       WHERE datname = current_database() AND pid <> pg_backend_pid() AND (backend_type = 'client backend' OR backend_type IS NULL) GROUP BY 1, 2;
      ```
      남은 행이 있으면 그 프로세스(관리 도구 · 다른 psql 포함)를 먼저 끊는다.
   4. `01-cleanup.sql`도 BEGIN 바로 뒤에 같은 조건을 다시 본다 — 다른 세션이 하나라도 있으면 아무것도 바꾸지 않고 `55006`(object_in_use)으로 멈춘다(끄는 변수는 없다).
      **실행 롤**은 superuser이거나 `pg_read_all_stats` 권한이 있어야 한다(코드 리뷰 CR14-29). 권한 없는 롤에는 남의 세션 행의 `backend_type` 등이 NULL로 보여(PostgreSQL 16 문서 §28.2.2) 검사가 무음으로 통과할 수 있다 — 그래서 권한이 없으면 `42501`(insufficient_privilege)로 먼저 멈추고, `backend_type`이 NULL인 행도 세션으로 센다. 위 3.의 세션 조회도 같은 롤로 돌린다(권한 없는 롤이면 남의 세션이 `backend_type` NULL로 보인다).
   5. 검사 직후 T1·T2 대상 테이블(`seat_hold` · `user_hold_quota` · `product_seat`)을 `ACCESS EXCLUSIVE MODE NOWAIT`로 잠근다 — COMMIT 전까지 새로 붙은 세션은 이 테이블을 읽지도 쓰지도 못하고 기다린다. 다른 세션이 이미 그 테이블 잠금을 쥐고 있으면 기다리지 않고 `55P03`(lock_not_available)으로 멈춘다.
2. **정리** — 변수 `quota_touch`는 지금 DB의 증분 대조 방식(`seat.hold.counter-drift.reconcile.incremental.method`)으로 정한다. 주지 않으면 문법 오류로 멈춘다(아무것도 바뀌지 않는다).
   ```sh
   # method = none · trigger (trigger는 V9 트리거가 updated_at을 쓴다)
   psql -X -v ON_ERROR_STOP=1 -v quota_touch= -f ops/expiry-mode-cutover/01-cleanup.sql
   # method = stmt (앱 감소 문장처럼 updated_at = now()를 함께 쓴다 — §설계-2.4 DS14-6)
   psql -X -v ON_ERROR_STOP=1 -v "quota_touch=, updated_at = now()" -f ops/expiry-mode-cutover/01-cleanup.sql
   ```
   실패하면(예: 감소가 음수 — V5 CHECK `cnt >= 0` · 정지 상태 검사 `55006` · 실행 롤 권한 `42501` · 잠금 `55P03`) 트랜잭션 전체가 롤백된다. 원인(카운터 어긋남 · 남은 세션)을 먼저 본다 — 조용히 0으로 자르지 않는다.
   - **증분 방식이 memory(ⓐ — 앱 메모리 집합)일 때**: `quota_touch`는 빈 값이다(`-v quota_touch=` — ⓐ에는 `updated_at` 표시 열이 없다). T1의 감소는 앱 경로가 아니라 메모리 집합에 들지 않고, 집합은 프로세스 메모리라 앱을 다시 올리면 비어 있다 — **증분 대조는 T1이 바꾼 쌍을 보지 않는다**. 그래서 그 쌍의 확인은 3번 `v_counter_mismatch = 0`이 맡는다(0이 아니면 올리지 않는다). 앱을 내리기 직전에 커밋됐는데 증분 틱이 아직 못 본 쌍도 집합과 함께 사라진다(ADR-012 재합의 2026-10-20 — 보장 밖은 안전망 몫) — 3번 확인이 정리 시점의 그 어긋남도 함께 본다.
3. **확인** — 세 값이 모두 0이어야 한다. 하나라도 0이 아니면 앱을 올리지 않는다.
   ```sh
   psql -X -v ON_ERROR_STOP=1 -f ops/expiry-mode-cutover/02-verify.sql
   ```
4. 손잡이(`seat.hold.expiry-mode`)를 바꿔 앱을 올린다. B(lazy)는 정리 ①(`seat.hold.counter-drift.cleanup.mode=schedule`)이 켜져 있어야 뜬다(코드 리뷰 CR14-1 — 업무 앱만. reconciler 프로필(F — 대조 전용 · 정리 끔)은 이 검사를 받지 않는다 · CR14-26).

`01-cleanup.sql`은 정지 상태에서 다시 돌려도 된다 — T1은 그 사이 새로 만료된 홀드만 지우고 지운 만큼 내리며, T2는 같은 결과를 다시 쓴다.

## 한계

- `LOCK … NOWAIT`는 그 순간 autovacuum 작업자가 대상 테이블을 처리 중이면 55P03으로 멈출 수 있다 — 아무것도 바뀌지 않으므로 잠시 뒤 다시 돌린다(코드 리뷰 루프 3 수정 보고).

- 한 DB에 다른 방식 앱이 붙는 것을 막는 장치(DB 방식 표시 + 기동 검사)는 없다 — 1번(모든 앱 내림)은 절차로 지키고, `01-cleanup.sql`의 정지 상태 검사는 정리 순간에 붙어 있는 세션만 본다. 검사 뒤 COMMIT 전에 새로 붙는 세션은 대상 세 테이블 잠금(CR14-29)에서 COMMIT까지 기다린다 — 정리 중 끼어들지는 못하지만, 그 세션은 COMMIT 직후 새 데이터로 이어서 돈다(옛 방식 앱이면 그때부터 섞인다 — 정리 동안 앱을 올리지 않는다). 검사와 잠금 문장 사이에 붙어 잠금을 먼저 쥔 세션이 있으면 잠금이 `55P03`으로 멈춘다(아무것도 바뀌지 않는다). 잠금 대상 밖 테이블(`product_schedule` — 읽기만)은 막지 않는다.
- 테스트는 정지 상태 검사 블록을 잠금·T1·T2와 따로 읽는다(`Adr014Support.kt` — 스프링 테스트 DB에는 같은 DB의 Hikari 풀 세션이 있어 검사가 늘 멈춘다 · 잠금은 빼지 않는다). 앱을 내린 새 DB에서는 검사까지 운영 파일 그대로 돌린다(`Adr014CutoverContractTest`). 권한 검사 · 잠금 `NOWAIT`는 `Adr014ImplSweepTest`가 따로 본다.
- 시드 크기 사본에서의 소요 시간은 아직 재지 않았다(`[구현 검증]` §설계-10 #14). 정리 동안 `seat_hold` · `user_hold_quota` · `product_seat` 테이블 잠금(ACCESS EXCLUSIVE)을 쥐므로 앱이 내려가 있어야 한다.
- 마감 회차(`starts_at <= now()`)는 감소하지 않는다 — 카운터 불변식 범위 밖이다(판정기 `v_counter_mismatch`가 빼고 정리 ①이 지운다).
