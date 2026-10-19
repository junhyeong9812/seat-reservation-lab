# ADR-012: 카운터 증분 대조 — 바뀐 쌍만 짧은 주기로 대조해 C의 규모 한계를 없앤다

- 상태: **제안 — 측정 전 고정**(2026-10-18). §1~§7은 측정 전에 커밋한다(1차 01d82fad → 리뷰 rv36 반영). 결과·판정은 측정 뒤 §8 이후에 쓴다. **진행: 1단계 측정 끝(§8 — 2026-10-18) · R8 보강 측정 전(§4.1 고정) · 2단계 전.**
- 명세: `docs/plans/2026-10-18/adr-012-incremental-reconcile/requirement-spec.md` · 기록: 같은 폴더 `log.md`
- 선행: **ADR-009**(A+C 기본값 · `PairRepair` 복구 규칙 · S3e 효과 측정 · H3 탐지 기준) · **ADR-011**(§6.4 — C의 규모 한계 · 측정 조건 §8)
- 후행: **ADR-013**(Kafka + 읽기 복제본 대조 — 이 ADR의 M0~M3 결과를 기준선으로 쓴다) · ADR-014(운영 중 매수 방식 전환 — 대조를 전환 도구로 쓴다) · ADR-015(홀드 만료) · ADR-020(오픈 폭주)

## 1. 문제

ADR-009가 기본으로 켠 C(앱 안 대조)는 60초마다 **열린 회차의 쿼터 행 전체와 홀드·확정 예약 전체**를 훑는다. 한 바퀴의 일은 쌓인 행 수에 비례한다. ADR-011의 30분 S4c4200(고정 도착률 4,200건/s)에서:

- 쌍이 약 400만을 넘자 바퀴가 14~26초로 길어졌다(마지막 766만 쌍 25.4초). 그동안 DB CPU 사용률은 338~384%(한도 400%)였다.
- 정상 구간 느린 초가 대조 끔 1 ↔ 켬 12(서버형 1 ↔ 10)였다 — 1회씩, 켜고 끄기로 확정. 1초 p99 최대는 50.6 → 97.6ms였다.
- 7분 회차의 비용 판정(ADR-009 §7.7 — p99 +2.2%)은 이 증가를 보지 못했다.

ADR-015(홀드 만료)·ADR-020(오픈 폭주)처럼 행이 쌓이는 장시간·대량 측정 전에 대조 구조를 고쳐야 한다. 그대로 두면 그 측정의 꼬리에 C 바퀴가 섞인다.

## 2. 선택지 — 모델과 고른 것

### 2.1 모델

| 모델 | 바뀐 쌍을 아는 방법 | 탐지를 읽는 곳 | 원본 DB 부하 | 이 ADR |
|---|---|---|---|---|
| M0 C(지금) | 없음 — 매번 전체 훑기 | 원본 | 큼(행 수에 비례) | 기준선 |
| M1 F(별도 대조 앱, ADR-009 후보) | 없음 — 전체 훑기 | 원본 | 큼 — 앱 JVM(CPU·GC·커넥션 풀)만 분리 | 1단계 — 병목 가름 |
| M2 앱 안 증분 | ⓐ 앱 메모리 집합 · ⓑ 쿼터 행 `updated_at`(DB 트리거) · ⓒ 쿼터 행 `updated_at`(앱 문장) | 원본 | 작음(바뀐 쌍 수에 비례) | **구현·비교** |
| M3 외부 앱 증분 | ⓑ·ⓒ(메모리 집합은 다른 프로세스가 볼 수 없다) | 원본(.158 → .164) | 작음 — 앱 JVM도 분리 | **구현·비교** |
| M4 Kafka + 읽기 복제본 | Kafka 메시지(CDC 또는 아웃박스) | **복제본** | 복구만 | **ADR-013**으로 나눔 |

공통:
- 증분은 **탐지 후보를 줄이기만** 한다. 후보 쌍의 cnt 대 실제 값 비교는 지금 C와 같은 의미의 쿼리로 원본 DB에서 하고(바뀐 쌍 추적에 DB 비용이 없는 메모리 집합도 이 비교 비용은 같다), 어긋나 보인 쌍은 기존 `PairRepair`(쿼터 행 잠금 → 다시 세기 → 다르면 덮어씀 · 맞으면 오탐 기록)로만 고친다.
- **안전망 = 예산 있는 이어서 훑기 + 최근 창 우선**(§2.2 — 재합의 2번: 1시간 전체 → 10분 전체 → 이것). 증분이 못 보는 어긋남(§2.3)을 잡는다.
  - 1분마다 실행하고, 실행마다 **DB 시간 예산 2초**만 쓴다(청크 하나가 끝나는 시점에 예산을 넘었으면 멈춤). 예산의 앞 절반은 **최근 창**(최근 2시간에 생긴 홀드·확정 예약 행의 쌍 — `seat_hold.id`·`reservation.id` 범위: 실행마다 (DB 시각, 최대 id)를 기록해 2시간 전 id를 찾는다, 새 인덱스 없음)에, 나머지는 **전체 커서**(지금 C의 두 갈래 — 쿼터 행 PK 순 · 행 없음 id 범위 — 를 멈춘 곳부터 이어서)에 쓴다. 최근 창을 다 보면 남은 예산은 전체 커서로 넘긴다.
  - **최근 창에도 자기 커서가 있다**: 아직 안 본 가장 오래된 id부터 id 순으로 읽고, 다 본 곳까지 커서를 옮긴다. 최근 창의 유입(도착률 — 4,200건/s면 1분에 약 25만 행)이 실행당 몫보다 크면 커서가 밀린다 — 실행마다 **최근 창 적체**(커서 id와 최신 id의 차이 · 커서 행의 나이)를 기록하고, 커서가 2시간 경계 밖으로 밀리면 경계로 건너뛰며 건너뛴 id 범위를 기록·경고한다(그 범위는 전체 커서 몫이 된다 — 무음 누락 금지).
  - **id 순서 ≠ 커밋 순서**: `BIGSERIAL`은 할당 순서라 늦게 커밋한 트랜잭션의 작은 id가 커서 뒤에 나타날 수 있다. 실행마다 직전 30초 동안 할당된 id 범위(실행마다 남긴 (DB 시각, 최대 id) 기록으로 찾음)를 다시 읽는다. 30초보다 긴 트랜잭션의 행은 전체 커서가 잡는다(한계 — §7).
  - **예산 = 실행 전체의 DB 시간**(탐지 청크 + 발견 쌍의 `PairRepair` 복구 + 기록 INSERT — 커넥션을 빌린 동안의 벽시계 합). 청크 하나가 끝날 때마다 예산을 보고, 넘었으면 남은 복구만 마치고 멈춘다(복구를 미루면 그 쌍은 다음 실행 맨 앞). 청크 크기는 설계에서 '청크 하나 ≤ 200ms'(766만 쌍 규모 실측)가 되게 정한다.
  - 실행당 비용은 쌓인 행 수와 무관하게 일정하다. 대신 **전체 한 바퀴 시간**(커서가 처음으로 돌아올 때까지)이 데이터에 따라 늘어난다 — 바퀴마다 기록하고, 1시간을 넘으면 경고 로그·지표를 남긴다(무음 지연 금지).
  - 예산·주기·창 값은 측정 전 고정값이다. 바꾸면 측정 전에 이 문서를 고친다.

### 2.2 고른 것과 고르지 않은 것 (인터뷰 2026-10-18 — 원문 명세 §0)

| 질문 | 고른 것 | 고르지 않은 것 | 이유·대가 |
|---|---|---|---|
| 해결 방향 | **바뀐 쌍만 대조(증분)** + 긴 주기 안전망 | 청크 사이 쉬기(바퀴를 늘려 펴기) · 주기만 늘리기 · DB CPU 늘리기(스케일업) · 앱 분리만(F) | 쉬기·주기·스케일업은 비용이 여전히 행 수에 비례해 늦출 뿐이다. 앱 분리는 DB 부하를 옮기지 않는다 — 1단계(M1)로 확인한다 |
| 범위 | **F 측정 + 증분 구현(M0~M3)** · M4는 ADR-013 | F 측정만 · 한 ADR에 M4까지 · M4 중심 | M4는 인프라(복제본·Kafka)가 커서 결정 주기를 짧게 나눴다 |
| ADR 번호 | **012(증분)·013(Kafka·복제본)으로 끼움** — 기존 012~018 → 014~020(8차) | 019 뒤에 붙임 | 이 결정이 ADR-014(운영 중 매수 방식 전환)·015(홀드 만료)의 입력이다 |
| 바뀐 쌍을 아는 방법 | **세 가지 모두 비교** — 메모리 집합 · updated_at 트리거 · updated_at 앱 문장 | 하나만 | 볼 수 있는 범위와 비용이 다르다(§2.3) |
| 증분 주기 | **1초·5초 측정으로 비교** | 1초 고정 · 5초 고정 | 1초는 탐지가 빠르고, 5초는 호출이 적다 |
| 안전망 | **예산 있는 이어서 훑기 + 최근 창 우선**(1분마다 DB 시간 2초 — 앞 절반 최근 2시간 창 · 나머지 전체 커서 · 전체 한 바퀴 시간 기록·1시간 초과 경고). 재합의 이력: ① 기존 C 1시간 → ② 리뷰 rv36에서 앱 밖 어긋남 탐지 퇴행(60초 → 약 1시간)이 드러나 10분 → ③ 사용자 "데이터가 계속 쌓이면 10분 안에 전체를 못 도니" — 한 번에 하는 일에 상한 | 10분·1시간 전체 훑기(쌓이면 주기 안에 못 돎 — C와 같은 문제) · 매시간 최근 2시간 창만(창의 양이 도착률 × 2시간이라 상한이 아님 — 4,200건/s면 약 3,000만 행 · 2시간보다 오래된 쌍은 다시 보지 않음) · 청크 사이 쉬기만 · 안전망 없음 | 실행당 비용이 일정해 꼬리에 큰 튐을 만들지 않는다. 대가: 오래된 쌍의 탐지는 '전체 한 바퀴 시간'만큼 늦어지고, 그 시간은 데이터에 따라 늘어난다(기록·경고로 드러냄) |
| 구현 위치 | **앱 안 + 별도 폴더 외부 앱**(독립 앱 · Dockerfile · compose — .158) | 외부 앱만 · 앱 안만 | 외부 앱은 앱 JVM을 나눈다. DB CPU는 그대로 .164다(DB 부하 분산은 ADR-013) |
| 저장소 구조 | **Gradle 다중 프로젝트 — `main-app/`(기존 앱 이동) · `reconciler-app/`(외부 대조 앱 — Kotlin) · `reconcile-core/`(탐지·복구 공통)**, 1단계 측정 뒤 동작 무변경 리팩토링으로 | 루트 유지 + 하위 프로젝트만 추가 · 구조 변경을 1단계 전에 | 두 앱이 같은 탐지·복구 코드를 의존해 갈라지지 않는다. 대가: 과거 하네스(ADR-001~011) 빌드 경로를 새 구조에 맞춰야 한다 — 같은 계획·렌더를 만드는지 확인 |
| core 추출 시점(2026-10-19 — 구조 변경 중 발견) | **task 05 설계 때 함께** — 구조 변경(04b)은 폴더만, R8(04c)은 main-app에 | 구조 변경에서 바로 | 대조 코드 5클래스가 앱 전용 타입(앱 설정 · 홀드 전략 내부 함수 · 판매 마감 검사 · JPA 엔티티)에 묶여 동작 무변경으로는 못 옮긴다. 경계(설정 분리 · 공유 방식)를 증분·외부 앱 설계와 함께 정하면 코드를 한 번만 옮긴다 |
| 탐지 쿼리 모양(재합의 — 1단계 뒤, 사용자 제안) | **R8로 비교** — 지금 한 방 쿼리(쿼터 행마다 홀드·확정 예약 상관 서브쿼리) 대 `split`(조인 없이 쿼터·홀드·확정 예약 범위를 행으로 읽고 앱이 셈) · `group`(테이블별 `GROUP BY user_id` 개수만 읽고 앱이 합침) · F 컨테이너로 30분 × 3 · 저장소 구조 변경 뒤 | 별도 ADR · 증분 구현 뒤 · 변형 하나만 | 1단계에서 바퀴 비용이 거의 다 DB에 있었다(F CPU 1.7% 대 DB 258%). 비교·집계를 앱으로 옮기면 DB는 범위 훑기만 한다. 대가: 행 전송 비용(`split`) · 조회 사이 시점 차이(한 스냅샷 트랜잭션으로 묶음) · 앱 메모리(청크로 묶음). 바퀴가 데이터에 비례해 커지는 기울기는 그대로다 — 증분과 합쳐 쓴다 |
| 측정 서버 | **.164 Linux 유지** | .9로 옮김 · .164 + .9 | ADR-011 수치와 직접 비교한다 |
| 성공 기준 | **대조 끔(noc)과 같은 꼬리** + ADR-009 효과 기준 | C 대비 절반 · 측정 뒤 정함 | §6 |
| 효과 측정(S3e) | **증분 전부 × 3 · 증분 + 안전망 합산** + **측정 전용 앱 경로 주입 추가**(재합의 — 지금 S3e는 DB 직접 주입뿐이라 메모리 집합의 앱 경로 탐지를 반증할 수 없다) | 고른 기본값만 · 증분만으로 판정 · 앱 경로 탐지는 테스트로만 | §3·§2.3 |
| 기본값 | **성공 시 교체**(손잡이로 옛 방식 복귀) | 손잡이만 | §6 |
| 범위가 겹칠 때 고르는 순서 | **탐지 범위 우선** — 트리거 > 메모리 집합 > 앱 문장 · 같은 방식이면 1초 · 앱 안 > 외부(재합의 — 처음 '같으면 메모리 집합'에서 리뷰 뒤 바꿈) | 단순함 우선(메모리 > 앱 문장 > 트리거) | 볼 수 있는 어긋남이 넓은 쪽부터. 앱 문장은 트리거와 같은 V9가 필요한데 범위는 더 좁다 |
| 외부 앱만 성공 | **기본값 A+C 유지** — 외부 앱은 선택 배포, 결과는 ADR-013 입력 | 외부 앱을 기본 구성으로 | 배포 단위를 늘리지 않는다 |
| 반복 | **×3, 단계 나눔** | ×2 · ×1 후 일부만 ×3 | 범위 판정에 3회가 필요하다(ADR-009·010 규칙) |
| 여러 앱 인스턴스 | **범위 밖**(대조는 1대에서만) | 앱 2대 | 메모리 집합은 다른 인스턴스의 변경을 못 본다 — 한계 |
| stakes | **높음** | 중간 | 동시성·카운터 정합성·마이그레이션 V9·기본값 교체 |

### 2.3 방식별로 볼 수 있는 것 (설계 전제 — 코드·주입 방식 기준)

