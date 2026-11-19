# 실제 서버용 준비 파일 — 미배포

[설명과 검증 범위](../../docs/external-storage-preparation.md)를 먼저 읽는다. `prepare_server.py`는 새 폴더에 Compose JSON·장치별 ACL·S3 권한 정책·Nginx/모니터링 설정을 생성한다. 입력 예시는 실행용 자원이 아니다. 생성·문법 검사까지는 로컬 작업이며, 아래 설치·AWS 초기화·DNS·인증서 발급·서비스 기동은 외부 작업 승인 후 수행한다.

## 릴리스·개인 파일

- `/opt/parking/release`: 검토한 생성물. `mosquitto.conf`와 `mosquitto.acl`은 state의 broker 폴더에 각각 `mosquitto.conf`와 `acl`로 설치해야 한다.
- `/opt/parking/state/secrets`: Compose가 서비스별로 선택 마운트하는 비밀 파일. 호스트 부모는 관리자만 접근한다. 여러 UID가 읽는 파일은 마운트된 파일에만 읽기 권한을 주고 부모 디렉터리를 통째로 공유하지 않는다. 로컬 O2 방식처럼 파일 0444/부모 0700을 사용하거나 서비스별 UID 복사본을 공급한다. `aws-ledger-credentials`만은 UID 10001 소유·0600이어야 한다.
- `/opt/parking/state/backup-status`, `log-status`, `recovery`: 작업 UID 10001이 쓰고 backend/recovery가 읽는 0700 디렉터리. backup timer도 UID 10001로 실행한다. Docker 그룹 접근은 호스트 관리자 권한과 동등한 범위로 관리한다.
- broker의 `passwords`, `server.key`는 Mosquitto 실행 UID가 읽어야 한다. broker 부모를 제한하고 실제 고정 이미지의 UID/권한을 확인한다. Nginx에는 `web-tls/server.crt`, `server.key`, backend/blackbox에는 `tls/ca.crt` 신뢰 bundle을 공급한다. 로컬 localhost 인증서를 재사용하지 않는다.
- `/opt/parking/private`: 백업 작업의 별도 AWS credentials와 age 공개 recipient. 백업 작업 UID만 읽도록 소유권·0600을 설정한다. age 개인 identity는 이 폴더나 일반 runtime에 넣지 않는다.

AWS credentials 파일은 `[parking]` 섹션의 `aws_access_key_id`, `aws_secret_access_key`, 필요하면 `aws_session_token`만 허용한다. ambient 환경변수·기본 AWS profile·IMDS·사용자 지정 endpoint를 사용하지 않는다. 임시 자격증명 자동 갱신은 구현하지 않았으므로 만료 전 갱신·재시작 및 만료 실패 경보를 실제 환경에서 확인한다. 값은 Git·명령 인자·공개 문서에 넣지 않는다.

## 이미지와 최초 준비 순서

1. backend·web은 기존 Dockerfile, S3 gateway는 `infra/external-storage/Dockerfile`로 `docker buildx build --platform linux/amd64 --load`하여 시험한다. Grafana는 기존 `infra/monitoring/grafana/Dockerfile`의 오프라인 플러그인 포함 이미지를 사용한다. 승인된 registry 게시 후 digest로 고정하고 OS/architecture와 이전 릴리스 파일을 기록한다. 이 생성기는 registry에 push하지 않는다.
2. 백업 계정에서 별도 ledger/backup 버킷, Versioning·공개 차단·암호화·bucket-owner-enforced를 준비하고 생성된 최소 권한 정책을 실제로 검증한다. S3 gateway의 `init`은 별도 provisioning 자격으로 한 번만 실행한다. runtime 자격은 `If-Match` 갱신만 가능하다. 버킷 삭제/과거 head 복원을 정상 복구 절차로 사용하지 않는다.
3. 생성된 프로젝트 이름 `parking-server`가 다른 서비스/데이터와 겹치지 않는지 확인한다. PostgreSQL은 새 볼륨에서만 역할을 초기화한다. 기존 volume을 자동 인수하지 않는다.
4. 새 가상 환경은 postgres·revision-ledger만 먼저 실행한 뒤 `initialize-virtual`을 명시 실행한다. DB가 비어 있고 revision 두 볼륨도 비어 있어야 한다. initializer는 일시 root의 CHOWN/DAC_OVERRIDE로 private 파일을 읽고 마지막에 revision 소유자를 UID 10001로 바꾼다. 일반 backend에는 이 권한을 부여하지 않는다. 이후 grants·operator의 웹 계정 생성, backend·web·모니터링을 순서대로 준비한다. 일반 `migrate`를 먼저 실행해 빈 DB 조건을 깨지 않는다. 기존 설치의 업데이트는 migrate/validate·grants 경로로 분리한다.
5. bootstrap/기동 실패 후 초기화 명령을 반복하거나 DB/원장을 삭제하지 않는다. 당시 결과를 보존하고 상태를 확인한다. 실제 로그인·사건 저장/ACK·SSE·장치 ACL 거부를 확인하기 전 팀에 접속 주소를 넘기지 않는다.

