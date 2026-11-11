# 장치 SDK·MQTT 안내

백엔드가 저장·통신 SDK와 PC 모의 어댑터를 작성하고 AI가 실제 보드의 어댑터·빌드·설치·결합을 맡는다. SDK 언어·라이브러리·바인딩은 보드 OS·ABI·런타임 지원 확인 후 정한다.

| 확인할 내용 | 파일 |
|---|---|
| AI–SDK 책임·함수 입력/출력·저장 경계 | [07 내부 계약](../개발자료/07_보드내부_SDK_연동계약_v1.4.json) |
| MQTT 세션·접속·재연결·재시도·TTL | [transport_settings_v1.4.json](../개발자료/09_구현설계/mqtt_sdk/transport_settings_v1.4.json) |
| 단일 writer·비동기 작업·큐·stop_generation | [sdk_processing_v1.4.json](../개발자료/09_구현설계/mqtt_sdk/sdk_processing_v1.4.json) |
| 구현 해석과 적용 순서 | [mqtt_sdk_notes.txt](../개발자료/09_구현설계/mqtt_sdk/mqtt_sdk_notes.txt) |
| 브로커·토픽 접근 예제 | [mosquitto.conf.example](../개발자료/09_구현설계/mqtt_sdk/mosquitto.conf.example), [acl.example](../개발자료/09_구현설계/mqtt_sdk/acl.example) |
| 기존 설정 검사 기록 | [validation.json](../개발자료/09_구현설계/mqtt_sdk/validation.json) |

설명은 [B2 내부 경계](../spec/roles/B2.md)·[B3 처리 책임](../spec/roles/B3.md), [D1 접속](../spec/storage/D1.md)·[D2 복구](../spec/storage/D2.md)·[D3 저장·중단](../spec/storage/D3.md)를 따른다.

AI가 상태를 결정하고 SDK의 단일 SQLite writer가 checkpoint·횟수·메시지·outbox를 원자 저장한다. 같은 프로세스의 내부 호출과 완료 통지를 사용하며 AI와 SDK를 서로 다른 상태 DB로 나누지 않는다. 네트워크·사진 작업은 제어 루프를 막지 않는다.

COMMITTED는 새 안내를 시작하기 위한 조건 중 하나다. 현재 guard·stop_generation도 재검사한다. 안전 stop/LED off는 DB 저장보다 먼저 수행할 수 있으며, 명령 성공 보고는 명령별 실제 확인과 결과 저장을 따른다.

설정 예제는 설치 완료 파일이 아니다. 실제 인증서·계정·허용 토픽·경로를 준비하고 검사한다. 기존 JSON은 처리 규격이며 실행 가능한 SDK 구현이 아니다.

PC BC01~BC05와 실제 보드 BC06은 [시험 안내](../tests/README.md)에서 분리해 기록한다. 보드 호환 제약이 있으면 SDK 코어 문제와 플랫폼 바인딩 문제를 나누어 AI·백엔드가 해결한다.
