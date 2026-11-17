# M5 배포·운영 구현 명세

2026-11-16 · 설계 기준 main `03b6dd5` · 상태: O4_ACCEPTED_LOCAL

## 1. 범위·근거·책임

기존 [공개 데모 설계](public-demo-plan.md)의 M5/M6를 구체화한다. [ADR-010](../adr/010-deployment-operations.md)을 따른다. v1.43 원본·MQTT wire·현재 상태 판정은 변경하지 않는다. AI 담당자의 모델·보드 언어 결정과 분리한다. `app-prod`·`mqtt-ingest` 프로필과 O1 권한 설정은 구현했고 [운영 설정 안내](../../infra/deploy/README.md)에 실제 파일 매핑·명령을 기록했다. O2 Compose·Nginx도 [로컬 HTTPS](../local-https.md)에서 인수했다. O3 업무 지표와 내부 관리 경계는 [로컬 모니터링](../local-monitoring.md)에 구현했다. O4 복원 CLI는 [로컬 백업·복원](../local-backup.md)에서 구현·인수했다. 실제 외부 저장소·서버 공개는 **구현 예정**이다. O5는 [배포 준비 안내](../deployment-readiness.md)에 서버·비용·도메인·독립 저장소·키 보관 추천과 입력표를 작성했다. 추천값은 구매 확정이나 외부 인수 완료를 뜻하지 않는다.

설계 시점의 코드 근거: `infra/handoff/compose.yaml`, `EventIngestionConfiguration.kt`, `TelemetryConfiguration.kt`, `RevisionReservations.kt`, `application-database.yml`, `application-web-api.yml`. 당시 로컬 전용 수신·인증서/계정 설정 부재·runtime 마이그레이션 권한을 O1에서 분리했다. health만 노출하며, 두 revision 파일의 일치 검사로 두 파일과 DB가 함께 과거로 돌아가는 제한은 O4의 별도 기록과 백업 후 범위 예약으로 로컬에서 검증했다. 호스트 전체 유실과 독립 저장소 자체의 과거 복원은 별도 한계다.

## 2. 네트워크와 HTTPS

| 경로 | 연결 | 노출·규칙 |
|---|---|---|
| 브라우저 → Nginx | HTTPS 443 | React 정적 파일·`/api/`만 제공. 단속 서비스로 표현하지 않음 |
| 인터넷 → Nginx | HTTP 80 | ACME HTTP-01 challenge와 HTTPS 전환만. DNS-01 선택 시 80 사용 여부 재검토 |
| Nginx → Spring | 내부 HTTP 8080 | host ports 없음. 세션 Secure/HttpOnly/SameSite=Lax 유지 |
| Spring → PostgreSQL | 내부 5432 | host ports 없음. runtime DML만 허용 |
| Spring·가상 장치 → Mosquitto | 내부 MQTT TLS 8883 | 익명 금지·장치별 계정·ACL. 서버 인증서 SAN에 내부 접속 이름 포함 |
| 관리자 → Grafana | SSH 터널 | Grafana는 호스트 127.0.0.1에만 바인딩. Prometheus·관리 포트는 내부만 |
| 실제 보드 → Mosquitto | 외부 TLS 8883 | 첫 공개에서 차단. 보드 CA 신뢰·계정·망 확인 후 별도 단계로 개방 |

Compose 네트워크는 web/app, app/data, app/mqtt, monitoring으로 역할별 분리하고 DB·브로커의 불필요한 egress를 제한한다. 관리자 SSH는 키 인증과 허용 IP를 사용한다. 클라우드 방화벽과 실제 외부 포트 스캔을 함께 확인한다. Docker publish가 UFW만으로 차단된다고 가정하지 않는다 [S4].

Nginx는 `/api/stream`에 HTTP/1.1, `proxy_buffering off`, `proxy_cache off`, `proxy_read_timeout 60s`를 사용한다 [S1]. 현재 SSE keepalive 주기보다 timeout을 길게 유지하고 실제 프록시 시험으로 확인한다. `/actuator`, DB·MQTT·관리 포트는 public location에 연결하지 않는다. SPA fallback이 관리 경로를 200으로 위장하지 않도록 관리 경로는 명시적으로 404 처리한다. API 응답·인증 정보는 캐시하지 않고, 로그는 query string·쿠키·Authorization·본문을 기록하지 않는다.

`forward-headers-strategy=none`은 초기 운영에서도 유지한다. 현재 세션 API는 같은 출처이며 HTTPS 전환은 Nginx가 담당한다. 원본 접속 IP나 absolute URL이 필요해지면 Nginx가 헤더를 덮어쓰고 신뢰 프록시만 허용하는 별도 변경으로 시험한다.

