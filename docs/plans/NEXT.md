# NEXT — 다음 작업 전망

## 기준

- 마지막 갱신: 2026-10-11 · 직전 완료 작업: `docs/plans/2026-10-09/adr-007-throughput-wall/` → `2026-10-10/adr-007-disk-cause/` → `2026-10-11/adr-007-fsync-fio/`(ADR-007 C5 확인·전환 결정) · 작업 브랜치: `feat/adr-007-throughput-wall`(미push)

## 다음 작업 후보 (우선순위순)

### N1. 새 ADR-008 counter-upsert-precheck 기본 적용·검증 — 우선순위: 높음 · 사용자 지시(2026-10-11 "전환및 테스트까지", "adr8로", "정말 괜찮은지 보고 확정하는 과정까지")

- **왜 다음인가**: ADR-007 §8 결정(advisory-try → counter-upsert-precheck). 그런데 코드 기본값은 지금도 `none`이다(`application.yml:36`·`SeatHoldProperties.kt:14`) — ADR-005의 advisory-try 결정도 코드에 반영된 적이 없다.
- **사용자 합의(인터뷰 2026-10-11)**: 설정+코드 기본값 둘 다 · Flyway 백필 마이그레이션(유효(만료 전 HELD) 홀드 + 확정 예약으로 `user_hold_quota.cnt`) · 기존 테스트 전체 + 기본값 테스트 · 서버 스모크(기본값 기동) · 부하 하네스 무영향 확인 · 기본값으로 기능 셀 재측정(S2·S3-a20·S7-m1·S7-m1-late × 3, S4 1) · 번호 = 기존 ADR-008~013을 009~014로 미루고 새 ADR-008.
- **발생 가능한 문제**: 백필은 데이터 변경(stakes 높음 — 불가역) · counter 계열은 배경 행 시드를 거부(`LoadtestDataService.kt:38`) → 방식을 명시하지 않는 기존 하네스(ADR-002 배경 행 등)가 기본값 변경으로 깨질 수 있음 · 만료 배치가 카운터를 내리는 경로(새 ADR-009 홀드 만료와 겹침).
- **대처**: 명세 인터뷰부터(새 작업 폴더) · 하네스 전수 grep으로 방식 미지정 호출을 찾아 none 명시 · 백필은 빈 DB·홀드 있는 DB 양쪽 테스트.

### N1a. 서버 디스크가 처리량 측정을 깎는다 — 우선순위: 중간 · ADR-007 §7.2

- **관측**: 이 서버 저장 경로(`nvme1n1` Samsung 980 · ext4 · LVM)에서 flush가 일정 쓰기 뒤 느려진다 — flush/s 약 1/3·flush 평균 약 3배. fio 단독(pgwal, flush 약 2,000회/s)에서는 약 3분(+179초 · 5.2GB) 뒤, DB S4c4200에서는 +149~160초 · 호스트 누적 3.50~3.74GB 뒤다(`20261010-dc-b943a3f0/WDROP.md`). DB 회차의 S4 벽(디스크 12회 중 11회 — precheck rep1의 256초 벽은 미설명)과 **같은 서명**이다. 쉬면 회복되는지는 pgwal 1회(40.5초 쉼 → 정상 기준선)뿐이고, 옛 append fio-2는 23초 쉬고 일찍(60초 · 3.79GB) 꺾였다. "DRAM 없는 SSD 동작"은 해석(장치 안은 미측정).
- **대처 후보**: 처리량 비교는 tmpfs 조건 병행 · 다른 디스크(nvme0n1은 Windows — 금지) · `synchronous_commit`·`commit_delay`로 flush 빈도 낮추기(측정 의미가 바뀜 — 명세 필요) · 반감 위치 차이(fio 5.2GB vs DB 3.5GB) 원인은 미확인.

### N1b. ADR-009 홀드 만료(기존 ADR-008, 번호 이동 예정) — 우선순위: 높음(새 ADR-008 뒤)

- counter-upsert-precheck를 기본으로 택했으므로(ADR-007 §8) 만료 배치가 카운터를 내리는 주체다. 쿼터 행은 만료로 지워지지 않는다 — 회차 종료 정리·vacuum 비용을 함께 본다(ADR-006 §7).
- **쿼터 행 정리 설계**는 ADR-006 §6 전환 규칙의 조건이었지만 ADR-007 §8 결정에 포함하지 않고 여기로 넘겼다.

### N2. (완료 2026-10-09) ADR-005 main 병합 — PR #5 merge commit 201aabdd

### N3. `pg_try_advisory_xact_lock` vs 3b nowait — 우선순위: 낮음(후순위, 사용자 2026-10-06) · ADR-009(홀드 만료, 기존 ADR-008)와 함께

- **왜**: DB만으로 즉시 실패하는 또 하나의 방법 — 행을 잠그지 않아 만료 배치·확정과 부딪히지 않는다(3b 대가 ③). 미측정(ADR-003 §9)
- **비교할 것**: S1·S6 + 만료 배치 동시 실행에서 409 비율·지연, 키 공간 구분

### N4. ADR-004 후속 측정 — 우선순위: 낮음

- ① 무경합 요청은 지연 없이(핫만 지연) + 무경합 대조군(M=1) ② conditional의 '판정 먼저' 배치 ③ advisory vs pessimistic 진 쪽 락 보유·대기 이벤트 ④ k6 VU·Tomcat max-connections 조정(S6 에러 상한 3,616 제거) ⑤ 풀 20 ⑥ 전략 순서 교차 — 근거 ADR-004 §5.5·§7

