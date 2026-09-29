# log — adr-002-db-baseline

## 타임라인

| 시각 | 사건 | 결과/결정 |
|------|------|----------|
| 09-29 13:00 | 사용자 제안: 인덱스·풀 확장을 먼저 하는 게 새 ADR-002, 기존 락 비교는 미룸 → 인터뷰 | 인덱스 × 풀 10/20/40, S1·S2·S4·S3 원본, L2·L4 × 5회. 58h 추정 보고 → "인덱스 없음 쪽 축소"로 224회·약 38h |
| 09-29 13:10 | 명세 작성 → 사용자 "adr1은 커밋후 … adr2 문서작성 및 실험준비까지하고 각 커밋 후 푸시해줘 그리고 실험 시작하면" | 합의로 기록 SPEC=1·MODE=auto. ADR-001 마감 커밋·push(85ae35e) |
| 09-29 13:20 | task01: 구 ADR-002~009 → 003~010(git mv + 번호 참조 갱신). 나열 표기(`ADR-002·003·005`)의 뒷 번호가 안 밀리는 문제 2곳 발견·수정 | 새 ADR-002 가설(H1~H5·판정 기준 D1~D5) 작성, ADR-003·004 선행에 ADR-002 추가 |
| 09-29 13:40 | task02: V2 일반 인덱스 5개 + flyway target=1 전환, IndexMigrationTest 3개(인덱스 5개·유니크 아님 / 중복 삽입 허용 / target 1이면 없음) | 37 green, 커밋 0bb8ecd push |
| 09-29 13:45 | 사용자 제안: 더미 데이터 규모별 인덱스 성능 비교 → 테이블별 규모 영향 분석 보고(seat_hold·reservation이 대상, product_seat는 PK) → "부하 전체(S1·S4)도 규모별로"·5회·홀드·예약 같은 양 | 명세 §1 범위 확장(규모별 80회 + S5), ADR-002 가설 H6·H7·판정 D6·D7 추가. 약 49h |
| 09-29 14:00 | task03-a: reset에 backgroundRows(배경 회차에 HELD N·홀드 N + RESERVED N·CONFIRMED N) + 테스트 — 편집 도구 따옴표 충돌로 1회 미적용, 재적용 후 닫는 괄호 중복으로 컴파일 실패 1회 → 수정 | 38 green |
| 09-29 14:05 | 사용자: "사용자 검증 단계는 제외 … 유저 더미 1만개는 별로" → 앱에 사용자 테이블·검증이 없음을 답변(사용자 = X-User-Id 숫자, 1인 2매 확인은 인덱스 대상 쿼리로 남음) · "이 과정이 추가되면 얼마나 지연되는지 후자로" | NEXT N3로 이월 |
| 09-29 14:10 | 규칙 위반: 45918bb에 코드(src)와 문서(NEXT·log)를 한 커밋에 섞음(이미 push) | 되돌리지 않고 이후 분리 |
| 09-29 14:15 | 사용자 "adr1은 main에 머지해야되지않아?" → PR #2 생성·merge commit 병합(b7487e5), main = ADR-001 브랜치 내용 확인 | NEXT N4 해소 |
| 09-29 14:30 | task03: k6/ADR-002 = ADR-001 하네스 복사 + 조건 축(--index·--pool·--bg), 회차마다 DB 실제 인덱스 목록 기록, campaign.sh(조건 10개·이어서 실행), s5-bench.sh(pgbench 4쿼리 × 규모 × 인덱스 × 단계) | 발견: campaign·s5가 동결되지 않음 → 캠페인 동결 추가 + 동결 사본에서 부른 run.sh가 결과를 임시 폴더에 쓰지 않도록 ADR_DIR 전달. 커밋 02ff77c(코드)·ad363c8(README) push |
| 09-29 14:40 | 스모크 시작(S5: L4·규모 0/10만·5초 / S1: 인덱스 on·풀 40·배경 10만) ∥ 듀얼 리뷰(base 85ae35e → ad363c8, OUT=/tmp/tmp.D8glaKryi8, mirror=/tmp/tmp.cPprukmZco, 시크릿 스캔 0) | — |
| 09-29 18:55 | S5 스모크(ad363c8): **Q1(1인 2매 — 홀드 수)이 인덱스 있음에서도 Seq Scan**(3.9 → 4.1ms), Q2~Q4는 60~90배 개선 — Opus 리뷰 F-1 지적과 일치 | 원인: 쿼리 조건은 seat_hold.user_id + product_seat.schedule_id, 인덱스 앞 열(seat_hold.schedule_id) 미사용 |
| 09-29 19:00 | 사용자: "seat_hold(user_id)로 교체" · "이건 이슈로 남겨놓자" · "수정까지 하고 리뷰 내용들도 정리해서 기존형태와 변경한 내용까지 adr2에 기록 후 보여주는게 맞지않아?" | V2 직접 수정(적용된 DB는 매번 새로 만든 것뿐) + 리뷰 12건 수정 |
| 09-29 19:05 | envcheck.py 음성 검증 중 `rm -rf` 상대 glob이 안전 검사에 차단(cd 후 glob — 대상 정적 해석 불가) | 삭제 없이 새 폴더로 검증(양성 exit 0 · 범위 밖 exit 1 · 누락 exit 1) |
| 09-29 19:10 | 수정 커밋 eb979fc → codex 1차 재점검: 8 해소·3 부분·1 이연 타당 + 신규 3(P1 누락 회차 통과·P2 중앙값이 범위 밖 숨김·P2 S5 재실행 누락 DONE) | 수정 ea5cd91 → 2차 재점검 신규 0(부분 1: S5 좌석 수 기대값) → 7368551 |
| 09-29 19:15 | 사고 재발: 수정 후 스모크(eb979fc)가 도는 중에 하네스를 고쳐 S5는 문법 오류(직접 실행 비동결), 부하 스모크는 SHA 불일치 거부로 둘 다 exit 2 | S5 직접 실행에도 동결 추가(7368551), 스모크 중 k6/ADR-002 편집 금지 → 스모크 재실행(7368551) |
| 09-29 19:20 | ADR-002 §6(측정 전 검증 — 이슈·리뷰 기존→변경·사고·한계) 작성, 명세 §9.1 인덱스 변경 기록 | §6.4는 스모크 결과로 채움 |
| 09-29 20:08 | 수정 후 스모크(7368551) 통과: S5 Q1 Index Scan(idx_seat_hold_user_id) 3.433 → 0.102ms, Q2~Q4 30~80배 / 부하 S1·S2 ok, pool 40·인덱스 5개 대조 통과, 배경 제외 대조 0, S1 성공 33(풀 40) | ADR-002 §6.4 기록 → 캠페인 시작 |
| 09-29 21:14 | **캠페인 시작**: `campaign.sh --sha 7368551 --id 20260929-adr002-7368551` — systemd 유닛 seatlab-adr002(ManagedOOMPreference=avoid), 캠페인·실행기·S5 모두 동결 사본 | 순서: c00 환경 확인 → envcheck 판정 → S5 → c01~c09 |

