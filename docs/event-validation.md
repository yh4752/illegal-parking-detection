# 정식 사건 메시지 수신·검증

v1.43의 `EventMessage`를 사용하는 제품 수신 경계다. 현재는 PC에서 계약과 등록을 확인하는 단계로, `mqtt-validation-local` 프로필에서만 켠다. 기존 `learning/hello` 실습과 구분한다.

> 저장 전 보완 기준은 [사건 검증 설계](design/event-ingest-v1.md)다. 단일 메시지 의미 검사 V04~V12까지 구현했다. 기존 DB 상태와 비교하는 저장 판단 D01~D08은 아직 구현 전이다.

## 어디까지 검사하나요?

| 순서 | 기준 | 구현 |
|---|---|---|
| 전송 | C2: events, QoS 1, retained 끔 | events만 구독. QoS·retained 검사. MQTT 5의 Retain As Published로 실시간 발행의 retain 플래그도 유지 |
| 입력 | SC06: UTF-8, 엄격한 JSON, 최대 65,536바이트 | 크기 초과·잘못된 UTF-8·중복 키·NaN/Infinity·후행 JSON 거부 |
| 형식 | parking.schema.json의 EventMessage | 필수·nullable·추가 필드·숫자 범위·상태 조합. date-time 형식 검사도 활성화 |
| 값 사이 관계 | SC01·C1 | envelope와 snapshot의 device_seq/boot_id/clock_synced, event와 image_update의 사건 참조 일치 |
| 의미 조합 | V04~V12 | 시각 불신·정차시간, 중지·모드, 정책 수신/적용, 사건 상태·횟수·미해소, 종료 요약, 같은 사건의 공통 값, 보류 우선순위 |
| 등록 | C1·SC09의 등록 관계 | 토픽과 본문의 device_id 일치, DB의 device_id/zone_id/enabled 확인 |

Gradle이 보존본의 `parking.schema.json`을 그대로 실행 파일에 포함한다. 검증용 축약 Schema를 따로 유지하지 않는다. networknt JSON Schema Validator 1.5.9의 Draft 2020-12 검증기를 사용하며 YAML 입력 모듈은 제외한다. 일반 Jackson 파싱과 Schema 검증은 역할이 다르다.

`EVENT_CONTRACT_VALIDATED ... stored=false ack_sent=false`는 **이 표의 검사 통과**다. 전체 업무 검증·저장 성공을 뜻하지 않는다. 기존 정책 버전 존재·정책 내용·중복 ID의 내용 비교·사건별 순서·종료 상태 보호는 저장 트랜잭션에서 이어서 구현한다. 등록 정보도 저장 직전 같은 트랜잭션에서 재확인해야 한다. 과거 사건의 event_id가 현재 snapshot.current_event_id와 다를 수 있으므로 두 값을 무조건 같게 강제하지 않는다.

오류 로그 `EVENT_INVALID`는 서버 내부 진단이며 wire의 `REJECTED` ACK가 아니다. V04~V12에서 거부하면 `rule_id`에 설계 규칙을 함께 남긴다. 기존 형식·참조·등록 오류는 `rule_id=none`이다. DB 장애는 `EVENT_VALIDATION_UNAVAILABLE reason=database`로 구분한다. 모두 응용 ACK를 보내지 않는다. 원문과 개인정보가 로그에 남지 않도록 전체 payload는 출력하지 않는다.

## 현재 제한

- 로컬 Mosquitto는 익명·평문이다. DB 등록 조회와 토픽 비교는 **인증된 발신자 확인을 대신하지 않는다.** 운영 TLS·계정·ACL은 이후 통합하며 이 프로필을 원격 운영에 쓰지 않는다.
- DB에 사건·수신 이력·현재 snapshot을 저장하지 않고, 오류 원문 격리도 아직 없다. STORED/DUPLICATE/REJECTED ACK는 모두 미발행이다. MQTT 자체 PUBACK는 계속 발생하며 저장 확인이 아니다.
- heartbeat·명령 결과·설정 보고·availability는 이번 구독 대상이 아니다. 이벤트 예제를 받아도 ONLINE으로 바꾸지 않는다.
- 고정 Client ID `serverin0001`이므로 한 서버만 실행한다. Keep Alive 15초·연결 제한 10초·Receive Maximum 8·Clean Start=true·Session Expiry=0을 사용한다. Paho의 기본 자동 재연결을 사용하며 D1의 분산 재시도·충돌 중단을 모두 구현한 상태는 아니다.
- 장치 시계의 실제 정확성·차량 인식 결과는 서버가 이 메시지만으로 검증할 수 없다. 사진 재사용·토큰·인증·보관 기간·물리 출력 등의 의미 검사는 해당 기능에서 수행한다.