### N5. L2 S3 붕괴 원인(ADR-002 A3) — 우선순위: 중간 · 후속 ADR(번호 미정)

- **관측**: 2 CPU에서 S3 원본 확정 실패 약 62%, 인덱스·풀과 무관. ③ 서버 쪽 요청 도착 기록 → ① 연결 대기열 지표 → ② GC 순.

### N6. 하네스 — 우선순위: 중간

- 유선 경로 L4 S4 약 4,300건/s 상한(원인 미확정, 사용자 보류 2026-10-03 — ADR-006에서 12단계(6,487) 세 방식 동일 붕괴로 재관측) · 쉘 판정식 `A || B && C` 우선순위 함정(ADR-006 재측정 오판 — `if`로 묶기) · 서버 쪽 요청 도착·연결 대기열 지표 · 사용자 linger 꺼짐(`loginctl enable-linger` 미실행) · S1 순간 표본(0.5초)이 버스트를 놓침 · 공정성용 µs 시각

### N7. 배경 100만에서 인덱스 있음의 처리량 저하(ADR-002 A4) — 우선순위: 낮음 · 자원 증설 vs 파티셔닝

### N8. 사용자 검증 단계 추가 시 지연 — 우선순위: 낮음(사용자 2026-09-29)

### N9. 다중 인스턴스·MSA 경합 제어 — 우선순위: 낮음 · ADR-003 ⑦ 결과 승계(앱 2대: JVM 락만 깨짐)

- 키 기준 라우팅 · 서비스별 DB(사가·이벤트) · Redis 장애 주입(redis-nx 부분 실패) · 처리량 확장

### N10. Spring JDBC 기여 후보 — 55P03 번역 불일치 — 우선순위: 낮음 · 사용자 제기(2026-10-09)

- **관측**(ADR-005 §6.1): `JdbcTemplate`은 사용자 `sql-error-codes.xml`이 없으면 `SQLExceptionSubclassTranslator` → `SQLStateSQLExceptionTranslator`를 쓴다(Spring 6.2.10 바이트코드로 확인). SQLState 번역기에는 `55` 클래스·`55P03`이 없어 `UncategorizedSQLException`이 되지만, Spring이 함께 싣는 `sql-error-codes.xml`의 PostgreSQL 항목은 `55P03` → `cannotAcquireLockCodes`(CannotAcquireLockException ⊂ PessimisticLockingFailureException)다 — 같은 오류가 Spring 자신의 두 경로에서 다르게 분류된다.
- **다음**: 기존 이슈·PR 검색 → 없으면 `40001`·`57014` 개별 매핑 선례를 근거로 이슈 초안(외부 발행은 사용자 확인 — open-source playbook)

## 보류·이월

- **study-note 이슈 아카이브 push 대기** — archive/2026-10-09(ADR-005, 5커밋) + archive/2026-10-11(ADR-006 이월 3건 + ADR-007 5건, 7커밋, worktree study-note-wt-archive-1011)이 이어진 미push 배치. main이 archive/2026-10-09 기점보다 1커밋 앞서 ff 불가 — 병합 방식·push는 사용자 확인. 재개 조건: 사용자 확인

- **study-note 이슈 아카이브(ADR-003·004 CS 이슈)** — 2026-10-06 사용자 확인 후 작성 완료(브랜치 archive/2026-10-06, 커밋 6) — main ff·push만 사용자 확인 대기. 후보: Kotlin 기본 인자 × CGLIB 프록시 NPE · 기동 실패를 못 잡아 이전 컨테이너를 잼 · 오류를 삼키는 수집기의 무음 실패(빈 파일) · 단방향 @OneToMany @JoinColumn의 추가 FK UPDATE · 환경변수 로그 레벨 소문자화 · 끝 상태 판정기가 만료된 일시 중복을 놓침 · (리뷰 발견) 서로 다른 단위의 지표를 한 칸에 둔 집계 오류. 재개 조건: 사용자 범위 확인
- ADR-001·ADR-002 카드는 study-note main 병합 완료(이전 배치)

## 완료 이력

- 완료 → `docs/plans/2026-10-11/adr-007-fsync-fio/`·`2026-10-10/adr-007-disk-cause/`·`2026-10-09/adr-007-throughput-wall/`(ADR-007 벽 = 서버 디스크(C5 확인) · counter-upsert-precheck 전환 결정 → 새 ADR-008)
- 완료 → `docs/plans/2026-10-09/adr-006-counter-upsert/`(ADR-006 advisory-try 유지 · counter-upsert 벽 진단은 ADR-007, 해결 시 전환)
- 완료 → `docs/plans/2026-10-06/adr-005-user-limit/`(ADR-005 advisory-try 확정 · counter는 ADR-006)
- 완료 → `docs/plans/2026-10-03/adr-003-lock-comparison/`(ADR-003 3b nowait 결정 · ADR-004 느린 작업은 판정 뒤로)
- 완료 → `docs/plans/2026-09-29/adr-002-db-baseline/`
- 완료 → `docs/plans/2026-09-28/adr-001-harness/`
- 완료 → `docs/plans/2026-09-27/adr-000-baseline/`
