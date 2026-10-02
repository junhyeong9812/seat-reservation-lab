-- ADR-003 낙관락 전략용 버전 열. 엔티티에는 매핑하지 않는다(@Version을 달면 모든 전략에 낙관락 검사가 걸려 기준선이 바뀐다)
-- — 낙관락 전략만 이 열을 읽고 올린다. 다른 전략에는 무해하다.
ALTER TABLE product_seat ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
