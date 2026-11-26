-- Public local fixture only; never apply to production. Preserve events, IDs and grants.
UPDATE app_user SET password_hash='$2y$12$G3Rq05om1cuQqS00fOs7CuputFU0REVfOUbsiV0bueEtVAo7xBO5a' WHERE username='handoff-viewer' AND role='VIEWER';
