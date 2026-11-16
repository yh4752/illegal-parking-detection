#!/bin/sh
set -eu
umask 077
if [ "${1:-}" = create-user ]; then
  cp /run/secrets/web-password /tmp/account-password
  chmod 600 /tmp/account-password
  export PARKING_WEB_PASSWORD_FILE=/tmp/account-password
  trap 'rm -f /tmp/account-password' EXIT
fi
java -Dloader.main=kr.ac.sejong.parkingtools.WebAccountCliKt -cp /app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher "$@"
