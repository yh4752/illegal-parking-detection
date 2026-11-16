# O3 로컬 모니터링 구현 계획

2026-11-16 · 기존 O3 설계 실행 · 외부 배포·외부 알림 없음

**목표:** HTTPS 제품 경로의 수신·저장·ACK·조회 상태를 실제 계측하고, Prometheus와 Grafana에서 정상/실패의 차이를 설명할 수 있게 한다.

**구조:** Micrometer → 내부 management 9091 → Prometheus(15초 수집·7일/1GB 보존) → Grafana 대시보드 1개. 별도 `parking-monitoring` 환경에서 O2 Compose를 확장한다. web HTTPS 19443/HTTP 19480과 Grafana 13000만 localhost에 바인딩한다. Prometheus·management·exporter·DB·MQTT는 호스트 포트를 열지 않는다.

**기술:** Spring Boot 3.5.16/Micrometer, Prometheus, Grafana, node exporter와 blackbox exporter의 고정 버전 이미지. Grafana의 Prometheus 플러그인도 버전·공식 SHA-256을 고정해 이미지에 포함하고 자동 설치/갱신을 끈다. Python 로컬 도구, 기존 Playwright Chromium, promtool 규칙 시험.

기존 승인된 [운영 설계](deployment-operations-plan.md)의 O3 범위를 따른다. 구현은 이 세션에서 단일 에이전트가 진행하며 기존 O1/O2 변경과 계약을 보존한다.

## 지표의 의미와 구현 경계

| 질문 | 지표/관찰 위치 | 주의점 |
|---|---|---|
| 사건이 들어오고 어떤 결과가 났나? | MQTT adapter의 `parking_ingest_total{outcome}` | STORED/DUPLICATE/REJECTED는 커밋 반환 후 집계. 거부도 DB에 기록될 수 있음 |
| 저장이 실패하거나 느린가? | `parking_commit_failures_total`, `parking_ingest_commit_seconds` | DB 오류만 commit failure; timer는 수신 처리 시작부터 커밋 반환까지, ACK 시간 제외 |
| 장치에 결과를 발행했나? | `parking_ack_publish_total{result}` | MQTT 5 PUBACK 0/16 수락 확인. 브로커 수락과 장치 응용 ACK 처리는 다름 |
| 세 MQTT 연결이 살아 있나? | events/acks/telemetry 연결 callback gauge | TCP 단절 감지는 keepalive에 따라 늦을 수 있음 |
| 저장된 데이터를 조회할 수 있나? | 별도 DB·revision 읽기 probe, readiness health | 브로커 단절은 조회 readiness에 포함하지 않음; liveness에 DB를 넣지 않음 |
| 실시간 연결이 쌓이나? | SSE active/limit reject/slow close | 사용자·세션·구역 ID label 없음 |
| 자원이 모자라나? | 기본 JVM/Hikari 지표와 node exporter | macOS 호스트가 아니라 Docker Linux runtime/마운트 파일시스템 범위 명시 |
| HTTPS와 인증서는 정상인가? | 내부 blackbox HTTPS 검사·남은 유효기간 | 서버 전체 장애를 탐지하는 외부 관측은 O5. 짧은 로컬 인증서는 14일 경고 대상 |

라벨은 제한된 enum만 사용한다. 메시지/장치/사건/사용자 ID·토큰·payload·query는 지표에 넣지 않는다. 사건 어댑터 로그도 ID 없이 결과만 기록한다. 관리 endpoint는 별도 포트와 scrape 전용 Basic 인증으로 제한하며 제품 로그인 경로는 유지한다. secret은 파일로 공급한다.

node exporter에 Docker socket·privileged·호스트 루트 마운트를 주지 않는다. 로컬 Docker Linux 자원과 exporter가 보는 파일시스템을 측정한다. 실제 Linux 서버의 호스트 디스크·백업 마운트 검증은 O5에 남긴다. 백업 시각 지표는 O4의 실제 백업 검증 구현 이후에 추가하며 가짜 성공값을 만들지 않는다.

## 작업과 완료 조건

- [x] **계측 시험 → 구현:** `backend/.../operations/OperationalMetrics.kt`, MQTT adapter/config, SSE에 고정 라벨과 정확한 결과 집계 추가. 단위 시험에서 성공/DB 실패/ACK 실패/열린 트랜잭션을 구분하고 비밀 sentinel이 라벨에 없는지 확인한다.
- [x] **관리 경계:** `application-monitoring.yml`, `MonitoringConfiguration.kt`, 읽기 전용 readiness probe 구현. 별도 포트·인증·공개 경로 차단, DB down과 MQTT down에서 liveness/readiness 차이를 실제 검사한다.
- [x] **관찰 구성:** `infra/monitoring/compose.yaml`, Prometheus scrape/alerts, Grafana provisioning/dashboard 작성. `promtool check config`, `promtool test rules`로 문법·임계값·표본 수 조건을 검사한다.
- [x] **로컬 도구:** `tools/production/local_monitoring.py`와 O2 도구의 작은 확장점 추가. 프로젝트별 이미지 ID·설정 해시를 대조하고 기존 O2 환경을 변경하지 않는다. 새 학습 환경을 준비하고 반복 기동의 데이터 보존을 확인한다.
- [x] **실제 인수:** `tools/production/verify_o3.py`로 정상 사건, DB 쓰기 거부/DB 정지, ACK 권한 거부, 브로커 단절과 조회 유지, backend down을 발생시킨다. Prometheus query/alert 결과와 복귀를 확인한다. 변경한 자원은 finally에서 복구하고 임시 프로젝트만 정리한다.
- [x] **Grafana 브라우저:** 로그인·프로비저닝된 대시보드·실제 데이터 응답과 경보 표시를 Chromium으로 확인하고 비밀값 없는 화면을 저장한다.
- [x] **회귀/설명:** 백엔드 일반·DB 시험, O2 HTTPS 회귀, 문서/계약 검사. `docs/local-monitoring.md`, C4 그림, 상태/작업표/검증 JSON과 생성 HTML/SVG 갱신.

모든 장애·경보 인수는 로컬이며 외부 메시지를 보내지 않는다. 메모리/디스크를 실제로 고갈시키는 대신 자원 지표를 실제 수집하고 임계값 규칙은 합성 시계열로 시험한다. 실제 알림 전달·호스트 전체 장애 탐지는 외부 관측 경로가 정해지는 O5에서 수행한다.
