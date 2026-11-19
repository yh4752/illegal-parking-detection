#!/bin/sh
set -eu
# Explicit new virtual installation only; refuse any prior revision state.
[ -z "$(ls -A /state)" ] && [ -z "$(ls -A /state-copy)" ] || { echo 'existing_revision_state_refused' >&2; exit 1; }
java -Dloader.main=kr.ac.sejong.parkingtools.PrepareHandoffCliKt -cp /app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --initialize-new-demo
chown -R 10001:10001 /state /state-copy
