# 운영 배포 준비

**2026-11-19:** 이 폴더의 로컬 O2/O3/O4 경로를 유지한다. 실제 서버용 생성물은 [infra/server](../server/README.md), S3·암호화 백업 구현은 [새 안내](../../docs/external-storage-preparation.md)를 따른다. 외부 배포는 수행하지 않았다.

**O1 운영 프로필·DB 권한·MQTT TLS/ACL과 O2 로컬 HTTPS Compose의 구현 경로**다. 먼저 [로컬 HTTPS 안내](../../docs/local-https.md)에서 구조와 실행 순서를 읽는다. O3 모니터링과 O4 로컬 독립 revision·복원도 인수했다. O5는 준비 추천 문서까지 작성했고 외부 저장소 구현·실제 서버 공개는 남아 있다. `app-prod`라는 이름만으로 외부 공개 조건을 충족하지 않는다. 기존 HTTP 인계 실행은 `infra/handoff`와 [인계 설명서](../../docs/frontend-handoff.md)를 따른다.

- [O1 개념·이해 확인 질문](../../docs/production-basics.md): TLS·계정·ACL·DB 역할을 먼저 구분한다.
- [배포 요약](../../docs/deployment-operations.md)
- [운영 구현 명세](../../docs/design/deployment-operations-plan.md)
- [구현 순서·완료 기준](../../docs/design/deployment-operations-workplan.json)
- [입력 양식](inputs.example.json): 비밀값 없이 채울 항목. `null`은 미확정이며 운영 기본값이 아니다.
- [O5 배포 준비](../../docs/deployment-readiness.md): 서버·도메인·독립 저장소·키 보관 추천과 준비 목록.
- [예산 예시](budget.example.json): 확인한 단가와 환율·사용량 가정을 분리한 계산. 확정 견적·지출 권한이 아니다.
- [O2 상세 계획](../../docs/design/local-https-plan.md): 로컬 Compose·인증서·이미지 기록과 인수 기준.

개인 입력은 저장소 루트 `.local-state/deploy/inputs.json`으로 복사해 기록한다. 자격증명은 이 JSON에도 넣지 않는다.

## O2 로컬 HTTPS 진입점

루트에서 `python3 tools/production/local_https.py prepare`를 실행한다. [compose.yaml](compose.yaml)과 [compose.local.yaml](compose.local.yaml)을 함께 사용해 `parking-https` 전용 새 DB·암호·시험 CA·이미지 ID 기록을 준비한다. HTTPS 18443/HTTP 18480만 `127.0.0.1`에 연결한다. 기본 Compose를 실제 운영 DB 자동 초기화 경로로 사용하지 않는다. local fixture는 새 `parking_handoff` DB에만 적용한다.

`node tools/production/open_https.mjs`로 별도 학습 브라우저를 연다. 시스템 인증서 신뢰 저장소는 변경하지 않는다. 로그인·가상 송신·중지/재시작·계정 관리·인증서 갱신 명령은 [실습 안내](../../docs/local-https.md)에 있다.

`python3 tools/production/verify_o2.py`는 매번 `parking-o2-*` 임시 프로젝트에서 실제 HTTPS·DB·TLS MQTT와 Chromium을 검사하고 자기 자원만 정리한다. Node/프론트 의존성·Playwright Chromium이 필요하며 O1 시험과 달리 호스트 Python MQTT 패키지는 필요하지 않다. 성공 표시는 `O2_ACCEPTANCE_PASSED`다. 로컬 이미지 ID는 게시된 레지스트리 digest가 아니며 외부 Linux 배포와 ACME 갱신은 별도 인수 대상이다.

## 운영 프로필과 파일 입력

`SPRING_PROFILES_ACTIVE=app-prod`는 `web-api`, `mqtt-ingest`, `database`를 포함한다. `mqtt-ingest-local`도 같은 MQTT 전송 구성을 사용하며 기존 평문 로컬 설정을 유지한다. 운영과 `*-local` 또는 `mqtt-learning`을 섞거나, Flyway 자동 실행·평문 MQTT·다른 DB 계정·Secure가 아닌 쿠키를 지정하면 DB 연결 전에 실패한다.

운영 필수 configtree 디렉터리는 `/run/secrets/`다. 다른 경로를 쓰면 `PARKING_SECRETS_DIR`에 끝 `/`까지 지정한다. 디렉터리·필수 파일이 없으면 환경변수에 값이 있어도 시작하지 않으며, 최종 설정이 파일 값과 다르면 중단한다. 실제 파일은 저장소 밖이나 Git에서 제외된 `.local-state/deploy/` 아래에 두고 호스트 접근 권한을 제한한다. 컨테이너 UID가 읽을 수 있는 파일만 해당 서비스에 읽기 전용으로 마운트한다.

