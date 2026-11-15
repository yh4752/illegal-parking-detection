# 관리자 웹 — 프론트 담당의 진입점

React 관리자 웹의 위치다. 아직 프로젝트·화면·Node 의존성을 생성하지 않았다.

오늘의 착수·완료 기준은 [프론트 인계 체크리스트](../docs/frontend-handoff-checklist.md)를 따른다. REST에 연결되는 화면을 먼저 만들고 heartbeat·SSE와 결합한다.

다음 구현은 [웹 인계 계획](../docs/public-demo.md)과 [상세 설계](../docs/design/public-demo-plan.md)를 따른다. React + TypeScript + Vite로 로그인·구역 현황·사건 목록/상세를 먼저 구현하고, API/SSE 연결 모듈과 UI 수정 영역을 나누어 인계한다. 아직 실행 가능한 프론트 코드는 없다.

서버를 기다리지 않고 기존 `zone_view` 예제로 화면을 만들 수 있다. 정식 응답은 [계약 안내](../docs/contracts.md)를 따른다. 백엔드 로그인·구역 권한·상태/목록/상세 API는 구현되어 있다. 정식 세션 인증과 허용된 구역 권한이 필요하며 [M1 실행 안내](../docs/authenticated-read.md)를 따른다.

첫 연결 대상: `GET /api/zones/{id}/status`. 후속 실시간 경로: `GET /api/stream?zone_id=...`, `zone_snapshot` 이벤트. 후속 구현에서는 SSE 연결 후 REST 조회, 재연결 재조회, 더 큰 view_revision만 반영하는 규칙을 적용한다.

관리자 요청의 접수·실제 적용·미확인을 나누고, 서버와의 연결 문제와 카메라의 관측 문제를 다르게 표시한다. 브라우저는 MQTT에 직접 연결하지 않는다.
