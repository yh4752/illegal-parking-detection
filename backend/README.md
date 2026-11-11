# 백엔드

Kotlin + Spring Boot 애플리케이션 하나다. 현재 구현은 `ParkingApplication`, `SecurityConfiguration`, `application.yml`과 기동/접근 경계 테스트뿐이다.

| 위치 | 현재 파일 | 향후 책임 |
|---|---|---|
| `configuration` | SecurityConfiguration | 빈 조립·연결·보안 설정 |
| `device` | README만 있음 | MQTT 입출력, 수신 업무, 순서 규칙, JDBC 저장 |
| `zone` | README만 있음 | 상태 조회 서비스·HTTP API |

기능 작성 전에 해당 폴더 안내와 [첫 기능 작업표](../docs/first-slice.md)를 읽는다. DTO·서비스·Repository는 실제 사용처와 함께 추가한다.

`./gradlew test bootJar` → `./gradlew bootRun` 순으로 실행한다. Wrapper가 Gradle을 내려받으므로 전역 설치는 필요 없다. JDK 17이 필요하다. 기본 바인딩은 127.0.0.1:8080이다.

Actuator health의 UP는 애플리케이션 기동만 뜻한다. DB·브로커 연결 및 제품 API는 아직 없다. 모든 제품 경로는 차단되어 있으며 기존 세션·CSRF·권한은 이후 구현한다.

선택 버전: Spring Boot 3.5.16, Kotlin 2.1.21, Gradle 8.12.1. 다음 작업에서 Spring Integration MQTT·JDBC·Flyway·PostgreSQL 의존성을 같은 Boot BOM 기준으로 추가하고 실제 호환성을 검증한다.
