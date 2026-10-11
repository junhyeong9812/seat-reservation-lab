# NEXT — 다음 작업 전망

## 기준

- 마지막 갱신: 2026-10-12 · 직전 완료 작업: `docs/plans/2026-10-11/adr-008-precheck-default/`(ADR-008 기본값 적용·확정) ← `2026-10-11/adr-007-fsync-fio/`(ADR-007 C5 확인·전환 결정) · 작업 브랜치: `feat/adr-007-throughput-wall`(미push)

## 다음 작업 후보 (우선순위순)

### N1. (완료 2026-10-12) 새 ADR-008 counter-upsert-precheck 기본 적용·검증 — `docs/adr/ADR-008-precheck-default.md`

- 기본값 none → counter-upsert-precheck · V6 백필 · 기본값 확정 측정 13회 전부 ok(정합·동작 ADR-007 precheck와 같음). 남은 원인 미확인: S2 선점 p99 1.75배(판정 아님, ADR-008 §5).

### N1a. 서버 디스크가 처리량 측정을 깎는다 — 우선순위: 중간 · ADR-007 §7.2

- **관측**: 이 서버 저장 경로(`nvme1n1` Samsung 980 · ext4 · LVM)에서 flush가 일정 쓰기 뒤 느려진다 — flush/s 약 1/3·flush 평균 약 3배. fio 단독(pgwal, flush 약 2,000회/s)에서는 약 3분(+179초 · 5.2GB) 뒤, DB S4c4200에서는 +149~160초 · 호스트 누적 3.50~3.74GB 뒤다(`20261010-dc-b943a3f0/WDROP.md`). DB 회차의 S4 벽(디스크 12회 중 11회 — precheck rep1의 256초 벽은 미설명)과 **같은 서명**이다. 쉬면 회복되는지는 pgwal 1회(40.5초 쉼 → 정상 기준선)뿐이고, 옛 append fio-2는 23초 쉬고 일찍(60초 · 3.79GB) 꺾였다. "DRAM 없는 SSD 동작"은 해석(장치 안은 미측정).
- **대처 후보**: 처리량 비교는 tmpfs 조건 병행 · 다른 디스크(nvme0n1은 Windows — 금지) · `synchronous_commit`·`commit_delay`로 flush 빈도 낮추기(측정 의미가 바뀜 — 명세 필요) · 반감 위치 차이(fio 5.2GB vs DB 3.5GB) 원인은 미확인.

### N1b. ADR-009 홀드 만료(기존 ADR-008 — 2026-10-11 번호 이동) — 우선순위: **최우선**(ADR-008 완료 — 기본값이 counter 계열이 됐다)

