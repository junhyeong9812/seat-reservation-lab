# log — ADR-009 카운터 어긋남 탐지·격리·복구 + 판매 마감 + 쿼터 행 정리

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-12 07:xx | 인터뷰 3차(범위·후보·주입·브랜치 / 번호·추가 범위·측정·결정 기준 / 복구 정책·행 정리·모드 / 시작 후 선점 / 마감 시각) | 명세 초안 작성 — 사용자 합의 대기 |
| 10-12 07:xx | 실코드 확인: startsAt을 읽는 코드 없음(판매 기간 미정의 — ADR-000 설계 누락, 사용자 확인) · 부하 시드 회차 시각 2026-12-31 19:00+09(이후 하네스 전부 거절될 위험 — 기록) · 만료 배치 단일 트랜잭션 재조회 | 명세 §0 근거 |
| 10-12 07:xx | 사용자 합의 "진행하자" → SPEC=1 · MODE=auto(set-state) · 브랜치 feat/adr-009-counter-drift(feat/adr-007-throughput-wall 4b137670 위 — PR #7 병합 후 main으로 rebase, push 전) · task 00(번호 이동, Opus 워커) ∥ task 01(설계 선검증, Opus 워커) 위임 | 진행 |
| 10-12 08:xx | task 00 회수 cd05733d(번호 이동 009~014 → 010~015 · ADR-000 판매 기간 정정 메모 · NEXT N1b/N1c) — 메인 교차 확인: 파일 목록·ADR-008:7 이름 정정 · 애매 1건(ADR-007 괄호 '2026-10-11 … ADR-010') 메인 정리 커밋 · 코드 주석 옛 번호 2곳(ConfirmReservationService:19 → 011, ExpireHoldsService:11 → 010)은 구현 단계에서 | task 00 완료 |
| 10-12 08:xx | task 01 설계 선검증 회수(Opus, scratchpad/adr009/design.md 596줄 + SQL·실험 E1~E10·B1~B4, 임시 PG16 — 컨테이너 정리 확인 보고) · 메인 교차: exp-out-2 E7(한 문장 DELETE … NOT EXISTS가 진행 중 선점을 무시하고 행 삭제)·E8(잠금 후 재확인 안전) 원문 확인 · 사용자 결정 D1~D8 · codex 교차 검증 시작(미러 scratchpad/rv15, 결과 폴더 제외, 보안 스캔 0건) | 진행 |
| 10-12 09:xx | codex 설계 교차 검증 회수: 지적 7(C1 마감 회차 대조 제외 → 무음 · C2 정리① 회차 조건 재확인 누락(E8이 다른 사용자 홀드 남은 채 삭제) · C3 정리①이 확정 쌍 삭제 → 판정기 불일치 · E1 E10이 실제 reconcile.sql 미실행 · E2 B2 끝 상태 정합 미검증 · B1 트리거 무변화 UPDATE · O1 lock_timeout 뒤 skipped 기록 경계) + 메인 발견 M1(B2 끝 상태 = 확정 + 좌석 AVAILABLE — 확정·만료 둘 다 좌석 행 무잠금·@Version 미매핑, 기존 위험 → ADR-011) · 사용자 결정 8건 → 명세 §10 재합의 | 설계 수정 위임 |
| 10-12 09:xx | 사용자 "병합 진행하고 가자" → PR #7 merge commit fb59ecbb(MERGEABLE·CLEAN, 체크 0) · feat/adr-009-counter-drift를 origin/main 위로 rebase(push 전 — 원격 브랜치 없음 확인): cd05733d → 9b8be68d · 8f4740ae → 6a15678d · 7102ba6b → 4baf138e · 로컬 main ff → fb59ecbb | 구현 브랜치 준비 |
| 10-12 10:xx | 설계 v2 회수(Opus): v1 청크 대조 오류 발견(162만 쌍 155초 → 두 갈래 15초) · C2a 회차 FOR SHARE/FOR UPDATE 직렬화 실증 · M1 끝 상태는 DELETE 행 수 확인 생략 탓(행 수 확인 넣으면 6경우 모두 정합) · 매트릭스 84회 ≈ 18.7시간 | codex 재점검 |
| 10-12 10:xx | codex 재점검: 기존 7 중 6 해소·E1 부분 · 신규 N1(행 없음 SQL 열린 회차 필터·예약 갈래 미실측) · N2(마감 판정 순서가 실제 호출 경로·전략별로 미보장) · N3(무관한 열린 회차 하나로 판정기 공허 통과) → 설계 재루프 없이 구현 합격 항목으로(§11) · 사용자 R1 FOR SHARE · R2 두 갈래 · R3 60초 | 설계 선검증 종료 |
| 10-12 10:xx | 구현 검증 대장 신설 `docs/implementation-verification.md`(IV-009-1~7) · 설계 v2·SQL을 작업 폴더에 보존(design-v2.md·design-sql/ — scratchpad 유실 방지, 2파일 규칙의 편차) · 계약 표면 contract.md(blind 테스트용 이름 발췌) | task 02 ∥ 03 위임 |

## 리뷰 ledger

- review packet(설계): rv15 — 미러(fb181a30 전 HEAD tracked, 결과 폴더 제외) + design·SQL·실험 · 보안 스캔 0건

| id | first_seen_loop | source | 근거 | disposition | status | fixed_in_loop |
|----|-----------------|--------|------|-------------|--------|---------------|
| DC1 | 0 | codex | design §4 마감 회차 제외 | 채택 — 사용자 §10(카운터 안 씀, 제외 기록) | fixed | v2 |
| DC2 | 0 | codex | reconcile.sql 정리① 사용자 단위 재확인 | 채택 — 회차 행 FOR SHARE/FOR UPDATE 직렬화(C2a 실증) | fixed | v2 |
| DC3 | 0 | codex | 정리① 확정 쌍 삭제 → 판정기 | 채택 — 판정기 마감 회차 제외(C3 실증) | fixed | v2 |
| DE1 | 0 | codex | E10이 실제 SQL 아님 | 채택 — 실측으로 v1 오류 발견, 두 갈래 | partial → N1 | v2 |
| DE2 | 0 | codex + main M1 | B2 끝 상태 | 채택 — 선잠금 = 교착만, 정합은 ADR-011(IV-009-1) | fixed(범위 밖 이연) | v2 |
| DB1 | 0 | codex | V8 트리거 무변화 UPDATE | 채택 | fixed | v2 |
| DO1 | 0 | codex | lock_timeout 뒤 skipped | 채택 — 롤백 뒤 독립 트랜잭션 기록 | fixed | v2 |
| N1 | 1 | codex | orphan-v2.sql:2 | 채택 — 구현 합격 항목(§11) | open | — |
| N2 | 1 | codex | HoldSeatService.kt:27 · HoldSeatProcess.kt:45 | 채택 — 구현 합격 항목(§11) | open | — |
| N3 | 1 | codex | design §5a 판정기 | 채택 — 하네스 미판정 사유(§11) | open | — |

## 생략한 검증

- 없음
