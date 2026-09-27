# log — adr-000-baseline

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 2026-09-27 | 인터뷰 → ADR-000 + 가설 ADR-001~009 작성, 구 ADR-001 삭제 | 사용자 지시: 해법 선결정 대신 기준선 → 관측 → ADR 순 |
| 2026-09-27 | 명세 합의 | SPEC=1, MODE=auto, stakes 낮음, 브랜치 feat/adr-000-baseline |
| 20:08 | task01 첫 실행 — 테스트 파일이 src/main에 생성됨(상대경로 오산) | src/test로 이동·재작성 |
| 20:09 | contextLoads 실패: Testcontainers 1.21.3 → Docker 29 "client version 1.32 is too old" (load-bearing 가정 1 깨짐) | 웹 확인: 1.21.4에서 수정 → `extra["testcontainers.version"]="1.21.4"` |
| 20:11 | contextLoads green, Flyway V1 적용 + ddl validate 통과 | task01 완료 |
| 20:13 | task02~04 구현(선점·확정·만료 UseCase/impl, API 2개, 스케줄러) + 테스트 16개 | 전부 green |
| 20:14 | 뮤테이션 점검 2건: 좌석 상태 비교 제거 → 3개 실패 / 만료 경계 `isAfter`→`isBefore` → 1개 실패 | 테스트가 계약을 실검증 확인, 소스 복구(diff 0) |
| 20:17 | 스모크: bootTestRun(TTL 20s, 배치 5s) + curl — 선점 201 → 타 사용자 409 → 확정 200(RESERVED·예약 CONFIRMED) / 선점 후 만료 배치로 HELD→AVAILABLE(만료 11:17:05 → 해제 11:17:08) | 실경로 동작 확인, 앱·컨테이너 정리 |
| 20:19 | self-review: datasource 기본값 localhost:5432가 금지영역 study-pg18과 겹침 | 기본값 제거(env 필수) — 테스트 재실행 green |
| 20:30 | 사용자: "DDD방식이 맞아?" → 아님(트랜잭션 스크립트) 인정 → 애그리거트 결정 3건 → ADR-000 §2.1·§3·§7 갱신 | 리팩토링 범위 재합의(spec §10) |
| 20:36 | 리팩토링 ② 특성테스트: 홀드 픽스처·단언을 SeatHoldRepository → JdbcTemplate(DB 수준)으로 이전 + 에러 우선순위 테스트 추가 | baseline 17개 green |
| 20:37 | ④-U1 ErrorCode·예외 service → domain 이동 | 17 green |
| 20:39 | ④-U2 Seat 애그리거트(홀드 소유·hold/confirm/expireHolds) + HoldLimitPolicy + 서비스 조율 전용 + SeatHoldRepository 삭제 | 10 red: `hold.id` null — 관리 중 좌석에 saveAndFlush → merge가 새 자식의 복사본을 영속화 |
| 20:40 | save 대신 flush() (cascade PERSIST가 원본 홀드 INSERT) | 17 green |
| 20:42 | 뮤테이션 3건: assertHoldable 무력화 → 5 red / isExpired 경계 → 3 red / 에러 우선순위 뒤집기 → 1 red | 새 위치에서도 규칙이 실검증됨, 소스 복구(cmp 일치) |
| 20:44 | ⑤ 계약 표면: V1 mtime 20:06(무변경), API 매핑·헤더·상태코드 동일 / ADR §3 선점 절차를 실제 순서로 동기화 | diff 0 |
| 20:46 | 스모크(bootTestRun): 선점 201 → 타 사용자 409 → 확정 200 / 만료 배치로 HELD→AVAILABLE | 리팩토링 후 실경로 동일 |
| 20:49 | 누락 발견: 변경 표에 약속한 좌석 애그리거트 단위 테스트 미작성 → ProductSeatTest 5개 추가(Spring·DB 없음) | 전체 22 green |
| 20:55 | 사이클 마감: NEXT.md 생성(N1 = ADR-001 하네스), measurement-log 1행 | — |
| 20:55 | 아카이브 보류(금지영역: study-note) — CS 이슈 2건 NEXT `보류·이월`에 등재 | 사용자 보고 → 09-28 해제 |
| 09-28 | 범위 확장 합의(spec §11): study-note 이슈 아카이브·작업 기록 + PR 병합 | study-note 상태: 체크아웃 docs/nextjs-app-render(미병합 32·미커밋 변경), issue/ 이동은 그 브랜치에만 → b5af227f 기반 archive/2026-09-28 worktree(`../study-note-wt-archive-0928`) |
| 09-28 | 아카이브 매칭: 기존 카드와 같은 원리 없음 → 새 카드 2 (cross-cutting/infra/client-api-version-floor, kotlin/spring/jpa-save-merge-copy) | 원 식별자(노출 스캔 입력): seat-reservation-lab, backend-labs, com.jun.labs, ProductSeat, SeatHold, HoldSeatService, junhyeong9812, 경로 /home/jun |
| 09-28 | 카드 근거(카드에는 미기재): 이슈1 = build.gradle.kts `extra["testcontainers.version"]`, 실패 로그 "client version 1.32 is too old. Minimum supported API version is 1.44", ~/.testcontainers.properties `docker.api.version=1.44`가 있었는데도 1.32 전송 / 이슈2 = HoldSeatService `saveAndFlush(seat)` → `hold.id!!` NPE(10 red) → `flush()`로 17 green | — |
| 09-28 | study-note 노출 스캔(추가 222행 — 원 식별자·경로·IP·시크릿 패턴) | 0건 → 커밋 `fdd7a0d7`(issue 카드 2 + 인덱스 4) |
| 09-28 | study-note 작업 기록 append(lab README 끝, `## 작업 기록` 첫 생성) + 스캔 0건 | 커밋 `f31b845f` — 브랜치 archive/2026-09-28, 병합·push 보류(NEXT 등재) |

## 리뷰 ledger

- stakes 낮음 → 셀프체크(듀얼 리뷰 비대상)

## 생략한 검증

- (없음)

## 완료 요약

- 된 것: ADR-000 기준선 — 엔티티 5개 + Flyway V1(PK/FK만) + 선점·확정 API + 만료 배치, 레이어드(api / service·impl / domain·repository), 파생 쿼리 메서드만 사용
- 검증: 테스트 16개 green(Testcontainers postgres:16), 뮤테이션 2건 검출, HTTP 실경로 스모크
- 빌드 변경: Testcontainers 1.21.3 → 1.21.4 (Docker Engine 29 API 호환)
- 리팩토링(spec §10): 트랜잭션 스크립트 → DDD 애그리거트(Seat ⊃ SeatHold, HoldLimitPolicy 도메인 서비스, 루트별 리포지토리). 특성테스트 17 green + 계약 표면 diff 0 + 애그리거트 단위 테스트 5 (총 22)
- 이월: 동시성 문제는 의도적으로 미해결 → ADR-001(판정·k6 하네스)부터