| 파일 이름 | 매핑·내용 |
|---|---|
| `POSTGRES_PASSWORD` | `spring.datasource.password`: **parking_runtime 전용** 비밀번호 |
| `parking.mqtt.password` | `parking-server` 계정 비밀번호 |
| `PARKING_CURSOR_HMAC_KEY` | 32바이트 이상 난수의 Base64. 재시작·페이지 cursor 검증에 같은 키 사용 |

| 환경변수 | 비밀값이 아닌 필수 설정 |
|---|---|
| `PARKING_DB_URL` | 명시적인 PostgreSQL JDBC URL. DB runtime 사용자 이름은 `parking_runtime` |
| `PARKING_MQTT_URL` | `ssl://mosquitto:8883` 형식. URL에 자격증명·경로·query를 넣지 않음 |
| `PARKING_MQTT_CA_FILE` | 신뢰할 PEM CA 파일 경로. 접속 호스트와 인증서 SAN이 일치해야 함 |
| `PARKING_REVISION_PRIMARY` | 쓰기 가능한 기본 revision 파일 경로 |
| `PARKING_REVISION_BACKUP` | 다른 디렉터리에 있는 revision 복사본 경로 |

현재 revision 두 파일의 일치 검사·프로세스 잠금은 유지한다. 같은 호스트의 두 볼륨은 독립 재해 복구 기록이 아니다. O4의 별도 로컬 원장 참조 구현은 인수했으며 실제 외부 어댑터·호스트 유실 복원은 O5에서 구현·검증해야 한다. 세션은 프로세스 재시작 때 무효화된다.

## DB 권한 설정 순서

전용 DB의 관리자만 [create-roles.sql](sql/create-roles.sql)을 실행한다. 같은 역할 이름이 이미 있으면 중단하며 기존 DB를 자동 인수하거나 초기화하지 않는다. 역할마다 서로 다른 비밀번호를 비공개 provisioning 경로로 설정하고, 전용 DB 소유자를 `parking_migrator`로 지정한다. 스크립트에는 비밀번호가 없다.

1. 관리자: 네 역할 생성·암호 설정·전용 DB 생성. 기존 운영 DB의 소유권 이전은 별도 인수 검토 대상이다.
2. migrator: 일회성 CLI로 migrate/validate. 서비스·MQTT를 시작하지 않고 recovery hold를 해제하지 않는다.
3. migrator: 같은 DB에 `psql -X -v ON_ERROR_STOP=1 -f infra/deploy/sql/grants.sql` 적용. psql 연결 설정·암호는 비공개 파일로 공급한다. 새 마이그레이션 뒤에도 재적용한다.
4. runtime: `app-prod` 실행. 실제 로그인과 현재 역할이 모두 `parking_runtime`인지, 역할 속성·상위 역할 소속·테이블 소유권·스키마 CREATE 권한과 마이그레이션 상태가 맞는지 검사한다. 관리자 로그인 뒤 `SET ROLE`만 하는 방식은 거부한다.

일회성 migration 진입점은 아래와 같다. `app.jar`는 `bootJar` 산출물을 가리킨다. `PARKING_DB_URL`, `PARKING_DB_USERNAME=parking_migrator`, `SPRING_CONFIG_IMPORT=configtree:/run/migration-secrets/`를 지정하고 해당 디렉터리의 `POSTGRES_PASSWORD`만 migrator 암호로 공급한다. **이 디렉터리를 runtime에 마운트하지 않는다.**

```sh
java -Dloader.main=kr.ac.sejong.parkingtools.MigrateDatabaseCliKt \
  -cp app.jar org.springframework.boot.loader.launch.PropertiesLauncher --migrate
```

성공 마지막 표시는 `DATABASE_MIGRATED`다. 반복 실행은 이미 적용한 마이그레이션을 검증하며, 잘못된 역할은 `migration_account_required`로 중단한다. 구역·정책·사용자 초기 데이터 생성은 이 CLI의 역할이 아니다.

| 역할 | 허용 범위 |
|---|---|
| `parking_migrator` | DB·스키마 소유, 마이그레이션. 일회성 프로세스 전용 |
| `parking_runtime` | 조회, 사건/receipt/격리/상태/보관 만료에 필요한 개별 DML. 정책·계정 변경, DDL·TRUNCATE·Flyway 이력 변경 금지 |
| `parking_operator` | 사용자 생성/회수·구역 권한·감사 기록. 기존 `WebAccountCli` 사용, 데이터 복원 권한은 아직 부여하지 않음 |
| `parking_backup` | 기존 테이블 SELECT와 dump. 쓰기·DDL 금지. 새 테이블은 grants 재적용 후 읽기 허용 |