쿼터를 바꾸는 경로: 선점 증가·행 만들기(`UserLimitStrategies.kt` — upsert의 INSERT 갈래 포함) · 만료 감소 A(`ExpiryCounters.kt`) · 복구(`PairRepair.kt` — INSERT·UPDATE) · 정리 삭제(`QuotaCleanup.kt`) · 시드(`LoadtestDataService.kt`) · counter-trigger(B — V8 `quota_add` DB 함수). **실제 값 쪽만** 바뀌는 경로(확정 — 쿼터 행 그대로 · 홀드·예약 직접 수정 · ADR-016 확정↔만료 경합)는 쿼터 행을 건드리지 않는다.

| 어긋남이 생기는 곳 | ⓐ 메모리 집합 | ⓑ updated_at 트리거 | ⓒ updated_at 앱 문장 | 안전망(최근 창 · 전체 커서) |
|---|---|---|---|---|
| 앱 경로의 쿼터 변경(선점·만료·복구 — 버그 포함) | **봄**(커밋 뒤 등록) | **봄** | **봄**(precheck 계열 문장만 — B의 V8 함수는 V8 무변경이라 못 남김) | 봄 |
| DB 직접 값 변경(S3e L1·L2·H) | 못 봄 | **봄**(BEFORE UPDATE) | 못 봄 | 봄 |
| DB 직접 행 삭제(S3e D — 쿼터 행 없음) | 못 봄 | 못 봄 | 못 봄 | 봄(행 없음 갈래) |
| 실제 값 쪽만 바뀜(쿼터 행 그대로) | 앱 경로면 봄(그 쌍을 등록하면) | 못 봄 | 못 봄 | 봄 |
| 앱 재시작 전 변경 | 잃음 | 남음 | 남음 | 봄 |

- S3e(DB 직접 주입)에서 증분 경로로 잡힐 것으로 기대하는 것: ⓐ 0 · ⓑ L1·L2·H · ⓒ 0. 나머지는 안전망 몫이다.
- 쿼터 INSERT 경로(upsert INSERT 갈래·행 만들기·`PairRepair`)는 열 기본값(`updated_at DEFAULT now()`)으로 덮는다.
- updated_at에 인덱스를 두면 cnt 갱신마다 인덱스 열이 바뀌어 가장 뜨거운 행의 HOT 갱신이 사라진다 — ⓑ·ⓒ 둘 다의 비용(추론 — 측정으로 확인: §4 R5).

## 3. 동작 불변식 (설계 선검증 대상 — 측정 전 고정)

1. **유실 없음**: 커밋된 모든 쿼터 변경(앱 경로 — ⓐ·ⓒ / 모든 UPDATE — ⓑ)은 적어도 한 번 증분 탐지 대상이 된다.
   - updated_at 기준점은 **DB 시계**로 잡는다(외부 앱 .158의 시계를 쓰지 않는다). `now()`는 트랜잭션 시작 시각이라 늦게 커밋한 변경이 기준점보다 과거 값을 가질 수 있다 — 다음 창은 앞 창과 **최대 트랜잭션 시간 이상** 겹치게 읽고(또는 스냅숏 xmin 기반), 중복은 쌍 단위로 걸러낸다. 겹침 폭과 그 근거는 설계에서 정한다.
   - 메모리 집합은 **커밋 뒤에만** 등록한다(롤백된 변경은 넣지 않고, 커밋 전에 넣어 옛 스냅숏을 보고 지우는 일이 없게). 탐지에서 꺼낸 쌍은 비교·복구가 끝나기 전 실패하면 다시 넣는다. 집합 크기 상한을 두고, 넘치면 넘친 것을 기록하고 다음 안전망 바퀴를 앞당긴다(무음 버림 금지).
2. **복구 규칙 그대로**: 증분·안전망·만료 A가 같은 쌍을 동시에 다뤄도 결과가 맞다 — 모두 `PairRepair`(쿼터 행 `FOR UPDATE` 뒤 다시 세기)를 쓰고, 여러 쌍을 한 트랜잭션에서 잠그지 않는다(쌍마다 트랜잭션 — 지금 C와 같음). 오탐 복구 0.
3. **행 없음 갈래**: 쿼터 행이 없는 쌍(D)은 증분이 보지 않는다 — 안전망의 행 없음 갈래가 잡는다. 새 홀드가 행을 다시 만드는 경우(upsert INSERT)는 그 순간의 cnt가 기존 확정 예약을 빠뜨릴 수 있다 — 그 쌍은 앱 경로 변경으로 증분 후보가 되어 실제 값(홀드 + CONFIRMED)으로 다시 비교된다(ⓐ·ⓑ·ⓒ 모두 — INSERT도 등록·기본값 대상).
4. **새 손잡이를 지정하지 않으면 동작·렌더·계획이 이전과 같다** — 기본값 교체는 판정 뒤 별도 커밋.

## 4. 실험

모든 측정: S4c4200 · tmpfs 12g(DB 14g) · 2g + 세대형 ZGC · L4 · 측정기 두 곳 · 주 k6 v2.2.0 · 캠페인 전 k6 PC 점검 1행(ADR-011 §8). 30분 측정은 `--seed-schedules 800 --s4c-steps 60 --pg-tmpfs-size 12`. 같은 캠페인 안에서 조건 순서를 회차마다 바꾼다(ABAB). **비교는 같은 캠페인 안에서만** 한다.

| 단계 | 셀 | 회차 | 묻는 것 |
|---|---|---|---|
| 1 | noc(A + 정리, C 끔) · M0 C · M1 F | 30분 × 3 | C 바퀴 비용의 출처(앱 JVM 대 DB) · noc 기준선 |
| 2 | noc · M0 · M2ⓐ·ⓑ·ⓒ × {1초, 5초} | 30분 × 3 | 증분 + 안전망(예산 이어서 훑기)이 noc와 같은 꼬리인가 · 방식별 비용 |
| 2' | noc · M2 비교 셀(2단계에서 성공한 앱 안 셀 중 고르는 규칙 1위, 없으면 M2ⓑ 1초) · M3 ⓑ·ⓒ × {1초, 5초} | 30분 × 3 | 외부 앱이 앱 안과 같은가 · 앱 JVM 분리 효과 |
| 효과 | M2·M3 각 셀(운영 설정 — 안전망 예산 이어서 훑기) | S3e × 3 + 앱 경로 주입 갈래 | 주입 어긋남을 끝까지 고치는가 · 탐지 경로별 시간 |

- **창 정의**: '정상 구간' = 램프 뒤 첫 초부터 k6 끝까지(tail.py `t0` 기준 1,800초) · '후반' = 정상 구간 15분~30분. 지표는 회차마다 낸 뒤 3회 범위로 본다. 서버형 느린 초 = tail.py 정의(waiting > blocked + sending + receiving, 최대 응답 > 50ms인 1초).
- **효과 셀**: S3e는 약 17분(200,000건 ÷ 300건/s + 꼬리 360초)이고 좌석 1만 석 규모라 안전망의 전체 한 바퀴가 짧다(최근 창이 주입 쌍 대부분을 먼저 본다). 설계에서 '마지막 주입 + 전체 한 바퀴 시간(스모크 실측) < 끝 검사'를 확인하고, 안 되면 S3e 꼬리를 늘린다(판정 전 고정).
- **앱 경로 주입**(새 — loadtest 프로필에서만 켜지는 손잡이 · 기본 꺼짐): 앱의 선점·만료 경로 안에서 일부 쌍의 cnt를 일부러 틀리게 쓴다(버그 흉내 — 등록·updated_at 표시는 정상 경로 그대로 남는다). 기대: ⓐ·ⓑ·ⓒ 모두 증분 경로로 잡음.
- **측정 전에 하네스에 필요한 것**(없으면 그 가설은 판정 불가): ① 후반 창 서버형 느린 초·1초 p99 최대(tail.py) ② 정상 구간 DB·앱 컨테이너 CPU 평균(docker stats — 램프 제외, 결측 표본 수 기록) ③ 증분 바퀴마다 `counter_reconcile_run` 1행 + 새 source 값(방식·주기별) — 안전망과 구분 ④ `counter_drift_log.source`로 주입 쌍별 첫 탐지 출처·시각 → effect.json 경로별 탐지 시간 ⑤ summarize 허용 출처·`borrow_per_request` 차감에 증분 빌림 추가 ⑥ M3: .158 외부 앱 컨테이너 CPU·풀 표본 ⑦ C 바퀴 중 Hikari pending(1단계 R1) ⑧ `pg_stat_user_tables.n_tup_hot_upd`·updated_at 인덱스 크기(R5). 1단계에 필요한 ①②⑦은 1단계 전에, 나머지는 구현과 함께 넣는다.

### 4.1 R8 설계 (task 04c — 측정 전 고정)

**1단계에서 확인한 사실**(`counter_reconcile_run` — §8.2 갈래별 시간): 끝에서 두 번째 바퀴(쌍 733만~751만) 25~29초 = ① 쿼터 행 청크 14.8~15.3초(청크 약 8,300개 · 청크당 약 1.8ms) + ② 행 없음 갈래 7.2~11.4초(청크 약 750개) + 복구·기록. 두 갈래 모두 바퀴 비용에 크게 든다.

**바꾸는 범위 = ① 쿼터 행 청크만.** ②는 '쿼터 행이 없는 쌍'을 찾는 반조인(`NOT EXISTS`)이다. 앱으로 옮기려면 열린 회차의 쿼터 키 전체(약 770만 쌍)를 앱 메모리에 들고 있어야 해서 F 컨테이너(메모리 1g)에 맞지 않는다 — ②는 지금 그대로 두고, 바퀴 기록의 `chunk_ms_total`(①)·`orphan_ms_total`(②)로 갈래별 시간을 따로 본다.

**모양 3가지**(손잡이 `seat.hold.counter-drift.reconcile.detect-shape` = `one-shot`(기본 — 지금) | `split` | `group`). 청크 경계는 셋 다 같다 — 회차 s의 쿼터 행을 PK keyset으로 k개(지금과 같음):

| 모양 | 청크마다 DB에 보내는 것 | 앱이 하는 것 |
|---|---|---|
| `one-shot` | 지금 `CHUNK_SQL` 한 문장(쿼터 행마다 홀드·확정 예약 상관 서브쿼리 — 자동 커밋) | 어긋난 행만 받는다 |
| `split` | ⓐ 쿼터 행 k개(user_id, cnt — keyset) ⓑ 홀드 `SELECT user_id FROM seat_hold WHERE schedule_id = s AND user_id = ANY(U)` ⓒ 확정 예약 `SELECT user_id FROM reservation WHERE schedule_id = s AND user_id = ANY(U) AND status = 'CONFIRMED'` — 행 그대로(U = ⓐ의 사용자 k명) | 행을 흘려 읽으며(목록에 쌓지 않음) 사용자별로 세어 쿼터 행과 비교 |
| `group` | ⓐ 같음 · ⓑⓒ 같은 조건 `… GROUP BY user_id`(개수만) | 세 결과를 합쳐 비교 |

- **읽는 대상 = one-shot과 같다**(선검증 rv39 H1 — 처음 안의 `user_id` 범위 `(a, b]`는 범위 안의 다른 회차·다른 사용자 홀드까지 읽어 '인덱스 부재'를 재게 되므로 버렸다): ⓑⓒ는 ⓐ가 돌려준 사용자 k명만 본다. `seat_hold`는 `user_id` 단일 인덱스(V2:7)라 one-shot의 상관 서브쿼리와 같은 인덱스·같은 행을 읽는다 — 다른 것은 '쌍마다 한 번씩 k번' 대 '배열 하나로 한 번'이다. 쿼터 행이 없는 사용자는 U에 없으므로 결과에 나오지 않는다(②가 맡는다). 메모리는 사용자 k명의 카운터만(앱이 셀 때 U 밖 사용자는 없다). `split`의 ⓑⓒ는 JDBC fetch size를 양수(청크 크기)로 지정해 커서로 읽는다 — pgJDBC 기본은 결과 전체를 한 번에 받는다(트랜잭션 안이라 커서가 성립한다).
- **한 스냅샷 · 동등성**: `split`·`group`은 ⓐⓑⓒ를 **탐지 전용** 읽기 전용 `REPEATABLE READ` 트랜잭션 하나에서 읽는다(빌림 1). 복구 트랜잭션(`repairOne`)이 쓰는 템플릿은 그대로 READ COMMITTED다(탐지용 템플릿을 따로 둔다 — 선검증 M1). 같은 스냅샷이면 세 모양은 ①에서 **같은 어긋난 쌍 집합**(쌍 · cnt · 실제 값)을 낸다. 범위: ① 쿼터 행이 있는 쌍. ②(행 없음 갈래)는 모양과 무관하게 같은 코드다.
- **경계 규칙(셋 다 같게)**: ⓐ가 0행이면 ⓑⓒ를 보내지 않고 그 회차를 끝낸다 — 그래도 청크 1개·빌림 1로 센다(one-shot의 '빈 청크'와 같음 — `scanned == k` 뒤 빈 청크 포함). 다음 청크 커서 = ⓐ의 마지막 user_id. ⓑⓒ에 없는 사용자는 0. 홀드는 `expires_at`으로 거르지 않는다(one-shot과 같음). 실제 값은 `Math.toIntExact`(넘치면 오류 — one-shot의 `::int`와 같은 실패).
- **실패**: ⓐⓑⓒ나 앱 집계 중 하나라도 실패하면 그 청크는 통째로 버리고 지금과 같은 경로(`stage=chunk` · `errors` · `skipped_chunks` · 그 회차 남은 청크 건너뜀)를 탄다 — 부분 집계로 어긋남을 내지 않는다.
- **`chunk_ms`의 뜻**: 청크 하나의 벽시계 시간 = 트랜잭션 시작·끝(isolation 설정 왕복 포함) + 세 조회 + 앱 집계. 트랜잭션 왕복도 '모양의 비용'에 넣는다(선검증 M2).
- **복구·기록은 그대로**: 어긋난 쌍은 지금처럼 `repairOne`(→ `PairRepair`). 바퀴 기록 열은 더하지 않는다 — 모양은 회차 설정(`reconciler-config.json`의 `detectShape`)으로 남는다.
- **하네스**: run.sh `--reconcile-shape one-shot|split|group` — F 조건(A+F)에서만 받고 다른 조건이면 거부 · 지정할 때만 F의 `SPRING_APPLICATION_JSON`에 키를 넣는다(지정하지 않으면 렌더·계획 그대로) · 기대 설정 검사(`reconciler-config-mismatch`)에 `detectShape`를 더한다(지정한 값과 다르면 회차 사유 — 조용히 one-shot으로 돌지 않게). 세트 `adr012r8` = {f, f-split, f-group} × 3(1단계와 같은 30분 구성 · 회차마다 1칸 회전).
- **해석 주의(코드 리뷰 rv40)**: 앱의 트랜잭션 매니저는 JPA(`JpaTransactionManager`)라 `split`·`group` 청크마다 Hibernate 세션 생성 · 격리 수준 설정·원복 왕복 2회 · COMMIT이 붙는다(청크당 약 1.5ms 규모에서 무시 못 함). `split`은 fetch size = k라 행이 여러 번에 나뉘어 온다. 둘 다 '모양의 비용'에 들어간다 — **R8이 기각되면 이 몫(트랜잭션 왕복)을 먼저 갈라 본다**(예: 첫 문장에 `SET TRANSACTION ISOLATION LEVEL REPEATABLE READ READ ONLY`). 읽기 전용 여부와 fetch size는 blind 테스트가 고정하지 않는다(동작은 리뷰로 확인 — 스모크 때 `pg_stat_activity`로 한 번 본다). `runOnce`를 바깥 트랜잭션 안에서 부르면 탐지 트랜잭션이 거기 합류해 READ COMMITTED로 돈다 — 지금 호출처는 스케줄러뿐.
- **측정 전 확인(스모크)**: 시드 데이터에서 세 모양의 청크 하나씩 `EXPLAIN (ANALYZE, BUFFERS)`로 읽은 행·버퍼·시간을 log에 고정하고, 청크 ≤ 200ms를 본다.

