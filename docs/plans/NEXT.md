# NEXT — 다음 작업 전망

## 기준

- 마지막 갱신: 2026-09-29 · 직전 완료 작업: `docs/plans/2026-09-28/adr-001-harness/` · 기준 브랜치: `feat/adr-001-harness` · 병합 상태: 대기(main PR 미생성)

## 다음 작업 후보 (우선순위순)

### N1. 새 ADR-002 DB 기준선 보정(일반 인덱스 × 커넥션 풀) — 우선순위: 높음 · 착수됨

- **왜 다음인가**: ADR-001 §4.3 — 선점마다 `seat_hold` 전체 스캔 2회가 처리량 한계를 지배(DB CPU 포화). 이 위에서 락 비교를 하면 스캔 비용에 묻힌다.
- **발생 가능한 문제**: 풀을 키우면 S1 중복 수가 늘 수 있음(ADR-001: 중복 = 풀 크기 10) · ADR-001 결과 재사용 시 환경 변화(측정 시간대 차이가 L4 < L2 미해명의 후보) · k6 PC 메모리(S3 원본 k6 RSS 5.8GB, oomd 전례)
- **대처**: 명세 `docs/plans/2026-09-29/adr-002-db-baseline/` — 환경 동일성 확인 4회 선행, 별도 systemd 유닛 실행

### N2. 락 비교(구 ADR-002 → 새 ADR-003) — 우선순위: 중간 · N1 결과가 기준선

### N3. ADR-001 main 병합 — 우선순위: 중간 · PR 생성은 사용자 확인 필요

## 보류·이월

- study-note `archive/2026-09-28`(이슈 카드 2 + 작업 기록, 커밋 `fdd7a0d7`·`f31b845f`) — 미병합·미push · 보류 이유: 개정 규칙(`issue/`)이 미병합 브랜치 `docs/nextjs-app-render` 위에만 있어, main에 병합하면 그 브랜치의 미병합 커밋 32개가 함께 들어감 · 재개 조건: `docs/nextjs-app-render`가 main에 병합된 뒤 archive 브랜치를 main 위로 옮겨 병합·push

## 완료 이력

- 완료 → `docs/plans/2026-09-28/adr-001-harness/`
- 완료 → `docs/plans/2026-09-27/adr-000-baseline/`
