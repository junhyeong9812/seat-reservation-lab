# log — ADR-013 운영 중 매수 방식 전환 (기존 데이터가 있을 때)

> 작업 폴더의 두 번째 파일(명세서와 한 쌍). **메인이 단일 writer** — 워커 산출은 packet 원시각을 명시해 메인이 append(미회수 시 "미회수" 행 필수).
> **발생 시점에 append** — 사후 재구성 금지.

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 10-23 17:48 | 사용자 "진행해보자"(ADR-012 PR #11 뒤 ADR-013 착수) · 새 작업 폴더 · 입력: docs/adr/ADR-013-limit-strategy-cutover.md(선택지 ①~④ 후보) · NEXT N0(ADR-012에서 넘어온 것 — 대조 전환 도구 · V9 운영 중 적용) | 인터뷰 1차 시작(L0 — 게이트 리셋) |
| 10-23 17:55 | 인터뷰 1차 답: 범위 = 매수 방식 전환 · 증분 스키마 운영 중 적용 · 대조 방식 전환(C → 증분) 모두 + 사용자 제안 '이전 카운터 계열을 이번 업데이트 형태로 바꾸는 작업으로 주제를 바꾸자' · 선택지 ①~④ 전부 · 롤링 재현 = 앱 2대 동시 기동 · 데이터 규모 = 두 규모 모두 → 주제 확인 질문: **ADR-009 구성(counter-upsert-precheck + C · updated_at 없음) → ADR-012 구성(V9 트리거 + 증분 + 안전망) 운영 중 전환만**(사용자 선택 — 원래 질문 advisory/none → counter는 NEXT 후보로) | 인터뷰 2차 |
| 10-23 21:42 | 인터뷰 2·3차 답: 전환 중 부하 = S3e + S4c · 앱 배치 = .164 둘 다 · 브랜치 = feat/adr-012 끝에서 · 고르는 기준 = 정합 > 다운타임 > 단계 수 · 측정 = 조합 둘(S4c 큰 규모 · S3e 작은 규모) × ①~④ × 3 = 24회 · V9·V10 파일 무변경(운영 중 적용판은 별도) · stakes 높음 · auto → 명세 초안(필수 6칸 · 가정 2 · task 01~07) | 사용자 합의 대기 |
| 10-23 22:08 | 사용자 '가보자' → set-state spec-approved · mode auto(SPEC=1 · MODE=auto) · 브랜치 feat/adr-013-config-cutover(feat/adr-012 끝 4b266e86에서) · task 01 시작 | 진행 |
| 10-23 22:10 | task 01 끝 9d59a1ea(ADR-013 주제 변경 — 파일 이름 counter-config-cutover · 문제·선택지 ①~④·고른 것 표 · NEXT N0 · README) · task 02 가정 스모크 워커(Opus — .164 · 프로젝트 seatlab-smoke13 · 정리 포함) 시작 · .164 가동 18시간 · 컨테이너 3(사용자 것) · 디스크 45G 여유 | 진행 |
| 10-23 22:20 | task 02 회수(Opus · .164 13:12~13:19 UTC · 프로젝트 seatlab-smoke13 · 정리 확인 — 컨테이너·볼륨·네트워크·이미지 0 · 사용자 컨테이너 무변경 · 배포 폴더 93dd610a·9d59a1ea는 남김): **가정 1 성립** — 옛 앱(93dd610a)이 V10 DB에서 Flyway WARN 1줄('version 10 newer than latest 7')만 남기고 기동 · 기동 검사 무사 · 선점 UPDATE·만료·C 복구 UPDATE마다 V9 트리거가 updated_at 갱신 · 확정은 쿼터 미변경 · C 4바퀴 오류 0 · 주입 어긋남 복구 1 · updated_at NULL 0 · 옛 reset은 counter_app_inject를 비우지 않음 · **가정 2 띄우기 성립 · 하네스 재현 부분** — 옛·새 동시 기동 · 앱 간 한도 공유(409) · 한 대 stop/start · 앱별 actuator 분리 가능 · **stop/start 뒤 컨테이너 IP 교환 실측**(nginx 정적 upstream 오경로 위험 — 동적 재해석 필요) · k6/ADR-009 하네스는 앱 1대 고정(run.sh:114 --apps 거부 · seatlab-app-1 고정 등) → 혼재 오버레이(다른 SHA 이미지) · lb 재해석 · 회차 중 내리기 오케스트레이션 · 앱별 수집 · ApplicationName 분리 · reset 소유 고정 필요 · 한계: 부하 없는 스모크 · 옛 C와 새 증분 동시 복구 경합 미재현 · 운영 중 적용판(V7 DB에 V9 적용) 범위 밖 | task 03 설계 |

## 리뷰 ledger (中↑ — 듀얼 리뷰가 돈 작업 필수)

> 스키마 정본 = `playbooks/review.md §2`.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|

## 생략한 검증 (긴급 경로 빚 — DEBT=1인 동안 정본)

- (없음)
