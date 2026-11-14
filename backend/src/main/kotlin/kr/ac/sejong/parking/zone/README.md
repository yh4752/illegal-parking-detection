# 구역·사건 조회

`adapter/http/ReadController`가 인증된 사용자 요청을 받고 `application/ReadQueries`가 조회 및 revision gate를 조정한다. `adapter/jdbc/JdbcReadStore`가 구역 권한·보관 규칙을 SQL에 반영한다. 목록 cursor는 `application/EventCursor`가 검증한다.

ZoneView는 snapshot·명령·사진·revision을 한 statement에서 읽는다. 목록과 상세는 읽기 전용 REPEATABLE READ다. DTO를 축약하지 않으며 이미지 확인 토큰은 공개 조회에 노출하지 않는다.

`web-api`의 정식 세션 인증을 사용한다. SSE는 다음 M2이며 별도 서버로 만들지 않는다. 루트의 `docs/authenticated-read.md`와 ADR-007을 함께 읽는다.
