-- Explicit local fixture for examples.json; never run automatically on startup.
-- The zero digest is a placeholder with no usable device token. This is NOT authentication setup.
BEGIN;
INSERT INTO zone(zone_id, display_name) VALUES ('zone-01', '가상 장치 시험 구역')
ON CONFLICT (zone_id) DO NOTHING;
INSERT INTO device(device_id, zone_id, https_token_sha256)
VALUES ('edge-01', 'zone-01', repeat('0', 64))
ON CONFLICT (device_id) DO NOTHING;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM device WHERE device_id='edge-01' AND zone_id='zone-01' AND enabled) THEN
    RAISE EXCEPTION 'Existing edge-01 registration differs; inspect it instead of overwriting';
  END IF;
END $$;
INSERT INTO zone_view(zone_id, device_id) VALUES ('zone-01', 'edge-01')
ON CONFLICT (zone_id) DO NOTHING;
DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM zone_view WHERE zone_id='zone-01' AND device_id='edge-01') THEN
    RAISE EXCEPTION 'Existing zone_view registration differs; inspect it instead of overwriting';
  END IF;
END $$;
COMMIT;
