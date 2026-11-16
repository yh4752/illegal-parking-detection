#!/bin/sh
set -eu
java -Dloader.main=kr.ac.sejong.parkingtools.PrepareHandoffCliKt -cp /app/app.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher --initialize-new-demo
chown -R 10001:10001 /state /state-copy
