# 사건 저장과 저장 완료 응답 실습

2026-11-13 · v1.43 계약 + [저장 전 검증 기준](design/event-ingest-v1.md)

이제 서버가 검증을 통과한 사건을 PostgreSQL에 저장하고, **커밋이 끝난 뒤** 장치의 `/acks` 토픽으로 결과를 보낸다. 실제 차량 판단은 하지 않는다. 이번 단계에는 조회 API가 없으므로 저장 결과는 SQL로 확인한다.

| 실행 프로필 | 하는 일 | 응용 ACK |
|---|---|---|
| `mqtt-learning` | `learning/hello` 수신·학습용 검사 | 없음 |
| `mqtt-validation-local` | 정식 메시지의 형식·의미·장치 등록 검사 | 없음 |
| `mqtt-ingest-local` | 검증·중복 처리·사건 저장·결과 회신 | 있음 |

새 프로필은 `database`를 함께 켠다. 기존 검증/학습 프로필과 동시에 실행하지 않는다. 검증 모드와 저장 모드의 수신 client ID가 같으므로 서버 프로세스도 하나만 실행한다.

## 1. 예제 정책 등록

기존 Spring 서버 터미널에서 `Ctrl+C`로 종료한다. Docker Desktop·PostgreSQL·Mosquitto는 켜 둔다. 아래 명령은 저장소 루트에서 시작한다. 처음 실행하는 사람은 먼저 [DB 준비](database.md)를 완료해야 한다.

```sh
cd infra
docker compose --env-file .env.example up -d postgres mosquitto
docker compose --env-file .env.example exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U parking -d parking < sql/register-local-device.sql
docker compose --env-file .env.example exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U parking -d parking < sql/register-local-policy.sql
```

원본 `guidance_complete`는 `zone-01`의 정책 3을 참조한다. 따라서 해당 정책이 DB에 있어야 한다. SQL은 원본 `config_desired.desired_policy`를 등록하며 다른 내용의 기존 정책을 덮어쓰지 않는다. 정책 작성자 외래키를 위한 비활성 fixture 계정은 로그인이 불가능하다. 예제 좌표는 실험 장소의 보정값이 아니다.

## 2. 저장 모드로 시작

같은 터미널에서 다음을 실행한다. 비밀번호를 바꿨다면 실제 로컬 DB 설정과 맞춘다.

```sh
cd ../backend
export POSTGRES_PASSWORD=replace-with-your-local-password
./gradlew bootRun --args='--spring.profiles.active=mqtt-ingest-local'
```

처음에는 Flyway가 `V2__inbound_quarantine.sql`을 추가 적용한다. 기존 V1은 수정하지 않았다. 구역 상태에 사용할 revision 범위도 먼저 확보한다. 아래 로그가 수신 준비를 뜻한다.

```text
EVENT_INGEST_READY topic=parking/v2/devices/+/events mode=local_persistence
```

`bootRun`은 서버가 실행되는 동안 끝나지 않는다. health의 UP만으로 MQTT 저장 완료를 판단하지 않는다.

## 3. 응답을 먼저 구독

두 번째 터미널의 `parking-system/infra`에서 실행하고 대기한다.

```sh
docker compose --env-file .env.example exec mosquitto \
  mosquitto_sub -h localhost -p 1883 -V mqttv5 -q 1 \
  -t parking/v2/devices/edge-01/acks -v
```

응용 ACK는 retained 메시지가 아니다. **발행 전에 구독해야** 이번 응답을 볼 수 있다. 출력 후에도 구독 명령은 다음 응답을 기다린다.

## 4. 정상 발행과 재전송

세 번째 터미널의 `parking-system/infra`에서 실행한다. `-s`는 표준 입력 전체를 메시지 본문으로 읽는 옵션이며 `-r`은 사용하지 않는다.

```sh
python3 ../tools/event_fixture.py --message-id storage-001 --device-seq 10 |
  docker compose --env-file .env.example exec -T mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -q 1 \
  -t parking/v2/devices/edge-01/events -s
```

최초 처리라면 다음 형태의 응답을 받는다. `committed_at`은 실제 서버 시각이다.

```json
{
  "schema_version": 2,
  "device_id": "edge-01",
  "message_id": "storage-001",
  "status": "STORED",
  "error_code": null,
  "committed_at": "2026-11-13T07:00:00Z"
}
```

동일 명령을 한 번 더 실행하면 `DUPLICATE`다. 이미 이 실습을 실행했다면 첫 발행부터 DUPLICATE일 수 있다. 새 사건 보고는 새 message_id를 사용하고, 재전송은 ID·본문을 그대로 유지한다. 예전 E09/E11 예제는 같은 `msg-010`을 재사용하므로 서로 다른 신규 시험에 그대로 쓰면 충돌한다.

| 입력 | 기대 결과 |
|---|---|
| 새 ID·정상 메시지 | `STORED` |
| 같은 ID·같은 내용 재전송 | `DUPLICATE`, 이력·상태 재갱신 없음 |
| `storage-001`을 유지하고 순번을 11로 바꿔 발행 | `REJECTED / MESSAGE_ID_CONFLICT`, 원래 기록 보존 |
| 새 ID의 seq 12 → 다른 새 ID의 seq 10 | 두 원문 저장, 현재 상태는 12 유지 |
| 이미 거부된 메시지를 그대로 재전송 | 최초 `REJECTED`와 오류 코드 유지 |
| DB 저장·커밋 실패 | 응용 ACK 없음, 동일 메시지를 나중에 재전송 |

