# NEXT — 다음 작업 전망

## 기준

- 마지막 갱신: 2026-10-09 · 직전 완료 작업: `docs/plans/2026-10-09/adr-006-counter-upsert/`(ADR-006 측정·결정) · 기준 브랜치: `feat/adr-006-counter-upsert` · 병합 상태: main 미병합(push·PR 사용자 확인 대기). ADR-005는 main 병합(PR #5)

## 다음 작업 후보 (우선순위순)

### N1. ADR-007 counter-upsert 처리량 벽 진단 — 우선순위: 높음 · 사용자 지시(2026-10-09 "처리량이 무너지는 이유를 찾고 이게 해결되면 바꾸는 방향으로", "7번으로")

- **왜 다음인가**: ADR-006에서 counter-upsert는 롤백 피해 0·같은 사용자 지연 최소·CPU 최소였다. 그러나 S4 4,325건/s에서 26~27초 뒤 같은 초에 무너졌다(10/10, CPU 무관). 이 원인이 풀리면 매수 제어 기본을 counter-upsert로 바꾼다(ADR-006 §6 전환 규칙).
- **관측 신호**: 벽이 시간 기준 체크포인트 진행 구간 안에 있다. counter-upsert만 체크포인트 쓰기가 빨라졌다(완료 +429초 vs +458~480초). WAL +21%, 파일 확장 +44%, Hikari 대기가 1초 안에 0 → 190으로 뛴다.
- **발생 가능한 문제**: 누적 행 수와 경과 시간(체크포인트 주기)이 겹쳐 있다. 설정 하나를 바꾸면 캐시·쓰기·체크포인트 시점이 같이 움직여 원인을 단정할 수 없다. 대기 이벤트 수집이 측정 자체를 흔들 수 있다(샘플링 주기·부하 확인). 고정 도착률 장시간 구간은 디스크·시간 비용이 크다.
- **방법론**: ① 대기 이벤트 초 단위 샘플링 + 체크포인트 로그 정렬 ② `max_wal_size`·`shared_buffers` 축 ③ 도착률을 낮춰 같은 누적에 늦게 닿기(누적 vs 시간) ④ 고정 도착률 5분 이상(지속성). 쓰기 비용(S3 WAL 2.8배)을 줄이는 변형(좌석 먼저 → upsert)은 명세 인터뷰에서 범위 결정.
- **대처**: 대상은 counter-upsert·none 두 방식, S4 L4로 좁힌다. 축마다 짧은 셀로 나눠 먼저 스모크한다. 벽이 설정으로 안 사라지면 구조 쪽(쿼터 행 재사용·파티션)으로 넘긴다.

### N1b. ADR-008 홀드 만료 — 우선순위: 높음(ADR-007 뒤)

- counter-upsert를 택하면 만료 배치가 카운터를 내리는 주체다. 쿼터 행은 만료로 지워지지 않는다 — 회차 종료 정리·vacuum 비용을 함께 본다(ADR-006 §7).

### N2. (완료 2026-10-09) ADR-005 main 병합 — PR #5 merge commit 201aabdd

### N3. `pg_try_advisory_xact_lock` vs 3b nowait — 우선순위: 낮음(후순위, 사용자 2026-10-06) · ADR-008과 함께

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

- **study-note 이슈 아카이브(ADR-003·004 CS 이슈)** — 2026-10-06 사용자 확인 후 작성 완료(브랜치 archive/2026-10-06, 커밋 6) — main ff·push만 사용자 확인 대기. 후보: Kotlin 기본 인자 × CGLIB 프록시 NPE · 기동 실패를 못 잡아 이전 컨테이너를 잼 · 오류를 삼키는 수집기의 무음 실패(빈 파일) · 단방향 @OneToMany @JoinColumn의 추가 FK UPDATE · 환경변수 로그 레벨 소문자화 · 끝 상태 판정기가 만료된 일시 중복을 놓침 · (리뷰 발견) 서로 다른 단위의 지표를 한 칸에 둔 집계 오류. 재개 조건: 사용자 범위 확인
- ADR-001·ADR-002 카드는 study-note main 병합 완료(이전 배치)

## 완료 이력

- 완료 → `docs/plans/2026-10-09/adr-006-counter-upsert/`(ADR-006 advisory-try 유지 · counter-upsert 벽 진단은 ADR-007, 해결 시 전환)
- 완료 → `docs/plans/2026-10-06/adr-005-user-limit/`(ADR-005 advisory-try 확정 · counter는 ADR-006)
- 완료 → `docs/plans/2026-10-03/adr-003-lock-comparison/`(ADR-003 3b nowait 결정 · ADR-004 느린 작업은 판정 뒤로)
- 완료 → `docs/plans/2026-09-29/adr-002-db-baseline/`
- 완료 → `docs/plans/2026-09-28/adr-001-harness/`
- 완료 → `docs/plans/2026-09-27/adr-000-baseline/`
