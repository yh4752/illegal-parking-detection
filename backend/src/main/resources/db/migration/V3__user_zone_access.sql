-- No implicit grants, including for OPERATOR. Web authentication is implemented separately.
CREATE TABLE user_zone_access (
 user_id cap_id NOT NULL REFERENCES app_user(user_id),
 zone_id cap_id NOT NULL REFERENCES zone(zone_id),
 PRIMARY KEY(user_id,zone_id)
);
CREATE INDEX user_zone_access_zone_idx ON user_zone_access(zone_id,user_id);
