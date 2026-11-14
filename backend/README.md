# 백엔드

Kotlin + Spring Boot 애플리케이션 하나다. 사건 저장·응용 ACK와 세션 인증·구역 권한·조회 API를 구현했다. 기본 프로필은 health 확인용이며, 제품 기능은 명시적으로 켠다. [로그인·조회 실행 안내](../docs/authenticated-read.md)에서 신규 데모 준비·계정 생성·서버 실행 순서를 따른다.

| 위치 | 현재 파일 | 향후 책임 |
|---|---|---|
| `configuration` | SecurityConfiguration, MQTT 학습·검증·저장 구성 | 빈 조립·연결·보안 설정 |
| `device` | 검증기, EventIngestionService, EventTransactionStore, MQTT 핸들러 | MQTT 입출력, 수신 업무, 순서 규칙, JDBC 저장 |
| `zone` | ReadController, ReadQueries, JdbcReadStore, EventCursor | 상태·사건 조회와 페이지 처리 |
| `auth`, `web` | 세션·로그인 필터, WebIdentity, RestErrors | 사용자 재검사·CSRF·실패 제한·공통 오류 |
| `operations` | FreshDemoInitializer | 신규 단일 구역 데모의 검사·명시 초기화 |

초기화 명령의 엔트리포인트는 `kr.ac.sejong.parkingtools`에 분리했다. 웹 애플리케이션의 컴포넌트 검색에 포함하지 않으며, HTTP·MQTT를 시작하지 않는다. [신규 데모 초기화 안내](../docs/demo-foundation.md)를 따르고 기존 사건 데이터가 있는 DB에는 적용하지 않는다.

기능 작성 전에 해당 폴더 안내와 [첫 기능 작업표](../docs/first-slice.md)를 읽는다. DTO·서비스·Repository는 실제 사용처와 함께 추가한다.

`./gradlew test bootJar` → `./gradlew bootRun` 순으로 실행한다. Wrapper가 Gradle을 내려받으므로 전역 설치는 필요 없다. JDK 17이 필요하다. 기본 바인딩은 127.0.0.1:8080이다.

Actuator health의 UP는 애플리케이션 기동만 뜻한다. `database` 프로필에서는 DB 연결도 점검한다. `web-api`는 database를 포함하고 세션·CSRF·구역 권한을 적용한다. `app-local`은 로컬 HTTP 실행용이다. `app-local,mqtt-ingest-local`은 같은 앱에서 수신·조회를 함께 켠다. 기본 프로필과 학습/검증 모드에서는 제품 조회를 열지 않는다.

선택 버전: Spring Boot 3.5.16, Kotlin 2.1.21, Gradle 8.12.1. Spring Integration MQTT는 Boot BOM 버전을 사용하며 Paho MQTT 5는 1.2.5로 고정한다. JDBC·Flyway·PostgreSQL 드라이버도 Boot BOM 버전을 사용한다.

학습 실행과 코드 설명은 [MQTT 학습 안내](../docs/mqtt-learning.md)를 따른다.

제품 DB 실행과 마이그레이션 검사는 [DB 준비 안내](../docs/database.md)를 따른다. 기본 `test`와 실제 PostgreSQL을 사용하는 `databaseTest`를 구분한다.

정식 메시지는 [사건 수신·검증 안내](../docs/event-validation.md)의 `mqtt-validation-local` 프로필로 확인한다. 이 프로필은 `database`를 함께 활성화하며 저장 완료 ACK를 보내지 않는다.

저장과 응답 확인은 [사건 저장 실습](../docs/event-storage.md)을 따른다. V2 격리 테이블과 별도 revision 예약 파일을 사용한다. 기존 검증 서버를 종료한 뒤 `mqtt-ingest-local`로 실행한다. 기본 `bootRun`은 저장 기능을 켜지 않는다.