인증서는 ACME 자동 갱신을 기본안으로 하고 만료 14일 전 경고한다. HTTP-01은 도메인/DNS와 80 접근이 있어야 한다. 시험용 자체 CA는 로컬 시험에만 쓰며 공개 인증서로 간주하지 않는다.

O2 구현은 이 표의 공개 서버 목표와 구분한다. web만 localhost 18443/18480에 바인딩하고, 별도 시험 CA와 고정 HTTPS redirect를 사용한다. web 인증서 갱신/reload·로그인·SSE·backend의 실제 IP 변경 후 DNS 복구를 검증했다. 일반 요청 로그는 서버 블록에서 기본 combined 로그를 덮어쓰며 query를 제외한다. Nginx 요청 오류 로그는 원본 URI 노출을 막기 위해 비활성화하고 상태 코드·backend 로그로 진단한다. 시스템 신뢰 저장소·DNS·ACME는 변경하지 않았다.

## 3. 자격증명·DB 권한·MQTT ACL

Compose secrets를 `/run/secrets`로 마운트하고 운영 Spring은 필수 configtree를 읽는다 [S2,S3]. 파일이 없으면 시작하지 않는다. O1에서 파일 이름을 `POSTGRES_PASSWORD`(runtime DB 암호), `parking.mqtt.password`, `PARKING_CURSOR_HMAC_KEY`로 고정했다. Compose secrets는 호스트 파일 암호화 서비스가 아니므로 원본 권한·디스크·암호화 백업을 별도로 관리한다. `.env`에는 비밀값 대신 이미지 digest·공개 도메인 등만 둔다. 운영에 `app-local` 또는 `mqtt-ingest-local`이 섞이면 시작을 거부한다.

| 계정 | 권한·제약 |
|---|---|
| DB owner/migrator | 일회성 migration job만 사용. 서비스 컨테이너에는 비밀번호 제공 안 함 |
| DB runtime | 필요한 테이블의 SELECT/INSERT/UPDATE/DELETE를 개별 허용. 스키마 CREATE/ALTER와 정책 원본 UPDATE/DELETE 금지. 계정·권한 변경용 DML은 운영자 CLI에 분리 |
| DB operator | 계정 생성/권한 회수 및 승인된 복구 작업. 일회성 관리 도구에서만 사용 |
| DB backup | 덤프에 필요한 읽기 권한. 암호는 백업 서비스에만 제공 |
| MQTT server | events/heartbeat/availability 구독, acks 발행만 |
| MQTT edge-01 | 자기 events/heartbeat/availability 발행, 자기 acks 구독만 |

첫 ACL은 `user edge-01` 아래 정확한 `parking/v2/devices/edge-01/{events,heartbeat,availability,acks}`를 지정한다. 장치 계정에는 `+`/`#` 토픽 권한을 주지 않는다. 서버만 `/devices/+/events`, `/heartbeat`, `/availability` 읽기와 `/devices/+/acks` 쓰기를 허용한다. 장치 B 토픽, 장치의 ACK 위조, 익명 연결을 모두 거부하는 실제 시험을 작성한다. 아직 구현하지 않은 commands/desired 토픽 권한은 미리 열지 않는다.

Mosquitto 2.0.22에 맞는 설정을 사용한다. 최신 온라인 문서의 2.1 플러그인 설정을 그대로 복사하지 않고 고정 이미지에서 `password_file`, `acl_file`, TLS listener를 확인한다 [S5]. MQTT 5·QoS·retained 거부·커밋 후 ACK·재접속 규칙은 유지한다. 서버 인증서 호스트 검증을 끄지 않는다. 초기 TLS+장치별 암호 인증을 사용하고 mTLS는 보드 지원 확인 후 별도 도입한다.

## 4. 배포·롤백

CI에서 테스트를 통과한 동일 커밋의 backend/web 이미지를 생성하고 digest를 release manifest에 기록한다. 최초 운영은 Linux x86_64 한 종류를 대상으로 하며 서버에서 빌드하지 않는다. 공급자·도메인·유료 자원은 실제 입력 전 생성하지 않는다.

O2 로컬 manifest는 `LOCAL_ONLY` 범위로 커밋·미커밋 소스 해시·설정 해시와 **로컬 이미지 ID**를 기록한다. 실행 Compose의 모든 서비스 이미지와 실제 컨테이너를 대조한다. 프로젝트별 이미지 태그를 보존해 다른 시험 빌드가 학습 환경의 이미지 참조를 없애지 않도록 한다. 이는 게시된 레지스트리 digest·깨끗한 커밋의 Linux x86_64 release 발행을 대신하지 않는다.

