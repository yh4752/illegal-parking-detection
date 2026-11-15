-- Local virtual-device fixture, copied from canonical examples.json/config_desired.
-- Geometry is sample data, not a calibrated real-world zone. Never deploy this policy.
-- The disabled author is an audit FK only; it cannot log in and has no usable password.
BEGIN;
INSERT INTO app_user(user_id,username,password_hash,role,enabled)
VALUES ('local-fixture-author','local-fixture-author','!disabled-fixture','OPERATOR',false)
ON CONFLICT (user_id) DO NOTHING;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM app_user WHERE user_id='local-fixture-author' AND NOT enabled
                AND password_hash='!disabled-fixture' AND username='local-fixture-author') THEN
  RAISE EXCEPTION 'Fixture author differs; inspect existing data';
 END IF;
END $$;
INSERT INTO zone_policy(zone_id,version,base_version,change_class,content,content_sha256,created_by,reason)
VALUES ('zone-01',3,2,'TIMING',
 $policy${"zone_id":"zone-01","version":3,"base_version":2,"change_class":"TIMING","geometry":{"roi_polygon_m":[[0,0],[4,0],[4,2],[0,2]],"exit_polygon_m":[[4,0],[6,0],[6,2],[4,2]],"calibration_id":"cal-001","reference_points":[{"image_uv":[0.1,0.2],"ground_xy_m":[0,0]},{"image_uv":[0.7,0.2],"ground_xy_m":[4,0]},{"image_uv":[0.7,0.8],"ground_xy_m":[4,2]},{"image_uv":[0.1,0.8],"ground_xy_m":[0,2]}]},"timers":{"first_guidance_ms":300000,"confirm_ms":3000,"repeat_after_end_ms":30000,"max_attempts":2,"unresolved_ms":600000,"exit_confirm_ms":3000},"vision_profile_id":"vision-001","operating_hours":{"timezone":"Asia/Seoul","windows":[{"weekday":1,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":2,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":3,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":4,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":5,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":6,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":7,"start_hhmm":"00:00","end_hhmm":"24:00"}]}}$policy$::jsonb,
 '1fc3649b9c17304be21fa6e62b9ac3cd712bda71a231ff6960d08531b8dacf78','local-fixture-author','v1.43 가상 장치 저장 실습')
ON CONFLICT (zone_id,version) DO NOTHING;
DO $$ BEGIN
 IF NOT EXISTS (SELECT 1 FROM zone_policy WHERE zone_id='zone-01' AND version=3
                AND content_sha256='1fc3649b9c17304be21fa6e62b9ac3cd712bda71a231ff6960d08531b8dacf78'
                AND content=$policy${"zone_id":"zone-01","version":3,"base_version":2,"change_class":"TIMING","geometry":{"roi_polygon_m":[[0,0],[4,0],[4,2],[0,2]],"exit_polygon_m":[[4,0],[6,0],[6,2],[4,2]],"calibration_id":"cal-001","reference_points":[{"image_uv":[0.1,0.2],"ground_xy_m":[0,0]},{"image_uv":[0.7,0.2],"ground_xy_m":[4,0]},{"image_uv":[0.7,0.8],"ground_xy_m":[4,2]},{"image_uv":[0.1,0.8],"ground_xy_m":[0,2]}]},"timers":{"first_guidance_ms":300000,"confirm_ms":3000,"repeat_after_end_ms":30000,"max_attempts":2,"unresolved_ms":600000,"exit_confirm_ms":3000},"vision_profile_id":"vision-001","operating_hours":{"timezone":"Asia/Seoul","windows":[{"weekday":1,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":2,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":3,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":4,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":5,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":6,"start_hhmm":"00:00","end_hhmm":"24:00"},{"weekday":7,"start_hhmm":"00:00","end_hhmm":"24:00"}]}}$policy$::jsonb) THEN
  RAISE EXCEPTION 'Policy 3 differs; do not overwrite an existing policy';
 END IF;
END $$;
UPDATE zone SET latest_policy_version=3 WHERE zone_id='zone-01' AND latest_policy_version IS NULL;
COMMIT;