- counter-upsert-precheck를 기본으로 택했으므로(ADR-007 §8) 만료 배치가 카운터를 내리는 주체다. 쿼터 행은 만료로 지워지지 않는다 — 회차 종료 정리·vacuum 비용을 함께 본다(ADR-006 §7).
- **쿼터 행 정리 설계**는 ADR-006 §6 전환 규칙의 조건이었지만 ADR-007 §8 결정에 포함하지 않고 여기로 넘겼다.
- **ADR-008에서 넘긴 것**(ADR-008 §6): ① R1 카운터 어긋남 — 쿼터 행 없음·음수면 만료 배치가 매 틱 전체 롤백(수동 복구까지 전역 만료 정지), 덜 어긋나면 조용히 매수 초과 허용 또는 거절 → 탐지(주기 점검)·격리(쌍 단위)·복구 설계 ② 쿼터 테이블 없는 환경의 기동 시 검사(fail-fast) ③ 쿼터 행 정리.
- **발생 가능한 문제**: 만료 배치를 쌍 단위로 격리하면 부분 실패가 생긴다 — 실패 쌍을 어떻게 드러낼지(무음 스킵 금지) · 점검 쿼리 비용(전 테이블 집계)이 부하 중 DB와 경합(ADR-001 실측 L1 58s).
- **해결 방법 후보(사용자 논의 2026-10-12 — ADR-009에서 비교·측정)**:
  - 전제: PG의 조회 전용 집계는 쓰기를 막지 않는다(MVCC 스냅샷 · AccessShare 락). 비용은 CPU·디스크 읽기·공유 버퍼 밀림·긴 스냅샷의 vacuum 지연이다. 별도 앱이 가져가 비교해도 DB 읽기는 그대로라, 효과의 핵심은 '어디서'가 아니라 '청크로 짧게'다. 청크 사이에 쓰기가 계속되므로 의심 쌍은 주 DB에서 그 쌍만 잠그고 다시 확인한 뒤 고친다.
  - A 만료 배치를 쌍 단위로 견디게 — 감소 실패(행 없음·음수) 쌍만 그 자리에서 실제 값으로 재계산·덮어쓰고 나머지는 진행, 고친 쌍은 로그·지표로 노출(무음 금지). 막는 것: 전역 만료 정지. 못 보는 것: 조금 어긋남.
  - B DB 트리거로 카운터 유지 — 홀드·예약 행 변경 시 같은 트랜잭션에서 cnt 갱신. 앱 버그·옛 버전 쓰기·수동 수정까지 원천 차단. 비용: 모든 쓰기 + 같은 사용자 행 경합 + 로직이 DB에 숨음 → 처리량 측정 필요.
  - C DB 안 청크 대조 — 회차 단위(끝난 회차는 건너뜀) 또는 (회차, 사용자) 키 범위로 짧은 쿼리 반복. 조금 어긋남까지 탐지.
  - D 최근 건드린 쌍만 대조 — 바뀐 쌍 목록 기록 비용, 목록 밖 수동 수정은 못 봄.
  - E 읽기 복제본에서 탐지 + 주 DB에서 쌍 단위 복구 — 복제 지연으로 가짜 어긋남 → 재확인 필수, 복제본 인프라 필요.
  - F 별도 대조 앱(배치 서버) — C·D·E를 앱 프로세스로 분리. 앱 서버 CPU·스케줄 간섭은 줄지만 DB 읽기 부하는 그대로 · 배포 단위 증가. 측정으로 필요가 보일 때.
  - G CDC(변경 스트림) 실시간 검증 — 이 규모에선 과함.
  - 공통 복구: 주 DB에서 쿼터 행 `FOR UPDATE` → 그 쌍 실제 값 집계 → 덮어쓰기(counter 계열은 홀드 생성 때 같은 쿼터 행을 잡으므로 경합 안전).
  - 메인 의견(판정 아님): A 먼저(가장 큰 피해를 싸게 제거) → C로 조금 어긋남 탐지 → B는 원천 차단 후보로 함께 측정 → F는 측정에서 대조가 앱·DB와 자원을 다툴 때. 측정 예: S4(도착률 계단, 요청마다 새 사용자·좌석 — 처리량 한계 셀) 부하 중 C를 같이 돌려 처리량·p99 영향.

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

- (완료 2026-10-12) study-note 이슈 아카이브 push — archive/2026-10-11(ADR-006 이월 3 · ADR-007 5 · ADR-008 3)을 origin/main에 ff(1f04dca0..7f7659a3). archive/2026-10-09는 이미 origin/main에 반영돼 있었다. 로컬 study-note main은 origin과 갈라져 있음(1 앞·5 뒤 — 이번에 건드리지 않음).

- **study-note 이슈 아카이브(ADR-003·004 CS 이슈)** — 2026-10-06 사용자 확인 후 작성 완료(브랜치 archive/2026-10-06, 커밋 6) — main ff·push만 사용자 확인 대기. 후보: Kotlin 기본 인자 × CGLIB 프록시 NPE · 기동 실패를 못 잡아 이전 컨테이너를 잼 · 오류를 삼키는 수집기의 무음 실패(빈 파일) · 단방향 @OneToMany @JoinColumn의 추가 FK UPDATE · 환경변수 로그 레벨 소문자화 · 끝 상태 판정기가 만료된 일시 중복을 놓침 · (리뷰 발견) 서로 다른 단위의 지표를 한 칸에 둔 집계 오류. 재개 조건: 사용자 범위 확인
- ADR-001·ADR-002 카드는 study-note main 병합 완료(이전 배치)

## 완료 이력

- 완료 → `docs/plans/2026-10-11/adr-008-precheck-default/`(ADR-008 매수 제어 기본값 counter-upsert-precheck 적용·V6 백필·확정 측정 13회 합격)
- 완료 → `docs/plans/2026-10-11/adr-007-fsync-fio/`·`2026-10-10/adr-007-disk-cause/`·`2026-10-09/adr-007-throughput-wall/`(ADR-007 벽 = 서버 디스크(C5 확인) · counter-upsert-precheck 전환 결정 → 새 ADR-008)
- 완료 → `docs/plans/2026-10-09/adr-006-counter-upsert/`(ADR-006 advisory-try 유지 · counter-upsert 벽 진단은 ADR-007, 해결 시 전환)
- 완료 → `docs/plans/2026-10-06/adr-005-user-limit/`(ADR-005 advisory-try 확정 · counter는 ADR-006)
- 완료 → `docs/plans/2026-10-03/adr-003-lock-comparison/`(ADR-003 3b nowait 결정 · ADR-004 느린 작업은 판정 뒤로)
- 완료 → `docs/plans/2026-09-29/adr-002-db-baseline/`
- 완료 → `docs/plans/2026-09-28/adr-001-harness/`
- 완료 → `docs/plans/2026-09-27/adr-000-baseline/`
