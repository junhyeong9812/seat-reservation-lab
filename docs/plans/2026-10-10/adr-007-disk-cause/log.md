# log — ADR-007 후속: 디스크 원인(C5) 확인

## 타임라인

| 시각 | 사건 | 결과 |
|------|------|------|
| 10-10 12:40 | 사용자 결정: ADR-007 전환 판정 보류 — "디스크 원인 먼저 확인하고 결정하자". 인터뷰: 방법 4종 전부(재측정+iostat · fio · tmpfs · 휴식 시간) · 최소 셀(디스크 12 + tmpfs 12) · ADR-007에 이어 기록 | 명세 작성 |
| 10-10 12:55 | 명세 합의 "합의, auto로 진행" → set-state spec-approved · mode auto | SPEC=1 MODE=auto |
| 10-10 12:57 | 가정 1 조기 실증(메인, 읽기 전용 SSH): 커널 6.8.0-136, `/proc/diskstats` nvme1n1·dm-0(= ubuntu-lv) 20열(flush 수·시간 포함) sudo 없이 읽힘 · `/proc/pressure/io` 읽힘 | **가정 1 성립** |
| 10-10 13:00 | task 01·02 위임(Opus·high 워커, 백그라운드): 호스트 샘플러(diskstats·meminfo·PSI 1초 + iostat 교차) · tmpfs preset · 쓰기 반감 탐지 스크립트 · fio 스크립트 · 캠페인 배선 + 스모크 3종 | 회수 대기 |
| 10-10 13:20 | ADR-007 §7.1(본측정 결과·가설 판정)·§7.2(후속 계획)·§8(보류)·§2 C5·상태 행 작성(메인). 교차 확인 중 정정: H6 뒤 절(남는 틈은 드물다) **반증** — S7-m1 precheck 틈 포함 100/100(매 회차). 판정기 위반은 none-s2(대조군)뿐 확인 | 문서 리뷰는 §7.2 뒤 한꺼번에 |
| 10-10 13:40 | 사용자 지적: ADR-006에 ADR-005 counter → counter-upsert 코드 변화 스니펫 없음 → ADR-006 §2.1(counter의 prepare+UPDATE vs counter-upsert 한 문장, 비교표: 빌림 3/2회 → 1회 등)·ADR-007 §3.1(precheck acquire·assertSeatAvailable, 상황별 표) 추가 — 스니펫은 UserLimitStrategies.kt·HoldSeatService.kt 실파일에서 복사, hasPrepare 기본 false 확인 | 문서만 |
| 10-10 13:55 | 사용자 지적: 스니펫이 override 정의뿐이고 실제 실행 흐름(hold 내부)이 없음 → ADR-006 §2.1을 (1) 실행 흐름(Controller → HoldSeatService.hold → 3b PessimisticNoWaitHoldStrategy tx.execute → HoldSeatProcess.hold, 끼어드는 자리 ⓐ prepare·ⓑ around·ⓒ acquire·ⓓ check + 인터페이스 기본값 + 자리별 표) / (2) 방식별 코드로 재구성. ADR-007 §3.1은 ⓒ만 바뀐다고 연결. 실파일 확인: DbLockHoldStrategies.kt·HoldSeatProcess.kt·UserLimitStrategies.kt 인터페이스·ActiveUserLimit | 문서만 |
| 10-10 14:15 | 사용자 요청: ADR-005도 같은 형식(실행 흐름 안에서 방식별 구간) → §2.1.1 실행 흐름(ActiveUserLimit·Controller·Service·3b·Process·인터페이스 기본값·HoldLimitPolicy) 추가, §2.2~2.8 스니펫을 방식별 ⓐ~ⓓ 흐름 + 실코드로 교체. 기존 오류 정정: L3 스니펫이 around에서 ensureQuotaRow를 부르는 것으로 적혀 있었음 → 실제는 prepare. ADR-006·007에 내가 적은 3b 락 SQL 'FOR UPDATE NOWAIT' → 실측 'for no key update nowait'(ADR-003 §288·317)로 정정(ADR-006 8행 기존 표기 포함) | 문서만 |
| 10-10 14:30 | 사용자 요청: ADR-005에 쿼터 테이블 설명 추가 → §2.1.2(V5 DDL 실파일, 뷰가 아닌 이유, PK = 락 단위, 방식별 사용 표, 비정규화 cnt 유지·대가). 질문 'advisory + 쿼터 테이블 감사' 답변(측정 안 한 조합 — 판정기 v_over_limit_users가 이미 COUNT로 감사함을 LoadtestDataService.kt에서 확인) | 문서만 |
| 10-10 14:55 | 사용자 요청: 트랜잭션·사용자 락 설명을 ADR-005에 정리 + 별도 동작 방식 문서 → ADR-005 §2.1.3(앱 tx = DB tx·같은 커넥션 조건·MANDATORY·약속형 락·줄이는 작업·xact 자동 해제·세션 락·장기 tx 대비책) + 새 문서 docs/how-it-works/hold-request-flow.md(흐름도·전략 패턴·방식별 표·트랜잭션·락 범위표·쿼터 테이블·xact·확정/만료·코드 위치). 확인한 실파일: DbLockHoldStrategies.kt:131(JpaTransactionManager 커넥션 노출 주석)·ConfirmReservationService·ExpireHoldsService·ProductSeat·ProductSeatRepository·UserLimitStrategyType(12종)·application.yml(타임아웃 설정 없음). 인용 정정: S2 none 수치를 ADR-006 §5.3 ① 값(L4 17 [11–24])으로 | 문서만 |

## 리뷰 ledger (中↑)

- (없음)

## 생략한 검증

- (없음)

## 완료 요약