## 인증서 발급·갱신 경계

인증서 발급 자체는 실행하지 않는다. 승인된 ACME 도구가 얻은 파일은 다음처럼 **검사·별도 staging만** 할 수 있다.

```sh
.local-state/external-python/bin/python tools/production/stage_certificate.py \
  --chain .local-state/tls/fullchain.pem --private-key .local-state/tls/privkey.pem \
  --trust .local-state/tls/trusted-ca-bundle.pem \
  --domain illegal-parking-detection.duckdns.org --output .local-state/tls/reviewed-new
```

정확한 SAN·키 일치·현재 유효성/최소 1일 여유·체인을 검사한다. 현재 설치 파일을 덮어쓰거나 reload하지 않는다. Nginx 설정에는 ACME webroot 경로가 있지만, 최초 인증서가 없으면 TLS web 컨테이너는 뜨지 않는다. 최초 발급은 DNS-01 또는 승인된 별도 HTTP challenge 단계에서 준비한다. 자동 DNS token 보관이나 ACME 계정 생성은 이 도구의 기능이 아니다.

갱신 파일 검증 후 관리자 작업으로 web/broker에 설치하고, `nginx -t` 후 reload, Mosquitto 인증서 재적용 후 실제 TLS 연결을 검사한다. directory bind mount 교체 시 기존 inode를 계속 보는 문제를 피하려면 해당 컨테이너를 재생성하고 연결 복귀를 확인한다. 실패하면 기록해 둔 직전 파일로 복귀한다. 아직 공인 인증서 자동 갱신과 실제 재접속을 인수하지 않았다.

## 백업·로그 작업

`parking-backup.service/.timer`는 표준 `/opt/parking` 배치의 설치 예시이며 자동으로 설치/활성화되지 않는다. 코드 checkout·가상환경·검토한 release를 지정된 경로에 배치하고 `/opt/parking/backup.env`에는 비밀값이 아닌 `PARKING_BACKUP_KEY_REF`만 넣는다. 매일 03:00 Asia/Seoul 실행하며, 재부팅 직후 예고 없이 앱을 중단하지 않도록 catch-up은 끈다. 누락은 24시간 지표로 감지하고 별도 수행한다. 발표·집중 작업 시간과 겹치지 않게 관리한다.

`server_backup.py --category protected`는 변경 직전·종료 전 사본이다. 일반 백업은 daily로 저장한다. 백업은 web/backend를 중지하며 실패 시 그대로 닫혀 있을 수 있다. 원인과 receipt를 확인한 후 직접 재개하고 실제 readiness/로그인을 검사한다. 원본 snapshot 검증과 암호문 전송 확인만 자동화했으며, 서버 밖 개인키 회수·다운로드 dump의 전체 제품 DB 복원/hold 해제는 O4 명시 인수 절차를 수행해야 한다.

`parking-log-copy.service/.timer`는 5분 로그 복사 예시다. 실패 시 이전 복사 경계를 유지한다. Docker 로그 rotation 전에 복사될 만큼 저장 공간·주기·장애 알림을 검증해야 한다. `systemctl status`만으로 외부 파일 보존 완료를 판단하지 않는다. 개인 복구 매체의 두 번째 사본과 증거 열람 확인은 별도다.

보존 삭제 작업은 별도 권한으로 수행한다. 백업 runtime에는 delete가 없다. 성공 daily 7세대 정책을 protected/evidence/revision에 적용하지 않는다. 이 구현은 어떤 기존 파일·S3 객체도 자동 삭제하지 않는다.
