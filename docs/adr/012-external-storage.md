# ADR-012: S3 gateway와 별도 스트리밍 백업

2026-11-19 · 구현 및 로컬 모의 검증 · 외부 자원 인수 전

기존 독립 revision HTTP 계약을 유지하면서 gateway의 SQLite 저장 계층을 S3 버전 관리 객체로 분리한다. backend에 AWS SDK/키를 직접 넣는 대신 gateway만 저장소 권한을 가진다. 내부 HTTP는 비공개 Docker 네트워크와 bearer 인증을 사용하고 외부 S3 경로는 TLS/SigV4·예상 계정 ID로 제한한다.

단일 versioned head의 If-Match가 원자적인 갱신 경계다. 이력 객체와 head를 별도로 쓰는 두 단계 트랜잭션의 실패 복구를 추가하지 않는다. S3 Versioning의 과거 객체를 삭제/조회할 runtime 권한은 없으며, 관리자의 의도적 rollback까지 방어한다고 주장하지 않는다. 처음부터 존재하지 않는 원장을 자동 생성하거나 과거 버전으로 대체하지 않는다. 동일 재시도는 선행 해시·후속 버전·결과 문서가 일치할 때만 성공한다.

운영 백업은 age X25519 파일 스트리밍을 사용한다. 생성 서버에 공개키만 두고 복호화 개인키를 분리할 수 있다. 이는 기존 Fernet 사본을 재암호화하는 변경이 아니다. 원본 dump 격리 복원 성공, 암호문 업로드 대조, 독립 환경에서 개인키를 회수한 전체 복원은 서로 다른 판정이다. 후자의 실제 외부 인수는 남아 있다.

[구현 설명](../external-storage-preparation.md) · [구조 그림](../diagrams/12-external-storage.svg) · [서버 준비](../../infra/server/README.md)
