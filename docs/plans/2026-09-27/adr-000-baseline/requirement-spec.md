# 요구사항 명세서 (requirement-spec)

> 작성일: 2026-09-27 · 작업 폴더: `docs/plans/2026-09-27/adr-000-baseline/`

---

## 0. 요구사항 원문 (인터뷰 기록)

- 원문: "ADR-000으로 순수하게 좌석 예약이 되는 도메인 구조 즉 처리만 되는 구조로 순수 기능 자체에 대한 구현 … 우선 실제 구현만 진행하고, 간단한 테스트 코드 설계부터 검증 및 상태전환이 단일 요청일 경우 제대로 되는지까지 테스트컨테이너로 확인까지 … 코드 구조는 레이어드로 api, service, domain. api폴더 내 좌석선점 관련 api 1개 … service에 usecase … domain에 엔티티 … impl폴더 내에 유즈케이스 구현체 … domain내 repository폴더에 jpa인터페이스를 호출해서 프로세스 절차형태로 구현"
- "쿼리말고 순수 jpa로 처리 … usecase에서 직접 리포지토리들을 호출 … 기본구조는 당연히 불가능함을 보이고" (동시성 문제는 이후 ADR에서 관측·해결)
- Q/A:
  - 대상 Question: Q1부터 (ADR 순서는 docs/adr 목록)
  - DB: PostgreSQL / 영속: Spring Data JPA (쿼리 없이 파생 메서드)
  - 좌석 상태 원천: 직관 모델 — 좌석에 HELD 저장 + @Scheduled 배치 만료 (권장안 채택)
  - 상태전환 범위: **선점 + 만료 + 확정**
  - 스키마: Flyway
  - 모드: auto / 브랜치: `feat/adr-000-baseline`
  - TTL 5분(원본), 1인 최대 2매 = 홀드 + 확정 예약 (앱에서 COUNT)
  - 부하테스트·k6·정합성 판정은 이번 범위 밖 (ADR-001)

---

## 1. 목표·대상 (필수)

`seat-reservation-lab`에 ADR-000 기준선을 구현한다 — 5개 엔티티(상품·회차·좌석·홀드·예약) + Flyway V1 스키마 + 선점 API·확정 API·만료 배치를 레이어드(api / service · service/impl / domain · domain/repository) 절차형 JPA로 만들고, **단일 요청 기준 상태전환이 올바름을 Testcontainers(PostgreSQL) 테스트로 확인**하면 끝.

## 2. 경계·불변식 (필수)

단일 요청(비동시) 기준으로 항상 참:
- 선점 성공: 좌석 `AVAILABLE → HELD`, 홀드 1행 생성(`expiresAt = now + ttl`)
- 선점 거절: 좌석이 `HELD`/`RESERVED`면 409 `SEAT_NOT_AVAILABLE`, (사용자, 회차)의 홀드 + 확정 예약 ≥ 2면 409 `HOLD_LIMIT_EXCEEDED`, 없는 좌석(또는 회차 불일치) 404 — 거절 시 DB 불변
- 확정 성공: 좌석 `HELD → RESERVED`, 홀드 삭제, 예약 `CONFIRMED` 1행(paymentUid 포함)
- 확정 거절: 홀드 없음 404, 남의 홀드 403, 만료(`expiresAt <= now`) 409 `HOLD_EXPIRED`, 좌석이 `HELD` 아님 409 `SEAT_NOT_HELD` — 거절 시 DB 불변
- 만료 배치: `expiresAt <= now`인 홀드만 대상 — 좌석 `HELD → AVAILABLE` + 홀드 삭제. 만료 전 홀드는 건드리지 않는다
- **의도된 비불변식**: 동시 요청 시 중복 선점·매수 초과 등은 막지 않는다(ADR-000 원칙 — 문제를 가리지 않음). 스키마에 PK/FK 외 제약·인덱스를 두지 않는다

## 3. 기준소스 (필수)

- `docs/adr/ADR-000-baseline-domain.md` (도메인·전이·스키마·API·구조) — 이 명세와 충돌 시 사용자 확인
- 기존 스캐폴드: `build.gradle.kts`(Kotlin 2.2.20 · Boot 3.5.5 · JDK 21), 패키지 `com.jun.labs.seatreservation`

## 4. 금지영역 (필수)

- repo `README.md`(원본 설계 문서 — 수정 금지)
- `main` 브랜치 직접 커밋, push(요청 시에만 — 확인 후)
- 동시성 제어(락·조건부 쿼리·유니크 제약·`@Version`) 도입 — 이후 ADR 몫
- 네이티브/JPQL 쿼리(`@Query`) — 파생 쿼리 메서드만
- 로컬에 떠 있는 기존 컨테이너(study-pg18 등) 사용·변경
- study-note 기록(이번 범위 밖)

## 5. 검증 방법 (필수)

