# log — ADR-016 억울한 사용자 최소화 ('결제했는데 좌석을 잃음' 줄이기)

- 브랜치: `feat/adr-016-paid-seat-loss`(main 5ae6c788에서 — 로컬 · push 안 함)
- 명세: `requirement-spec.md`(작성 전 — 설계 L0 먼저)

## 타임라인

| 시각 | 사건 | 결과 |
|---|---|---|
| 11-07 | PR #13 · #14 squash 병합(사용자 승인 '진행하자') — #14는 #13 squash 뒤 충돌 → main을 `-s ours`로 이어 붙여(내용 diff 0) 해소 | main 5ae6c788 = ADR-015 브랜치 내용(diff 0) |
| 11-07 | 사용자 요청: EOF 하네스 수정 + '억울한 사용자 최소화'를 ADR-016으로 · 해결 방법 검토 | 인터뷰 시작 |
| 11-07 | 인터뷰 답: ⓐ 새 ADR-016을 끼우고 기존 016~020 → 017~021(10차 번호 이동 — 옛 016 확정 실패 보상은 017) ⓑ EOF 수정 = ADR-016 첫 단계 ⓒ 범위 = 예방(ⓐⓑⓒ) + 보상 설계(판정 기준까지 · 구현은 017) ⓓ 설계 먼저(EOF 재현 병행) | — |
| 11-07 | 닫힌 ADR-000~015의 번호 참조 수정 여부 질문 → '번호 참조만 고침'(1~9차와 같은 방식 · 내용 무변경) | 번호 이동 착수(docs = L0 · 코드 주석 · ops 참조는 명세 뒤) |
| 11-07 | 10차 번호 이동: `git mv` ADR-016~020 → 017~021 · 참조 치환(docs/adr · README · NEXT — '> 번호 이동' 머리줄은 제외) · NEXT 9차 기록 문장 1곳 되돌림(과거 사실) · 전 ADR에 10차 머리줄 | 코드 주석 `SeatMapQuery.kt` · ops 참조 2곳은 명세 뒤(L1) |
| 11-07 | 코드 사실 확인: ① 늦은 확정 = 409 HOLD_EXPIRED(배치 전) 또는 404(배치 뒤) — NEXT의 '404'만이 아니다 · ④ payment-start가 이미 too_late 409(ⓑ)를 품음 · 거부된 확정의 paymentUid는 남지 않음 | ADR-016 설계-1 |
| 11-07 | ADR-016 설계 초안(L0) — 문제 · 지금 코드 · 선택지 ⓐ~ⓓ · 측정 뼈대 · 보상 판정 기준 · EOF 첫 단계 · 열린 질문 Q1~Q6 | 사용자 검토 대기 |
| 11-07 | EOF 1단계 명세 합의('합의 · auto') · task 05 .164 ④ race 스모크 = 한다(시작 전 .164 사용 재확인) · set-state SPEC=1 · MODE=auto | task 01 재현 착수 |
| 11-07 | task 01 재현(로컬 · `k6/ADR-015/results/_evidence-20261107/16-eof-repro/` · Repro.java keep-alive 2초 · gap.js 도착률 150/s · 2분): VUS 330 = EOF 0(유휴가 한도 밑) · VUS 600 = 0(전부 새 연결) · **VUS 400 = 반복 첫 요청 EOF 14 · 9**(2회) | 가정 1(반복 사이 유휴 재사용) 실증 |
| 11-07 | 처방 비교(같은 VUS 400 · 2분 × 2): (가) 반복 끝 close = EOF 0 · 0 · 첫 요청 전부 새 연결 · (나) `noVUConnectionReuse` = 0 · 0 · 반복 안 둘째 요청은 두 처방 모두 재사용 4501/4501(반복 안 동작 무변경) | 가정 2 실증 |
| 11-07 | 구현 전 발견: (가)는 '마지막 요청'을 미리 알아야 한다 — 선점 실패 · 본문 결함 조기 종료 · 재선점 201 종료는 보낼 때 마지막인지 모른다 → (가)로는 명세 §2 '모든 종료 경로' 불변식을 못 지킨다 · (나)는 반복 끝에 연결을 닫아 전 경로를 덮는다 | 사용자 재합의 질문(결정 항목 변경) |
| 11-07 | 사용자 '(나) noVUConnectionReuse로 변경' → task 02: `confirm-contention.js` options에 `noVUConnectionReuse: true`(+ 주석) · selftest `--campaign` EXIT 0 · k6 v2.3.0(재현과 같은 바이너리) | fix 6295ad81 |
| 11-07 | task 04: 번호 참조 SeatMapQuery.kt 주석(018→019) · ops README · r1 SQL(016→017) — `git grep` 옛 번호 0 | chore 6e5df233 |
| 11-07 | 사용자 추가 요청: 'k6 문법 · 설정 설명 문서' → Opus 워커(배경 · `docs/k6-guide.md` · 커밋 없음) | 회수 대기 |
| 11-07 | 리뷰 듀얼 1패스 착수(中) · base dfd84761 · OUT = scratchpad/rv16eof.nmZj · 미러 = scratchpad/mir16.Z7TT(k6/*/results 제외 + 재현 증거 파일만) · 첫 packet 시도는 untracked 결과 1.3만 건 유입으로 폐기 · 보안 스캔 0 · codex(medium) ∥ Opus 워커 | 회수 대기 |
| 11-07 | k6 안내서 회수(Opus 워커 · 154k 토큰 · 30 도구 · 354초): `docs/k6-guide.md` 602줄 · 실행기 3종(per-vu-iterations · constant-arrival-rate · ramping-arrival-rate) · '확인 필요' 10항 · 메인 교차 확인: ramping-arrival-rate 실사용(ADR-001 s4:28 등) · summarize.py:100 parse_tags 일치 | 커밋 대기(docs) |
| 11-07 | codex 리뷰 회수: F1(중간) — 명세 §1 · §2 · §5 · §9가 처음 처방 (가) 기준인데 구현은 (나) → **채택**(재합의가 log에만 있고 명세 미반영 — 명세 결함) · 명세 동기화(재합의 줄 · §5 '로컬 스모크'는 하네스가 .164 원격 기동 전용이라 .164 ④ race 스모크로 대신 — 사용자 보고) · open question(검증 기록) = 이 log · .164 스모크 대기 | Opus 회수 대기 |
| 11-07 | Opus 리뷰 회수(131k 토큰 · 12 도구 · 182초): F1(높음 — codex F1과 같음) · F2(중간 — (나)는 PC가 닫아 TIME_WAIT가 PC에) · F3(중간 — 하네스 경로별 증거 없음) · F4(낮음 — 증거 폴더 재현성) · OQ 5 | 종합 아래 ledger |
| 11-07 | F2 메인 확인: `ip_local_port_range` 32768–60999(28,232) · `tcp_tw_reuse`=2(루프백만) · plan15 cost 300/s · ρ 0 · F_DUP 0.1 → 약 330 연결/s × 60초 ≈ 2만(계산) → 사용자 질문 → '(가)+(나) 둘 다' · .164 스모크 '나중에' | 재합의 2 |
| 11-07 | 수정: confirm-contention.js — 단독 확정 · 짝 확정 둘 · 재선점 마지막 시도에 `closeConn` + 기존 `noVUConnectionReuse` 유지 · selftest `--campaign` EXIT 0 · `k6 inspect`(더미 env) 구문 통과 · 명세 재합의 2 · ADR-016 Q6 결정 · 설계-5 처방 줄 | 커밋 대기 |
| 11-07 | F4 수정: 재현 v1 로그 → `v1/`(옛 gap.js 판 — last_* 카운터 없음) · Tomcat 작업 폴더 `srv-*` 삭제 · run.sh가 conditions.tsv(태그 · env · k6 버전 · gap.js sha · TIME_WAIT 서버/PC 최댓값) 기록 · gap.js EARLY(조기 종료 비율) · 변형 넷 재실행(배경) | 회수 대기 |

## 리뷰 ledger — 루프 1(中 듀얼 1패스 · base dfd84761)

| id | first_seen | source | 근거 | disposition | status | fixed_in |
|---|---|---|---|---|---|---|
| R16-1 | 1 | codex F1 · opus F1 | confirm-contention.js:67-70 vs 명세 §1 · §2 · §5 · §9 · ADR-016:83 Q6 | 채택 — 재합의가 log에만 있고 명세 · ADR 미반영 | fixed | 1(명세 재합의 줄 · ADR Q6) |
| R16-2 | 1 | opus F2 | confirm-contention.js:70 · plan15.py:33 · :38 | 채택 — PC TIME_WAIT 포트 고갈 위험(계산) | **open** — (가)+(나) 적용했으나 루프백 재현에서 PC 쪽 감소 12%뿐 · .164 cost 스모크 PC TIME_WAIT 최댓값으로 판정 | — |
| R16-3 | 1 | opus F3 | 명세 §5 '경로 전수' | 채택 — §5를 (나)에 맞게 재정의 · 조기 종료 경로는 재현 EARLY로 · 하네스 경로별 `http_req_connecting`(name=hold · rehold)은 .164 스모크 CSV로 | open(.164 스모크 대기) | — |
| R16-4 | 1 | opus F4 | 증거 폴더 gap.js · 로그 불일치 · srv-* | 채택 | fixed | 1 |
| OQ-1 | 1 | opus | hhash.sh:6 — 하네스 해시 바뀜 | 의도대로 — 재측정은 새 캠페인 id(설계-5 '대조군도 같은 하네스로') | — | — |
| OQ-2 | 1 | opus | 재선점 첫 시도 `t`에 연결 수립 포함(약 0.5ms) | 기록만 — 조밀 간격 20ms 대비 작음 · FM1 겹침 판정 [ts, te]는 확정 쪽 | — | — |
| OQ-3 | 1 | opus | 측정기 k6(probes · PROBE_RATE 50) 반복 사이 유휴 | 범위 밖(무효 6은 main CSV code0) → NEXT 후보 | — | — |
| OQ-4 | 1 | codex · opus | 셀프테스트 · 스모크 기록 | 이 log 행(셀프테스트 EXIT 0) · 스모크 = .164 대기 | — | — |
| 11-07 | 재현 재실행(현재 gap.js be084462 · VUS 400 · 2분 · 조기 종료 30% · conditions.tsv): EOF — 처방 없음 9 · (가)만 **3**(조기 종료 경로가 남음 — (가) 단독 불충분 실증) · (나)만 0 · (가)+(나) 0 · 반복 첫 요청 새 연결 (나) 포함 변형 18000/18000 · 반복 안 재사용 유지. TIME_WAIT 최댓값(서버/PC): 4580/365 · 6705/573 · 5621/**7435** · 5036/**6573** | (가)가 PC TIME_WAIT를 거의 못 줄임(−12%) — 메인의 앞 설명('(가)면 서버가 먼저 닫아 PC에 안 남는다')은 루프백에서 성립하지 않음 → 사용자 보고 · R16-2 open(.164 cost 실측으로 판정) |
| 11-07 | post-fix 타깃 재점검(codex medium · f865f509 hunks + 정의 · 명세): **clean** — closeConn 대상 객체는 요청마다 새로 만들어 새지 않음 · batch 객체 형식 일치 · k === REHOLD_OFFS.length 마지막 판별 맞음 · 요청 순서 · 시각 · 태그 무변경 | 루프 1 마감(R16-2 · R16-3은 .164 스모크 대기 — 미해소) |
