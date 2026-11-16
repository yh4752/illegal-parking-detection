-- postgres runs this only when creating a new, empty data volume.
\i /opt/parking/create-roles.sql
-- Read mounted files inside the DB container; passwords never become CLI arguments.
SELECT format('ALTER ROLE %I PASSWORD %L', role_name, pg_read_file('/run/secrets/' || secret_file))
FROM (VALUES
  ('parking_migrator', 'migrator-password'),
  ('parking_runtime', 'runtime-password'),
  ('parking_operator', 'operator-password'),
  ('parking_backup', 'backup-password')
) AS passwords(role_name, secret_file) \gexec
ALTER DATABASE parking_handoff OWNER TO parking_migrator;