## 실행 순서

기존 Spring은 종료하고 PostgreSQL과 Mosquitto는 켜 둔다. `mqtt-learning`과 함께 활성화하지 않는다.

**1. 등록 자료 준비 — infra 폴더**

```sh
docker compose --env-file .env.example exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U parking -d parking < sql/register-local-device.sql
```

명시적으로 `edge-01`과 `zone-01`, 미확인 zone_view를 등록한다. 기존 정보는 덮어쓰지 않으며 같은 ID의 다른 연결 관계나 비활성 장치면 오류로 중단한다. 토큰 해시의 0 값은 로컬 자리표시자다. 사용 가능한 장치 토큰·사용자 계정·정책은 생성하지 않는다. 이 SQL은 서버 시작 시 자동 실행하지 않는다.

**2. 서버 실행 — backend 폴더**

```sh
export POSTGRES_PASSWORD=replace-with-your-local-password
./gradlew bootRun --args='--spring.profiles.active=mqtt-validation-local'
```

앞선 `.env.example`을 사용한 경우의 비밀번호다. 실제 로컬 값을 바꿨다면 일치시킨다. 이 프로필은 `database`도 함께 활성화한다. `EVENT_VALIDATION_READY`가 나온 뒤 발행한다.

**3. 정상 예제 발행 — 별도 터미널, infra 폴더**

```sh
python3 ../tools/event_fixture.py | docker compose --env-file .env.example exec -T mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -q 1 \
  -t parking/v2/devices/edge-01/events -s
```

도구는 원본 examples.json의 guidance_complete를 읽어 출력한다. 자동으로 발행하지 않으며 위 파이프가 출력 바이트를 `mosquitto_pub`의 표준 입력에 전달한다. `-s`는 표준 입력 전체를 메시지 한 건으로, `-q 1`은 QoS 1로 보내라는 Mosquitto 옵션이다. `-r`을 쓰지 않으므로 retained는 꺼져 있다. 예제 시각은 과거 기록 그대로 유지한다.

| 입력 변경 | 예상 로그 reason |
|---|---|
| 기본 예제 | EVENT_CONTRACT_VALIDATED, stored=false ack_sent=false |
| 도구에 `--case sequence-mismatch` 추가 | snapshot_device_seq |
| 도구에 `--case missing-field` 추가 | schema |
| `--case unknown-device`와 발행 토픽도 edge-unknown으로 변경 | device_registration |
| 본문 그대로 발행 토픽만 edge-02로 변경 | topic_device_mismatch |
| 발행 명령의 `-q 1`을 `-q 0`으로 변경 | delivery_options |

오류 다음에 기본 예제를 다시 보내 정상 검사 지속을 확인한다. 같은 메시지를 반복해도 아직 중복 처리 여부는 판정하지 않는다.

## 추가 의미 검사 직접 확인

변경된 코드를 사용하려면 실행 중인 Spring을 종료하고 위 `mqtt-validation-local` 프로필로 다시 시작한다. PostgreSQL·Mosquitto는 계속 켜 둔다. READY 이후 `infra`에서 아래 명령을 실행한다.

```sh
python3 ../tools/event_fixture.py --design-case E11 | docker compose --env-file .env.example exec -T mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -q 1 \
  -t parking/v2/devices/edge-01/events -s
```

E11은 JSON 형식은 맞지만 **시각 불신 상태에서 정차시간 304,000ms를 유지**한 모순이다. 예상 로그는 `EVENT_INVALID reason=clock_untrusted_stop_time rule_id=V04 ack_sent=false`다.