1. 이미지 digest·환경 설정·스키마 버전·백업 ID를 묶은 배포 계획을 만든다.
2. 최근 백업과 revision 독립 기록이 정상인지 검사한다. 같은 DB를 쓰는 backend가 둘 이상이면 중단한다.
3. 유지보수 응답으로 전환하고 수신·웹 프로세스를 정상 종료한다. 배포 중 장치는 로컬 outbox로 재시도하며 보존 용량·시각을 기록한다.
4. migrator로 Flyway validate/migrate 후 runtime 권한의 기동을 확인한다. runtime은 Flyway 실행을 끈다.
5. 새 프로세스의 revision gate·DB readiness, 로그인·SSE·MQTT 정상 왕복을 확인한 뒤 외부를 연다.
6. 이전 이미지와 호환되는 스키마 변경만 이미지 rollback한다. 비호환 변경은 자동 down migration 대신 유지보수 상태에서 수정하거나 승인된 복구 절차를 사용한다.

지금은 단일 인스턴스/프로세스 잠금 구조이므로 무중단 rolling·blue/green 두 backend 동시 실행을 사용하지 않는다. 재시작 시 세션은 무효화되고 사용자는 다시 로그인한다. 이 제한을 인계 문서에 표시한다.

## 5. 모니터링과 운영 목표

Prometheus 15초 수집·7일 보존, Grafana 대시보드 1개로 시작한다. 로그는 컨테이너당 10 MiB × 3개 회전으로 제한한다. 숫자는 초기 운영 설정이며 부하 검증 후 변경한다. Loki·분산 추적은 첫 범위에 넣지 않는다. O3에서 Prometheus registry와 내부 management 9091·파일 기반 전용 Basic 인증을 구현했다. Grafana는 localhost:13000, Prometheus와 exporter는 호스트 포트를 열지 않는다. 실제 검증 상태는 [로컬 모니터링 안내](../local-monitoring.md)와 현재 상태를 따른다 [S6].

| 신호 | 구현 지표 또는 후속 수집 대상 | 최초 경보·판정 |
|---|---|---|
| 프로세스/조회 준비 | liveness, readiness(DB+revision gate), scrape up | down 1분. gate 차단은 즉시 확인 대상 |
| MQTT 연결 | `parking_mqtt_connected{channel}` | 필수 수신/ACK/heartbeat 연결 각각 30초 단절 |
| 수신 결과 | `parking_ingest_total{outcome}`, `parking_commit_failures_total` | 정상 트래픽 대비 거부율, 커밋 실패 발생 경고 |
| 저장 지연 | `parking_ingest_commit_seconds` histogram | 5분 구간 표본 20건 이상에서 p95 > 1초 지속 시 경고 |
| ACK 발행 | `parking_ack_publish_total{result}` | 실패 증가 경고. publish 성공을 장치 수신 성공으로 간주하지 않음 |
| SSE | 연결 수·거부·느린 연결 종료 횟수 | 기존 세션당 3/전체 50 상한 근접 확인 |
| DB/JVM/호스트 | 풀 대기·사용량, heap·GC, 디스크·inode | 디스크 80% 경고·90% 긴급, 메모리/GC는 측정 후 조정 |
| 인증서 / 후속 백업 | 내부 HTTPS probe·인증서 잔여일 / 백업 지표는 O4 예정 | 인증서 14일 미만 / 백업 26시간은 O4 구현 시 연결 |

위 업무 지표와 21개 패널 대시보드·13개 경보 규칙을 구현했다. outcome/result/channel은 제한된 enum만 label로 사용한다. device/message/event ID·원문 토픽·URL·사용자 입력은 label에 넣지 않는다 [S7]. O3 사건 어댑터 로그도 ID를 제외하고 결과 코드만 남긴다. 원문 payload와 개인정보는 기록하지 않는다.

읽기 probe는 별도 worker에서 5초마다 읽기 전용 검사를 하고 수집은 캐시된 결과를 사용한다. node exporter는 Docker Linux runtime과 exporter 루트 파일시스템만 관찰한다. 실제 호스트와 DB/백업 마운트 관측은 O5에서 검증한다. ACK success는 MQTT 5 PUBACK reason code 0/16의 브로커 수락이며 장치 처리는 별도다.

