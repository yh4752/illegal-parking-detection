FROM grafana/grafana:13.3.1@sha256:0615ec4fbd4072004b7d86b46ded63013a74b7d6eb1119273dd89e8fa25c13cf
USER root
ARG TARGETARCH
# Official signed Prometheus datasource 13.2.6; checksums from its Grafana catalog API.
# Install into the image, outside the writable Grafana data volume.
RUN case "$TARGETARCH" in \
      amd64) checksum=1384e582886998c505be71e33a7e6ab05eebb45aae5dd997a4bcfab478e6f503 ;; \
      arm64) checksum=524b5825594ab292f09ea55bc005ad59ce9c51847725d5fdbc5ad3e23fb6c06c ;; \
      *) exit 1 ;; \
    esac \
    && wget -q -O /tmp/prometheus.zip "https://grafana.com/api/plugins/prometheus/versions/13.2.6/download?os=linux&arch=${TARGETARCH}" \
    && echo "$checksum  /tmp/prometheus.zip" | sha256sum -c - \
    && mkdir -p /opt/parking-plugins \
    && unzip -q /tmp/prometheus.zip -d /opt/parking-plugins \
    && rm /tmp/prometheus.zip \
    && chmod -R a+rX /opt/parking-plugins
USER grafana
