-- ADR-017 배수 뒤 DB 덤프(계약 INTERFACE.md §6.2 — 같은 문장 · 한 스냅숏). run17.sh가 db_json으로 보낸다
SELECT json_build_object('t_ms', extract(epoch FROM clock_timestamp()) * 1000,
  'payment_intent', coalesce((SELECT json_agg(json_build_object('uid', uid, 'hold_id', hold_id, 'user_id', user_id, 'schedule_id', schedule_id,
      'seat_id', seat_id, 'section', section, 'pay_by_ms', extract(epoch FROM pay_by) * 1000, 'state', state, 'result_code', result_code,
      'reservation_id', reservation_id, 'closed_reason', closed_reason, 'created_ms', extract(epoch FROM created_at) * 1000) ORDER BY uid) FROM payment_intent), '[]'::json),
  'reservation', coalesce((SELECT json_agg(json_build_object('id', id, 'schedule_id', schedule_id, 'seat_id', seat_id, 'user_id', user_id,
      'payment_uid', payment_uid, 'status', status, 'intent_id', intent_id, 'origin', origin, 'created_ms', extract(epoch FROM created_at) * 1000) ORDER BY id)
      FROM reservation), '[]'::json),
  'confirm_rejection', coalesce((SELECT json_agg(json_build_object('id', id, 'uid', uid, 'code', code, 'http_status', http_status,
      'rejected_ms', extract(epoch FROM rejected_at) * 1000) ORDER BY id) FROM confirm_rejection), '[]'::json),
  'compensation_task', coalesce((SELECT json_agg(json_build_object('id', id, 'uid', uid, 'type', type, 'source', source, 'status', status,
      'attempts', attempts, 'next_attempt_ms', extract(epoch FROM next_attempt_at) * 1000, 'lease_until_ms', extract(epoch FROM lease_until) * 1000,
      'last_result', last_result, 'created_ms', extract(epoch FROM created_at) * 1000, 'done_ms', extract(epoch FROM done_at) * 1000) ORDER BY id)
      FROM compensation_task), '[]'::json));
