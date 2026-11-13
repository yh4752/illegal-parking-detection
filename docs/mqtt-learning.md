# MQTT 학습: 수신 도구를 Spring Boot로 바꾸기

## 오늘 확인할 것

발행 도구가 보낸 문자열을 Mosquitto가 중계하고 Spring Boot가 로그로 출력하는지 확인한다. 실제 차량 데이터, DB 저장, 저장 확인 응답(응용 ACK)은 이 실습에 포함하지 않는다.

| 역할 | 이전 실습 | 이번 실습 |
|---|---|---|
| 발행 | mosquitto_pub | mosquitto_pub |
| 중계 | Mosquitto | Mosquitto |
| 구독 | mosquitto_sub | Spring Boot의 MQTT 수신 코드 |

MQTT는 통신 규칙, Mosquitto는 중계 프로그램, 토픽은 메시지 분류 이름이다. 서버 안에서 MQTT 연결을 담당하는 라이브러리를 추가하면 서버도 구독자가 된다.

## 추가한 코드 읽기

| 파일 | 역할 |
|---|---|
| [build.gradle.kts](../backend/build.gradle.kts) | Spring Integration MQTT와 Paho MQTT 5 클라이언트 추가 |
| [application-mqtt-learning.yml](../backend/src/main/resources/application-mqtt-learning.yml) | 브로커 주소·토픽·클라이언트 ID 설정 |
| [MqttLearningConfiguration.kt](../backend/src/main/kotlin/kr/ac/sejong/parking/configuration/MqttLearningConfiguration.kt) | 브로커 연결·토픽 구독·처리 코드 연결 |

파싱 코드는 [MqttLearningMessageHandler.kt](../backend/src/main/kotlin/kr/ac/sejong/parking/configuration/MqttLearningMessageHandler.kt)에 있다.

`mqtt-learning`은 이 프로젝트에서 붙인 프로필 이름이다. 프로필은 실행할 설정 묶음을 고르는 Spring 기능이며, 이 이름을 지정할 때만 학습용 MQTT 연결이 켜진다. 기본 실행에는 브로커가 필요 없다. 클라이언트 ID는 브로커가 연결을 구별하는 이름이며 실행마다 생성한다.

Spring Boot는 맥에서 실행하므로 `127.0.0.1:11883`에 접속한다. `docker compose exec`로 실행하는 발행 도구는 컨테이너 내부에 있으므로 `localhost:1883`을 사용한다. 두 주소는 같은 브로커로 이어진다.

## 실행 순서

아래 명령의 시작 위치는 저장소 루트다. 브로커는 Docker Desktop이 실행 중이어야 한다.

```sh
cd infra
docker compose --env-file .env.example up -d mosquitto
cd ../backend
./gradlew test bootJar
./gradlew bootRun --args='--spring.profiles.active=mqtt-learning'
```

`MQTT_LEARNING_READY topic=learning/hello` 로그를 확인한 후 발행한다. `Started`나 `/actuator/health`의 UP만으로 MQTT 구독 성공을 판단하지 않는다. READY가 없고 연결 오류가 나오면 브로커 상태·주소를 확인한다. 최초 연결 실패 시 브로커를 먼저 켠 뒤 서버를 다시 실행한다.

새 터미널에서 저장소의 infra 폴더로 이동해 실행한다.

```sh
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 \
  -t learning/hello -m "hello spring"
```

`-V mqttv5`는 발행 도구도 MQTT 5를 사용하도록 지정한다. 서버 로그의 예상 결과는 다음과 같다.

```text
MQTT_LEARNING_RECEIVED topic=learning/hello payload=hello spring
```

이후 추가한 JSON 파싱 코드에서는 일반 문장 `hello spring`을 보내면 RECEIVED와 INVALID_JSON이 함께 나온다. 수신 실패가 아니라 JSON 문법에 맞지 않기 때문이다.

그다음 토픽을 `learning/other`로 바꾸고 내용도 바꿔 발행한다. 서버에 해당 수신 로그가 추가되지 않아야 한다. 두 결과를 직접 확인한 뒤에만 학습용 서버 수신 성공으로 기록한다.

