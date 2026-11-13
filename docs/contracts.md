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

2026-11-13에 [사건 수신·저장 전 검증 기준 v1](design/event-ingest-v1.md)을 확정했다. 필드·타입은 위 원본을 유지하며 메시지 내부 조건·DB 조건·엣지 책임과 정상 허용 사례를 구분한다. [결정 기록](adr/003-event-validation-boundary.md)과 [수신 예제·DB 인수 시나리오](design/event-validation-cases.json)를 함께 따른다. 추가 의미 검사 V04~V12는 구현·자동 테스트를 마쳤다. quarantine V2·DB 상태 검사·사건 저장·응용 ACK도 구현해 일반·PostgreSQL 시험을 마쳤다. [ADR-004](adr/004-event-storage-and-ack.md)의 범위·복구 제한과 [실행 안내](event-storage.md)를 함께 따른다. 새 프로필의 정상 1건 MQTT 왕복은 사용자 실습으로 확인했다. 중복 재전송의 DUPLICATE 수신도 확인했다. SQL로 원문 1건도 확인했다. 추가 수신 시험·조회 API는 다음 단계다.

수신 객체를 빠르게 확인하려면 [사건 메시지 DTO 필드 명세](design/event-message-dto.md)를 본다. 원본 Schema의 6개 중첩 객체를 펼쳤으며, 현재 JsonNode 구현을 Kotlin DTO 구현 완료로 표현하지 않는다.

## 공개 데모에서 보완할 웹 계약

[ADR-005](adr/005-public-demo-boundary.md)는 첫 조회 인계판의 구역 권한 DB·목록 정렬 시각·세션·heartbeat/SSE·복구 결정을 기록한다. [REST overlay](design/public-demo-rest-overlay.json)는 일반 서버 오류와 요청 제한 코드만 REST에 추가하는 **설계 파일**이다. 아직 런타임에서 사용하지 않는다. M0에서 유효 계약을 생성하고 웹 응답 검증과 프론트 타입 생성에 연결한다. MQTT error_code enum·wire schema_version=2·성공 DTO는 유지한다.

조회·배포 구현의 범위는 [상세 설계](design/public-demo-plan.md), 완료 조건은 [작업 JSON](design/public-demo-workplan.json)을 따른다. 기준 파일과 이 보완 결정을 함께 읽되, 첫 인계판 밖의 명령·사진 등 v1.43 요구를 삭제하지 않는다.
