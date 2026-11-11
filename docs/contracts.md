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

문서 v1.43과 `schema_version=2`는 별개입니다. SQL은 실제 적용 검증 후 Flyway 마이그레이션으로 승격합니다. 승격 이후에는 실행용 마이그레이션을 수정하지 않고 새 변경으로 관리하며, 기존 설계 SQL과의 관계를 기록합니다.

계약을 바꾸면 원본·예제·의미 검사·관련 구현과 시험·C4/arc42 영향을 함께 확인합니다. 작업표나 그림을 고쳤다는 이유로 wire 계약이 자동 변경되는 것은 아닙니다.
