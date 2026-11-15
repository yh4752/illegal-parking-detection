# ADR-008: 프론트 인계판의 화면과 실시간 전달

2026-11-14 · 로컬 인계 범위 구현·검증 · ADR-006/007과 v1.43의 wire 계약 유지

## 결정

React + TypeScript + Vite의 동일 출처 SPA를 제공한다. 로그인, 구역 상태, 사건 목록·상세를 실제 API에 연결한다. OpenAPI 타입과 JSON Schema 실행 검증기를 생성하며 화면/스타일과 통신/인증/revision 코드를 나눈다. 비밀번호와 토큰은 브라우저 저장소에 보관하지 않는다.

첫 단일 서버 데모의 SSE는 **1초마다 커밋된 ZoneView를 조회**한다. 한 SQL로 권한·복구 hold·revision·snapshot·부속 자료를 읽는 기존 조회 경계를 사용한다. 따라서 트랜잭션 중간 상태를 보내지 않으며, 사건 ACK의 성공 여부와 화면 송신을 분리한다. 연결마다 이전 revision보다 큰 전체 상태만 보낸다. 중간 revision의 전부 전달은 보장하지 않는다. 사건 이력은 REST 10초 재조회로 별도 갱신한다.

공개 데모 계획의 ‘커밋 후 callback + 누락 보정 polling’을 첫 인계판에서 polling 하나로 단순화한다. 별도 서버/메시지 큐/DB 변경은 없다. 1초의 전달 지연을 수용하고 조회 비용을 제한한다. 실제 지연·동시 접속 범위를 검증 기록에 남긴다. 대규모 서비스 처리량을 주장하지 않는다.

SSE는 Servlet 비차단 출력으로 구현한다. 연결당 송신 중 1개·대기 중 최신 1개, 전역 50개·세션당 3개, 작업자 4개·대기열 100개를 상한으로 둔다. 5초 이상 출력이 진행되지 않는 연결은 닫는다. 15초 keepalive는 세션 활동으로 계산하지 않는다. 매 상태 조회에서 계정·세션·구역 권한을 확인하며 로그아웃 시 해당 세션 연결을 닫는다. 프론트는 SSE 에러의 HTTP 상태를 추측하지 않고 REST로 확인한다.

heartbeat는 원본 Schema와 snapshot 의미 검사를 공유한다. events와 동일한 zone→device→incident 잠금 및 snapshot/요약/중지 투영 코드를 사용한다. 순번이 현재 상태/heartbeat 순번보다 커야 최신성·진단·상태를 갱신한다. heartbeat에는 응용 ACK나 수신 원문/receipt 저장이 없다. 사건만 수신했다고 장치를 ONLINE으로 바꾸지 않는다. 마지막 유효 heartbeat 기준 15초 초과 STALE, 30초 초과 OFFLINE을 사용한다. availability는 연결 진단이며 ONLINE 권한으로 사용하지 않는다.

DB 단절 검증에서 예약 파일을 변경하기 **전**의 DB 조회 실패는 요청을 거절하되 다음 요청에서 다시 검증하도록 구분한다. 파일 변경·파일/DB 불일치 또는 예약 저장 후 실패는 기존처럼 gate를 닫는다. 같은 범위 안의 과거 DB 복원을 자동 탐지한다는 보장은 추가하지 않는다.

## 인계 경계

코드 인계는 새 클론의 설치·실행·실제 로그인·MQTT→DB→REST→화면 재현과 문서로 판정한다. 공개 인계는 별도 HTTPS 배포와 외부 접속 검증이 추가로 필요하다. 사용자 실습 DB를 초기화하지 않고 별도 프로젝트/DB/포트/상태 디렉터리를 사용한다.

## 근거

- [Vite 시작 안내](https://vite.dev/guide/)
- [React Router 선언형 라우팅](https://reactrouter.com/start/declarative/routing)
- [Spring MVC 비동기 요청](https://docs.spring.io/spring-framework/reference/6.2/web/webmvc/mvc-ann-async.html)
- 유효 REST 계약, 기존 원본 MQTT 계약과 ADR-006/007


## 2026-11-14 인계 재검증 보완

문서 커밋 b4f9287의 반복 CI에서 브로커 복귀 50초 검사가 실패했다. 고정 버전 [Paho 1.2.5 재접속 구현](https://github.com/eclipse-paho/paho.mqtt.java/blob/v1.2.5/org.eclipse.paho.mqttv5.client/src/main/java/org/eclipse/paho/mqttv5/client/MqttAsyncClient.java)은 `getMaxReconnectDelay()`를 사용하므로, 기존 `setAutomaticReconnectDelay` 설정만으로 실제 루프의 최대 지연이 바뀌지 않는 것을 확인했다. 수신·ACK·telemetry·검증 클라이언트에 실제 사용되는 밀리초 설정 `setMaxReconnectDelay(8000)`을 적용했다. 통합 검사의 50초 기준은 유지하고 두 번의 중단/복귀를 검사한다.

EventSource의 `Accept: text/event-stream` 요청에서 스트림을 열기 전 발생한 오류도 정식 JSON으로 반환하도록 Content-Type을 명시했다. 실제 HTTP 시험에 해당 Accept 헤더를 사용하며 400/403/404/429 오류 응답을 검사한다. 대기 시간을 늘리거나 실패 검사를 제거해 통과시키지 않는다.