이번 실습의 QoS는 0이고 메시지를 retain하지 않는다. 미접속 중 메시지 보관·전달 보장·중복 제거 시험이 아니다. 로그는 개행을 이스케이프하고 최대 512자까지만 출력한다. 제품 설정과 계약은 기존 v1.43을 유지한다.

서버 종료는 Ctrl+C, 브로커 중지는 infra에서 `docker compose --env-file .env.example stop mosquitto`다. health는 서버 기동 확인이며 MQTT 연결 상태를 표시하지 않는다.

## JSON 파싱 확인

파싱은 JSON 텍스트를 프로그램이 항목별로 읽을 수 있는 구조로 바꾸는 작업이다. Jackson은 이 작업을 수행하는 라이브러리이며 기존 프로젝트에 포함되어 있다. 파싱 다음에는 아래의 학습용 세 필드 검증을 수행한다. 정식 사건 전체·등록 장치 여부 같은 제품 검증과는 별개다.

코드 수정 후 서버를 Ctrl+C로 종료하고 backend에서 `./gradlew test bootJar`가 성공하면 학습 프로필로 다시 실행한다. READY 로그 이후 아래 명령을 infra의 다른 터미널에서 실행한다.

```sh
# 1. 정상 JSON: RECEIVED 다음 PARSED 로그에서 항목별 값을 확인한다.
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -t learning/hello \
  -m '{"device_id":"edge-01","zone_id":"zone-01","device_seq":10}'

# 2. 닫는 중괄호 누락: RECEIVED 다음 INVALID_JSON을 확인한다.
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -t learning/hello \
  -m '{"device_id":"edge-01"'
```

오류 메시지 뒤에 1번을 다시 보내 PARSED가 나타나는지 확인한다. 이번 예제는 정식 제품 메시지의 일부 항목만 가져온 학습 입력이다.

- RECEIVED: 메시지가 도착했다.
- PARSED: JSON 객체를 읽었다. 필드가 없으면 null로 표시하고 다음 검증에서 누락을 구분한다. PARSED 자체는 검증·저장 성공이 아니다.
- INVALID_JSON: 잘못된 문법·빈 입력·하나의 JSON 뒤에 추가된 값을 구분한다.
- UNEXPECTED_SHAPE: JSON 문법은 맞지만 객체가 아닌 배열·숫자·null 등의 입력이다.

로그 미리보기는 길이를 제한하지만 파싱에는 원문 전체를 사용한다. 잘못된 입력을 처리한 뒤에도 다음 메시지를 받을 수 있어야 한다. 이 처리의 단위 테스트는 브로커 없이 수행되며, 실제 MQTT 연동 시험과 구분한다.

## 세 필드 유효성 검증

JSON 문법을 해석하는 파싱과, 필요한 데이터인지 확인하는 검증을 분리한다. 기존 [Schema](../reference/v1.43/개발자료/09_구현설계/contracts/parking.schema.json)의 DeviceEnvelope 규칙 중 세 필드만 학습용으로 적용한다. 다른 필드나 장치 등록 여부는 검사하지 않으며, 학습용 메시지를 제품 사건으로 취급하지 않는다.

| 필드 | 규칙 | 오류 예시 |
|---|---|---|
| device_id | 필수, 1~128자의 문자열 | 누락, null, 빈 문자열, 숫자 |
| zone_id | 필수, 1~128자의 문자열 | 누락, null, 빈 문자열, 숫자 |
| device_seq | 필수, 0~9007199254740991의 정수 값 | 누락, null, 음수, 문자열 "10", 소수 1.5, 범위 초과 |

숫자를 문자열에서 자동 변환하지 않는다. JSON Schema의 integer 의미에 맞게 숫자 1.0처럼 값이 정수인 표현은 허용한다. 경계값 근처 소수를 부동소수점 반올림으로 잘못 허용하지 않도록 BigDecimal로 읽는다. 문자열 길이는 유니코드 코드 포인트 수를 사용한다. 이 단계에서 추가 필드는 검사하지 않는다.

새 코드는 PARSED 다음에 아래 중 하나를 출력한다.