### 4.2 증분 대조 설계 (task 05 — 측정 전 고정)

기준 코드 = 브랜치 `feat/adr-012-incremental-reconcile`의 `main-app/src/main/kotlin/com/jun/labs/seatreservation/`(아래 경로는 이 뒤를 줄여 쓴다). 줄 번호는 이 설계를 쓸 때(2026-10-20)의 것이다. 구현 접촉 전에는 판정할 수 없는 값은 `[구현 검증]`으로 적고 이 절 끝에 모은다.

**공통 원칙**: 새 손잡이를 하나도 지정하지 않으면 동작·렌더·계획이 지금과 같다(§3-4). 그래서 ① 새 마이그레이션은 모두 **선택 위치**(`spring.flyway.locations`에 더할 때만 적용 — V4·V8과 같은 방식)에 둔다. ② 기존 SQL 문장 문자열은 손잡이가 꺼져 있으면 한 글자도 바뀌지 않는다(새 문장은 별도 상수). ③ 새 스케줄러는 손잡이가 켜질 때만 만든다. ④ 새 지표 이름은 기존 이름과 겹치지 않는다(기존 지표에 태그를 더하지 않는다).

#### 4.2.1 reconcile-core 경계

**옮기는 것**(패키지 이름은 그대로 — 모듈만 `reconcile-core/`로):

| 대상 | 지금 위치 | core에서 바뀌는 것 |
|---|---|---|
| `CounterReconciler`·`CounterRepairProbe`·`ReconcileRun` | `service/impl/drift/CounterReconciler.kt:1-463` | 생성자 `properties: SeatHoldProperties`(:72) → `ReconcileProperties`. `:120`의 `properties.counterDrift.reconcile` → `properties`. 탐지(:192-295)는 4.2.4의 `ChunkDetector`로 떼어 내고, 쌍 복구(:345-390)는 `PairRepairRunner`로 떼어 낸다(전체 대조·증분·안전망이 같이 쓴다). 행 없음 갈래(:298-343)는 id 범위를 인자로 받는 함수로 바꿔 안전망도 쓴다. 기본 경로(one-shot · 손잡이 없음)의 문장·순서·빌림 수는 그대로다 |
| `PairRepair`·`CounterDriftRecorder`·`DriftKind`·`DriftAction`·`DriftOutcome`·`DriftSource`·`skipReason` | `service/impl/drift/PairRepair.kt:1-156` | `:3` import만 바뀐다. 4.2.2의 등록 이음새(`QuotaChangeListener`)와 ⓒ 문장 선택이 더해진다 |
| `sqlStateOf` | `service/impl/hold/DbLockHoldStrategies.kt:66-68`(`internal`) | core의 같은 패키지 `service/impl/hold/SqlState.kt`로, `public`. 호출처(`DbLockHoldStrategies.kt:57` · `UserLimitStrategies.kt:13·408` · `PairRepair.kt:3`)는 import 그대로 컴파일된다(파일 파사드 클래스 이름만 `SqlStateKt`로 바뀐다) |
| 마감 판정식 `isClosed(startsAt, now) = !now.isBefore(startsAt)` | `service/impl/SalesCloseGate.kt:73-76`(companion) | core `service/impl/drift/ScheduleClosure.kt`(`object` — 순수 함수). `SalesCloseGate.isClosed`는 그대로 두고 이 함수를 부른다(`QuotaCleanup.kt:124` 등 호출처 무변경). `SalesCloseGate` 자체(도메인 `ErrorCode` · ThreadLocal 판정 시각 · `enabled`)는 main-app에 남는다. 복구 트랜잭션의 마감 재확인(`CounterReconciler.kt:363`)은 지금처럼 `sales-close.enabled`와 무관하게 판정식만 쓴다 |
| `SeatHoldProperties.Reconcile`(:42-54)·`DetectShape`(:67-71) | `service/SeatHoldProperties.kt` | core 최상위 타입 `ReconcileProperties`·`DetectShape`(패키지 `service.impl.drift`). `SeatHoldProperties.CounterDrift.reconcile`(:38)의 타입만 바뀐다. **설정 키·configprops 모양은 그대로**다 — 키는 계속 `seat.hold.counter-drift.reconcile.*`이고, configprops JSON은 클래스 이름이 아니라 속성 이름으로 그려지므로 `counterDrift.reconcile.{enabled, interval, lockTimeout, chunkSize, orphanChunkSize, detectShape}` 경로가 같다. 새 키(4.2.2·4.2.5)는 `counterDrift.reconcile.incremental.*`·`counterDrift.reconcile.safetyNet.*`로 **더해지기만** 한다 — 하네스의 jq 경로(`run.sh:1254-1262`·`tail.py:857-858`)는 바뀌지 않는다. 테스트의 `SeatHoldProperties.DetectShape` 참조는 import만 바뀐다(단언 무변경) |

**main-app에 남는 것과 이유**:
- `ExpiryCounters`(`service/impl/drift/ExpiryCounters.kt`) — JPA 엔티티 `SeatHold`(:3·:40)를 받고, 만료 틱 ThreadLocal(:34-38)이 `ExpireHoldsService.kt:28-49`와 묶여 있다. 외부 앱은 만료를 하지 않는다. core의 `PairRepair`만 부른다(방향 main-app → core).
- `QuotaCleanup` — 정리는 업무 앱만 한다(F도 `cleanup.mode: none` — `application-reconciler.yml:15-16`). `SeatHoldProperties.cleanup`에 묶여 있다.
- `CounterDriftScheduling` — 프로필로 출처를 정하고(:46-47 `Environment`), 정리도 스케줄한다. 증분·안전망 스케줄 2개가 여기에 더해진다(손잡이가 켜질 때만).
- `SalesCloseGate`·`HoldStrategyStartupCheck`(기동 검사 — 4.2.2의 스키마 검사 호출이 더해진다)·메모리 집합 구현(4.2.2 ⓐ — 트랜잭션 동기화와 업무 쓰기 경로에 묶인다).

**새 이음새(core에 둔다)**:
- `QuotaChangeListener`(`fun interface changed(scheduleId, userId)`) — core의 `PairRepair`가 쿼터 행을 쓴 뒤 부른다(`ObjectProvider.ifAvailable` — 빈이 없으면 아무것도 안 함). main-app의 메모리 집합 모드만 빈을 둔다.
- `CandidateSource`(4.2.3) — 증분 틱이 '바뀐 쌍'을 받는 곳. 구현: `UpdatedAtCandidates`(ⓑⓒ — core) · `MemoryCandidates`(ⓐ — main-app).
- `IncrementalSchemaCheck`(core 함수) — 방식과 스키마가 맞는지 기동 때 본다(4.2.2). 두 앱의 기동 검사가 부른다.

**의존 방향**: main-app → reconcile-core ← reconciler-app. core는 앱 타입(도메인·JPA·`SeatHoldProperties`)을 import하지 않는다. core 의존성 = spring-jdbc · spring-tx · spring-context · micrometer-core · slf4j(버전은 Spring Boot BOM — `[구현 검증]` 부트 플러그인 없는 모듈의 BOM 적용 방식). core 클래스의 `@Component`는 그대로 둔다 — main-app은 기본 패키지 스캔(`com.jun.labs.seatreservation`)으로 지금처럼 빈을 만든다. main-app은 `@Bean fun reconcileProperties(p: SeatHoldProperties) = p.counterDrift.reconcile` 하나를 더한다.

**reconciler-app에 필요한 것**: `spring-boot-starter-jdbc`·`actuator`·PostgreSQL 드라이버(JPA·Redis·Flyway 없음 — `spring.flyway.enabled=false`, 스키마는 업무 앱이 만든다) · 메인 클래스 `com.jun.labs.seatreservation.reconciler.ReconcilerApplication`(`scanBasePackages` = `…service.impl.drift` + `…reconciler`) · `Clock` 빈(`Clock.systemUTC()` — main-app `SeatReservationApplication.kt:16`과 같음) · 설정 묶음 `@ConfigurationProperties("seat.hold") ReconcilerAppProperties(counterDrift.reconcile: ReconcileProperties)` — **같은 접두어·같은 키**라 configprops의 `prefix == "seat.hold"` · `.counterDrift.reconcile.*` 경로가 업무 앱과 같다(업무 앱에만 있는 `expiryRepair`·`cleanup` 등은 없다 — 외부 앱 검사는 4.2.9의 새 검사가 맡는다). 스케줄러는 자기 클래스(`ExternalReconcileScheduling` — `CounterDriftScheduling.kt:49-67`과 같은 단일 스레드 고정 지연)를 둔다. 트랜잭션 매니저는 `DataSourceTransactionManager`다(업무 앱은 JPA — §4.1 해석 주의의 탐지 트랜잭션 왕복 비용이 두 앱에서 다를 수 있다. 의미는 같다). 외부 앱은 전체 대조(C)를 하지 않는다 — `reconcile.enabled=true`면 기동 실패(전체 훑기를 다른 프로세스로 옮긴 것은 F가 이미 잰다).

#### 4.2.2 바뀐 쌍을 아는 방법 3가지

**손잡이**(모두 `seat.hold.counter-drift.reconcile.incremental.*`):

| 키 | 값 · 기본 | 뜻 |
|---|---|---|
| `method` | `none`(기본) · `memory` · `trigger` · `stmt` | 이 프로세스가 쿼터 쓰기에 남기는 표시(ⓐ 등록 · ⓒ 문장)와, 증분 틱이 후보를 읽는 방식 |
| `enabled` | `false`(기본) | 이 프로세스에서 증분 틱을 돌리나. M3(외부 앱 증분)의 업무 앱 = `method=trigger|stmt` + `enabled=false`(표시만) · 외부 앱 = 같은 `method` + `enabled=true` |
| `interval` | `1s`(기본) | 틱 주기(고정 지연). 측정은 1초·5초 |
| `memory-cap` | `200000` | ⓐ 집합 크기 상한(쌍 수) `[구현 검증]` |
| `start-lookback` | `60s` | ⓑⓒ 첫 틱의 창 하한 = 기동 시 DB 시각 − 이 값 |
| `max-overlap` | `60s` | ⓑⓒ 창 하한을 이보다 뒤로 끌지 않는다(4.2.3) |

기동 검사(`IncrementalSchemaCheck` — 어기면 기동 실패, 메시지에 이유): `method=memory`면 `enabled=true`(표시만 하는 메모리 집합은 뜻이 없다)이고 외부 앱이 아니어야 한다 · `method ∈ {trigger, stmt}`면 `user_hold_quota.updated_at` 열과 인덱스가 있어야 한다 · `method=trigger`면 트리거 `user_hold_quota_touch`가 있어야 하고, **그 밖의 방식(none 포함)이면 그 트리거가 없어야 한다**(트리거 비용이 다른 셀에 새지 않게) · 증분 또는 안전망이 켜졌으면 `counter_safety_run` 테이블(4.2.5)이 있어야 한다 · 증분이 켜지면 A(만료 복구)가 필수다(지금 C와 같은 규칙 — `HoldStrategyStartupCheck.kt:62` 계열).

**ⓐ 메모리 집합**(main-app `service/impl/drift/MemoryChangedPairs.kt`):
- 자료 = `ConcurrentHashMap` 키 집합(키 = (회차, 사용자)). `method ≠ memory`면 등록 함수는 바로 돌아온다(동기화 등록도 하지 않음 — §3-4).
- **커밋 뒤에만 등록**한다. 등록 함수 `changed(s, u)`: 트랜잭션 동기화가 켜져 있으면(`TransactionSynchronizationManager.isSynchronizationActive()`) 그 트랜잭션에 동기화를 **하나만** 걸고(트랜잭션별 쌍 목록에 모음) `afterCommit`에서 집합에 넣는다. 롤백이면 버린다. 동기화가 꺼져 있으면(자동 커밋 문장 — 이미 커밋됨) 바로 넣는다. `afterCommit` 안의 예외는 잡아서 지표·로그로 남긴다(요청을 실패시키지 않는다 — 커밋은 이미 끝났다).
- 등록 지점(업무 앱의 모든 쿼터 쓰기 + 실제 값만 바뀌는 앱 경로):

| 경로 | 위치 | 트랜잭션 |
|---|---|---|
| 쿼터 행 준비 INSERT(cnt 0) | `service/impl/limit/UserLimitStrategies.kt:127-147` `ensureQuotaRow` — INSERT 영향 1행일 때 | 자동 커밋(즉시 등록) |
| counter 선점 `cnt + 1` | `UserLimitStrategies.kt:229-235` — 영향 1행일 때 | 선점 트랜잭션(afterCommit) |
| upsert 선점(counter-upsert · 기본 counter-upsert-precheck) | `UserLimitStrategies.kt:265-272` `upsertQuota` — 영향 1행일 때(INSERT 갈래 포함) | 선점 트랜잭션 |
| counter-trigger(B) 선점 행 만들기·잠금 | `UserLimitStrategies.kt:338-349` `acquire`(행 증가는 V8 트리거가 같은 트랜잭션에서 한다) | 선점 트랜잭션 |
| counter-trigger(B) 만료 | `UserLimitStrategies.kt:351-373` — 쌍마다 | 만료 트랜잭션 |
| 만료 감소(A·옛 경로) | `service/impl/drift/ExpiryCounters.kt:44-52`·`:79-90` — 쌍마다 | 만료 트랜잭션(`ExpireHoldsService.kt:31`) |
| 복구 INSERT·UPDATE(A·C·F·증분·안전망) | core `PairRepair.kt:74-84` → `QuotaChangeListener` | 복구 트랜잭션 |
| 확정(실제 값 쪽만 바뀜 — §2.3 '앱 경로면 봄') | `service/impl/ConfirmReservationService.kt:30-55` — `seat.scheduleId`·`command.userId` | 확정 트랜잭션 |
| 측정 전용 앱 경로 주입(4.2.7) | 위 선점·만료 경로 안 | 같은 트랜잭션(정상 경로 등록이 이미 있음) |

  등록하지 않는 경로: 정리 삭제(`QuotaCleanup.kt:133`·`:194-204` — 지운 쌍은 쿼터 행이 없어 증분 대상이 아니다, §3-3) · 시드 `TRUNCATE`(`loadtest/LoadtestDataService.kt:41-43` — 집합에 남은 쌍은 다음 틱에 행이 없어 그냥 빠진다) · 쿼터 잠금만 하는 방식(quota-lock·quota-nowait — cnt를 쓰지 않음).
