-- Keep the original receipt when another payload reuses its message_id.
CREATE TABLE inbound_quarantine (
 quarantine_id cap_id PRIMARY KEY,
 device_id cap_id NOT NULL REFERENCES device(device_id),
 message_id cap_id NOT NULL,
 topic text NOT NULL,
 raw_payload bytea NULL CHECK(raw_payload IS NULL OR octet_length(raw_payload) <= 65536),
 payload_purged_at timestamptz NULL,
 raw_sha256 cap_sha256 NOT NULL,
 canonical_sha256 cap_sha256 NULL,
 error_code varchar(48) NOT NULL,
 rule_id varchar(128) NOT NULL,
 first_received_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL,
 UNIQUE(device_id,message_id,raw_sha256,rule_id),
 CHECK(expires_at = first_received_at + interval '30 days'),
 CHECK((raw_payload IS NULL) = (payload_purged_at IS NOT NULL))
);
CREATE INDEX inbound_quarantine_expiry_idx ON inbound_quarantine(expires_at);