같은 명령에서 `E11`만 `E09`로 바꿔 보내면 시각 불신·보류·정차시간 0인 정상 조합으로 `EVENT_CONTRACT_VALIDATED ... stored=false ack_sent=false`가 나와야 한다. 과거에 신뢰한 사건 시작 시각은 그대로 보존한다.

| 예제 | 의미 | 기대 결과 |
|---|---|---|
| E11 | 시각 불신인데 정차시간 유지 | V04 거부 |
| E09 | 시각 불신, 현재 보류·정차시간 0, 과거 시작 시각 보존 | 통과 |
| E21 | 같은 사건의 안내 횟수가 event=1, snapshot=2 | V11 거부 |
| E22 | 서로 다른 사건의 안내 횟수가 각각 1과 2 | 통과 |

`--design-case`는 프로젝트에서 만든 예제 선택 옵션이며 MQTT 자체 명령이 아니다. [설계 사례 파일](design/event-validation-cases.json)을 읽어 원본 예제에 지정된 변경만 적용한다. `--case`와 동시에 사용하지 않는다. 이 예제들은 저장 전 검증용이며 서로 같은 message_id를 사용할 수 있다. 저장·중복 시험에는 해당 단계의 독립 메시지 시나리오를 사용한다.

## 자동 검사와 코드 위치

PostgreSQL이 켜져 있고 환경변수가 설정된 `backend`에서:

```sh
./gradlew test databaseTest bootJar
```

| 코드 | 역할 |
|---|---|
| `device/application/EventMessageValidator.kt` | 엄격한 파싱·Schema·필드 관계·등록 검사 |
| `device/application/EventMessageSemantics.kt` | Schema·V03 통과 후 V04~V12 의미 검사. DB·시계·출력 제어 없음 |
| `device/adapter/jdbc/JdbcDeviceRegistrationLookup.kt` | SQL로 활성 장치와 구역 관계 조회 |
| `device/adapter/mqtt/EventValidationMessageHandler.kt` | 전송 메타데이터 확인·진단 로그 |
| `configuration/EventValidationConfiguration.kt` | 로컬 MQTT와 검증기 연결 |

단위 테스트는 원본 사건 예제 3개, 잘못된 입력·숫자/크기 경계·시각·필드 관계·오류 후 정상 처리·DB 장애 로그를 다룬다. 의미 검사 56개에는 설계 예제 26개, 같은 사건의 일곱 공통 필드, 과거/현재 사건 분리, 중지 우선순위, 시각 복구, 정상 출력 과도 상태가 포함된다. 일반 테스트는 총 122개다. databaseTest에는 기존 장치 등록 조회 시험이 있다. 이번 의미 검사 변경에서는 DB 테스트를 재실행하지 않았다. 자동 테스트만으로 새 코드의 실제 브로커 전달이나 응용 ACK를 검증했다고 하지 않는다. 실행 결과는 [현재 상태](status.md)에 기록한다.

근거: [계약 원본](contracts.md), [C1](../reference/v1.43/spec/protocol/C1.md), [C2](../reference/v1.43/spec/protocol/C2.md), [의미 검사](../reference/v1.43/개발자료/09_구현설계/contracts/semantic_checks.json), [검증기 공식 문서](https://github.com/networknt/json-schema-validator/tree/1.5.9).

## 사용자 로컬 수신 확인

2026-11-13, PID 26811: 13:37:44 정상 예제 통과 → 13:39:21 snapshot_device_seq 모순 거부 → 13:40:14 정상 예제 다시 통과. 사용자가 공유한 서버 로그 기준이며 사건 저장·응용 ACK 성공은 아니다.

2026-11-13 추가 의미 검사, PID 38291: 15:48:49.121 READY → 15:53:45.927 E11 거부(reason=clock_untrusted_stop_time, rule_id=V04) → 15:57:52.453 E09 통과(EVENT_CONTRACT_VALIDATED). 같은 프로세스에서 오류 뒤 정상 처리가 지속됐다. 사용자가 공유한 로그와 예제 식별을 근거로 기록했다. stored=false·ack_sent=false이며, 사건 저장·응용 ACK·실제 보드 검증은 아니다.

저장까지 확인하려면 [사건 저장 실습](event-storage.md)의 `mqtt-ingest-local`로 전환한다. 이 문서의 검증 전용 모드는 계속 저장·ACK하지 않는다.
