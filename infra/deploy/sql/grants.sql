-- Run after Flyway as database owner parking_migrator, in the dedicated application DB.
-- Does not modify v1.43 migrations. New tables require an explicit grant review.
BEGIN;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO parking_runtime, parking_operator, parking_backup;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM PUBLIC, parking_runtime, parking_operator, parking_backup;
REVOKE ALL ON ALL SEQUENCES IN SCHEMA public FROM PUBLIC, parking_runtime, parking_operator, parking_backup;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO parking_runtime, parking_operator, parking_backup', current_database()) \gexec
GRANT SELECT ON app_user, user_zone_access, zone, device, zone_policy, zone_view,
 incident, event_message, inbound_receipt, inbound_quarantine, pause_projection,
 entity_tombstone, command, device_image, review, flyway_schema_history TO parking_runtime;
GRANT INSERT, UPDATE, DELETE ON incident TO parking_runtime;
-- Raw messages are append-only until the retention job deletes them.
GRANT INSERT, DELETE ON event_message TO parking_runtime;
GRANT INSERT ON inbound_receipt, entity_tombstone TO parking_runtime;
GRANT INSERT, UPDATE ON inbound_quarantine, pause_projection TO parking_runtime;
GRANT UPDATE ON zone_view TO parking_runtime;
-- PostgreSQL SELECT FOR UPDATE also needs UPDATE privilege on at least one column.
-- Existing FK references protect registered device IDs. No enabled/credential/zone updates allowed.
GRANT UPDATE (device_id) ON device TO parking_runtime;
GRANT SELECT ON app_user, user_zone_access, zone, flyway_schema_history TO parking_operator;
GRANT INSERT, UPDATE ON app_user TO parking_operator;
GRANT INSERT, DELETE ON user_zone_access TO parking_operator;
GRANT INSERT ON audit_entry TO parking_operator;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO parking_backup;
COMMIT;