브로커 단절만으로 조회 readiness를 내리지 않는다. liveness에 DB/브로커 장애를 연결해 무한 재시작시키지 않는다. 장치 OFFLINE은 제품 상태이고 운영 서비스 down과 구분한다. 서버 자체가 죽으면 내부 대시보드도 죽으므로 외부 HTTPS 가용성 검사와 외부 알림 경로를 O5에서 추가한다. 알림 수신처는 아직 미정이고 실제 알림을 보내지 않았다.

## 6. 백업과 복구 — DB 파일만 되돌리지 않기

**2026-11-17 승인 목표:** 매일 03:00 KST 및 배포·DB 변경 직전·운영 종료 전 백업, 성공 일별 7세대와 변경 직전·최종본 별도 보호. DB 손실 목표는 최대 24시간, 서비스 복구 목표는 장애 인지·계정/키 회수·서버 준비를 포함해 중단부터 2시간이다. 이전 모의 복원 60분·주별 4개 초기안은 [승인된 보존·복구 정책](../backup-recovery-policy.md)으로 대체한다. 확정 증거는 손실 0/15분 회수, 실행 중 로그는 5분마다 외부 복사, 발표 전환은 5분 목표이며 아직 외부 인수하지 않았다. 이미 ACK된 사건은 장치에서 삭제됐을 수 있으므로 DB 백업 이후의 ACK된 사건을 재전송으로 모두 복구한다고 주장하지 않는다.

PostgreSQL 17 `pg_dump -Fc`와 `pg_restore --exit-on-error`로 격리된 새 DB에 복원한다 [S8,S9]. 단일 DB 덤프 외에 계정/권한 생성 스크립트, release digest, 암호화된 설정, simulator SQLite 일관 사본도 관리한다. SQLite는 실행 중 파일 단순 복사 대신 backup API 또는 정상 중지 후 복사한다. 브로커 persistence를 사건 저장 완료의 근거로 사용하지 않는다.

백업 산출물마다 checksum·생성 시각·스키마/이미지 버전·암호화 키 참조·검증 결과를 기록한다. DB와 키를 같은 공개 저장소에 보관하지 않는다. 복원 훈련은 격리 DB에서 수행하며 기존 parking-local/handoff 볼륨을 덮어쓰지 않는다.

### revision 독립 기록의 추가 설계

`RevisionReservations`의 로컬 두 파일은 정상 재시작 보호다. 별도 오브젝트 저장소의 버전 보존·조건부 갱신(CAS)을 **구현할 독립 예약 기록 방식**으로 선택한다. O4는 별도 로컬 서비스의 버전/CAS 참조 어댑터를 구현했다. 실제 공급자 API·내구성·접근 권한·비용은 O5 입력 및 검증 대상이다. S3 호환이라는 이름만으로 동일 보장을 가정하지 않는다.

- 새 범위 예약과 최초 초기화 claim을 로컬 두 파일 및 외부 기록에 내구 저장·확인한 뒤에만 DB에 그 범위를 반영한다. 외부 기록은 DB 일일 백업에 묶어 함께 되돌리지 않는다.
- 독립 기록의 업데이트는 예상 이전 버전/해시를 검사한다. 실패하면 신규 범위 사용·초기화·복구 개방을 중단한다. 현재 확보한 범위에서 계속할 조건은 구현 시험으로 고정하며 초기 구현은 불확실 시 쓰기 차단을 우선한다.
- 기동 시 최신 독립 기록과 로컬 쌍/DB를 대조한다. 조회 불능·불일치·과거 복사본이면 자동 정상화하지 않고 유지보수로 시작한다. 두 로컬 파일만으로 호스트 유실 후 재개하지 않는다.
- 독립 기록 쓰기 계정에는 과거 버전 삭제 권한을 주지 않는다. 보관 정책·관리 계정 탈취까지 막는 보장은 별도이며 최신성 입증이 안 되면 복구 중단이다.

### 과거 백업 복원 절차

1. 외부 API·수신 gate를 닫고 서버를 중지한다. 세션/SSE를 종료하며 장치 재전송은 outbox에 남긴다.
2. 백업 무결성·권한·정책·latest 독립 예약 기록을 검사하고 별도 DB로 복원한다. 오래된 DB가 열린 프로세스에 자동 연결되지 않게 한다.
3. 구역별로 DB/로컬/독립 기록이 증명하는 최대 예약 상한 다음의 새 범위를 예약한다. 경계·정수 상한을 검증하며 초기화 claim을 보존한다. 기존 기동 코드의 단순 재시작만으로 이 절차를 대신하지 않는다.
4. 새로운 manifest를 로컬 쌍과 독립 저장소에 확정한 뒤 DB에 범위와 복구 hold를 반영한다. 파일 예약 후 DB 반영 전 종료 시 다음 시도는 이미 예약한 범위까지 건너뛴다. 운영자 수동 파일 편집으로 재사용하지 않는다.
5. 기존 snapshot은 과거 관측으로 취급한다. 상태·정책·heartbeat·수신 순번을 대조하고 최신 장치 보고로 수렴하는지 확인한다. 명령/desired를 자동 재발행하지 않는다.
6. 점검 보고서를 운영자가 명시 인수한 경우만 hold를 해제한다. 별도 상태 대조용 수신과 일반 수신의 구분을 구현·시험한 뒤 열며 hold를 일괄 false로 바꾸지 않는다.