PostgreSQL의 `SELECT FOR UPDATE`에는 UPDATE 권한도 필요하므로 runtime의 `device`에는 `device_id` 열만 허용한다. 실제 등록 ID 변경은 구역 FK로 막히며 `enabled`, 구역, 자격증명 변경 권한은 없다. `grants.sql`은 소유자 권한을 runtime으로 이전하지 않고 PUBLIC의 스키마·DB 접근도 제한한다. DB 계정과 웹의 VIEWER/OPERATOR 역할은 서로 다른 권한이다.

## MQTT TLS와 장치 인증

[Mosquitto 설정](mosquitto/mosquitto.conf)은 2.0.22용이며 TLS 8883만 수신한다. `/mosquitto/config/`에 `mosquitto.conf`, [acl](mosquitto/acl), 해시된 `passwords`, `server.crt`, `server.key`를 공급한다. 현재 로컬 Compose에서는 호스트 포트로 publish하지 않는다. [조기 팀 통합 준비](../../docs/team-integration-preparation.md)에 따른 외부 8883 공개는 별도 운영 오버레이·도메인 인증서·장치별 ACL 인수 후 수행하며 아직 구현·배포하지 않았다. 암호 파일은 `mosquitto_passwd`의 대화형 입력 등 비공개 경로로 준비하고 평문 암호를 CLI 인자나 Git에 넣지 않는다.

`parking-server`는 events/heartbeat/availability 읽기와 acks 쓰기만, `edge-01`은 자기 세 토픽 쓰기와 자기 acks 읽기만 허용한다. 장치에 wildcard 권한이나 commands/desired 권한을 미리 주지 않는다. TLS CA·호스트 검증을 서버와 가상 장치 모두 유지한다. MQTT 프로토콜 PUBACK는 DB 커밋 후 보내는 응용 ACK와 다르다.

가상 장치에는 `--tls-ca`, `--username`, `--password-file`을 함께 지정한다. 비밀번호 파일은 한 줄이고 로그에 출력하지 않는다. 예를 들어 기존 장치 명령에 아래 옵션을 추가한다.

```sh
python3 simulator/device.py --host mosquitto --port 8883 \
  --tls-ca /run/tls/ca.crt --username edge-01 \
  --password-file /run/secrets/edge-password \
  --state /state/device.sqlite --seconds 30
```

## 격리 인수 시험

저장소 루트에서 Python 3.10+, `simulator/requirements.txt` 의존성, OpenSSL, 실행 중인 Docker/Compose가 필요하다.

```sh
python3 tools/production/verify_o1.py
```

매번 임의 이름 `parking-o1-*`의 DB·브로커·revision·가상 장치 볼륨을 만든다. 테스트용 암호·CA는 `.local-state/o1/`에 생성하고 종료 시 해당 자원만 삭제한다. 기존 `parking-local`·`parking-handoff`를 사용하지 않는다. 이미지를 처음 받거나 빌드하면 5~15분, 캐시가 있으면 약 2~4분이 걸릴 수 있다. localhost의 임시 MQTT 포트는 거부 시험용이며 운영 Compose가 아니다. 시험 단계마다 `PASS:`를 출력하고 실패하면 비밀값을 가린 예외와 로그를 남긴다. 전체 성공 표시는 `O1_ACCEPTANCE_PASSED`다.

실제 실행 결과와 미검증 범위는 [현재 상태](../../docs/status.md)를 따른다. CI에도 같은 인수 시험을 연결한다. 외부 HTTPS·계정 전달·백업 복원 인수는 이 시험에 포함되지 않는다.

설정 근거: [Spring Boot 3.5 configtree](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html#features.external-config.files.configtrees), [PostgreSQL GRANT](https://www.postgresql.org/docs/17/sql-grant.html), [Paho 1.2.5 연결 옵션](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.mqttv5.client/src/main/java/org/eclipse/paho/mqttv5/client/MqttConnectionOptions.java). Mosquitto 설정은 고정된 2.0.22 이미지로 시험한다.

O3 구성은 [모니터링 overlay](../monitoring/README.md), 설명은 [로컬 모니터링](../../docs/local-monitoring.md)을 따른다.

O4는 이 구성에 [backup overlay](../backup/README.md)를 더합니다. [사람용 복원 순서](../../docs/local-backup.md)에 따라 별도 환경에서 검증하며 기존 O2/O3 자료를 자동 인수하지 않습니다.
