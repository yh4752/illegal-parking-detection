# 로컬 운영 관찰 O3

사람용 설명·실행·지표 해석은 [로컬 모니터링 안내](../../docs/local-monitoring.md)를 먼저 읽는다. `compose.yaml`은 `infra/deploy/compose.yaml`과 `compose.local.yaml`에 더하는 overlay다. 경로는 첫 Compose 파일을 기준으로 해석한다.

- `prometheus/`: 15초 scrape, 13개 경보, promtool 시계열 시험 19개.
- `grafana/`: 내부 데이터 소스와 21개 패널 대시보드를 파일로 등록. Dockerfile은 공식 SHA-256을 검사한 Prometheus 플러그인 13.2.6만 포함하며 런타임 설치/갱신은 끈다.
- `blackbox.yml`: 시험 CA와 localhost SAN을 검증하는 내부 HTTPS 검사.
- `tools/production/local_monitoring.py`: `.local-state/monitoring`에서 준비·기동·조회. 실제 이미지 ID와 설정 해시를 대조한다.
- `tools/production/verify_o3.py`: 별도 임시 프로젝트에서 실제 장애·경보·브라우저 검사 후 정리.

호스트 공개 포트는 web과 Grafana의 `127.0.0.1` 바인딩뿐이다. management/Prometheus/exporter는 내부 전용이며 Grafana 계정과 scrape 계정은 제품 사용자와 분리한다. 외부 알림·실서버 배포·호스트 전체 디스크 관측은 이번 구성에 포함하지 않는다.