반드시 시험할 실패: DB만 과거 복원, DB+로컬 두 파일 동시 과거 복원, 독립 기록 없음/불일치, 외부 기록 실패, 예약 각 단계의 프로세스 종료, 최신 중복 재전송, hold 해제 전 웹/SSE 접근. 운영 복원 CLI 구현·시험 전 공개 완료로 판정하지 않는다.

O4 구현: 인증 암호화 → 복호화 → 격리 pg_restore → 전 테이블 비교 → 새 범위 예약까지 통과해야 백업 성공 시각을 기록한다. 복원 시 원본 DB를 보존하고 새 DB를 hold로 열며 별도 CLI의 최신 장치 보고와 audit 증거·DB 지문·독립 버전·명시 보고서 해시 인수를 요구한다. 일반 사건·중복 ACK·heartbeat·배경 정리는 hold 중 중단한다. 상세 시험·제한·실측은 [O4 설명서](../local-backup.md)와 [검증 JSON](../../validation/local-backup-2026-11-16.json)을 따른다. 일일 자동 실행·자동 보존 삭제는 아직 설치하지 않았다.

## 7. 구현 전 검토 결과

| 발견한 빈틈 | 설계 반영 |
|---|---|
| 로컬 프로필에 TLS URL만 넣어 운영 가능하다고 오인 | 프로필 분리·인증/호스트 검증·기동 거부 시험을 O1에 지정 |
| Spring health UP을 수신/복구 가능과 동일시 | liveness·조회 readiness·MQTT 연결 지표 분리 |
| 두 파일을 외부에 매일 복사하면 최신 revision 보장이라고 오인 | 범위/claim 확정 전 독립 기록, 복원 시 새 범위 예약, 최신성 불명 시 차단 |
| ACK된 사건은 장치가 항상 재전송할 것이라는 가정 | DB RPO의 손실 가능성과 ACK 이후 outbox 삭제를 명시 |
| UFW와 비밀값 환경변수만으로 공개 운영 가능하다는 가정 | host ports 최소화·외부 검사·파일 secrets·권한 분리 |
| 인계 코드 검사를 운영 배포 성공으로 간주 | O1~O5 각각 증거, 외부 PC 인수는 O5에서만 완료 |

## 8. 공식 참고 자료

2026-11-16 확인. 아래 문서는 도구 동작의 근거이며 이 프로젝트의 수치 목표·서버 크기·보관 기간은 자체 설계값이다.

- S1 [Nginx proxy module](https://nginx.org/en/docs/http/ngx_http_proxy_module.html): 응답 버퍼링·읽기 timeout.
- S2 [Docker Compose secrets](https://docs.docker.com/compose/how-tos/use-secrets/): 서비스별 파일 마운트.
- S3 [Spring Boot 3.5 external configuration](https://docs.spring.io/spring-boot/3.5/reference/features/external-config.html): configtree.
- S4 [Docker firewall](https://docs.docker.com/engine/network/packet-filtering-firewalls/): publish와 호스트 방화벽 관계.
- S5 [Mosquitto 설정 문서](https://mosquitto.org/man/mosquitto-conf-5.html): TLS·인증·ACL 개념. 고정 2.0.22 이미지의 지원 설정을 구현 때 별도로 시험.
- S6 [Spring Boot 3.5 actuator](https://docs.spring.io/spring-boot/3.5/reference/actuator/endpoints.html): Prometheus endpoint의 registry 의존성·노출 설정.
- S7 [Prometheus metric naming](https://prometheus.io/docs/practices/naming/): 단위와 label 사용 원칙.
- S8 [PostgreSQL 17 pg_dump](https://www.postgresql.org/docs/17/app-pgdump.html): 일관된 논리 백업과 custom archive.
- S9 [PostgreSQL 17 pg_restore](https://www.postgresql.org/docs/17/app-pgrestore.html): archive 복원·오류 처리.