- `./gradlew test` (JDK 21) 전부 green — Testcontainers PostgreSQL
- 테스트 항목: §2 불변식 각 1개 이상 (선점 성공/거절 3종, 확정 성공/거절 4종, 만료 배치의 만료/비만료 구분) + 선점 API HTTP 1개 이상(MockMvc — 상태 코드·응답 본문)
- Flyway V1 적용 + JPA `ddl-auto=validate` 통과 (엔티티↔스키마 일치 확인)
- 스모크: 선점 → 확정 경로를 HTTP로 실제 실행(통합 테스트)
- diff self-review

## 6. stakes (필수)

- 판정: **낮음** — 로컬 실험 repo, 실데이터·외부 연동 없음, 브랜치 작업이라 복구 쉬움, 동시성 정합성은 명시적으로 범위 밖.

---

## 7. 자율성

- [x] auto
- [ ] lazy

## 8. load-bearing 가정

1. 로컬 Docker로 Testcontainers PostgreSQL(`postgres:16`)이 뜨고, Boot 3.5의 `@ServiceConnection`으로 연결된다 — 착수 직후 contextLoads로 실증.
2. 시각은 주입한 `Clock` 빈으로 얻어, 테스트에서 만료 시점을 고정·이동할 수 있다.

## 9. task 분해

| task | 목표 | 의존 | acceptance |
|------|------|------|-----------|
| 01 | 의존성(JPA·Flyway·Postgres·Testcontainers) + V1 스키마 + 엔티티·리포지토리 + Testcontainers 설정 | — | contextLoads green, ddl validate 통과 |
| 02 | 선점 UseCase + API + 예외 매핑 | 01 | 선점 성공/거절 테스트 + HTTP 테스트 green |
| 03 | 확정 UseCase + API | 02 | 확정 성공/거절 테스트 green |
| 04 | 만료 UseCase + @Scheduled 배치 | 02 | 만료/비만료 구분 테스트 green |

---

## 10. 변경 합의 (2026-09-27) — DDD 애그리거트 리팩토링

- 원문: "지금 이 구조가 DDD방식이 맞아?" → "000은 어그레이트가 어떤건지 명시하는게 우선" → 애그리거트 결정(Seat ⊃ Hold, seat_hold 테이블 유지, 확정은 같은 트랜잭션) → "그 범위로 리팩토링 진행해줘"
- 범위 변경: §1의 "리포지토리를 직접 호출하는 절차형" → ADR-000 §2.1·§3·§7 (애그리거트 메서드가 검사·전이, UseCase는 조율만, 리포지토리는 루트별)
- 성격: **리팩토링** — 외부 관찰 동작 보존 (playbooks/refactoring.md 순서)
- 보존할 동작 (= 칸2 + 계약 표면):
  - API 2개의 경로·헤더·요청/응답 본문·상태 코드·에러 코드, **에러 우선순위**(선점: 좌석 없음 → 선점 불가 → 매수 초과 / 확정: 홀드 없음 → 남의 홀드 → 만료 → 좌석 HELD 아님)
  - Flyway V1 스키마 무변경 (diff 0)
  - §2의 단일 요청 상태전환·거절 시 DB 불변
  - 만료 배치: 만료 홀드만 제거, 좌석이 HELD면 AVAILABLE (기준선 의미 그대로 — 중복 홀드 상황의 동작도 바꾸지 않음)
- 특성테스트: 기존 16개. 내부 구조(SeatHoldRepository)에 기대는 픽스처·단언은 DB 수준(JdbcTemplate)으로 먼저 옮겨 green을 세운 뒤 변환 착수

## 11. 변경 합의 (2026-09-28) — 사이클 마감 범위 확장

- 원문: "스터디 노트에 이슈는 정리 진행하고 스터디 노트에 이슈 커밋 해놓자 … 작업 기록도 스터디 노트에 기록하자. 그리고 메인에는 PR작성 후 병합처리하자"
- §4 금지영역에서 study-note 해제 — 대상: `issue/` 카드 2장 + 인덱스, `lab/backend-labs/commerce/seat-reservation-lab/README.md` 작업 기록 append
- Q/A: 카드 기준 = 개정 규칙 `issue/`(미병합 `docs/nextjs-app-render` @ b5af227f 기반 `archive/2026-09-28` worktree) · 작업 기록도 같은 브랜치 · study-note는 **커밋까지만**(main 병합 시 미병합 32커밋이 딸려 들어가므로 병합·push 안 함) · seat-reservation-lab PR = merge commit
- 불변: 기존 study-note 체크아웃(docs/nextjs-app-render, 미커밋 변경 있음)은 건드리지 않는다

---

## 승인 상태

- [x] 필수 6칸 전부 기입 (빈 칸 없음)
- [x] 사용자 합의 → SPEC=1 (2026-09-27)
- [x] 자율성 선택 → MODE=auto
