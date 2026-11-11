-- v1.4 implementation design, 2026-11-05. NOT an executed production migration.
-- Flyway V1 candidate. PostgreSQL SQL; actual selected server version must be tested.
-- No initial users, credentials, policies or real vehicle data are inserted.
-- JSONB values are validated against contract 04 and semantic rules BEFORE this layer.
CREATE DOMAIN cap_id AS varchar(128) CHECK (char_length(VALUE) BETWEEN 1 AND 128);
CREATE DOMAIN cap_seq AS bigint CHECK (VALUE BETWEEN 0 AND 9007199254740991);
CREATE DOMAIN cap_sha256 AS char(64) CHECK (VALUE ~ '^[0-9a-f]{64}$');

CREATE TABLE app_user (
 user_id cap_id PRIMARY KEY,
 username varchar(128) NOT NULL UNIQUE,
 password_hash text NOT NULL,
 role varchar(16) NOT NULL CHECK (role IN ('VIEWER','OPERATOR')),
 enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE zone (
 zone_id cap_id PRIMARY KEY,
 display_name varchar(128) NOT NULL,
 latest_policy_version cap_seq NULL CHECK (latest_policy_version IS NULL OR latest_policy_version >= 1),
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE device (
 device_id cap_id PRIMARY KEY,
 zone_id cap_id NOT NULL UNIQUE REFERENCES zone(zone_id),
 enabled boolean NOT NULL DEFAULT true,
 registration_generation bigint NOT NULL DEFAULT 1 CHECK (registration_generation >= 1),
 https_token_sha256 cap_sha256 NOT NULL, -- random device Bearer token hash; never plaintext
 credential_version cap_seq NOT NULL DEFAULT 1 CHECK(credential_version>=1),
 credential_rotated_at timestamptz NOT NULL DEFAULT now(),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(device_id,zone_id)
);
CREATE TABLE zone_policy (
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 version cap_seq NOT NULL CHECK(version >= 1),
 base_version cap_seq NOT NULL,
 change_class varchar(16) NOT NULL CHECK(change_class IN ('TIMING','GEOMETRY')),
 content jsonb NOT NULL CHECK(jsonb_typeof(content)='object'),
 content_sha256 cap_sha256 NOT NULL,
 created_by cap_id NOT NULL REFERENCES app_user(user_id),
 reason varchar(200) NOT NULL CHECK(char_length(reason)>=1),
 created_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(zone_id,version),
 CHECK(base_version < version)
);
ALTER TABLE zone ADD CONSTRAINT zone_latest_policy_fk
 FOREIGN KEY(zone_id,latest_policy_version) REFERENCES zone_policy(zone_id,version)
 DEFERRABLE INITIALLY DEFERRED;

CREATE TABLE incident (
 event_id cap_id PRIMARY KEY,
 device_id cap_id NOT NULL,
 zone_id cap_id NOT NULL,
 state varchar(24) NOT NULL CHECK(state IN ('OBSERVING','STOP_CONFIRMED','GUIDING','GUIDED','LONG_UNRESOLVED','HELD','CLOSED_EXIT','CLOSED_MANUAL')),
 event_policy_version cap_seq NOT NULL CHECK(event_policy_version>=1),
 started_at timestamptz NULL,
 closed_at timestamptz NULL,
 close_reason varchar(16) NULL CHECK(close_reason IN ('OBSERVED_EXIT','MANUAL')),
 guidance_count smallint NOT NULL CHECK(guidance_count BETWEEN 0 AND 2),
 body_type varchar(16) NOT NULL CHECK(body_type IN ('CAR','BUS','TRUCK','UNKNOWN')),
 use_type varchar(16) NOT NULL CHECK(use_type IN ('TAXI','SCHOOL','DELIVERY','OTHER','UNKNOWN')),
 manual_use_type varchar(16) NULL CHECK(manual_use_type IN ('TAXI','SCHOOL','DELIVERY','OTHER','UNKNOWN')),
 continuous_stop_ms cap_seq NOT NULL,
 overdue boolean NOT NULL,
 last_device_seq cap_seq NOT NULL,
 first_received_at timestamptz NOT NULL,
 first_close_received_at timestamptz NULL,
 summary_expires_at timestamptz NULL,
 FOREIGN KEY(device_id,zone_id) REFERENCES device(device_id,zone_id),
 FOREIGN KEY(zone_id,event_policy_version) REFERENCES zone_policy(zone_id,version),
 CHECK((state IN ('CLOSED_EXIT','CLOSED_MANUAL')) = (close_reason IS NOT NULL)),
 CHECK((state IN ('CLOSED_EXIT','CLOSED_MANUAL')) = (first_close_received_at IS NOT NULL)),
 CHECK((state IN ('CLOSED_EXIT','CLOSED_MANUAL')) = (summary_expires_at IS NOT NULL))
);
CREATE INDEX incident_list_idx ON incident(zone_id,first_received_at DESC,event_id DESC);
CREATE INDEX incident_expiry_idx ON incident(summary_expires_at) WHERE summary_expires_at IS NOT NULL;
-- Deliberately NO unique index for an 'open' incident: delayed close messages can
-- leave historical incident A open while the current device snapshot points to B.

CREATE TABLE zone_view (
 zone_id cap_id PRIMARY KEY REFERENCES zone(zone_id),
 device_id cap_id NOT NULL UNIQUE REFERENCES device(device_id),
 view_revision cap_seq NOT NULL DEFAULT 0,
 last_device_seq bigint NOT NULL DEFAULT -1 CHECK(last_device_seq BETWEEN -1 AND 9007199254740991),
 snapshot jsonb NULL CHECK(snapshot IS NULL OR jsonb_typeof(snapshot)='object'),
 current_event_id cap_id NULL, -- logical reference: last-known snapshot can outlive history
 received_at timestamptz NULL,
 last_heartbeat_at timestamptz NULL,
 last_heartbeat_seq bigint NOT NULL DEFAULT -1,
 connection_state varchar(8) NOT NULL DEFAULT 'OFFLINE' CHECK(connection_state IN ('ONLINE','STALE','OFFLINE')),
 current_connection_id cap_id NULL,
 current_boot_id cap_id NULL,
 revision_range_start cap_seq NOT NULL DEFAULT 0,
 revision_range_end cap_seq NOT NULL DEFAULT 0,
 recovery_hold boolean NOT NULL DEFAULT true,
 CHECK(view_revision BETWEEN revision_range_start AND revision_range_end)
);

CREATE TABLE inbound_receipt (
 device_id cap_id NOT NULL REFERENCES device(device_id),
 message_id cap_id NOT NULL,
 family varchar(24) NOT NULL CHECK(family IN ('events','command-results')),
 payload_sha256 cap_sha256 NOT NULL,
 outcome varchar(8) NOT NULL CHECK(outcome IN ('STORED','REJECTED')),
 error_code varchar(48) NULL,
 first_received_at timestamptz NOT NULL,
 PRIMARY KEY(device_id,message_id),
 CHECK((outcome='REJECTED')=(error_code IS NOT NULL))
);
CREATE TABLE event_message (
 device_id cap_id NOT NULL,
 message_id cap_id NOT NULL,
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 event_id cap_id NULL, -- logical ref: history may outlive an incident summary
 device_seq cap_seq NOT NULL,
 occurred_at timestamptz NULL,
 received_at timestamptz NOT NULL,
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 PRIMARY KEY(device_id,message_id),
 FOREIGN KEY(device_id,message_id) REFERENCES inbound_receipt(device_id,message_id)
);
CREATE INDEX event_message_event_idx ON event_message(event_id,device_seq,message_id);
CREATE INDEX event_message_retention_idx ON event_message(received_at);

CREATE TABLE command (
 command_id cap_id PRIMARY KEY,
 device_id cap_id NOT NULL REFERENCES device(device_id),
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 type varchar(24) NOT NULL CHECK(type IN ('STOP_GUIDANCE','RESUME_GUIDANCE','REQUEST_SNAPSHOT','CLOSE_EVENT')),
 target_event_id cap_id NULL, -- may refer to compacted event; use tombstone validation
 requested_by cap_id NOT NULL REFERENCES app_user(user_id),
 wire_payload jsonb NULL CHECK(wire_payload IS NULL OR jsonb_typeof(wire_payload)='object'),
 wire_sha256 cap_sha256 NOT NULL,
 server_status varchar(24) NOT NULL CHECK(server_status IN ('QUEUED','WAITING_RESULT','APPLIED','REJECTED','EXPIRED','FAILED','DELIVERY_TIMEOUT')),
 issued_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 result_received_at timestamptz NULL,
 result varchar(16) NULL CHECK(result IN ('APPLIED','REJECTED','EXPIRED','FAILED')),
 error_code varchar(48) NULL,
 result_data jsonb NULL,
 first_terminal_at timestamptz NULL,
 last_result_device_seq cap_seq NULL,
 token_deadline timestamptz NULL,
 CHECK(expires_at=issued_at+interval '60 seconds'),
 CHECK((result IS NULL)=(result_received_at IS NULL))
);
CREATE INDEX command_zone_recent_idx ON command(zone_id,issued_at DESC,command_id DESC);
CREATE INDEX command_deadline_idx ON command(expires_at) WHERE first_terminal_at IS NULL;
CREATE INDEX command_retention_idx ON command(first_terminal_at) WHERE first_terminal_at IS NOT NULL;

CREATE TABLE server_outbox (
 outbox_id cap_id PRIMARY KEY,
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 kind varchar(8) NOT NULL CHECK(kind IN ('COMMAND','POLICY')),
 command_id cap_id NULL UNIQUE REFERENCES command(command_id),
 policy_version cap_seq NULL,
 topic text NOT NULL,
 payload jsonb NULL,
 state varchar(24) NOT NULL CHECK(state IN ('READY','LEASED','WAITING_RESULT','DONE','CANCELLED')),
 next_attempt_at timestamptz NOT NULL,
 attempt_count integer NOT NULL DEFAULT 0 CHECK(attempt_count>=0),
 lease_token cap_id NULL,
 lease_until timestamptz NULL,
 last_published_at timestamptz NULL,
 terminal_at timestamptz NULL,
 FOREIGN KEY(zone_id,policy_version) REFERENCES zone_policy(zone_id,version),
 CHECK((kind='COMMAND' AND command_id IS NOT NULL AND policy_version IS NULL) OR (kind='POLICY' AND command_id IS NULL AND policy_version IS NOT NULL)),
 CHECK((state='LEASED' AND lease_token IS NOT NULL AND lease_until IS NOT NULL) OR (state<>'LEASED' AND lease_token IS NULL AND lease_until IS NULL))
);
CREATE UNIQUE INDEX policy_outbox_identity_idx ON server_outbox(zone_id,policy_version) WHERE kind='POLICY';
CREATE INDEX outbox_due_idx ON server_outbox(next_attempt_at,outbox_id) WHERE state IN ('READY','WAITING_RESULT');

CREATE TABLE pause_projection (
 pause_id cap_id PRIMARY KEY,
 device_id cap_id NOT NULL REFERENCES device(device_id),
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 target_event_id cap_id NULL,
 source varchar(24) NOT NULL CHECK(source IN ('USER','SCHEDULE','PHYSICAL','RESTART','RECONFIGURE','REARM_REQUIRED','OUTPUT_FAULT','OBSERVATION_REVIEW')),
 status varchar(16) NOT NULL CHECK(status IN ('SCHEDULED','ACTIVE','ACK_REQUIRED','CLEARED')),
 payload jsonb NOT NULL CHECK(jsonb_typeof(payload)='object'),
 last_device_seq cap_seq NOT NULL,
 first_seen_at timestamptz NOT NULL,
 cleared_seen_at timestamptz NULL,
 CHECK((status='CLEARED')=(cleared_seen_at IS NOT NULL))
);
CREATE INDEX pause_device_idx ON pause_projection(device_id,status);
CREATE TABLE review (
 review_id cap_id PRIMARY KEY,
 event_id cap_id NOT NULL, -- logical ref; preserve each review's own 30-day retention
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 created_by cap_id NOT NULL REFERENCES app_user(user_id),
 created_at timestamptz NOT NULL,
 note varchar(500) NOT NULL,
 use_type varchar(16) NULL CHECK(use_type IN ('TAXI','SCHOOL','DELIVERY','OTHER','UNKNOWN'))
);
CREATE INDEX review_event_idx ON review(event_id,created_at,review_id);
CREATE INDEX review_retention_idx ON review(created_at);

CREATE TABLE request_receipt (
 user_id cap_id NOT NULL REFERENCES app_user(user_id),
 action_key varchar(300) NOT NULL,
 client_request_id cap_id NOT NULL,
 request_sha256 cap_sha256 NOT NULL,
 resource_kind varchar(8) NOT NULL CHECK(resource_kind IN ('COMMAND','REVIEW')),
 resource_id cap_id NOT NULL,
 original_http_status integer NOT NULL CHECK(original_http_status IN (201,202)),
 original_response jsonb NULL,
 created_at timestamptz NOT NULL,
 compacted_at timestamptz NULL,
 PRIMARY KEY(user_id,client_request_id)
);
CREATE TABLE device_image (
 image_id cap_id PRIMARY KEY,
 device_id cap_id NOT NULL REFERENCES device(device_id),
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 event_id cap_id NULL,
 request_command_id cap_id NULL,
 image_kind varchar(24) NOT NULL CHECK(image_kind IN ('FIRST_GUIDANCE','EVENT_CLOSE','VIEW','RESUME_CONFIRM','CLOSE_CONFIRM')),
 status varchar(12) NOT NULL CHECK(status IN ('PENDING','AVAILABLE','FAILED','EXPIRED')),
 error_code varchar(40) NULL,
 captured_at timestamptz NULL,
 first_received_at timestamptz NOT NULL,
 expires_at timestamptz NULL,
 byte_sha256 cap_sha256 NULL,
 metadata_sha256 cap_sha256 NULL,
 metadata jsonb NULL,
 storage_key cap_sha256 NULL UNIQUE, -- server-generated safe relative path component
 byte_size integer NULL CHECK(byte_size BETWEEN 1 AND 5242880),
 last_device_seq bigint NOT NULL DEFAULT -1,
 stored_at timestamptz NULL,
 deletion_pending boolean NOT NULL DEFAULT false,
 file_deleted_at timestamptz NULL,
 CHECK(status<>'AVAILABLE' OR (byte_sha256 IS NOT NULL AND metadata_sha256 IS NOT NULL AND storage_key IS NOT NULL AND stored_at IS NOT NULL AND expires_at IS NOT NULL)),
 CHECK((status IN ('PENDING','AVAILABLE'))=(error_code IS NULL))
);
CREATE UNIQUE INDEX one_automatic_image_idx ON device_image(event_id,image_kind) WHERE event_id IS NOT NULL AND image_kind IN ('FIRST_GUIDANCE','EVENT_CLOSE');
CREATE INDEX image_zone_recent_idx ON device_image(zone_id,first_received_at DESC,image_id DESC);
CREATE INDEX image_expiry_idx ON device_image(expires_at) WHERE expires_at IS NOT NULL;
CREATE TABLE image_upload_intent (
 image_id cap_id PRIMARY KEY REFERENCES device_image(image_id),
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 upload_token cap_id NOT NULL,
 temp_key cap_sha256 NOT NULL,
 final_key cap_sha256 NOT NULL,
 byte_sha256 cap_sha256 NOT NULL,
 metadata_sha256 cap_sha256 NOT NULL,
 metadata jsonb NOT NULL,
 byte_size integer NOT NULL CHECK(byte_size BETWEEN 1 AND 5242880),
 created_at timestamptz NOT NULL,
 lease_until timestamptz NOT NULL
);
CREATE TABLE entity_tombstone (
 device_id cap_id NOT NULL REFERENCES device(device_id),
 entity_kind varchar(8) NOT NULL CHECK(entity_kind IN ('EVENT','COMMAND','IMAGE')),
 entity_id cap_id NOT NULL,
 terminal_state varchar(24) NOT NULL,
 terminal_device_seq cap_seq NULL,
 payload_sha256 cap_sha256 NULL,
 compacted_at timestamptz NOT NULL,
 PRIMARY KEY(device_id,entity_kind,entity_id)
);
CREATE TABLE audit_entry (
 audit_id cap_id PRIMARY KEY,
 zone_id cap_id NULL REFERENCES zone(zone_id),
 actor_user_id cap_id NULL REFERENCES app_user(user_id),
 device_id cap_id NULL REFERENCES device(device_id),
 action varchar(64) NOT NULL,
 related_id cap_id NULL,
 detail jsonb NOT NULL,
 created_at timestamptz NOT NULL
);
CREATE INDEX audit_retention_idx ON audit_entry(created_at);
-- Ordinary runtime role: cannot UPDATE/DELETE zone_policy, disable audit constraints,
-- or replace revision manifest. Separate migration/operator credentials are required.
-- Referential links marked logical are validated under the zone lock, including
-- entity_tombstone, instead of CASCADE deletion that would violate per-record retention.

-- Enforce device/zone ownership for tables whose wire messages carry both IDs.
ALTER TABLE zone_view ADD CONSTRAINT zone_view_device_zone_fk FOREIGN KEY(device_id,zone_id) REFERENCES device(device_id,zone_id);
ALTER TABLE command ADD CONSTRAINT command_device_zone_fk FOREIGN KEY(device_id,zone_id) REFERENCES device(device_id,zone_id);
ALTER TABLE pause_projection ADD CONSTRAINT pause_device_zone_fk FOREIGN KEY(device_id,zone_id) REFERENCES device(device_id,zone_id);
ALTER TABLE device_image ADD CONSTRAINT image_device_zone_fk FOREIGN KEY(device_id,zone_id) REFERENCES device(device_id,zone_id);