- **꺼내기**: 틱 시작에 집합의 키를 하나씩 `remove`로 꺼낸 **뒤에** DB를 읽는다(꺼낸 뒤 커밋된 변경은 다시 등록되어 다음 틱이 본다). 꺼낸 쌍의 탐지·복구가 끝나기 전에 실패하면(청크 실패 · 복구 skipped) 그 쌍을 다시 넣는다(§3-1). 다시 넣기도 상한을 따른다.
- **상한·넘침**: 크기가 `memory-cap` 이상이면 넣지 않는다. 대신 ① 지표 `…incremental.memory.overflow` +1 ② 넘친 첫 쌍과 10초마다 요약을 WARN 로그 ③ 안전망에 '앞당김'을 요청(4.2.5 — 다음 실행을 바로 시작). 넘친 쌍은 안전망 몫이 된다 — 새 행이 생긴 쌍이면 최근 창이, 아니면(만료 감소 등) 전체 커서가 잡는다(탐지가 전체 한 바퀴만큼 늦어질 수 있다 — 기록으로 드러냄).
- **재시작**: 집합은 사라진다. 기동 때 INFO 로그 1행만 남기고, 그 사이 변경은 안전망 몫이다(§2.3 '잃음').

**ⓑ updated_at — DB 트리거**:
- 마이그레이션 `db/migration-updated-at-trigger/V9__quota_updated_at_trigger.sql`:
  - `ALTER TABLE user_hold_quota ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now()` — `now()`는 STABLE이라 기존 행은 다시 쓰지 않고(빠른 기본값) 마이그레이션 시각을 갖는다. INSERT 경로(upsert INSERT 갈래 · 행 만들기 · `PairRepair` INSERT · 직접 INSERT)는 이 기본값으로 덮인다(§2.3).
  - `CREATE INDEX idx_user_hold_quota_updated_at ON user_hold_quota (updated_at)` — B-tree.
  - 함수 `trg_quota_touch()`(`NEW.updated_at := now(); RETURN NEW`) + `CREATE TRIGGER user_hold_quota_touch BEFORE UPDATE ON user_hold_quota FOR EACH ROW`. 조건(WHEN)을 두지 않는다 — 모든 UPDATE(앱 · V8 `quota_add`(`V8__counter_trigger.sql:135-146`) · 직접 SQL · S3e L1·L2·H — `k6/ADR-009/scripts/lib.sh:545-546`)가 표시를 남긴다. ON CONFLICT DO UPDATE도 BEFORE UPDATE 트리거를 탄다. 행 삭제(S3e D)는 표시가 없다(§2.3 — 안전망 몫).
- HOT(Heap-Only Tuple — 인덱스 열이 안 바뀌면 인덱스를 고치지 않는 갱신) 영향: 모든 cnt 갱신이 인덱스 열 `updated_at`도 바꾸므로 `user_hold_quota`의 HOT 갱신은 사실상 0이 된다. 갱신마다 PK 인덱스와 `updated_at` 인덱스에 새 항목이 생긴다. 이것이 R5(updated_at 선점 비용)가 재는 비용이다 — 하네스 ⑧(`n_tup_hot_upd` · 인덱스 크기)로 기록한다.
- 인덱스 대안: BRIN — 기각. PostgreSQL 16은 요약 인덱스(BRIN)만 바뀌는 갱신에 HOT을 허용하지만, 갱신된 행이 제자리(같은 페이지)에 남아 페이지마다 `updated_at` 범위가 [오래된 값, 지금]으로 넓어진다 — 창 조회가 거의 모든 페이지를 읽는다. 인덱스 없음 — 기각(틱마다 766만 행 순차 읽기).

**ⓒ updated_at — 앱 문장**:
- 마이그레이션 `db/migration-updated-at/V9__quota_updated_at.sql` = ⓑ 파일에서 트리거·함수만 뺀 것(열 + 인덱스 — DDL 문자열이 같다. blind 테스트가 두 위치의 열·인덱스 정의를 카탈로그로 비교해 같음을 고정한다).
- `method=stmt`일 때만 아래 문장이 `updated_at = now()`를 함께 쓰는 별도 상수로 바뀐다(아니면 지금 문자열 그대로):
  1. `UserLimitStrategies.kt:231` counter `UPDATE … SET cnt = cnt + 1` → `, updated_at = now()`
  2. `UserLimitStrategies.kt:267-269` upsert `DO UPDATE SET cnt = user_hold_quota.cnt + 1` → `, updated_at = now()`(INSERT 갈래는 기본값)
  3. `ExpiryCounters.kt:48` 가드 감소 · `:83` 옛 감소 → `, updated_at = now()`
  4. core `PairRepair.kt:83` 복구 UPDATE → `, updated_at = now()`(`:76` INSERT는 기본값)
  5. INSERT 문장(`UserLimitStrategies.kt:133-146` · `:377-380`)은 바꾸지 않는다 — 기본값이 덮는다.
- **덮지 못하는 것**: V8 `quota_add`(B의 트리거 함수 — V8 무변경 원칙)와 직접 SQL. §2.3 표 그대로다. 기동 검사가 `method=stmt` + `limit-strategy=counter-trigger` 조합을 경고한다(B의 cnt 변경은 증분이 못 본다 — 막지는 않음).

**V9를 선택 위치에 두는 이유**(명세 §2 'updated_at을 쓰지 않는 방식에서도 V9 적용 상태가 동작에 영향이 없어야'):
- 고른 것: **같은 버전 V9를 두 선택 위치에 하나씩**(`migration-updated-at` · `migration-updated-at-trigger`). ⓑ 셀은 트리거 쪽, ⓒ 셀은 문장 쪽만 더한다. 둘 다 더하면 Flyway가 '같은 버전 2개'로 기동을 멈춘다(조용히 섞이지 않음). 손잡이가 없으면 스키마가 지금과 같아 §3-4가 그대로 성립한다. ⓐ·noc·M0 셀에는 열·인덱스·트리거가 없다.
- 고르지 않은 것: ① V9를 `db/migration`에 늘 적용(열 + 인덱스) — 인덱스 유지 비용(새 행마다 항목)이 noc·M0·ⓐ 셀에도 붙어 1단계 기준선과 비교가 흐려지고, 앱 설정 기록(`app-config.json`의 `db_indexes`)이 바뀐다. ② 트리거를 늘 만들고 세션 설정으로 켬 — 직접 SQL(S3e)은 세션 설정이 없어 ⓑ가 봐야 할 DB 직접 수정을 놓친다. ③ V9(열) + V10(트리거) 분리 — 명세가 'V9 = 열 + 인덱스(+ 트리거 함수)'로 정했고, 위치 2개를 함께 더해야 하는 조합 실수가 생긴다.
- 한계: 이미 V9를 적용한 DB를 다른 위치로 다시 띄우면 Flyway 검증이 실패한다(측정은 회차마다 새 DB라 해당 없음). 운영 DB에 적용할 때 큰 테이블의 `CREATE INDEX`는 쓰기를 막는다 — `CONCURRENTLY`는 트랜잭션 밖 마이그레이션이 필요하다(ADR-014에서 다룬다).

#### 4.2.3 창(watermark) 규칙과 증분 틱

**ⓑⓒ 창**(core `UpdatedAtCandidates`): 시계는 모두 DB 시계다. 앱·외부 앱(.158)의 시계는 창에 쓰지 않는다.
- 틱 k는 문장 두 개를 **이 순서로** 보낸다(각각 자동 커밋 — 빌림 2):
  1. `SELECT now() AS t, (SELECT min(xact_start) FROM pg_stat_activity WHERE datname = current_database() AND backend_type = 'client backend' AND pid <> pg_backend_pid() AND xact_start IS NOT NULL) AS o`
  2. `SELECT schedule_id, user_id FROM user_hold_quota WHERE updated_at >= ? ORDER BY schedule_id, user_id` — 인자 = 앞 틱이 정한 하한 `L_k`.
- 다음 틱 하한 `L_{k+1} = max(min(t, o) − 1초, t − max-overlap)`. 첫 틱 하한 = 기동 시 `now() − start-lookback`.
- **유실 없음의 근거**(§3-1): `updated_at = now()` = 쓴 트랜잭션의 시작 시각이다. 문장 2의 스냅숏에 안 보인 커밋은 ① 문장 1 때 이미 진행 중이었거나(그 시작 시각 ≥ `o`) ② 문장 1 뒤에 시작했다(시작 시각 ≥ `t`). 그래서 `min(t, o)` 이상인 행만 다음 틱이 다시 읽으면 놓치지 않는다. **문장 1을 먼저 보내는 것이 핵심**이다 — 한 문장에 묶으면 스냅숏을 잡은 뒤 `pg_stat_activity`를 읽는 사이에 커밋한 트랜잭션이 양쪽 모두에서 빠질 수 있다. `− 1초`는 트랜잭션 시작 시각이 `pg_stat_activity`에 늦게 보이는 틈의 여유다 `[구현 검증]`.
- **겹침 상한**: 오래 열린 세션(남겨 둔 psql · 긴 만료 틱)이 `o`를 붙잡으면 창이 끝없이 커진다. 그래서 하한은 `t − max-overlap`(60초)보다 뒤로 가지 않는다. 상한에 걸린 틱은 `…incremental.window_clamped` +1 · WARN 로그(가장 오래된 `xact_start`와 그 차이) — 60초보다 긴 트랜잭션의 변경은 안전망 몫이 된다(§7-2와 같은 종류의 한계 — 무음 아님).
- 중복: 틱 안에서는 PK라 쌍이 한 번씩이다. 겹침 때문에 앞 틱이 본 쌍을 다시 볼 수 있다 — 같은 쌍을 다시 대조할 뿐 결과는 같다(맞으면 아무것도 안 함).

**ⓐ 꺼내기**: 4.2.2. 창이 없다(등록이 커밋 뒤라 늦게 커밋한 변경도 커밋 순간 들어온다).

**증분 틱 한 번**(core `IncrementalReconciler.tick(source)` — 공개 함수, 스케줄러가 부르고 테스트도 부른다):
1. 후보 쌍을 받는다(`CandidateSource`).
2. 후보의 회차 중 열린 회차만 남긴다 — `SELECT id FROM product_schedule WHERE id = ANY(?) AND starts_at > ?`(앱 `Clock` — 지금 C의 `CounterReconciler.kt:122-142`와 같은 기준). 빠진 회차·쌍 수는 기록의 `skipped_schedules`·`skipped_pairs`.
3. 회차별로 사용자를 정렬해 k명(`chunkSize`)씩 `ChunkDetector.detect(s, Users(U))`(4.2.4).
4. 어긋나 보인 쌍을 (회차, 사용자) 순으로 `PairRepairRunner`(= 지금 `repairOne` — 쌍마다 트랜잭션 · `lock_timeout` · 마감 재확인 · `PairRepair`)로 고친다.
5. `counter_reconcile_run` 1행(4.2.6). 후보가 0이어도 쓴다(틱 결손을 하네스가 볼 수 있게).
- 주기: `scheduleWithFixedDelay(interval)` — 틱이 주기보다 길면 다음 틱은 끝난 뒤 시작한다(겹쳐 돌지 않음).

**§3 불변식과의 대응**:

| 불변식 | 이 설계에서 지키는 곳 |
|---|---|
| 1 유실 없음 | ⓑⓒ: 문장 1 → 문장 2 순서와 `min(t, o) − 1초` 하한(상한에 걸리면 기록) · ⓐ: afterCommit 등록 · 꺼낸 뒤 읽기 · 실패 시 다시 넣기 · 넘침 기록 + 안전망 앞당김 |
| 2 복구 규칙 그대로 | 증분·안전망 모두 `PairRepairRunner` → `PairRepair`(쿼터 행 `FOR UPDATE` → 다시 세기). 쌍마다 트랜잭션 · 여러 쌍을 한 트랜잭션에서 잠그지 않음 · 잠금 순서(회차 `FOR SHARE` → 쿼터 행)는 지금 C와 같다 — 만료 A·정리 ①과의 순서도 그대로다. 증분과 안전망이 같은 쌍을 동시에 보면 늦은 쪽은 잠근 뒤 맞은 값을 보고 false-positive로 남는다(덮어쓰지 않음 — 오탐 복구 0) |
| 3 행 없음 갈래 | 증분은 쿼터 행이 있는 쌍만 본다(`Users` 탐지는 행 없는 사용자를 내지 않는다 — 4.2.4). 행 없음은 안전망의 최근 창(행 없음 문장)과 전체 커서 ②가 잡는다. upsert INSERT로 행이 다시 생긴 쌍은 ⓐ 등록 · ⓑⓒ 기본값으로 후보가 된다 |
| 4 미지정 = 이전과 같음 | `method=none` · `enabled=false` · `safety-net.enabled=false`가 기본. 문장 문자열·빈 스케줄·마이그레이션 위치·지표가 지금과 같다 |

#### 4.2.4 탐지 모양과의 결합

R8(§4.1) 결과는 아직 없다. 그래서 증분·안전망은 **전체 대조와 같은 탐지기**를 쓰고, 모양은 기존 손잡이 `detect-shape` 하나로 셋 모두에 적용한다(R8 승자를 그 값으로 고르면 끝 — 코드 변경 없음).

```kotlin
// core — 지금 CounterReconciler.kt:192-295를 옮겨 일반화한다
sealed interface QuotaSelector {
    data class Keyset(val afterUser: Long, val k: Int) : QuotaSelector   // 전체 대조 ① · 안전망 전체 커서 ①
    class Users(val users: LongArray) : QuotaSelector                     // 증분 · 안전망 최근 창 — 정렬됨, 크기 ≤ k
}
class DetectedChunk(val scanned: Int, val lastUser: Long, val mismatches: List<PairObservation>)
interface ChunkDetector { fun detect(scheduleId: Long, selector: QuotaSelector): DetectedChunk }
// PairObservation(scheduleId, userId, cnt: Int?, actual: Int) = 지금 private Observation(:88)
```

