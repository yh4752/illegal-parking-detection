# 관리자 웹 — 프론트 담당의 진입점

React 관리자 웹의 위치다. 이번 골격에서는 프로젝트·화면·Node 의존성을 생성하지 않았다.

다음 구현은 [웹 인계 계획](../docs/public-demo.md)과 [상세 설계](../docs/design/public-demo-plan.md)를 따른다. React + TypeScript + Vite로 로그인·구역 현황·사건 목록/상세를 먼저 구현하고, API/SSE 연결 모듈과 UI 수정 영역을 나누어 인계한다. 아직 실행 가능한 프론트 코드는 없다.

서버를 기다리지 않고 기존 `zone_view` 예제로 화면을 만들 수 있다. 정식 응답은 [계약 안내](../docs/contracts.md)를 따른다. 현재 백엔드에는 상태 API가 구현되지 않아 요청하면 접근이 거부된다.

첫 연결 대상: `GET /api/zones/{id}/status`. 후속 실시간 경로: `GET /api/stream?zone_id=...`, `zone_snapshot` 이벤트. 후속 구현에서는 SSE 연결 후 REST 조회, 재연결 재조회, 더 큰 view_revision만 반영하는 규칙을 적용한다.

관리자 요청의 접수·실제 적용·미확인을 나누고, 서버와의 연결 문제와 카메라의 관측 문제를 다르게 표시한다. 브라우저는 MQTT에 직접 연결하지 않는다.
