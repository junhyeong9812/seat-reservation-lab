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
| 10-23 22:39 | task 03 설계 초안 회수(Opus): ADR-013 §4.0~4.10(기존 데이터 = S4c SQL 시드 약 499만 쌍 · S3e 회차 안 · 시각표 공통 T1·T2 · ② 그림자 = 기존 손잡이 · ④ 적용판 = Flyway 밖 열→트리거→인덱스 CONCURRENTLY + 동치 확인 뒤 V9 이력 기록(skipExecutingMigrations 1순위 [구현 검증]) · Flyway V9는 ACCESS EXCLUSIVE를 끝까지 쥐어 읽기도 막음 · 한도 탐침 · 하네스 k6/ADR-013 새 폴더 · 불변식 N1~N10 · [구현 검증] 9) · 열린 질문 8 → 사용자 결정 4(S4c 1,200초 · ⑤ 추가 → 30회 · 앱 자원 각자 그대로 · 탐침 수용) + 메인 4(§1 정정 · ops/online-v9/ · N8 채택 · 메시지 추가 안 함) → 명세 재합의 기록 §10 · ADR §4.2 ⑤ 행 · §4.10 정한 질문 표 | 설계 선검증 듀얼 리뷰 |
| 10-23 23:04 | 설계 선검증 듀얼 회수: codex H 5 · M 3 · Opus H 2 · M 6 · L 4 → 종합 DS-1~16(겹침 병합) 전부 채택 · DS-1(첫 증분 틱이 기존 610만 행 — ADR-012 기본값 결함) → 사용자 결정 '이번에 고침'(명세 §2 예외 · §10 재합의) · 설계 수정 워커(Opus) | 진행 |

## 리뷰 ledger (中↑ — 듀얼 리뷰가 돈 작업 필수)

> 스키마 정본 = `playbooks/review.md §2`.

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| DS-1 | 1 | opus F1(H) | V9:3-4 DEFAULT now() · IncrementalReconciler.kt:388-393·454·141-147 첫 창 무상한 | 채택 — 사용자 결정 '이번에 고침'(첫 하한 ≥ V9 적용 시각 · 후보 상한) + 지표 | open | — |
| DS-2 | 1 | opus F2(H) | ADR-013 N4(:137·259) 소유 공백 < 60초는 필요조건 아님 · SafetyNetReconciler.kt:426-428 | 채택 — '옛 C 마지막 바퀴 시작 ≥ 새 앱 첫 하한' · 못 넘긴 구간 기록 | open | — |
| DS-3 | 1 | codex F2(H) + opus F3(M) | N8 INVALID 검사를 afterLocations가 덮음(IncrementalSchemaCheck.kt:63-71) → V10 적용 뒤 거부 | 채택 — 기존 INVALID는 예측이 덮지 않음 · 테스트 DB 상태 고정 | open | — |
| DS-4 | 1 | codex F1(H) + opus F4(M) | ADR §4.4:171 V9만 든 위치 → V1~V7 미해석 | 채택 — 전체 위치 + target=9 · 실패 원인 분류 [구현 검증] | open | — |
| DS-5 | 1 | codex F3(H) + opus F7(M) | S3e 탐침 끝 대조 불성립(TTL 5분) | 채택 — 탐침 직후 스냅숏 대조 | open | — |
| DS-6 | 1 | codex F4(H) | S3e 전환 구간 정지 초 = 좌석 소진 구간 | 채택 — 전용 좌석 일정 도착률 탐침 · 201 지표는 S4c | open | — |
| DS-7 | 1 | codex F5(H) | '한 가지씩만 다름' 불성립(JVM 기동 · 적용 시각 등) | 채택 — 절차 전체 운영 결과로 판정 · 원인 비교는 교란 명시 참고 | open | — |
| DS-8 | 1 | opus F5(M) + codex F6(M) | lock_timeout 상속 → CONCURRENTLY 끊김·INVALID · N6 범위 | 채택 — 3단계 RESET lock_timeout · statement_timeout · N6 1·2단계 획득 대기로 좁힘 · 업무 차단 별도 관측 | open | — |
| DS-9 | 1 | opus F6(M) | lb 빈 upstream · reload 무음 | 채택 — 자리표시 서버 · nginx -t · $upstream_addr 확인 | open | — |
| DS-10 | 1 | opus F8(M) | 탐침 경계 이름표 | 채택 — 주입·판정 시각·소유 변경 이름표 · 경계 탐침 별도 | open | — |
| DS-11 | 1 | codex F7(M) | N1 옛 이미지 동일성(기반 이미지 미고정) | 채택 — 사전 확보 digest 고정 | open | — |
| DS-12 | 1 | codex F8(M) | 계획 사건 누락·시드 보정 문턱 없음 | 채택 — 계획 대 실제 대조 transition-failed · 보정 허용 범위 고정 | open | — |
| DS-13 | 1 | opus F9(L) | 가정 스모크가 부하 중 옛 앱 + V9 적용을 안 봄 | 채택 — 스모크 1회 추가(task 05 앞) | open | — |
| DS-14 | 1 | opus F10(L) | N5 attmissingval 비교 제외 | 채택 | open | — |
| DS-15 | 1 | opus F11(L) | S4c 끝 불일치는 약한 자료 | 채택 — task 04 고르는 규칙에 명시 | open | — |
| DS-16 | 1 | opus F12(L) | 시드 '열린 회차 100%' 조건 명시 | 채택 | open | — |

## 생략한 검증 (긴급 경로 빚 — DEBT=1인 동안 정본)

- (없음)
