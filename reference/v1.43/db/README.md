# DB·트랜잭션·복원 안내

원본은 `개발자료/09_구현설계/db/`에 있다. 이 폴더에서 동일 SQL을 복사해 별도 관리하지 않는다.

| 확인할 내용 | 파일 |
|---|---|
| 테이블·제약·인덱스 | [schema.sql](../개발자료/09_구현설계/db/schema.sql) |
| 관계도 원본 | [erd.mmd](../개발자료/09_구현설계/db/erd.mmd) |
| 데이터·보관·설계 결정 | [db_decisions.json](../개발자료/09_구현설계/db/db_decisions.json) |
| 원자 저장·행 잠금·파일 복구·백업 복원 | [transactions.txt](../개발자료/09_구현설계/db/transactions.txt) |
| 기존 문법·구조 검사 기록 | [validation.json](../개발자료/09_구현설계/db/validation.json) |

문서 기준은 [D4 DB 구조](../spec/storage/D4.md), [D5 동시 처리](../spec/storage/D5.md), [D6 사진·만료·복원](../spec/storage/D6.md)이다. 현재 SQL은 빈 개발용 PostgreSQL에서 적용·동시성 시험할 Flyway 초기 후보다. 실제 DB 적용이 완료됐다는 뜻이 아니다.

구역 상태 갱신은 기존 READ COMMITTED·`zone_view` 행 잠금과 트랜잭션 순서를 따른다. ACK·SSE·접수 응답은 커밋 후에 보낸다. 네트워크·파일 I/O 동안 긴 DB 잠금을 유지하지 않는다.

사진 파일과 DB는 하나의 ACID 트랜잭션이 아니다. 예약·파일 내구 저장·완료 확정과 재시작 복구를 구현한다. 과거 DB 백업 복원 시에는 일반 재시작과 달리 gate·외부 manifest·상태 대조·운영자 인수가 필요하다. 정확한 절차는 D6·transactions.txt를 따른다.

DB01~DB06, 관련 TC/OP 시험을 [시험 안내](../tests/README.md)에서 찾아 실행한다. 기존 문법 검사 결과로 동시성·복구·데이터 보존 시험을 대체하지 않는다.
