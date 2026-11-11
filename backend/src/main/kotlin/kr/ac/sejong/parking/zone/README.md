# 구역 조회 기능의 위치

현재 코드 없음. 첫 조회 기능을 만들 때 `adapter/http`, `application`을 실제 코드와 함께 생성한다.

`GET /api/zones/{id}/status`는 기존 OpenAPI의 ZoneView를 반환한다. 한 시점의 snapshot·명령·사진 정보를 읽고 view_revision을 전달한다. 편의상 임의의 축약 DTO를 제품 API로 노출하지 않는다.

조회는 JDBC 저장 접근 경계로 연결한다. SSE는 후속 작업이며 별도 서버를 만들지 않는다. 현재 부트스트랩 보안 설정에서 제품 API는 막혀 있으므로 첫 기능에서 명시적인 개발 조회 인증을 마련한다.
