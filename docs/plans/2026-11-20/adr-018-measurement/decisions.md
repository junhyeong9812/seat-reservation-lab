# decisions — 선택 질문과 답 (훅이 자동 기록)

## 2026-11-19 22:00:42 (toolu_01Qu9hymvrh9HoPiaGHxjkzJ)

### B0·B1
- 질문: 인터뷰에서 B1을 '1석 API 두 번(지금 방식)'으로 설명했는데, 제 설명이 틀렸습니다. 1단계 정의로 B1은 '둘째가 실패하면 해제 API로 첫 석을 바로 놓는' 방식이고, 진짜 지금 방식(놓지 않고 TTL까지 잡음)은 B0입니다. 어느 쪽을 비교에 넣을까요?
- 선택지: B0과 B1 둘 다 (Recommended) / B1만 (명세 초안대로) / B0만
- 답: B0과 B1 둘 다 (Recommended)

### 명세 합의
- 질문: 명세 초안(docs/plans/2026-11-20/adr-018-measurement/requirement-spec.md)을 합의할까요? 세션 구성 초안(확정 70% · 이탈 15% · 해제 15%)과 p99 허용 상한 초안(ADR-017 base 셀 p99의 1.1배)은 측정 전 고정 커밋에서 확정하고, 그때 다시 확인을 받습니다.
- 선택지: 합의, auto로 진행 (Recommended) / 고칠 것 있음
- 답: 합의, auto로 진행 (Recommended)

## 2026-11-19 22:57:41 (toolu_016cXqx5zBK111NbbUrUp5yA)

### 지울 대상
- 질문: 디스크를 제일 많이 쓰는 것은 도커 이미지가 아니라 seat-reservation-lab 저장소 안의 k6 측정 원본입니다. gitignore된 원본이 59.5G(ADR-009만 37.9G, 대부분 요청별 k6-requests.csv.gz)이고, 추적되지 않은 파일이 9.1G입니다. 우리 것 중 어떤 것을 지울까요? 지금 도는 postgres:16 컨테이너 34개는 지표 구현 워커의 전체 시험(Testcontainers)이 쓰는 중이라 건드리지 않습니다.
- 선택지: 1단계 blind worktree 6.7G / 우리 스모크 이미지 약 1.5G / 닫힌 ADR의 gitignore된 k6 원본 59.5G / 저장소 밖(예: 외장 디스크)으로 옮기기
- 답: 1단계 blind worktree 6.7G, 우리 스모크 이미지 약 1.5G, 닫힌 ADR의 gitignore된 k6 원본 59.5G

## 2026-11-19 23:00:13 (toolu_01JFiZwHcazSwdZ5Sc7jzYwt)

### 원본 삭제
- 질문: 닫힌 ADR의 gitignore된 k6 원본을 지우기 전 마지막 확인입니다. 대상은 두 종류입니다. (1) 요청별 원본 k6-requests*.csv.gz 1,056개, 58.8G (2) LB 접근 로그 lb-access.log.gz 38개, 0.67G. ADR별로는 ADR-009 37.9G, ADR-005 7.9G, ADR-013 6.4G, ADR-007 5.5G, ADR-006 1.5G, ADR-008 0.2G입니다. 회차별 요약·RESULT 문서·집계 파일은 git에 남아 있습니다. 지우면 p99 같은 수치를 요청 단위로 다시 계산할 수 없고 되돌릴 수도 없습니다. ADR-014~017의 추적되지 않은 결과(약 7.6G)는 이번 대상이 아닙니다. 지금 남은 공간은 16G입니다.
- 선택지: 두 종류 모두 삭제 (59.5G) / ADR-009만 삭제 (37.9G) / 지금은 삭제하지 않음
- 답: 두 종류 모두 삭제 (59.5G)
