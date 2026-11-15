# ADR-007: 세션 인증과 일관된 조회 API

상태: 채택·구현 · 2026-11-14

## 문제

MQTT 수신·저장은 있지만 프론트가 읽을 정식 API가 없었다. 단순 개발용 전체 공개 조회는 기존 구역 권한·세션·보관 규칙을 검증할 수 없다. M1에서 [ADR-005](005-public-demo-boundary.md)의 인증·조회 경계를 실제 코드로 연결한다.

## 결정

| 경계 | 구현 |
|---|---|
| 프로필 | web-api가 database를 포함. app-local은 loopback HTTP. 기본 프로필은 계속 health 외 차단 |
| 인증 | Spring Security DAO 인증 + BCrypt cost 12. LoginUser는 인증 중만 사용, 세션에는 자격증명 없는 WebIdentity 저장 |
| 로그인 상태 저장 | HttpSessionSecurityContextRepository 명시 저장, 세션 ID 변경과 CSRF 교체를 SessionAuthenticationStrategy로 실행 |
| CSRF | 세션 저장소 + Spring 기본 XOR 처리. JSON 로그인도 검사. 성공 후 토큰 재발급 |
| 세션 수명 | 30분 활동 제한·8시간 절대 제한을 주입 가능한 Clock으로 검사. SSE는 이후에도 활동 갱신에서 제외 |
| 권한 | 다음 HTTP 요청마다 활성 사용자·역할 재조회, 자료 SELECT에서 user_zone_access 재검사. OPERATOR도 암묵적 전체 접근 없음 |
| 로그인 제한 | 계정·실제 remoteAddr 쌍의 인증 실패 5회 후 5분 차단. 10분 TTL·최대 10,000개·용량 초과 429. 저장소 장애는 비밀번호 실패에 넣지 않음 |
| 구역 조회 | 한 SQL statement의 MVCC snapshot에서 권한·hold·revision·snapshot·명령·사진을 함께 읽음 |
| 목록·상세 | 읽기 전용 REPEATABLE READ. 목록은 최초 서버 수신 시각과 event_id의 keyset pagination |
| cursor | 필터 fingerprint·위치·버전의 HMAC-SHA256. 서버 키 32바이트 이상, 최대 2048자, 변조·필터 변경 거부 |
| 보관 | 목록/상세 읽기에서도 만료 적용. 실제 정리 배치 시점에 의존하지 않음. 권한 검사를 410 판단보다 먼저 실행 |
| 복구 gate | 수신과 조회가 RevisionReservations 하나를 공유. DB 읽기 트랜잭션 뒤 파일·DB 예약 범위 확인. 조회가 새 범위를 예약하지 않음 |
| 계정 운영 | 명시 CLI로 사용자·구역 권한·감사 기록을 원자 변경. 비밀번호는 콘솔/소유자만 읽는 파일. 초기 웹 계정 자동 생성 없음 |

신규 데모 초기화 후에 계정을 만든다. 계정 관리 감사 기록도 기존 이력이므로 반대 순서는 fresh 검사에 걸린다. 일반 DB 복원·기존 이력 인수는 M0 초기화와 구분하여 M5에서 구현한다.

## 응답과 실패

7개 auth/REST 경로를 [유효 OpenAPI](../../contracts/effective/openapi.json)에 맞춘다. ZoneView의 필수 8키, null과 배열을 유지한다. DB에 명령/사진 메타데이터가 있으면 실제로 읽고, 모든 역할의 일반 조회에서 confirmation_token은 null로 표시한다. 원문/리뷰/사진 상세 배열을 임의 개수로 자르지 않는다.

공통 오류는 RestError의 5키와 서버 생성 request_id를 사용한다. DB 접근·트랜잭션 연결 실패는 503, 예상하지 못한 내부 오류는 500이다. SQL·비밀번호·토큰을 응답에 넣지 않는다. 보관 만료 410과 없는 대상 404를 구분한다.

파일 gate는 DB 트랜잭션 밖에서 확인한다. 읽는 동안 예약 범위가 정상 확장되어 시점이 어긋나도 잠시 503으로 거부할 수 있다. 파일 손상을 발견하면 같은 프로세스에서 파일을 되돌리는 것만으로 자동 재개하지 않는다. 현재 gate는 같은 예약 범위 안의 과거 DB 복원을 자동 검출하는 기능이 아니다.

## 검증과 제한

[시험 기록](../../validation/authenticated-read-2026-11-14.json)은 계약 검증·실제 PostgreSQL·MockMvc·실제 HTTP 쿠키·별도 계정 CLI 프로세스를 구분한다. DB 장애는 시험 스키마의 테이블을 일시적으로 사용할 수 없게 하고 복구하여 검사한다. 사용자 DB 서버를 정지시키는 장애 시험은 아니다.

현재 세션과 로그인 제한은 단일 앱 메모리에 있다. 앱 재시작 시 재로그인이 필요하다. CORS 공개·임의 X-Forwarded-For 신뢰·JWT·Redis를 추가하지 않는다. 공개 시 프록시 신뢰 범위와 HTTPS를 M5에서 검증한다. M2 이전에는 SSE 연결·세션 종료에 따른 스트림 종료가 없다. 명령/정책 변경·사진 파일 API도 없다.

## 참고

- [Spring Security 6.5 세션 관리](https://docs.spring.io/spring-security/reference/6.5/servlet/authentication/session-management.html): 커스텀 로그인 필터의 세션 전략·명시 SecurityContext 저장
- [Spring Security 6.5 CSRF](https://docs.spring.io/spring-security/reference/6.5/servlet/exploits/csrf.html): 토큰 저장/처리·인증 성공 후 재발급
