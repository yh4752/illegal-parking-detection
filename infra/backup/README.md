# O4 로컬 백업·복원

먼저 [설명과 실습](../../docs/local-backup.md)을 읽는다. `tools/production/local_backup.py`가 O2·O3 Compose 위에 이 overlay를 적용한다. `verify_o4.py`는 별도 환경에서 실제 복원·차단·인수·재전송을 검증한다.

- `ledger.py`: 내부 전용 HTTP API, 명시 초기화, SQLite 버전/CAS 기록. GET head와 POST versions만 허용하며 삭제 API는 없다. 실제 공급자 어댑터가 아니다.
- `compose.yaml`: 독립 기록 서비스·오프라인 복구 CLI·O4 모니터링 추가. 호스트 공개는 localhost 제품·Grafana만 허용한다.
- `prometheus.yml`, `alerts.yml`, `rules.test.yml`: O3 수집과 O4 백업 검증 기록의 경보.
- `grafana.json`: 백업/복원 전용 3개 패널. O3 대시보드와 함께 별도 읽기 전용 폴더로 준비한다.

DB 덤프·키·토큰·SQLite 원장·실행 보고서는 `.local-state` 아래에만 생성한다. 키와 원장은 애플리케이션 백업 폴더 밖에 둔다. 같은 호스트에서의 이 분리는 실제 재해 독립성을 보장하지 않는다. 외부 저장소의 TLS·CAS·최신성·삭제 권한·키 보관과 보존 정책은 O5에서 인수한다.

복원은 항상 새 `parking_restore_*` DB에 수행한다. 실패한 단계는 닫힌 상태로 남기며 원본 DB나 과거 독립 기록을 삭제하지 않는다. 이미 사용한 범위를 수동으로 되돌려 재시도하지 않는다. 기존 자료의 `adopt`는 알려진 정상 로컬 쌍·DB와 아직 비어 있는 독립 저장소를 인수하는 오프라인 도구이며, 유실된 기록을 새로 만드는 우회 절차가 아니다.
