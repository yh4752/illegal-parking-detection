# ADR-005 로그인 가능한 공개 데모와 프론트 인계

2026-11-13 · 구현 설계에 채택 · 기능 구현 전 · 기준 코드 6c6c172

## 배경

사용자는 한 단계씩 학습하며 구현하는 방식에서, 웹 조회와 배포까지 먼저 구현하고 완성된 코드를 학습하는 방식으로 전환했다. 사건 저장·응용 ACK는 검증됐지만 조회 API·로그인·heartbeat·SSE·React·외부 배포는 없다. v1.43의 원본을 보존하면서 실제 구현에 필요한 결정을 보완한다.

## 결정과 영향

| 항목 | 결정 | 원본과의 관계 |
|---|---|---|
| 인계 범위 | 인증된 조회 데모를 우선 완성. 명령·사진·실보드 SDK는 후속 | v1.43 요구를 삭제하지 않고 첫 릴리스 범위를 구분 |
| 프론트/배치 | React·TypeScript·Vite SPA, 단일 Spring, 동일 출처 Nginx·Compose | C14~C16의 경계를 유지; SSR 서버 추가 없음 |
| 구역 권한 | V3 user_zone_access PK(user_id,zone_id), 각 FK, 기본 권한 없음 | C7의 권한 요구를 기존 DDL에 추가. V1/V2 수정 금지 |
| heartbeat 관측값 | V4 zone_view에 nullable fps·pending_message_count·storage_ok | Heartbeat 필드는 기존 계약. 웹 ZoneView 확장 없음 |
| 목록 시간 | first_received_at DESC,event_id DESC; from/to는 최초 서버 수신 | C7의 created_at을 DB의 실제 컬럼에 대응. started_at과 혼용 금지 |
| 세션 | idle 30분/absolute 8시간, SSE 검사·주석은 idle 연장 없음 | 원본에 없던 운영 초기값. 메모리 세션·CSRF·쿠키 기준 유지 |
| 접속 판정 | seq가 현재 snapshot/heartbeat보다 큰 새 heartbeat만 freshness 채택 | 보수적인 도착 순서 정책. 뒤늦은 heartbeat는 다음 보고까지 대기 |
| availability | 접속 ID는 진단; ONLINE/STALE/OFFLINE은 heartbeat 기준 | D1의 보조 정보 원칙. OFFLINE Will만으로 즉시 강등하지 않음 |
| SSE 일관성 | TX 내 전체 값 고정→커밋 후 발행, DB revision 주기 대조 | C8/D5 유지. 누락 복구·메모리 상한·세션 회수 동작 구체화 |
| 복구 gate | 신규 demo 초기화와 과거 DB 복원을 다른 CLI로 처리 | D6/ADR-004의 보류 항목을 구현 범위에 편입; 임의 hold 해제 금지 |

조회 시간 필터는 UI에도 ‘서버 최초 수신 기간’이라고 표시한다. cursor 내부에 서버 수신 시각을 넣되 EventListItem에 존재하지 않는 created_at 필드를 임의로 추가하지 않는다. 구역명도 ZoneView에 없으므로 첫 화면에는 설정된 표시 이름 또는 zone_id를 사용한다.

heartbeat는 ACK·원문/receipt 누적 대상이 아니다. 공통 순번, 정책·소유권·종료·tombstone·pause 검사는 사건 처리와 공유한다. 과거 사건 이력 추가가 구역 snapshot 변경을 동반하지 않을 수 있으므로 목록·상세 화면은 별도로 재조회한다.

## REST 오류 보완

원본의 RestError는 공통 error_code enum을 참조하지만 일반 서버 오류와 요청 제한 코드가 없다. 이를 STORAGE_FULL 같은 장치/업무 코드로 억지 대응하지 않는다.

| HTTP | REST에 추가하는 code | 의미 |
|---|---|---|
| 429 | RATE_LIMITED | 로그인 또는 SSE 연결 수 제한; Retry-After 포함 |
| 503 | SERVICE_UNAVAILABLE | DB·복구 gate 등 일시 이용 불가; retryable=true |
| 500 | INTERNAL_ERROR | 예기치 않은 서버 오류; retryable=false, request_id로 추적 |

**MQTT error_code enum·ACK·EventMessage·schema_version=2는 바꾸지 않는다.** RestError.properties.code에만 별도 enum 분기를 합친다. 원본 OpenAPI에는 구현 대상의 500, 로그인/SSE의 429 응답 설명을 overlay로 보완한다. 프록시가 반환한 비JSON 오류는 기존 default 규칙으로 처리한다.

변경 데이터는 [public-demo-rest-overlay.json](../design/public-demo-rest-overlay.json)이다. 이 파일은 설계 상태이며 아직 실행용 계약이 아니다. M0에서 생성기를 만들고 `contracts/effective/openapi.json`, `contracts/effective/parking.schema.json`을 생성한다. 원본 해시 검증과 overlay 대상 검증을 선행하며 생성물은 직접 편집하지 않는다. 웹 응답 검증·프론트 타입 생성은 유효 계약을 쓰고 MQTT 수신 검증은 보존 원본을 계속 사용한다.

예제와 인수 조건도 같은 파일·[작업 계획](../design/public-demo-workplan.json)에 기록했다. 원본 reference 127개를 덮어쓰거나 해시 목록을 재작성하지 않는다.

## 복구와 배포의 한계

신규 초기화는 빈 상태·등록·정책·manifest를 검증하고 명시적으로 개방한다. 기존 DB를 가져올 때는 기존 상태 인수 절차를 거친다. 과거 DB 복원은 gate 닫기→세션 종료→최신 manifest 검증→다음 revision 범위 예약→대조 수신→운영자 인수다. 누락된 manifest를 DB 숫자만 보고 재작성하지 않는다.

공개 프로필은 실험용 익명 브로커를 노출하지 않는다. 첫 가상 장치는 같은 호스트 내부에서 연결한다. 실보드의 원격 연결과 운영 권한·명령은 후속 범위다. 단일 호스트 유실 시 ACK된 이력을 과거 백업만으로 모두 복구할 수 있다고 보장하지 않는다.

실제 서버·도메인·팀원 계정·별도 백업 위치는 배포 입력으로 남긴다. 공개 URL 발행, 저장소 public 전환, 팀원 초대는 서로 다른 작업이다. 이번 설계는 GitHub 저장소 공개 전환을 포함하지 않는다.

## 대안과 검증

Next.js 서버·별도 API 게이트웨이·Redis 세션·MQTT 웹 클라이언트는 현재 단일 구역 조회 데모에 추가 배포/운영 경계를 만들기 때문에 도입하지 않는다. 이후 실제 필요가 생기면 별도 결정한다.

[구현 설계](../design/public-demo-plan.md)와 [검토서](../design/public-demo-review.md)의 인수 조건을 따른다. 이번 검토는 문서·계약 정합성 검토다. 동작·성능·보안·외부 배포 시험을 통과했다고 해석하지 않는다.
