-- Unknown until observed. These are internal columns, not extra ZoneView wire fields.
ALTER TABLE zone_view
 ADD COLUMN fps numeric NULL,
 ADD COLUMN pending_message_count cap_seq NULL,
 ADD COLUMN storage_ok boolean NULL,
 ADD CONSTRAINT zone_view_fps_finite_nonnegative
   CHECK (fps >= 0 AND fps < 'Infinity'::numeric);
-- PostgreSQL sorts NaN above Infinity, so the upper comparison also rejects NaN.
