# decisions — 선택 질문과 답 (훅이 자동 기록)

## 2026-11-21 15:50:22 (toolu_01FDK8uqpgCpsnNFJhm8E4oY)

### 맞지 않는 구성
- 질문: 묶음 API를 기본으로 켜면, 함께 쓸 수 없는 구성이 있습니다. 확정 모드 current, 보상 auth-capture·substitute, V16 앞 스키마가 그렇습니다. 지금은 이런 구성에서 켜면 기동 검사가 거부합니다. 기본으로 켠 뒤 이런 구성은 어떻게 다룰까요?
- 선택지: 명시 강제 (Recommended) / 자동으로 끄고 WARN
- 답: 명시 강제 (Recommended)

### 테스트
- 질문: 기존 테스트는 어떻게 돌릴까요? 확정 모드 current를 지정한 테스트가 33파일, flyway.target을 지정한 테스트가 8파일입니다. 기본값이 켬이 되면 이 테스트들의 기동이 거부됩니다.
- 선택지: 테스트 프로필에서 끔 (Recommended) / 테스트는 기본값(켬)으로
- 답: 테스트 프로필에서 끔 (Recommended)

### 기록 위치
- 질문: ADR-018은 #18로 병합돼 닫혔습니다(닫힌 ADR 수정 금지). 기본값 교체 기록은 어디에 둘까요?
- 선택지: README · NEXT · 작업 폴더 (Recommended) / ADR-018에 1줄 예외
- 답: README · NEXT · 작업 폴더 (Recommended)

### 진행 방식
- 질문: stakes(위험도), 자율성, 발행 범위를 정해 주세요. 제안은 높음입니다. 묶음 API 4개가 기본으로 열리고 기동 계약이 바뀌기 때문입니다. ADR-017 기본값 교체와 같은 수준입니다.
- 선택지: 높음 · auto · PR까지 (Recommended) / 높음 · auto · push 전 확인 / 중간 · auto
- 답: 높음 · auto · PR까지 (Recommended)

## 2026-11-21 15:51:56 (toolu_0186fuwP3qqtmL6dp2tNwAeM)

### 명세 합의
- 질문: 기본값 교체 명세 초안을 합의할까요? (docs/plans/2026-11-21/adr-018-default-switch/requirement-spec.md) 핵심: ① enabled 기본 true이고 mode는 split 그대로입니다. ② 맞지 않는 구성은 기동을 거부하고 enabled=false를 안내합니다. ③ 테스트 프로필은 false로 둡니다. ④ blind 계약 테스트 7개를 넣습니다. ⑤ README와 NEXT에 기록합니다. 더한 것이 하나 있습니다. 기본이 켬이 되면 flyway.target < 16 구성이 V15까지 마이그레이션한 뒤에야 거부됩니다. 그래서 V16 계획 검사를 Flyway 앞에 둡니다. ADR-017 기본값 교체의 '거부된 기동은 스키마를 바꾸지 않는다'와 같은 원칙입니다.
- 선택지: 합의, auto로 진행 (Recommended) / V16 검사는 빼고 진행 / 고칠 것 있음
- 답: 합의, auto로 진행 (Recommended)

## 2026-11-21 17:33:36 (toolu_01P2nrteMs3hrEzWqpJZmsqe)

### 거부 안내
- 질문: 리뷰에서 '키 없이 띄운 기동의 첫 거부 메시지에 seat.hold-group.enabled=false 안내가 없다'는 결함이 경로를 바꿔 네 번 나왔습니다(보상 검사 먼저 · target=14 V15 검사 먼저 · yml 되돌림 주석 · Flyway 끈 구성의 검사 순서). 검사마다 자기 키만 안내하는 구조가 원인이라 정지 규칙에 따라 구조를 정하려 합니다. 어떻게 할까요?
- 선택지: 묶음 검사를 맨 앞에 (Recommended) / 여러 번 거부를 설계로 받아들임 / 관문의 모든 거부에 안내 덧붙임
- 답: 묶음 검사를 맨 앞에 (Recommended)
