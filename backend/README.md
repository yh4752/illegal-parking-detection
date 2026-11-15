# 백엔드

Kotlin + Spring Boot 애플리케이션 하나다. 학습·검증 전용 프로필과 실제 사건 저장용 `mqtt-ingest-local`을 구분한다. 새 프로필은 v1.43 사건을 검증·저장하고 DB 커밋 후 응용 ACK를 보낸다. 일반·PostgreSQL 자동 시험을 마쳤으며 사용자 로그로 정상 1건의 실제 MQTT 저장·ACK 수신을 확인했다. 동일 메시지 재전송의 DUPLICATE 응답도 확인했다. SQL 직접 조회로 원문 1건도 확인했다. 조회 API는 아직 없다.

| 위치 | 현재 파일 | 향후 책임 |
|---|---|---|
| `configuration` | SecurityConfiguration, MQTT 학습·검증·저장 구성 | 빈 조립·연결·보안 설정 |
| `device` | 검증기, EventIngestionService, EventTransactionStore, MQTT 핸들러 | MQTT 입출력, 수신 업무, 순서 규칙, JDBC 저장 |
| `zone` | README만 있음 | 상태 조회 서비스·HTTP API |
| `operations` | FreshDemoInitializer | 신규 단일 구역 데모의 검사·명시 초기화 |

초기화 명령의 엔트리포인트는 `kr.ac.sejong.parkingtools`에 분리했다. 웹 애플리케이션의 컴포넌트 검색에 포함하지 않으며, HTTP·MQTT를 시작하지 않는다. [신규 데모 초기화 안내](../docs/demo-foundation.md)를 따르고 기존 사건 데이터가 있는 DB에는 적용하지 않는다.

기능 작성 전에 해당 폴더 안내와 [첫 기능 작업표](../docs/first-slice.md)를 읽는다. DTO·서비스·Repository는 실제 사용처와 함께 추가한다.

`./gradlew test bootJar` → `./gradlew bootRun` 순으로 실행한다. Wrapper가 Gradle을 내려받으므로 전역 설치는 필요 없다. JDK 17이 필요하다. 기본 바인딩은 127.0.0.1:8080이다.

Actuator health의 UP는 애플리케이션 기동만 뜻한다. `database` 프로필에서는 DB 연결도 점검한다. 제품 API는 아직 없다. 학습용 MQTT 연결 성공은 별도의 READY·수신 로그로 확인한다. 모든 제품 경로는 차단되어 있으며 기존 세션·CSRF·권한은 이후 구현한다.

선택 버전: Spring Boot 3.5.16, Kotlin 2.1.21, Gradle 8.12.1. Spring Integration MQTT는 Boot BOM 버전을 사용하며 Paho MQTT 5는 1.2.5로 고정한다. JDBC·Flyway·PostgreSQL 드라이버도 Boot BOM 버전을 사용한다.

학습 실행과 코드 설명은 [MQTT 학습 안내](../docs/mqtt-learning.md)를 따른다.

제품 DB 실행과 마이그레이션 검사는 [DB 준비 안내](../docs/database.md)를 따른다. 기본 `test`와 실제 PostgreSQL을 사용하는 `databaseTest`를 구분한다.

정식 메시지는 [사건 수신·검증 안내](../docs/event-validation.md)의 `mqtt-validation-local` 프로필로 확인한다. 이 프로필은 `database`를 함께 활성화하며 저장 완료 ACK를 보내지 않는다.

저장과 응답 확인은 [사건 저장 실습](../docs/event-storage.md)을 따른다. V2 격리 테이블과 별도 revision 예약 파일을 사용한다. 기존 검증 서버를 종료한 뒤 `mqtt-ingest-local`로 실행한다. 기본 `bootRun`은 저장 기능을 켜지 않는다.
