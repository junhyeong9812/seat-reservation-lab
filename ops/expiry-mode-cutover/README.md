# 만료 방식 전환 정리 (ADR-014 §설계-7)

`seat.hold.expiry-mode`(sweep · lazy · lazy-sweep · derived)를 바꿀 때 쓰는 운영 SQL이다. 방식 사이 전환은 모두 **멈추고 바꾸기**만 허용한다(ADR-014 §설계-7 · 선검증 DS14-2 — 저장 `HELD`의 뜻이 방식마다 달라 한 DB에서 섞이면 판정·좌석 맵이 틀린다). 방향(A ↔ B·C·D · B·C ↔ D · B ↔ C)마다 SQL을 따로 두지 않는다 — 같은 두 단계를 돌린다.

이 폴더는 Flyway 위치가 아니다(클래스패스 밖 — 앱이 실행할 수 없다). 테스트 `Adr014CutoverContractTest`가 이 파일들을 읽어 돌린다(`Adr014Support.kt` — 문장이 둘로 갈라지지 않게).

| 파일 | 내용 |
|---|---|
| `01-cleanup.sql` | 한 트랜잭션: T1 남은 만료 홀드 삭제 + 열린 회차 쌍별 감소(지운 행 수만큼) · T2 좌석 기준 재계산(`RESERVED`가 아닌 좌석 = 활성 홀드 있음 → `HELD` / 없음 → `AVAILABLE`) |
| `02-verify.sql` | 확인 3항목(`v_held_without_hold` · `v_duplicate_hold_seats` · `v_counter_mismatch`) — 판정기 정의 그대로 |

## 사용 순서

1. **모든 앱을 내린다**(업무 앱 전부 · 별도 대조 프로세스 F 포함). 남은 세션이 없는지 본다 — 이 DB에 붙은 다른 세션이 0이어야 한다:
   ```sql
   SELECT application_name, count(*) FROM pg_stat_activity
    WHERE datname = current_database() AND pid <> pg_backend_pid() GROUP BY 1;
   ```
2. **정리** — 변수 `quota_touch`는 지금 DB의 증분 대조 방식(`seat.hold.counter-drift.reconcile.incremental.method`)으로 정한다. 주지 않으면 문법 오류로 멈춘다(아무것도 바뀌지 않는다).
   ```sh
   # method = none · trigger (trigger는 V9 트리거가 updated_at을 쓴다)
   psql -X -v ON_ERROR_STOP=1 -v quota_touch= -f ops/expiry-mode-cutover/01-cleanup.sql
   # method = stmt (앱 감소 문장처럼 updated_at = now()를 함께 쓴다 — §설계-2.4 DS14-6)
   psql -X -v ON_ERROR_STOP=1 -v "quota_touch=, updated_at = now()" -f ops/expiry-mode-cutover/01-cleanup.sql
   ```
   실패하면(예: 감소가 음수 — V5 CHECK `cnt >= 0`) 트랜잭션 전체가 롤백된다. 원인(카운터 어긋남)을 먼저 본다 — 조용히 0으로 자르지 않는다.
3. **확인** — 세 값이 모두 0이어야 한다. 하나라도 0이 아니면 앱을 올리지 않는다.
   ```sh
   psql -X -v ON_ERROR_STOP=1 -f ops/expiry-mode-cutover/02-verify.sql
   ```
4. 손잡이(`seat.hold.expiry-mode`)를 바꿔 앱을 올린다. B(lazy)는 정리 ①(`seat.hold.counter-drift.cleanup.mode=schedule`)이 켜져 있어야 뜬다(코드 리뷰 CR14-1).

`01-cleanup.sql`은 정지 상태에서 다시 돌려도 된다 — T1은 그 사이 새로 만료된 홀드만 지우고 지운 만큼 내리며, T2는 같은 결과를 다시 쓴다.

## 한계

- 한 DB에 다른 방식 앱이 붙는 것을 막는 장치(DB 방식 표시 + 기동 검사)는 없다 — 1번(모든 앱 내림)은 절차로 지킨다.
- 시드 크기 사본에서의 소요 시간은 아직 재지 않았다(`[구현 검증]` §설계-10 #14). 정리 동안 `seat_hold` · `user_hold_quota` · `product_seat` 행 잠금을 쥐므로 앱이 내려가 있어야 한다.
- 마감 회차(`starts_at <= now()`)는 감소하지 않는다 — 카운터 불변식 범위 밖이다(판정기 `v_counter_mismatch`가 빼고 정리 ①이 지운다).