브로커가 MQTT 메시지를 받았다는 **PUBACK**와 서버가 보낸 **저장 결과 ACK**는 다르다. `mosquitto_pub` 종료만으로 DB 저장을 확정하지 않는다. 서버의 `EVENT_COMMITTED`는 커밋, `EVENT_ACK_PUBLISHED`는 ACK 발행 완료다. 후자는 장치 애플리케이션이 ACK를 처리했다는 증명까지 포함하지 않는다.

## 5. SQL로 확인

`infra`에서 실행한다.

```sh
docker compose --env-file .env.example exec postgres \
  psql -U parking -d parking -c "SELECT device_id,message_id,outcome,error_code FROM inbound_receipt ORDER BY first_received_at;"
docker compose --env-file .env.example exec postgres \
  psql -U parking -d parking -c "SELECT event_id,state,last_device_seq FROM incident;"
docker compose --env-file .env.example exec postgres \
  psql -U parking -d parking -c "SELECT zone_id,current_event_id,last_device_seq,view_revision,connection_state FROM zone_view;"
```

기존 예제를 처음 정상 저장했다면 사건은 GUIDED, 구역의 last_device_seq는 10이다. `connection_state=OFFLINE`은 정상이다. 사건 보고만으로 장치의 현재 연결 상태를 온라인으로 추정하지 않으며 heartbeat는 후속 구현이다.

## 구현 위치와 남은 범위

| 코드 | 책임 |
|---|---|
| `EventIngestionService` | 엄격한 파싱·상관 ID·canonical hash, 저장 업무 호출 |
| `EventTransactionStore` | 구역/장치 잠금, 정책·소유권·중복·역순 검사, 원자 저장 |
| `RevisionReservations` | DB 트랜잭션 밖에서 상태 번호 범위 예약 |
| `EventIngestionMessageHandler` | MQTT 전송 조건 검사, 커밋 결과의 ACK 발행 |
| `EventRetentionJob` | 원문 30일 정리, 종료 요약 tombstone, 최소 receipt 보존 |

`IMAGE_STATUS_CHANGED`는 검증 후 `unsupported_image_status`로 ACK 없이 남긴다. 사진 저장이 구현되기 전에 이를 STORED로 응답하면 장치가 사진 관련 보고를 지울 수 있기 때문이다. 첫 연동에서는 사진 보고를 활성화하지 않는다. 명령 결과·heartbeat·실제 보드 SDK·API/SSE·로그인·배포는 후속 단계다.

현재는 단일 서버·로컬 익명 MQTT를 사용하는 개발 프로필이다. 다중 서버나 수백 건 급증에 대한 처리 성능은 측정하지 않았다. 파일 예약 gate는 한 프로세스의 쓰기를 직렬화하며 DB 잠금·고유키도 유지한다. 장치/구역별 병렬화는 별도 부하 시험 뒤 결정한다. 이 모드를 외부 공개용 인증·TLS·ACL 구성으로 간주하지 않는다.

## revision 파일과 재시작

구역 상태의 `view_revision`은 프론트가 새 상태를 구분할 번호다. 설계에 따라 100만 개 범위를 파일에 먼저 예약하고 DB에 반영한다. 기본 파일은 저장소의 `.local-state/revisions/`와 `.local-state/revisions-copy/` 아래에 생기며 Git에 포함하지 않는다. 정상 종료·재시작 시 같은 파일을 유지한다. DB만 남긴 채 파일을 삭제하면 서버는 번호를 추정하지 않고 중단한다.

두 경로는 같은 PC에 있으므로 재해 복구용 이중화가 아니다. 파일 유실·복사본 불일치·DB 예약 범위 불일치 시 중단하지만, **같은 예약 범위 안의 과거 DB 복원을 모두 자동 감지하지는 못한다.** 복원 시 반드시 수신/발행을 중지해야 한다. D6의 새 범위 건너뛰기·상태 대조·운영자 인수 절차는 아직 구현하지 않았다. 복원 후 이 프로필을 바로 켜거나 파일을 지워 우회하지 않는다.

## 검증

`backend`에서 `./gradlew test databaseTest bootJar`를 실행한다. 일반 126개·실제 PostgreSQL 30개 시험이 통과했다. DB 시험은 전용 `it_...` 스키마에서 실제 커밋·커밋 실패·ACK 전송 예외를 사용하고, 사용자 public 데이터는 변경하지 않는다. 상세 근거는 [실행 기록](../validation/event-storage-2026-11-13.json)에 있다. 이후 사용자 실습에서 정상 1건의 실제 Mosquitto 왕복을 확인했다. 16:51:42.637 KST에 storage-001 커밋, .642에 ACK 발행 로그와 별도 구독자의 STORED 수신 로그를 확인했다. 이어 동일 storage-001의 DUPLICATE 응답(committed_at=2026-11-13T07:54:14.508581Z)을 구독자 로그로 확인했다. 이후 사용자가 event_message를 직접 조회해 edge-01/storage-001의 원문이 1건임을 확인했다. 이어 storage-001의 순번을 11로 변경한 재전송에서 REJECTED/MESSAGE_ID_CONFLICT 응답(07:57:46.952358Z)을 확인했다. 충돌 후 사용자 SQL 조회에서 원문 seq=10, receipt STORED, 격리 오류 MESSAGE_ID_CONFLICT가 각각 1행임을 확인했다. 이어 storage-012(seq 12)와 storage-011(seq 11)의 순서로 발행하여 두 건 모두 커밋·STORED 수신을 확인했다. 사용자 SQL 조회로 두 원문이 모두 존재하고 incident(evt-001)·zone_view(zone-01)의 last_device_seq가 12임을 확인했다. 정상·중복·충돌·역순의 실제 MQTT/SQL 실습을 완료했다. 실패 경로의 실제 브로커 시험은 아직 미확인이다. 다음 구현은 기존 계약의 상태 조회 API다. 자동 시험과 사용자 실습 근거는 구분한다.
