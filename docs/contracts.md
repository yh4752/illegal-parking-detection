# 정식 계약은 여기서 찾습니다

이번 골격에서는 기존 원본을 `reference/v1.43`에 보존했습니다. 별도 축약 메시지나 임시 제품 API를 만들지 않았습니다. 아래 링크의 파일이 제품 구현 기준입니다.

| 내용 | 원본 |
|---|---|
| 메시지 타입·필드 | [parking.schema.json](../reference/v1.43/개발자료/09_구현설계/contracts/parking.schema.json) |
| HTTP 경로·요청·응답·권한 | [openapi.json](../reference/v1.43/개발자료/09_구현설계/contracts/openapi.json) |
| 가상 입력·기대 상태 | [examples.json](../reference/v1.43/개발자료/09_구현설계/contracts/examples.json) |
| 의미 검사 | [semantic_checks.json](../reference/v1.43/개발자료/09_구현설계/contracts/semantic_checks.json) |
| SQL 초기 후보 | [schema.sql](../reference/v1.43/개발자료/09_구현설계/db/schema.sql) |
| SDK 내부 함수·자료형 | [내부 계약](../reference/v1.43/개발자료/07_보드내부_SDK_연동계약_v1.4.json) |

문서 v1.43과 `schema_version=2`는 별개입니다. SQL은 원본 DDL을 유지해 Flyway V1으로 승격했고 PostgreSQL 17.11에서 적용을 확인했습니다. [DB 안내](database.md)를 참고합니다. 승격 이후에는 실행용 마이그레이션을 수정하지 않고 새 변경으로 관리하며, 기존 설계 SQL과의 관계를 기록합니다.

계약을 바꾸면 원본·예제·의미 검사·관련 구현과 시험·C4/arc42 영향을 함께 확인합니다. 작업표나 그림을 고쳤다는 이유로 wire 계약이 자동 변경되는 것은 아닙니다.

## 사건 검증의 구현 보완 기준

2026-11-13에 [사건 수신·저장 전 검증 기준 v1](design/event-ingest-v1.md)을 확정했다. 필드·타입은 위 원본을 유지하며 메시지 내부 조건·DB 조건·엣지 책임과 정상 허용 사례를 구분한다. [결정 기록](adr/003-event-validation-boundary.md)과 [수신 예제·DB 인수 시나리오](design/event-validation-cases.json)를 함께 따른다. 추가 의미 검사 V04~V12는 구현·자동 테스트를 마쳤다. quarantine V2·DB 상태 검사·사건 저장·응용 ACK도 구현해 일반·PostgreSQL 시험을 마쳤다. [ADR-004](adr/004-event-storage-and-ack.md)의 범위·복구 제한과 [실행 안내](event-storage.md)를 함께 따른다. 새 프로필의 정상 1건 MQTT 왕복은 사용자 실습으로 확인했다. 중복 재전송의 DUPLICATE 수신도 확인했다. SQL로 원문 1건도 확인했다. 추가 수신·조회·실시간 화면의 현재 구현과 검증은 [현재 상태](status.md)를 따른다.

수신 객체를 빠르게 확인하려면 [사건 메시지 DTO 필드 명세](design/event-message-dto.md)를 본다. 원본 Schema의 6개 중첩 객체를 펼쳤으며, 현재 JsonNode 구현을 Kotlin DTO 구현 완료로 표현하지 않는다.

## 공개 데모 웹 계약의 실행 기준

M0부터 웹은 [유효 OpenAPI·Schema](../contracts/README.md)를 사용한다. 보존 원본 + REST 전용 overlay를 생성기로 적용하며 일반 오류 3종만 REST에 보완한다. MQTT 검증은 계속 보존 원본을 사용한다. 성공 DTO·ZoneView·wire schema_version=2는 바꾸지 않는다. [ADR-006](adr/006-public-demo-foundation.md)과 [M0 안내](demo-foundation.md)를 따른다. 인증·상태·이력 조회 API와 SSE는 구현했으며, 명령·정책 변경·사진 파일 기능은 남아 있다. 계약 파일에 경로가 있다는 사실만으로 구현 완료를 뜻하지 않는다.

구현 범위와 인수 조건은 [상세 설계](design/public-demo-plan.md)·[ADR-005](adr/005-public-demo-boundary.md)·[작업표](design/public-demo-workplan.json)를 함께 따른다. `docs/design/public-demo-rest-overlay.json`은 설계 기록이며 실행 기준은 `contracts/overlays/public-demo-rest-v1.json`과 생성물이다.

M1 인증·조회 구현은 [실행 안내](authenticated-read.md)와 [ADR-007](adr/007-authenticated-read-api.md)을 따른다. 명령·사진 메타데이터 조회는 파일 제공·명령 발행 구현을 뜻하지 않는다.