- 구현은 모양 3가지(`OneShotDetector` · `SplitDetector` · `GroupDetector`)다. `Keyset`은 지금 문장 그대로(`CHUNK_SQL` :416-427 · `QUOTA_KEYSET_SQL` :430-432). `Users`는 쿼터 행 조회만 `WHERE schedule_id = ? AND user_id = ANY(?)`로 바뀐 새 상수(one-shot은 `CHUNK_SQL`의 `q` CTE만 바뀐 문장 · split·group은 ⓐ 조회만 바뀌고 ⓑⓒ 홀드·예약 조회(:435-441)는 같다).
- 계약(모양 무관): 청크 하나 = 한 스냅숏(one-shot은 한 문장, split·group은 탐지 전용 읽기 전용 REPEATABLE READ 트랜잭션 — §4.1) = 빌림 1 · 실패하면 예외(부분 결과 없음 — 호출자가 `stage=chunk`로 기록하고 그 청크의 쌍을 처리하지 않는다. 증분이면 ⓐ 쌍을 다시 넣는다) · `Users`에서 쿼터 행이 없는 사용자는 결과에 없다(`scanned` = 찾은 행 수) · 실제 값 = 홀드 + CONFIRMED · `toIntExact`.
- 호출자 4곳: 전체 대조 C(`Keyset` — 기본 경로 문장·순서 무변경) · 증분(`Users`) · 안전망 최근 창(`Users`) · 안전망 전체 커서 ①(`Keyset` — 회차·커서를 실행 사이에 이어 감).
- 후보를 읽는 ⓑⓒ 문장 2와 탐지를 한 문장으로 합치는 안은 기각 — 탐지 모양(R8)이 끼어들 자리가 없어지고, 증분만 다른 비교 쿼리를 갖게 된다(§2.1 공통 '같은 의미의 쿼리').

#### 4.2.5 안전망 — 예산 있는 이어서 훑기 + 최근 2시간 창

**손잡이**(`seat.hold.counter-drift.reconcile.safety-net.*` — 기본 `enabled=false`): `interval=1m` · `budget=2s` · `recent-window=2h` · `reread=30s` · `recent-id-chunk=10000` · `lap-warn=1h` · `sample-interval=10s`. 값은 §2.1의 측정 전 고정값이다. 안전망과 전체 대조 C(`reconcile.enabled=true`)를 함께 켜면 기동 실패(전체 훑기 두 개).

**누가 돌리나**: M2(앱 안 증분) 셀 = 업무 앱 · **M3(외부 앱 증분) 셀 = 외부 앱**(rv36 RA-9에서 미룬 결정). M3의 업무 앱은 A·정리만 돈다(F 조건과 같은 `external-reconciler=true`). 이유: M3의 질문은 '대조의 JVM 일을 앱 밖으로 옮기면 같은가'이고, 안전망도 그 대조의 일부다. 안전망을 업무 앱에 남기면 M2 대 M3 차이가 증분 틱뿐이 된다.

**스레드**: 단일 스레드 스케줄러 하나에 두 일을 건다 — ① id–시각 표본(`sample-interval` 고정 지연) ② 안전망 실행(`interval` 고정 지연 + '앞당김' 요청 시 바로 1회, 앞당김은 10초에 1번까지). 같은 스레드라 겹쳐 돌지 않는다.

**id–시각 표본**(메모리 고리 — 2시간 10분치, 약 780개): `SELECT now(), (SELECT max(id) FROM seat_hold), (SELECT max(id) FROM reservation)`(빌림 1 · 안전망 빌림으로 셈). `id_at(T)` = 시각 ≤ T인 가장 늦은 표본의 최대 id(표본이 안전망 실행 때문에 늦어져도 더 작은 id를 고르게 되어 보수적이다). 고리는 저장하지 않는다 — 재시작하면 비어 있다.

**예산 = 실행 전체의 DB 시간**: 이 실행이 커넥션을 빌린 모든 블록(탐지 청크 · 최근 창 id 범위 조회 · 행 없음 문장 · `PairRepair` 복구 트랜잭션 · 기록 INSERT)의 벽시계 합이다(앱 쪽 `System.nanoTime`으로 `borrow { }`를 감싼다). 풀 대기와 왕복(외부 앱이면 .158 ↔ .164)이 들어간다 — DB 서버 CPU 시간이 아니다. 실행의 벽시계(시작~끝)는 따로 남긴다.

**실행 한 번의 순서**:
1. 열린 회차 목록(`SELECT id FROM product_schedule WHERE starts_at > ? ORDER BY id` — 빌림 1). 지금 C의 회차 조회(:127-136)와 달리 마감 회차의 쿼터 행 수를 세지 않는다(그 count가 마감 회차 행 수에 비례하기 때문 — 기록의 `skipped_pairs`는 0).
2. **다시 읽기 구역**(앞 실행이 남김): 앞 실행 k가 최근 창 커서로 지나간 id 중 '그때 30초 안에 할당된 것' = `(max(c_{k-1}, id_at(T_k − reread)), c_k]`(`T_k` = 실행 k 시작 DB 시각 · `c` = 실행 끝의 커서). 이번 실행이 맨 먼저 이 구역을 다시 읽는다. 커서가 밀려 있으면(지나간 id가 모두 30초보다 오래됨) 구역은 비어 있다. 이 구역도 2시간 경계 밖이면 버리고 기록한다.
3. **최근 창**(예산 앞 절반 = 1초): 하한 `B = id_at(now − recent-window)`. 커서 `c`(홀드·예약 각각)가 `B`보다 작으면 `B`로 건너뛰고 건너뛴 범위 `[c, B)`를 기록·WARN·지표(전체 커서 몫 — 따로 할 일은 없다, 전체 커서가 모든 id를 돈다). 커서부터 `recent-id-chunk` 폭씩(홀드 한 청크 · 예약 한 청크 번갈아):
   - (가) 행 없음: 지금 `ORPHAN_HOLD_SQL`·`ORPHAN_RESERVATION_SQL`(:444-461)을 그 id 범위로 — 쿼터 행 없는 쌍 = `cnt = null` 관측.
   - (나) 행 있음: `SELECT DISTINCT schedule_id, user_id FROM seat_hold WHERE id > ? AND id <= ? AND schedule_id = ANY(열린 회차)`(예약은 `status = 'CONFIRMED'` 더함) → 회차별 `ChunkDetector.detect(s, Users(U))`.
   - 청크 하나(가 또는 나의 탐지 청크 하나)가 끝날 때마다 예산을 본다. 커서가 최신 id(실행 시작 표본)에 닿으면 남은 앞 절반은 전체 커서로 넘긴다.
4. **전체 커서**(남은 예산): 지금 C의 두 갈래를 멈춘 곳부터 이어 간다 — ① 쿼터 행 `(schedule_id, user_id)` keyset(`Keyset` 탐지 — 열린 회차를 회차 id 순으로) ② 행 없음 id 범위(홀드 → 예약, 폭 `orphanChunkSize`). ①과 ②를 청크 하나씩 번갈아 진행한다(비율 `[구현 검증]`). 두 갈래가 모두 끝(① 마지막 열린 회차 · ② 바퀴 시작 때의 최대 id)에 닿으면 **한 바퀴**가 끝난다 — 바퀴 시간(바퀴 시작 실행의 DB 시각 → 끝 실행의 DB 시각)을 기록하고 커서를 처음으로 돌린다. 진행 중 바퀴 나이가 `lap-warn`(1시간)을 넘으면 WARN 1회 + 지표.
5. 예산을 넘으면 그 청크까지만 하고 탐지를 멈춘다. **이 실행에서 찾은 쌍은 모두 이 실행에서 고친다**(복구를 다음 실행으로 미루지 않는다 — R7 반증 조건이 '+ 그 실행의 복구 시간'을 허용한다). 복구 순서 = (회차, 사용자).
6. 기록: `counter_reconcile_run` 1행 + `counter_safety_run` 1행(같은 `run_id`).

**청크 ≤ 200ms**: 최근 창 id 폭 10,000(지금 행 없음 갈래 폭과 같음 — 1단계 끝에서 두 번째 바퀴 ② 750청크 7.2~11.4초 ≈ 청크당 10~15ms)과 탐지 청크 k = 1,000(① 청크당 약 1.8ms)이 출발값이다. 스모크에서 청크 최대를 보고 고친다 `[구현 검증]`.

**커서 의미**: 모든 커서는 메모리에만 있다(저장 안 함 — 저장할 테이블을 두면 손잡이 꺼짐 때도 스키마가 바뀐다). 재시작하면 전체 커서는 처음부터, 최근 창 커서는 기동 때 최대 id부터 시작하고 '기동 — 최근 창 시작점 = 기동 시 최대 id' WARN과 `restart=true` 기록을 남긴다(그 앞의 id는 전체 커서 몫). 시드 초기화(`TRUNCATE … RESTART IDENTITY`)로 최대 id가 커서보다 작아지면 커서를 0으로 되돌리고 WARN을 남긴다.

**행 없음 갈래 ②와의 관계**: 행 없음 쌍(D)은 최근 창 (가)(최근 2시간 안에 생긴 홀드·확정 예약의 쌍)와 전체 커서 ②(그 밖)가 잡는다. 두 곳 모두 지금 C의 행 없음 문장을 범위만 바꿔 쓴다.

**만료 A와의 관계**: A는 만료 트랜잭션 안에서 `PairRepair`(감소 예정 수 포함 — `ExpiryCounters.kt:72-74`)를 쓴다. 안전망·증분은 쌍마다 자기 트랜잭션에서 같은 `PairRepair`를 쓴다. 같은 쿼터 행 `FOR UPDATE`가 직렬화한다 — 늦은 쪽은 앞쪽 커밋 뒤 값으로 다시 센다(§3-2). 안전망의 쿼터 행 잠금은 한 번에 하나라 만료 틱의 정렬 잠금과 교착하지 않는다(지금 C와 같음).

**저장(새 테이블)**: `db/migration-incremental/V10__counter_safety_run.sql`(선택 위치 — 증분 또는 안전망 셀만 더한다. 기동 검사가 확인). 명세 §2는 'V9 = 쿼터 열만'이었다 — **사용자 합의 2026-10-20: '만든다 — 선택 위치 V10'**(명세 §0·§2 재합의 행 · 고르지 않은 대안: 테이블 없이 `counter_reconcile_run` 기존 열 + 지표 + 로그만 — R7의 '실행 DB 시간 · 복구 시간'과 최근 창 적체를 회차 끝 DB에서 못 읽는다).

| 열 | 뜻 |
|---|---|
| `run_id` BIGINT | `counter_reconcile_run.id`(FK 없음 — V7과 같은 이유) |
| `db_started_at` TIMESTAMPTZ | 실행 시작 DB 시각(창·표본 기준) |
| `budget_ms` · `db_ms_total` · `db_ms_recent` · `db_ms_full` · `repair_ms` · `record_ms` · `chunk_ms_max` | 예산과 갈래별 DB 시간(R7) |
| `recent_hold_cursor` · `recent_res_cursor` · `hold_head` · `res_head` | 최근 창 커서와 최신 id(적체 = head − cursor) |
| `recent_cursor_age_s` | 커서 id의 나이(표본 고리로 추정 · 표본이 없으면 NULL) |
| `recent_skip_from` · `recent_skip_to` · `recent_skip_ids` | 이번에 2시간 경계로 건너뛴 범위(없으면 NULL·0 — 홀드·예약 합) |
| `reread_ids` | 다시 읽기 구역 크기 |
| `full_schedule` · `full_user` · `full_orphan_hold` · `full_orphan_res` | 전체 커서 위치 |
| `lap_no` · `lap_completed` · `lap_seconds` · `lap_age_s` | 바퀴 번호 · 이번에 끝났나 · 끝났으면 바퀴 시간 · 진행 중 바퀴 나이 |
| `expedited` · `restart` | 앞당김으로 시작했나 · 기동 뒤 첫 실행인가 |

#### 4.2.6 source 값 · 기록 · 지표

**source**(`counter_reconcile_run.source`·`counter_drift_log.source` — VARCHAR(30)):

| 값 | 쓰는 곳 |
|---|---|
| `inc-app-mem-1s` · `inc-app-mem-5s` | M2 ⓐ 증분 틱 |
| `inc-app-trg-1s` · `inc-app-trg-5s` | M2 ⓑ |
| `inc-app-stmt-1s` · `inc-app-stmt-5s` | M2 ⓒ |
| `inc-ext-trg-1s` · `inc-ext-trg-5s` · `inc-ext-stmt-1s` · `inc-ext-stmt-5s` | M3 증분 틱(외부 앱) |
| `safety-app` · `safety-ext` | 안전망(업무 앱 · 외부 앱) |
| (그대로) `reconcile-app` · `reconcile-proc` · `expiry-repair` · `expiry-trigger` | 지금 값 |

- 주기 꼬리표는 `interval`에서 만든다(정수 초면 `Ns`, 아니면 `Nms`). 기동 로그에 source를 남긴다(`CounterDriftScheduling.kt:37`과 같은 방식).
- 증분 틱의 `counter_reconcile_run` 열: `schedules` = 후보의 열린 회차 수 · `chunks` = 탐지 청크 수 · `pairs_scanned` = 후보 쌍 수 · `orphan_chunks` = 0 · `chunk_ms_total` = 후보 읽기 + 탐지 · `orphan_ms_total` = 0 · 나머지는 지금과 같은 뜻. `started_at`·`finished_at`은 지금처럼 앱 시계다(외부 앱이면 .158 시계 — 하네스 ⑥의 시계 차로 맞춘다).
- 안전망의 `counter_reconcile_run` 열: `chunks` = 최근 창 (나) + 전체 ① 탐지 청크 · `orphan_chunks` = 최근 창 (가) + 전체 ② · `chunk_ms_total`·`orphan_ms_total`도 같은 묶음 · `skipped_pairs` = 0(세지 않음).
- `counter_drift_log.at`은 지금처럼 기록하는 앱의 시계다(바꾸면 손잡이 꺼짐 때 동작이 바뀐다).

**지표**(새 이름 — 기존 지표 무변경):

| 이름 | 종류 | 뜻 |
|---|---|---|
| `seat.hold.counter.reconcile.incremental.tick` | timer | 틱 벽시계 |
| `seat.hold.counter.reconcile.incremental.borrows` | counter | 틱의 커넥션 빌림(기록 INSERT 포함) |
| `seat.hold.counter.reconcile.incremental.candidates` | counter | 후보 쌍 |
| `seat.hold.counter.reconcile.incremental.window_clamped` | counter | ⓑⓒ 겹침 상한에 걸린 틱 |
| `seat.hold.counter.reconcile.incremental.errors{stage}` | counter | 후보·청크·기록 실패 |
| `seat.hold.counter.reconcile.incremental.memory.size` · `.memory.overflow` · `.memory.requeued` | gauge · counter · counter | ⓐ 집합 |
| `seat.hold.counter.safety.run` · `.safety.db_ms` | timer · summary | 실행 벽시계 · 실행 DB 시간 |
| `seat.hold.counter.safety.borrows` | counter | 안전망 빌림(표본 포함) |
| `seat.hold.counter.safety.recent.lag_ids` · `.recent.cursor_age_seconds` | gauge | 최근 창 적체 |
| `seat.hold.counter.safety.recent.skipped_ids` | counter | 2시간 경계 건너뜀 |
| `seat.hold.counter.safety.lap_seconds` · `.lap_overdue` · `.expedited` · `.errors{stage}` | gauge · counter · counter · counter | 바퀴 · 1시간 초과 · 앞당김 · 실패 |