- `MQTT_LEARNING_INVALID_DATA ... reason=device_seq:out_of_range`: JSON 객체는 읽었지만 순번 범위가 틀림.
- `MQTT_LEARNING_INVALID_DATA ... reason=device_seq:integer_required`: 순번의 자료형 또는 정수 조건이 틀림.
- `MQTT_LEARNING_INVALID_DATA ... reason=device_id:required`: 필수 항목 누락 또는 null.
- `MQTT_LEARNING_VALIDATED ... scope=three_fields`: 세 항목만 검사 통과. DB 저장·정식 사건 전체 검증 성공이 아님.

실습은 서버를 재시작하고 READY 확인 후 infra에서 진행한다.

```sh
# JSON 문법은 맞지만 순번이 음수다.
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -t learning/hello \
  -m '{"device_id":"edge-01","zone_id":"zone-01","device_seq":-1}'

# 숫자처럼 보이지만 문자열이다.
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -t learning/hello \
  -m '{"device_id":"edge-01","zone_id":"zone-01","device_seq":"10"}'

# 필수 항목 device_id가 빠져 있다.
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -t learning/hello \
  -m '{"zone_id":"zone-01","device_seq":10}'

# 잘못된 입력 이후에도 정상 입력을 처리해야 한다.
docker compose --env-file .env.example exec mosquitto \
  mosquitto_pub -h localhost -p 1883 -V mqttv5 -t learning/hello \
  -m '{"device_id":"edge-01","zone_id":"zone-01","device_seq":11}'
```

첫 세 입력은 PARSED 후 INVALID_DATA, 마지막은 PARSED 후 VALIDATED가 예상된다. 실제 결과를 확인한 후에 실습 완료로 기록한다. 동일 메시지 중복·과거 순번·장치 등록은 아직 검사하지 않는다.

## 회의·기록

서버 수신을 확인한 뒤에는 “PC 발행 도구 → 브로커 → Spring Boot 수신을 확인했고 DB 저장·실제 보드 연동은 다음 단계”라고 설명할 수 있다. 아직 확인하지 않았다면 “수신 코드 작성, 로컬 연동 확인 중”으로 설명한다.

실습 후 자신이 이해한 내용을 짧게 적는다: 왜 서버는 11883에 접속하는가, 브로커와 서버는 무엇이 다른가, 수신 로그만으로 DB 저장을 확인할 수 있는가. 이 답과 실제 실행 결과를 블로그 초안의 재료로 사용한다.

구현 참고: [Spring Integration 6.5 MQTT 5 공식 문서](https://docs.spring.io/spring-integration/reference/6.5/mqtt.html#_mqtt_v5_support).

## 실습 결과

2026-11-13, 한국 시간. 사용자가 자신의 터미널에서 실행하고 대화에 공유한 로그에 근거한다. 에이전트가 직접 수행한 통합 시험과 구분한다.

| 시각 | 입력 | 관찰한 결과 |
|---|---|---|
| 10:37:11 | 정상 JSON, device_seq=10 | RECEIVED 다음 PARSED, 세 필드 값 일치 |
| 10:37:27 | 닫는 중괄호가 빠진 JSON | RECEIVED 다음 INVALID_JSON, reason=syntax |
| 10:37:32 | 정상 JSON, device_seq=11 | 같은 서버 프로세스(PID 16258)에서 다시 PARSED |
| 10:47:07 | device_seq=-1 | INVALID_DATA, reason=device_seq:out_of_range |
| 10:53:27 | device_seq="10" | INVALID_DATA, reason=device_seq:integer_required |
| 10:53:33 | device_seq=11 | 같은 서버 프로세스(PID 17338)에서 VALIDATED, scope=three_fields |

확인한 것은 학습용 MQTT 수신, JSON 객체 파싱, 문법 오류 구분, 오류 이후 다음 정상 메시지 처리다. 세 필드 검증 중 음수·문자열 순번 거부와 정상 입력 통과도 확인했다. 필드 누락·나머지 경계값·등록 장치 검증, DB 저장, 제품 응용 ACK, 실제 보드, 부하 처리 성능은 검증하지 않았다. 빌드·READY 확인 요청에 사용자가 성공을 보고했으며, 단위 테스트별 상세 보고서는 아직 공유되지 않았다.
