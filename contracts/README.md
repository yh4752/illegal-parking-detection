# 웹에서 사용할 유효 계약

MQTT 수신은 계속 `reference/v1.43/개발자료/09_구현설계/contracts/parking.schema.json`을 사용합니다. 아래 생성물은 웹 응답과 프론트 타입 생성의 기준입니다.

| 파일 | 역할 |
|---|---|
| [overlays/public-demo-rest-v1.json](overlays/public-demo-rest-v1.json) | v1.43 원본 SHA-256과 REST에만 적용하는 변경 |
| [effective/openapi.json](effective/openapi.json) | 웹 API 계약. 경로가 있다고 구현 완료된 것은 아님 |
| [effective/parking.schema.json](effective/parking.schema.json) | 웹 응답 검증용 Schema. MQTT 정의는 원본과 같음 |

추가된 REST 코드는 RATE_LIMITED(429), SERVICE_UNAVAILABLE(503), INTERNAL_ERROR(500)입니다. 성공 응답·ZoneView 8키·공통 MQTT error_code enum·wire schema_version=2는 그대로입니다. API 구현과 실제 오류 매핑은 M1부터 진행합니다.

저장소 루트에서 실행합니다.

```sh
python3 tools/build_contracts.py
python3 tools/build_contracts.py --check
python3 -m unittest discover -s tools/tests -v
```

생성물은 직접 수정하지 않습니다. 생성기는 원본 해시, 패치 대상, REST 변경 범위, 내부 참조를 검사합니다. Spring 빌드에는 원본 `contracts/parking.schema.json`과 웹용 `contracts/effective/parking.schema.json`을 다른 경로로 넣습니다. 이후 프론트 타입도 이 OpenAPI에서 생성합니다.

[ADR-006](../docs/adr/006-public-demo-foundation.md)에 설계 원본과 이번 구현의 관계를 기록했습니다.