**하네스 ⑤에 필요한 변경(목록만 — 이 task에서 고치지 않는다)**: summarize의 기대 출처(`RECONCILE_SOURCE` — `summarize.py:750`)에 새 조건 → {증분 source, 안전망 source} · 효과 셀 허용 출처(`summarize.py:1105` `allowed`)에 같은 두 값 + `expiry-repair` · L2·H 복구 출처 검사(:1108-1109)를 '그 셀의 증분 또는 안전망'으로 · `borrow_per_request`(:649-680)의 차감 키에 `incremental_borrows`·`safety_borrows` 추가(업무 앱 풀만 — M3 외부 앱의 빌림은 업무 앱 Hikari에 없으므로 빼지 않는다) · run.sh가 회차 끝 지표에서 두 키를 모은다.

#### 4.2.7 측정 전용 앱 경로 주입

- 손잡이 `seat.hold.loadtest.inject-app.*`: `enabled=false`(기본) · `every=50`(쌍 선택: `(scheduleId × 31 + userId) mod every == 0`) · `max-pairs=60` · `paths=hold,expiry`. 빈 `AppPathInjector`는 `@Profile("loadtest")`에만 있다(loadtest 프로필이 아니면 빈이 없어 키를 켜도 아무것도 안 하고, 기동 검사가 '켰는데 프로필 없음'을 실패로 낸다). 쌍마다 한 번만 주입한다(주입한 쌍 집합 · 원자적 개수).
- **선점 경로**: `upsertQuota`(`UserLimitStrategies.kt:265-272`)와 counter `acquire`(:229-235)가 1행을 올린 직후, 같은 트랜잭션에서 고른 쌍에 `UPDATE user_hold_quota SET cnt = cnt + δ WHERE …` — δ는 쌍마다 번갈아 +1(종류 `AH` — cnt > 실제) · −1(`AL` — 방금 올린 뒤라 cnt ≥ 1, 결과 = 실제 − 1 ≥ 0). 선점이 뒤에서 롤백되면(좌석 실패 등) 주입도 함께 사라진다.
- **만료 경로**: A의 가드 감소(`ExpiryCounters.kt:47-51`)가 1행일 때 같은 트랜잭션에서 +1(`AE`). −1은 쓰지 않는다(CHECK 23514로 만료 틱 전체가 롤백된다).
- 주입 문장은 `updated_at`을 직접 쓰지 않는다. 표시는 정상 경로가 이미 남겼다(ⓐ 같은 트랜잭션 등록 · ⓒ 같은 트랜잭션 문장 · ⓑ 트리거가 같은 트랜잭션 시각으로 덮음 — 값이 같다). 그래서 ⓐⓑⓒ 모두 증분이 잡아야 한다(R6).
- **기록**: 같은 트랜잭션에서 `counter_app_inject`(V10과 같은 파일 — 같은 합의 대상)에 1행 — `id` · `at`(`clock_timestamp()` — DB 시계) · `path`(hold|expiry) · `kind`(AH|AL|AE) · `schedule_id` · `user_id` · `delta` · `cnt_after`. 커밋된 주입만 남는다.
- effect.json(하네스 ④): 주입 쌍마다 `at` 뒤 첫 `counter_drift_log`(action repaired)의 source·시각 → 경로(증분 `inc-*` · 안전망 `safety-*` · 만료 `expiry-repair`)와 탐지 시간. S3e DB 직접 주입(D·L1·L2·H)과 같은 표에 종류만 더한다. 주입 쌍은 '주입 밖 쌍 repaired'(`false-repair`)에서 뺀다.

#### 4.2.8 외부 앱(reconciler-app · .158) — 설계만

- 방식: ⓑ·ⓒ만(ⓐ는 기동 검사가 거부). 증분 + 안전망을 돈다(4.2.5). 정리·만료·전체 대조는 하지 않는다.
- 폴더: `reconciler-app/Dockerfile`(빌드 문맥 = 저장소 루트 — 루트 `Dockerfile`처럼 settings·`reconcile-core`·`reconciler-app`만 복사해 `:reconciler-app:bootJar` → `eclipse-temurin:21-jre`) · `reconciler-app/compose.yml`(서비스 `reconciler` 하나 — DB·Redis 없음).
- compose 모양: 프로젝트 `seatlab-reconciler` · 포트 `8103:8103`(actuator health·configprops·metrics만) · `cpus: ${RECONCILER_CPUS:-1}` · `mem_limit: ${RECONCILER_MEM:-1g}`(F와 같은 구성 — §8.3 'F는 꼬리를 키운다'를 같은 조건에서 본다) · JVM 옵션은 F와 같게 `[구현 검증]` · 환경: `DB_URL=jdbc:postgresql://<.164>:15432/seat?ApplicationName=seatlab-reconciler-ext` · `DB_USERNAME`·`DB_PASSWORD`는 **compose 파일에 값을 쓰지 않고** `${DB_USERNAME:?}`·`${DB_PASSWORD:?}`로 받는다 · `SPRING_APPLICATION_JSON`으로 방식·주기·탐지 모양만.
- Hikari: `maximum-pool-size=4`(증분 1 · 안전망 1 · 여유 2 — 동시 빌림은 스레드 2개뿐) · `application_name`은 URL의 `ApplicationName`(`pg_stat_activity`에서 업무 앱·F와 가른다 — 문장 1의 `o`에도 들어가는 세션이다).
- 하네스가 띄우고 내리는 법(PC가 지휘): ① PC에서 이미지 빌드(`seatlab-reconciler:<sha>`) → `docker save | ssh .158 docker load` ② `~/labs/seat-reservation-lab-reconciler/`에 compose 파일만 동기화 ③ 비밀번호는 표준 입력으로 넘긴다 — `ssh .158 'read -r DB_PASSWORD; export DB_PASSWORD; cd ~/labs/seat-reservation-lab-reconciler && docker compose -p seatlab-reconciler up -d'`(명령 인자·파일·셸 이력에 남지 않음) ④ 회차 끝·실패 시 `docker compose -p seatlab-reconciler down`을 정리 함정(trap)에 둔다 ⑤ 로그는 `docker compose logs`를 gz로 받는다. .158에서는 이 프로젝트의 컨테이너·이미지만 다룬다(명세 §4).

#### 4.2.9 하네스 ③~⑧ — 만들 것(목록만)

