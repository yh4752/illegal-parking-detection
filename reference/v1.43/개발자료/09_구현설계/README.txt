구현 설계 파일 사용 안내

contracts: JSON Schema / OpenAPI / 원래 예제 / 검증 스크립트. 이 폴더에서 별도 가상환경을 만들고 requirements.txt 설치 후 python validate_examples.py를 실행한다. --contract와 --report로 입력·결과 경로를 바꿀 수 있다. 공유 ZIP의 상대 경로 기준으로 04 계약을 읽는다.
db: schema.sql은 빈 개발용 PostgreSQL에서 검증할 Flyway 초기 후보다. 운영 DB에 자동 적용하지 않는다. transactions.txt의 트랜잭션·동시성·복원 절차를 함께 구현한다. erd.mmd는 Mermaid 관계도 소스다. 문법 검사와 실제 DB 적용/장애 시험은 다르다.
mqtt_sdk: JSON은 구현 초기 설정과 언어 독립 처리 규칙이다. 실제 SDK 코드는 아직 없다. Mosquitto/ACL 예제의 등록 ID·경로·인증서·계정을 준비하고 ACL 시험 후 적용한다. Clean Start와 재전송·TTL 규칙은 함께 구현해야 한다.

외부 라이브러리는 각 요구 파일 버전을 사용해 재현하되 보드/서버 서비스 라이브러리의 최종 호환 버전은 별도 빌드에서 확인한다. 검증용 라이브러리 자체나 비밀값은 이 묶음에 포함하지 않는다.
