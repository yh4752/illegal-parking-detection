# 프론트 개발 지침

루트 AGENTS.md와 이 파일을 함께 읽는다. `docs/frontend-handoff.md`가 실행·수정·검증의 진입점이다.

- React + TypeScript + Vite SPA와 동일 출처의 Spring API를 사용한다. 브라우저에서 MQTT·DB에 연결하지 않는다.
- wire 계약은 contracts/effective다. `src/api/generated`는 `npm run generate`로 갱신하고 직접 수정하지 않는다.
- `src/api`는 HTTP·오류 처리, `features/auth`는 세션, `features/zones`는 실시간·revision 처리를 담당한다. 화면·CSS를 바꿀 때 이 규칙을 지우지 않는다.
- 비밀번호·CSRF·세션 토큰을 localStorage, URL, 로그, VITE_*에 저장하지 않는다. VITE_DEMO_ZONE_ID는 공개 구역 ID일 뿐 권한이 아니다.
- 연결 상태·카메라 상태·사건 상태를 분리한다. 최초 null을 빈 구역으로 바꾸지 않고, 마지막 관측을 현재 관측처럼 표시하지 않는다. 시각은 한국 시각으로 표시하되 API UTC 값을 바꾸지 않는다.
- SSE와 REST는 같은 revision 비교를 사용한다. 구역/계정 변경 시 이전 요청·타이머·연결을 정리한다. 과거 사건 원문은 SSE만으로 갱신하지 않는다.
- UI는 가상 장치 데모 표시, loading/empty/error/401/403/404/410, keyboard/focus/label을 갖춘다. 미구현 명령 버튼·영상·주차 지도를 추가하지 않는다.
- 변경 검증은 `npm run generate`, `npm run test`, `npm run build`. 통신·사용 흐름 변경은 별도 데모 환경의 `npm run test:e2e`도 실행하고 실제 실행 범위를 기록한다.