| 필요 | 만들 것 |
|---|---|
| ③ 증분 바퀴 기록 · source | run.sh 새 조건 `A+I`(A + 앱 안 증분 + 안전망) · `A+X`(A + 외부 앱) · 플래그 `--incremental mem|trg|stmt` · `--incremental-interval 1s|5s` — 조건·방식에 맞춰 `FLYWAY_LOCATIONS`(4.2.2)와 `SPRING_APPLICATION_JSON` 키를 지정할 때만 넣는다(미지정 = 렌더 그대로) · `--reconcile-shape`를 `A+I`·`A+X`에도 허용 · 설정 검사 `incremental-config-mismatch`(configprops `counterDrift.reconcile.incremental.*`·`safetyNet.enabled`) · 스키마 검사 `schema-mismatch`(열·트리거·테이블이 방식과 맞나) · `lib.sh wait_reconcile_run`에 새 source · drift-db.json에 `counter_safety_run` · tail.py ⑦에 source별 증분 틱 시간(R2 — 첫 5분 대 마지막 5분 중앙값)과 안전망 실행 DB 시간·청크 최대·복구 시간(R7) · 틱 결손 검사(`reconcile_gaps`)를 증분 주기로 · campaign 세트 `adr012s2`(2단계) · `adr012s2x`(2') · `adr012eff`(효과) |
| ④ 경로별 탐지 시간 | run.sh `--inject-app on`(효과 셀만 · loadtest 프로필) · lib.sh가 `counter_app_inject` 수집 · summarize effect.json에 종류 AH·AL·AE와 첫 탐지 출처·시간 · R4 시간 기준(증분 갈래 = 주기 + 10초 · 안전망 갈래 = 적체에 따라 2분 또는 2 × 바퀴 시간)으로 판정 |
| ⑤ 허용 출처 · 빌림 차감 | 4.2.6 끝의 목록 |
| ⑥ M3 표본 | .158 docker stats 표본기(`host-sampler.sh`를 ssh로) · 외부 앱 Hikari(8103 actuator `hikaricp.connections.*`) · .158 ↔ .164 시계 차(회차 시작·끝 — `counter_drift_log.at`·바퀴 시각 보정용) |
| ⑦ C 바퀴 중 Hikari pending | 1단계에 이미 있음(그대로) |
| ⑧ HOT · 인덱스 크기 | 회차 시작·끝 `pg_stat_user_tables`(`user_hold_quota`의 `n_tup_upd`·`n_tup_hot_upd`) · `pg_relation_size('idx_user_hold_quota_updated_at')` → tail.py R5 칸 |

#### 4.2.10 테스트 계약 (blind 테스트 워커 입력)

모두 관측 가능한 동작으로 적는다. 테스트는 공개 진입점(`IncrementalReconciler.tick(source)` · 안전망 `runOnce()` · 앱 API·직접 SQL)과 DB 결과(`counter_drift_log` · `counter_reconcile_run` · `counter_safety_run` · 쿼터 cnt)만 본다.

1. **커밋 지연에도 유실 없음(ⓑⓒ)**: 쿼터를 바꾼 트랜잭션을 틱 k의 읽기 전에 시작하고 틱 k 뒤에 커밋한다(여러 틱에 걸쳐 열어 둠 포함 — `max-overlap` 안). 그 쌍의 어긋남은 커밋 뒤 첫 틱이 잡는다.
2. **겹침 창**: 틱 사이에 커밋된 모든 변경(시작 시각이 앞 틱 읽기 전이든 뒤든)이 다음 틱 후보에 든다. `max-overlap`보다 오래 열린 세션이 있으면 그 틱에 `window_clamped` 기록이 남는다.
3. **커밋 뒤 등록(ⓐ)**: 롤백된 선점·만료의 쌍은 후보가 되지 않는다. 커밋 전에는 후보가 아니다 — 커밋 전 틱이 그 쌍을 '맞음'으로 보고 지우는 일이 없다(커밋 뒤 첫 틱이 잡는다). 자동 커밋 경로(쿼터 행 준비)도 등록된다.
4. **넘침**: 상한을 넘긴 등록은 넘침 지표·로그로 남고, 안전망이 바로 한 번 더 돈다. 넘친 쌍의 어긋남도 결국 고쳐진다(안전망).
5. **실패한 꺼냄은 되돌아감(ⓐ)**: 탐지 청크나 복구가 실패한 쌍은 다음 틱 후보에 다시 있다.
6. **증분과 안전망·A가 같은 쌍을 동시에**: 끝 cnt = 실제 · repaired 1 · 나머지는 false-positive · 덮어쓴 정답 0.
7. **행 없음**: 쿼터 행을 지운 쌍은 증분 기록에 나오지 않고 안전망(최근 창 또는 전체 ②) 기록으로 고쳐진다. upsert INSERT로 다시 생긴 행의 쌍(확정 예약이 있는 쌍)은 증분이 실제 값(홀드 + CONFIRMED)으로 고친다.
8. **재시작**: ⓐ — 재시작 전 등록은 사라지고, 그 어긋남은 안전망이 고친다. ⓑⓒ — `start-lookback` 안의 변경은 재시작 뒤 첫 틱이 잡는다. 안전망 — 기동 뒤 첫 실행 기록에 `restart = true`가 남는다.
9. **V9 있음/없음**: 손잡이 미지정 + V9 없음 = 지금 테스트 전부 그대로 · `method=none`에서 트리거 위치를 더하면 기동 실패 · `method=stmt`인데 열이 없으면 기동 실패 · 두 V9 위치를 함께 더하면 기동 실패 · 두 위치의 열·인덱스 정의가 같다 · ⓐ 셀에 문장 위치(V9 열)가 있어도 ⓐ 결과가 같다.
10. **ⓑ 직접 SQL**: 앱을 거치지 않은 `UPDATE … cnt = cnt − 1`·`+ 1`을 ⓑ 증분이 잡는다(source `inc-app-trg-*`). ⓒ·ⓐ 증분은 잡지 않는다(안전망이 잡는다).
11. **ⓒ 문장 범위**: `method=stmt`면 선점·만료·복구 뒤 `updated_at`이 그 트랜잭션 시각이다. `method ≠ stmt`면 그 문장들이 `updated_at`을 바꾸지 않는다.
12. **안전망 예산**: 실행 기록의 DB 시간 ≤ 예산 + 그 실행의 가장 긴 청크 + 그 실행의 복구 시간. 최근 창이 앞 절반을 먼저 쓴다. 다 보면 남은 예산이 전체 커서로 간다.
13. **안전망 이어서 훑기**: 여러 번 돌리면 전체 커서가 모든 열린 회차의 쿼터 행·행 없음 범위를 한 번씩 지나 바퀴가 끝나고 `lap_seconds`가 남는다. 바퀴 나이가 `lap-warn`을 넘으면 경고 기록이 남는다.
14. **최근 창 경계·다시 읽기**: 커서가 2시간 경계 밖이면 건너뛴 범위가 기록된다. 앞 실행이 지나간 뒤 늦게 커밋한 작은 id 행(30초 안 할당)은 다음 실행이 잡는다.
15. **미지정 = 이전과 같음**: 새 키 미지정이면 전체 대조 C의 문장·바퀴 기록·빌림 수·configprops의 기존 경로 값이 지금과 같고, 증분·안전망 스케줄러·새 지표가 없다.
16. **외부 앱 동등성**: 같은 DB 상태에서 외부 앱의 증분·안전망이 앱 안과 같은 쌍을 같은 결과로 고친다(source만 `inc-ext-*`·`safety-ext`). 외부 앱은 `method=memory`·`reconcile.enabled=true`를 기동 때 거부한다.
17. **탐지 모양 동등성(증분)**: 같은 스냅숏에서 `Users` 선택자의 one-shot·split·group이 같은 어긋난 쌍 집합을 낸다(§4.1 계약을 `Users`로 넓힌 것). 쿼터 행 없는 사용자는 결과에 없다.
18. **앱 경로 주입**: loadtest 프로필 + 손잡이 켬에서만 주입되고, 주입마다 `counter_app_inject` 1행이 남는다. 롤백된 선점의 주입은 없다. 주입 쌍은 ⓐⓑⓒ 모두 증분 source로 먼저 고쳐진다.

#### 4.2.11 `[구현 검증]` 목록

1. core 모듈의 Spring Boot BOM 적용 방식(부트 플러그인 없는 모듈) · 같은 패키지가 두 jar에 나뉜 상태의 컴포넌트 스캔.
2. ⓐ 상한 `memory-cap` 200,000의 메모리(쌍당 바이트) — 1초·5초 유입(초당 약 4,200쌍) 대비 여유.
3. ⓑⓒ 하한 여유 `− 1초` — 트랜잭션 시작 시각이 `pg_stat_activity`에 보이는 지연(일부러 늦게 커밋하는 스모크로 확인).
4. 최근 창 id 폭 10,000 · 탐지 k 1,000 · 전체 커서 ①②의 번갈아 비율 — 청크 ≤ 200ms와 바퀴 시간(스모크 EXPLAIN · 실행 기록).
5. 외부 앱(`DataSourceTransactionManager`)과 업무 앱(JPA)의 탐지 트랜잭션 왕복 차이 — R8 승자가 split·group일 때 M2 대 M3 해석.
6. .158 → .164:15432 도달 · 문장당 왕복 · Hikari 4의 충분함(연결 스모크 — 명세 가정 2).
7. 이미지 전송(`docker save | ssh docker load`) 시간과 .158 디스크.
8. 외부 앱 JVM 옵션을 F와 같게 맞추는 방법(F는 업무 앱 이미지·`JAVA_TOOL_OPTIONS`를 물려받는다).
9. 효과 셀 조건 '마지막 주입 + 전체 한 바퀴 시간 < 끝 검사'(§4 효과 셀) — 스모크 실측 바퀴 시간으로.
10. 앞당김(10초에 1번) 아래에서 넘침이 계속될 때 안전망 실행 간격과 R7 예산의 관계.

## 5. 가설 (측정 전 고정)

| # | 가설 | 틀렸다고 판정할 관측 |
|---|------|------------------|
| R1 | C 바퀴의 비용은 DB 쪽이다 — F(별도 JVM·풀)로 옮겨도 바퀴가 짧아지지 않는다 | 아래 표에서 'F 낮음' |
| R2 | 증분 바퀴 시간은 쌓인 행 수와 무관하다 | 증분 셀에서 정상 구간 첫 5분 대비 마지막 5분의 증분 바퀴 시간 중앙값이 2배 이상 **그리고** +20ms 이상(3회 중 하나라도) · 메모리 집합의 '바퀴 시간' = 집합에서 꺼낸 뒤 비교·복구가 끝날 때까지 |
| R3 | 증분 + 안전망(예산 이어서 훑기)의 후반 꼬리는 noc와 같다 | 후반 서버형 느린 초 또는 후반 1초 p99 최대의 3회 범위가 같은 캠페인 noc 범위와 겹치지 않는다(위로). **판정 유효 조건**: 같은 캠페인의 M0 C 후반 서버형 느린 초 범위가 noc보다 위로 겹치지 않을 때만 판정 — 아니면 R3 판정 불가(그 캠페인으로 기본값을 바꾸지 않는다) |
| R4 | 증분 + 안전망은 ADR-009 효과 기준을 지킨다 | S3e·앱 경로 주입 3회 중 하나라도: 끝에 주입 쌍 cnt ≠ 실제 · 오탐 복구 > 0 · 정합성 위반 > 0 · 주입 원인 아닌 틱 실패 > 0 · **탐지 시간 초과** — 증분이 볼 수 있는 갈래(§2.3)는 증분 주기 + 10초 안, 안전망 몫 갈래는 최근 창 안의 쌍이면 '그 시점 최근 창 적체가 없을 때' 2 × 실행 주기(2분) 안 · 적체가 있었으면 2 × 그 회차의 전체 한 바퀴 시간(실측) 안, 최근 창 밖의 쌍은 2 × 전체 한 바퀴 시간 안(ADR-009 H3 '2주기'와 같은 원리) |
| R5 | updated_at 방식의 선점 비용은 허용선 안이다 | ⓑ 또는 ⓒ의 정상 구간 선점 p99 중앙값이 같은 캠페인 noc 대비 +10% 초과(ADR-009 허용선) — HOT 갱신 비율·인덱스 크기를 함께 기록 |
| R7 | 안전망 실행당 비용은 예산 안이고 데이터와 무관하다 | 30분 측정에서 실행 전체 DB 시간(탐지 + 복구 + 기록)이 '예산 2초 + 그 실행의 가장 긴 청크 1개 + 그 실행의 복구 시간'을 넘은 실행이 있다 · 청크 하나가 500ms를 넘은 실행이 있다 · 정상 구간 첫 5분 대비 마지막 5분의 실행당 DB 시간 중앙값이 2배 이상 그리고 +200ms 이상. **기록만**(판정 아님): 전체 한 바퀴 시간 · 최근 창 적체(30분 고부하에서 최근 창이 따라가는지 — R4의 '2분' 약속이 고부하에서 성립하는 조건) · 1시간 초과·경계 건너뜀 경고가 실제로 남는지 |
| R8 | 탐지를 단순 조회 + 앱 집계로 바꾸면 ① 쿼터 청크의 비용이 준다(1단계 보강 — task 04c) | `split`·`group` 둘 다 같은 캠페인 f 대비 후반 바퀴의 ① 시간(`chunk_ms_total`)이 범위 비겹침으로 짧지 않다. **기록**: 바퀴 시간(정상·후반) · ② 시간(`orphan_ms_total` — 모양과 무관해야 함) · 바퀴 중 DB CPU · 후반 1초 p99 최대 · F CPU·메모리(앱으로 옮긴 비용) · 스모크 EXPLAIN(읽은 행·버퍼) |
| R6 | 각 방식은 §2.3에서 '봄'인 갈래를 증분 경로로 잡는다 | 효과 셀에서 '봄'인 갈래의 첫 탐지 출처가 증분이 아닌 쌍이 하나라도 있다(안전망이 먼저 잡음) — 앱 경로 주입: ⓐ·ⓑ·ⓒ 전부 · S3e L1·L2·H: ⓑ |

**R1 판정표**(1단계 — 3회 범위, C 대 F):

| C 바퀴 시간 대 F 바퀴 시간 | 해석 |
|---|---|
| F가 범위 비겹침으로 짧다 | 앱 JVM 쪽(풀 대기·CPU·GC)이 바퀴를 늘렸다 — C 바퀴 중 Hikari pending·앱 CPU로 어느 쪽인지 본다. R1 기각 |
| 겹친다 | DB 쪽 비용 — R1 유지(바퀴 중 DB CPU로 확인) |
| F가 길다 | 네트워크·별도 풀 크기 등 F 고유 비용 — R1 유지, 원인은 기록만 |

판정 유효 조건(rv37): F 컨테이너는 ADR-009 구성 그대로 CPU 한도 1이다(`compose.reconciler.yml` — 앱보다 작다). F 바퀴 중 F CPU가 한도의 95% 이상인 표본이 절반을 넘으면 '겹친다'·'F가 길다'는 F JVM 포화와 갈리지 않으므로 **R1 판정 불가**로 적고 한도를 올린 재측정 여부를 따로 정한다. 기대한 대조 출처의 바퀴나 F 컨테이너 표본이 없는 회차도 판정에서 뺀다(사유 기록).

**1단계 결과 요약**(전문 §8 · 2026-10-18 — `k6/ADR-009/results/20261018-adr012s1-ad36e72e` 9회 ok): 바퀴 시간 정상 중앙 C 12.7 [12.7–13.3]초 대 F 13.2 [12.9–13.2](겹침) · 후반 중앙 21.1 [19.9–21.5] 대 22.2 [21.8–22.6] · 최대 28.0 [25.1–28.4] 대 29.2 [29.2–29.4](F가 김) → **R1 유지**(DB 쪽). 유효 조건 충족 — 바퀴 중 F CPU 1.7%·최대 18%·포화 0. 바퀴 중/밖 DB CPU C 278/165 · F 258/149. R3 양성 대조 유효 — 후반 서버형 느린 초 noc 0 [0–0] 대 C 12 [11–12]. 새로 본 것: F의 후반 1초 p99 최대 208 [187–221]ms가 C 107 [76–117]보다 크다(범위 비겹침 · 서버형 느린 초는 13 대 12 · 설정 같음 — 원인 미확인).

**R8 판정**(1단계 보강 — 3회 범위, 같은 캠페인 f 대비): 바퀴 중 DB CPU 평균은 ②·복구·업무 부하가 섞이고 표본이 바퀴당 1~2개라 ① 몫을 가르지 못한다(선검증 M3 — 쿼리별 통계 `pg_stat_statements`는 하네스에 없다). 그래서 **주 지표 = 후반 바퀴의 ① 시간 `chunk_ms_total`**(DB 실행 + 왕복 + 앱 집계를 다 담은 벽시계 — 줄면 DB 몫도 줄었거나 앱으로 옮겨 간 것), 보조 = 바퀴 중 DB CPU. 두 변형 중 ① 시간이 범위 비겹침으로 짧고, 바퀴 중 DB CPU와 후반 1초 p99 최대가 범위 비겹침으로 높지 않은 것이 있으면 R8 성립 — 그 모양을 task 05 설계에서 증분·안전망 탐지에 쓴다(둘 다면 ① 시간이 짧은 쪽, 겹치면 `group` — 전송이 작다). 없으면 R8 기각 — 지금 쿼리 모양을 유지한다. 판정 유효 조건: F CPU 포화 표본 비율 ≤ 절반(R1과 같음) · 탐지 모양 동등성 테스트 통과 · 회차 설정의 `detectShape`가 셀과 같음 · ② 시간이 세 모양에서 범위가 겹침(다르면 다른 원인이 섞였다 — 판정 보류).

## 6. 성공 기준 · 고르는 규칙 (측정 전 고정)

**성공(셀마다)**: R2·R3·R4·R5·R6(그 방식의 '봄' 갈래)·R7 모두 성립 + 30분 3회 모두 상태 ok(안정성 4항목 — ADR-010 §9). 판정 불가인 가설이 하나라도 있으면 그 셀은 성공으로 세지 않는다.

**고르는 규칙(기본값)** — 같은 캠페인에서 성공한 셀 중에서:
1. 1초 p99 최대(후반) · 정상 구간 선점 p99 · 정상 구간 DB CPU 평균 세 지표에서, **성공한 다른 모든 후보 대비** 범위 비겹침으로 낮은 지표가 하나 이상 있고 범위 비겹침으로 높은 지표가 없는 후보가 **하나뿐이면** 그것을 고른다.
2. 아니면 **탐지 범위 우선**: ⓑ 트리거 > ⓐ 메모리 집합 > ⓒ 앱 문장 · 같은 방식 안에서는 1초 · 앱 안 > 외부 앱.
3. 앱 안 셀이 하나도 성공하지 못하면 기본값은 **A+C 그대로**다. 외부 앱만 성공했으면 선택 배포로 두고 ADR-013의 입력으로 넘긴다. 아무것도 성공하지 못하면 원인을 적고 ADR-013으로 넘긴다.

기본값 교체 = A + 고른 증분(방식·주기) + 안전망(예산 이어서 훑기 + 최근 창). 옛 방식(C 60초 전체 훑기, 증분 없음)은 설정 손잡이로 되돌릴 수 있게 둔다.

## 7. 받아들이는 한계 (측정 전)

1. **안전망 몫 어긋남의 운영 탐지 시간**: 최근 2시간 안의 쌍은 최근 창이 밀리지 않으면 약 1~2분(밀리면 아래 2), 그보다 오래된 쌍은 **전체 한 바퀴 시간**(데이터에 따라 늘어남 — 예: 766만 쌍에서 C 한 바퀴 DB 시간 약 25초 ÷ 실행당 전체 커서 몫 약 1초 ≈ 25분, 추정)까지(지금 A+C는 60초 + 바퀴). 대상은 §2.3의 '못 봄' 갈래다. ADR-014(운영 중 전환)는 전환 동안 대조 주기를 따로 정해야 할 수 있다.
2. **최근 창은 고부하에서 따라가지 못할 수 있다** — 실행당 약 1초로는 4,200건/s 유입을 다 못 볼 수 있다(적체 기록으로 드러냄). 그때 최근 쌍의 DB 직접 수정 탐지도 전체 한 바퀴 시간으로 늦어진다. 30초보다 긴 트랜잭션이 늦게 커밋한 행은 최근 창이 놓치고 전체 커서가 잡는다.
3. **오래된 쌍의 어긋남은 다시 건드려질 때 한 번은 틀린 카운터로 판정될 수 있다**(그 사이 한도 초과 선점 통과 또는 부당 거절 — 부당 거절은 쿼터 행이 바뀌지 않아 증분도 못 본다). 전체 커서가 돌아올 때 고쳐진다.
4. 외부 앱(M3)도 탐지 쿼리는 .164 DB에서 돈다 — DB CPU는 나뉘지 않는다(ADR-013).
5. 앱 경로 주입은 측정 전용 흉내다 — 실제 버그의 모양을 다 담지 않는다.
6. 여러 앱 인스턴스는 범위 밖(ADR-009 '대조는 1대에서만' 제약 유지).
7. 측정은 S4c4200 tmpfs 한 부하와 S3e뿐이다 — S4c는 TTL 60분이라 30분 안에 만료(A)가 없다. 만료가 많은 부하에서의 증분 비용은 ADR-015에서 본다.
8. docker stats·Hikari 표본은 약 10~13초 간격이고 시각이 초 단위다 — 바퀴(13~25초) 하나에 '바퀴 중' 표본이 1~2개뿐이라 바퀴 중/밖 비교는 표본 수와 함께 본다. Hikari는 업무 앱 풀만 잰다(F 풀 대기는 관측하지 않음).
9. R8은 ① 쿼터 행 청크만 바꾼다 — ② 행 없음 갈래(반조인)는 앱 메모리에 쿼터 키 전체가 필요해 그대로 둔다. `seat_hold`에는 `(schedule_id, user_id)` 인덱스가 없다(`user_id` 단일) — R8은 '지금 스키마에서' 읽는 행은 같게 두고 쿼리 모양만 바꾼 판정이다. 인덱스를 더한 효과는 재지 않는다.
10. (R8 측정 전 스모크 2026-10-20에서 확인) S4c 부하에는 확정 예약이 없어 `reservation`이 비어 있다 — R8은 사실상 **홀드 쪽** 조회 모양만 비교한다(ⓒ 예약 조회는 빈 테이블 순차 읽기 · 0.005ms). EXPLAIN(회차 1 · 쿼터 1만 행 · 청크 1,000명): one-shot 2.57ms·버퍼 4,753 · split ⓐ0.20 + ⓑ1.00ms · group ⓐ0.20 + ⓑ1.22ms — 버퍼는 셋 다 약 4,500으로 같다(읽는 대상이 같다는 설계 확인).

## 8. 1단계 측정 결과 (측정 뒤 — 원문 `log.md` 10-18 13:24 ~ 20:40)

- 캠페인: `k6/ADR-009/results/20261018-adr012s1-ad36e72e` · 하네스 SHA ad36e72e · 2026-10-18 13:24~19:30 · **9회 모두 ok**(CAMPAIGN done — 비정상 회차 0). 집계 = 조건별 `summarize.py` → `tail.py --set adr012s1`(TAIL.md 1~6절).
- 구성: S4c4200(초당 4,200 선점 · 30분 = 60구간) · 시드 800회차(800만 석) · PostgreSQL tmpfs 12g · 앱 2g 세대형 ZGC · L4(앱 4·DB 4 CPU) · 측정기 켬 · k6 v2.2.0. 측정 전 PC 점검: 다른 컨테이너 1개(정상 — 재시작 반복 없음) · load 7.1/24코어.
- 조건(회차마다 1칸 회전 — 1회 noc c f · 2회 c f noc · 3회 f noc c): noc = A만(C 끔) · c = default(A + 앱 안 C) · f = A + F(별도 컨테이너 — CPU 한도 1 · 메모리 1g · 대조 60초 — default에서 C 대신 F).
- 값 = 3회 중앙값 [최소–최대]. '비겹침' = 3회 범위가 겹치지 않음.

### 8.1 꼬리 지연 (TAIL.md 4절 · 후반 창 = 정상 구간 900~1,800초)

| 지표 | noc | c | f |
|---|---|---|---|
| 후반 느린 초 | 0 [0–2] | 13 [13–15] | 14 [12–16] |
| 후반 서버형 느린 초 | 0 [0–0] | 12 [11–12] | 13 [12–14] |
| 후반 1초 p99 최대 ms | 26.7 [17.2–31.1] | 106.8 [75.7–117.3] | **207.7 [186.5–220.9]** |
| 정상 구간 전체 느린 초(1절) | 0 [0–2] | 13 [13–18] | 14 [13–17] |
| 측정기 튐 수 — PC / 서버(1절) | 1 [0–1] / 0 [0–0] | 3 [1–6] / 2 [2–3] | **38 [33–46] / 36 [31–44]** |
| 선점 p99 ms(회차 전체 — summarize) | 1.47 · 1.58 · 3.42 | 3.12 · 3.65 · 6.42 | 2.13 · 2.24 · 2.55 |
| 후반 관측·빈·얇은 초 | 900·0·0 [0–1] | 900·0·0 [0–1] | 900·0·0 |

- 선점 에러율 0 · 정합성 위반(카운터 불일치 포함 10항목) 0 · 대조 복구 0(주입 없음 — 어긋남 없음) · 안정성 4항목 모두 ○(3절) · 앱 메모리 최대 ÷ 한도 89.4~92.8%.

### 8.2 대조 바퀴 (TAIL.md 5절 — 출처 c = reconcile-app · f = reconcile-proc)

| 지표 | c | f |
|---|---|---|
| 바퀴 수 — 전체 / 정상 안 / 후반 안 | 28 / 24 / 11 | 28 / 24 / 11 |
| 바퀴 시간 중앙 s — 정상 / 후반 | 12.69 [12.66–13.28] / 21.10 [19.87–21.48] | 13.15 [12.92–13.20] / 22.17 [21.78–22.60] |
| 바퀴 시간 최대 s | 27.99 [25.08–28.39] | 29.24 [29.18–29.41] |
| DB CPU % — 바퀴 중 / 밖 | 278.2 [270.8–279.6] / 165.0 [164.3–166.2] | 257.5 [251.9–268.5] / 149.0 [148.9–153.5] |
| 앱 CPU % — 바퀴 중 / 밖 | 158.8 [157.5–165.9] / 156.7 [155.5–158.1] | 160.7 [159.7–161.8] / 152.6 [152.3–154.4] |
| F CPU % — 바퀴 중 평균 / 최대 · 포화(≥ 95%) 비율 | — | 1.7 [1.6–2.1] / 9.3 [9.2–18.4] · 0 |
| Hikari pending > 0 비율 — 바퀴 중 / 밖 | 0.017 [0.003–0.023] / 0.001 | 0.007 [0.006–0.019] / 0.001 [0–0.002] |
| 표본 수 — CPU 바퀴 중 / 밖 · Hikari 바퀴 중 / 밖 | 23 / 121 · 304 / 1,253 | 25 / 118 · 308 / 1,250 |
| 정상 구간 CPU 평균 % — 앱 / DB / F (4절) | 153.9 / 180.8 / — | 150.9 / 166.5 / 0.6 |

noc 정상 구간 CPU 평균: 앱 150.2 [149.8–152.0] · DB 156.4 [156.1–156.9].

**갈래별 시간**(`counter_reconcile_run` — 각 회차 끝에서 두 번째 바퀴(쌍 733만~751만)): 

| 회차 | 바퀴 s | ① 쿼터 청크 `chunk_ms_total` | ② 행 없음 `orphan_ms_total` |
|---|---|---|---|
| c rep1 / rep2 / rep3 | 25 / 28 / 28 | 14.9 / 14.8 / 15.3초 | 7.2 / 11.0 / 10.2초 |
| f rep1 / rep2 / rep3 | 29 / 29 / 29 | 15.1 / 15.2 / 15.1초 | 11.2 / 11.3 / 11.4초 |

①은 바퀴의 약 52~60%, ②는 약 29~39%다 — 둘 다 쌍 수에 비례해 자란다(§4.1이 ①만 바꾸는 근거).

### 8.3 판정

- **R1 유지 — C 바퀴의 비용은 DB 쪽이다.** 판정표(§5): 정상 중앙은 겹침, 후반 중앙·최대는 F가 범위 비겹침으로 길다 → 둘 다 'R1 유지'. F가 짧은 지표는 없다. 유효 조건 충족 — F CPU 포화 0(바퀴 중 평균 1.7%). F의 JVM은 거의 일하지 않고 바퀴 중 DB CPU가 바퀴 밖보다 약 110%p 높다. 앱 CPU·Hikari 대기는 바퀴 중/밖 차이가 작다 — 앱 JVM 쪽(풀 대기·CPU·GC)이 바퀴를 늘렸다는 근거는 없다.
- **R3 판정 유효 조건 충족**: 같은 캠페인에서 c가 noc를 넘는다(후반 서버형 느린 초 12 [11–12] 대 0 [0–0] · 후반 1초 p99 최대 106.8 대 26.7 — 비겹침). ADR-011 soakrc의 결론(C가 후반 서버형 튐의 원인)이 3회로 다시 섰다.
- **새로 본 것 — F는 꼬리를 키운다**: 후반 1초 p99 최대가 c의 약 2배(비겹침)이고, 측정기 튐이 PC·서버 양쪽에서 10배 넘게 많다(서버 측정기도 튀므로 PC 쪽 오염이 아니다). 반면 서버형 느린 초 수는 c와 비슷하고 회차 전체 선점 p99는 c보다 낮다. 설정(청크 1,000 · 행 없음 10,000 · 60초)은 c와 같다. **원인은 확인하지 않았다** — 후보: F는 업무 요청과 JVM을 나누지 않아 탐지 쿼리를 쉼 없이 몰아서 낸다(C는 업무 스레드와 앱 CPU를 나눠 쓰며 덜 몰린다). 이 후보는 M3(외부 앱)에도 걸린다 — 2단계에서 외부 앱 셀의 꼬리를 같은 지표로 본다.
- **다음에 주는 것**: 바퀴를 다른 프로세스·서버로 옮기는 것만으로는 꼬리가 줄지 않는다. DB가 하는 일의 양(전체 훑기)을 줄여야 한다 — 증분(M2) · 탐지 모양(R8 — 사용자 제안 2026-10-18, §4.1).

## 9. 의사결정 기록 (원문 `log.md` 타임라인 · 명세 §0)

측정 지표와 사용자 결정이 이 ADR의 범위·설계를 어떻게 바꿨는지 시간 순으로 남긴다. 사용자 결정은 원문 인용이다.

| 시각 | 근거(지표·사실) | 결정 | 고르지 않은 것 | 누가 |
|---|---|---|---|---|
| 10-17 (ADR-011 soakrc) | C를 켜고 끄면 30분 후반 서버형 느린 초 12 대 1 · C 바퀴가 400만 쌍을 넘으면 14~26초, 바퀴 중 DB CPU 338~384% | C의 규모 한계를 새 ADR로 다룬다 | ADR-011 안에서 해결 | 메인 판단 — ADR-011 결정 §8 |
| 10-18 00:50 | 인터뷰 16문항 | 범위 = F 측정 + 증분 대조 · 성공 = '대조 끔(noc)과 같은 꼬리' · 성공 시 기본값 교체 · stakes 높음 · 바뀐 쌍 아는 방법 · 주기 · S3e 판정 등(명세 §0) | 명세 §0 '고르지 않은 것' 열 | 사용자 답 |
| 10-18 01:05 | S3e 주입은 DB 직접 수정(`lib.sh:541-543`) — 메모리 집합은 주입을 못 보고 updated_at은 행 삭제를 못 본다 | S3e 판정 = 증분 + 안전망 합산 | 증분만으로 판정 · 주입을 앱 경로로 | 사용자 |
| 10-18 01:10 | 메모리 집합은 다른 프로세스가 볼 수 없다 | 외부 앱 = updated_at 방식 · 별도 폴더·도커 · .158 | 외부 앱만 · 앱 안만 | 사용자 "외부에 두는 구조는 별도 폴더에 앱이랑 도커도 별도로 해서 158에 띄울 수 있게" |
| 10-18 01:20 | 외부 앱도 탐지 쿼리는 원본 DB에서 돈다(DB CPU는 안 나뉨) | 비교 = M0~M4 전부 · M4(Kafka + 읽기 복제본)는 ADR-013으로 나눔 | M4 중심 · 세부 축소 | 사용자 "DB도 조회용과 커밋용으로 분리하고 … 카프카를 통해 쌓고 … 비교하는 모델을 비교하는 거 아니야?" |
| 10-18 03:10 | 측정 전 문서 리뷰 rv36(Opus 높음 6 · codex 높음 7) — 방식별로 볼 수 있는 경로·동시성 불변식·하네스 산출물 없음 | 안전망 비용 측정 · 측정 전용 앱 경로 주입 · 고르는 순서 = 탐지 범위 우선 · 외부 앱만 성공하면 A+C 유지 | 테스트로만 확인 · 외부 앱 기본 구성 | 사용자 결정 4 |
| 10-18 03:25 | 증분이 못 보는 갈래(DB 직접 수정·행 삭제·실제 값 쪽만·재시작) · 데이터가 쌓이면 10분 안에 전체를 못 돈다 | 안전망 = 예산 있는 이어서 훑기(1분마다 DB 2초) + 최근 2시간 창 우선 | 안전망 1시간 → 10분(재합의 2번) · 매시간 2시간 창만 | 사용자 "매 시간 2시간 범위만 처리하도록 … 데이터가 계속 쌓이면 10분 안에 전체를 못 도니깐" → 메인 의견(창의 양은 상한 아님) 뒤 선택 |
| 10-18 04:05 | .9·.158 사양(둘 다 15GiB — DB 14g + 앱 2g를 못 담음) | 측정 .164 · 외부 앱 .158 · .9는 쓰지 않음 | .9·.158 두 서버로 측정 | 사용자 '.164 측정 + .158 외부 앱' |
| 10-18 04:30 | 하네스 compose 8개가 루트 Dockerfile을 쓴다 | 저장소 구조 A — main-app · reconciler-app · reconcile-core, 1단계 뒤 동작 무변경 리팩토링 | B 루트 유지 · 1단계 전에 | 사용자 "싯 리져베이션 랩 안에 별도 앱 프로젝트를 코틀린으로 올리고 기존 앱은 별도 main-app 폴더로" → A |
| 10-18 07:40 | 하네스 리뷰 rv37 — F CPU 한도 1이 R1을 흐릴 수 있음 | F 한도는 ADR-009 구성 유지 · F CPU 포화 비율을 R1 판정 유효 조건으로 | 한도를 올려 측정(비교 변수 추가) | 메인 판단 |
| 10-18 13:23 | F 스모크 — 바퀴 중 F CPU 6% · DB CPU 215% | 1단계 캠페인 시작 | — | 메인(합의된 순서) |
| 10-18 20:40 | §8: 바퀴 시간 c 대 f 겹침 또는 f가 김 · F CPU 1.7% · 바퀴 중 DB CPU +110%p · F 후반 1초 p99 최대 2배 | **R1 유지** — 프로세스·서버를 옮기는 것만으로는 안 된다, DB 일의 양을 줄여야 한다 | — | 메인 판정(측정 전 판정표) |
| 10-18 21:00 | §8: F JVM은 거의 놀고 비용은 DB 한 방 쿼리에 몰림 | **R8 추가** — 탐지를 단순 조회 + 앱 집계로(split·group) · 순서 04b 먼저 | 별도 ADR · 증분 구현 뒤 · 변형 하나만 | 사용자 "쿼리가 아니라 각각 조회해서 앱에서 대조하는게 더 가볍고 … 원천 데이터가 저장될 DB는 1개 … 별도 배치앱이 이 비교처리를 해주는게 오히려 맞다" |
| 10-19 01:20 | 04b 중 발견 — 대조 코드 5클래스가 앱 전용 타입에 묶여 동작 무변경으로 못 옮김 | core 추출은 task 05 설계에서 · 04b는 구조만 · R8은 main-app에 | 04b에서 지금 | 사용자 '05 설계 때 함께' |
| 10-19 11:45 | §8.2 갈래별 시간 — ① 쿼터 청크 약 15초 · ② 행 없음 7~11초 · `seat_hold`에 (회차, 사용자) 인덱스 없음 | R8은 ①만 바꿈 · 인덱스 추가 안 함 | ②까지(쿼터 키 약 750만 쌍을 앱 메모리에) · 인덱스 추가(스키마 변경이 비교를 흐림) | 메인 판단 |
| 10-19 12:00 | 설계 선검증 rv39 — 사용자 범위 조회는 다른 회차 홀드까지 읽어 '인덱스 부재'를 잼 · DB CPU로는 ① 몫을 못 가름 | 읽는 대상을 one-shot과 같게(`user_id = ANY`) · 주 지표 = 후반 ① `chunk_ms_total` · 탐지 전용 RR 트랜잭션 | 사용자 id 범위 `(a, b]` · DB CPU 주 지표 | 메인 판단(리뷰 반영) |

