-- ADR-013 §4.4 단계 4 — 동치 확인용 카탈로그(한 줄 JSON). 같은 SHA 새 앱 Flyway가 V9를 적용한 빈 DB(하네스 seat_ref)와 운영 DB에서 같은 문장을 돌려 비교한다.
-- 비교하는 값: 열 정의(형 · NOT NULL · 기본값 식) · 인덱스 정의(pg_get_indexdef)·유효(indisvalid·indisready) · 함수 정의 · 트리거 정의·활성(tgenabled).
-- 비교에서 빼는 값(DS-14 · apply.sh가 'volatile' 아래로 따로 둔다): atthasmissing·attmissingval(빠른 기본값 m — 열을 더한 시각) · OID · relfilenode · reltuples·relpages · 통계.
SELECT json_build_object(
  'column', (SELECT json_build_object('type', format_type(a.atttypid, a.atttypmod), 'not_null', a.attnotnull,
                                      'default', pg_get_expr(d.adbin, d.adrelid))
             FROM pg_attribute a LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
             WHERE a.attrelid = 'user_hold_quota'::regclass AND a.attname = 'updated_at' AND NOT a.attisdropped),
  'index', (SELECT json_build_object('def', pg_get_indexdef(i.indexrelid), 'valid', i.indisvalid, 'ready', i.indisready)
            FROM pg_index i WHERE i.indexrelid = to_regclass('idx_user_hold_quota_updated_at')),
  'function', (SELECT pg_get_functiondef(p.oid) FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
               WHERE p.proname = 'trg_quota_touch' AND n.nspname = current_schema()),
  'trigger', (SELECT json_build_object('def', pg_get_triggerdef(t.oid), 'enabled', t.tgenabled::text)
              FROM pg_trigger t WHERE t.tgname = 'user_hold_quota_touch' AND NOT t.tgisinternal),
  'volatile', (SELECT json_build_object('atthasmissing', a.atthasmissing, 'attmissingval', a.attmissingval::text,
                                        'index_oid', to_regclass('idx_user_hold_quota_updated_at')::oid)
               FROM pg_attribute a WHERE a.attrelid = 'user_hold_quota'::regclass AND a.attname = 'updated_at' AND NOT a.attisdropped),
  'owner_ok', (SELECT pg_get_userbyid(c.relowner) = current_user FROM pg_class c WHERE c.oid = 'user_hold_quota'::regclass));
