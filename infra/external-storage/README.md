# S3 독립 revision gateway

[구현·실습·한계](../../docs/external-storage-preparation.md)와 [서버 설치 경계](../server/README.md)를 따른다. gateway는 내부 HTTP만 제공하며 S3에는 TLS/SigV4로 접속한다. runtime은 현재 head의 조건부 갱신만 허용하고, explicit init에는 별도 provisioning 권한이 필요하다. `ledger.py`의 SQLite 참조 구현과 제품의 HTTP/MQTT 계약은 유지한다.

`requirements.txt`는 실제 도구 의존성, `tools/production/requirements-external-test.txt`는 loopback emulator를 포함한 시험 의존성이다. 운영 이미지에는 emulator를 설치하지 않는다. AWS credentials는 owner-only 파일로만 공급하며 실제 계정/버킷은 이 코드 작성 과정에서 생성하지 않았다.
