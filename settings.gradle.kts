rootProject.name = "seat-reservation-lab"

// main-app = 업무 앱(이미지·하네스가 빌드하는 앱) · reconcile-core = 탐지·복구 공통 · reconciler-app = 외부 대조 앱(ADR-012) · mock-pg = 흉내 PG(ADR-017)
include("main-app", "reconcile-core", "reconciler-app", "mock-pg")