## 리뷰 ledger (中↑)

- review packet: base 85ae35e / OUT /tmp/tmp.D8glaKryi8 / mirror /tmp/tmp.cPprukmZco

| id | first_seen_loop | source | 근거(file:line) | disposition | status | fixed_in_loop |
|----|-----------------|--------|-----------------|-------------|--------|---------------|
| R1 | 1 | opus·codex(q) | V2__add_indexes.sql:7 1인 2매 인덱스가 쿼리 조건과 불일치 | 채택(사용자 합의) | fixed | 1 |
| R2 | 1 | opus·codex | summarize.py:206 배경 행이 응답-DB 대조에 섞임 | 채택 | fixed | 1 |
| R3 | 1 | opus | run.sh:46 캠페인 중 SHA 검사가 남은 조건 중단 | 채택 | fixed(재점검에서 우선순위 버그 추가 수정) | 1 |
| R4 | 1 | opus·codex | summarize.py 비교표·S5 표·0/5xx 분리 없음 | 채택(일부 이연: 측정 중 분석 도구로) | fixed(S2 에러율)·deferred | 1 |
| R5 | 1 | opus·codex | s5-bench.sh:102 실패 DONE·덮어쓰기·검증 부족 | 채택 | fixed | 1 |
| R6 | 1 | opus·codex | run.sh:60 --resume 조건 미복원 | 채택 | fixed | 1 |
| R7 | 1 | opus·codex(q) | run.sh:226 적용 조건 미검증 | 채택 | fixed | 1 |
| R8 | 1 | opus | IndexMigrationTest:20 판정기 검출 미확인 | 기각(기존 LoadtestEndpointsTest가 V2 위에서 검출 검증) | — | — |
| R9 | 1 | opus | s5-bench.sh:48 Q4 모양 | 채택 | fixed | 1 |
| R10 | 1 | opus·codex | campaign.sh:65 환경 확인 무판정 | 채택 | fixed | 1 |
| R11 | 1 | opus | s1·s2 주석 옛 번호 | 채택 | fixed | 1 |
| R12 | 1 | codex | s5-bench.sh:44 Q3 대상이 규모 따라 바뀜 | 채택 | fixed | 1 |
| P1 | 1(재점검) | codex | envcheck.py:19 누락 회차 통과 | 채택 | fixed | 1 |
| P2a | 1(재점검) | codex | envcheck.py:36 중앙값이 범위 밖 숨김 | 채택 | fixed | 1 |
| P2b | 1(재점검) | codex | s5-bench.sh:62 재실행이 계획 누락을 DONE | 채택 | fixed | 1 |
| P3 | 1(2차 재점검) | codex | s5-bench.sh:122 좌석 수 기대값 미대조 | 채택 | fixed | 1 |

## 생략한 검증

- (없음)

## 완료 요약

