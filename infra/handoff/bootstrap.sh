#!/bin/sh
set -eu
# Docker secret bind mounts may have host ownership. CLI reads a private copy owned by this process.
umask 077
cp /run/secrets/web_password /tmp/web-password
chmod 600 /tmp/web-password
export PARKING_WEB_PASSWORD_FILE=/tmp/web-password
trap 'rm -f /tmp/web-password' EXIT
java -Dloader.main=kr.ac.sejong.parkingtools.PrepareHandoffCliKt -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher --initialize-new-demo
java -Dloader.main=kr.ac.sejong.parkingtools.WebAccountCliKt -cp /app/app.jar org.springframework.boot.loader.launch.PropertiesLauncher create-user --username=handoff-viewer --role=VIEWER --zone-id=zone-01
chown -R 10001:10001 /state /state-copy
