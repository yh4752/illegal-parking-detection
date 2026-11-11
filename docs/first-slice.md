# 다음 구현: 가상 사건 수신 → 저장 → 조회

이 문서는 작업 지시서입니다. 아래 기능은 현재 골격에 구현되어 있지 않습니다.

## 완료 모습

PC 가상 장치가 사건 예제를 발행하면 서버가 저장하고 응용 ACK를 회신합니다. 조회 API에서 같은 사건과 현재 상태를 확인합니다. 같은 메시지를 다시 보내거나 오래된 메시지를 뒤늦게 보내도 데이터가 중복되거나 현재 상태가 되돌아가지 않습니다.

## 시작 순서

| 순서 | 작업 | 남길 결과 |
|---|---|---|
| 0 | 골격 빌드·기동, 기존 계약 검사 | 새 실행 로그. 기존 validation 파일을 새 성공으로 복사하지 않음 |
| 1 | JDBC·Flyway·PostgreSQL 의존성 추가, 기존 SQL 검토 후 마이그레이션 | 빈 개발 DB 적용 결과·명시적인 zone/device/policy fixture |
| 2 | Spring Integration MQTT 5 연결, 토픽·장치·Schema·의미 검사 | 메시지 종류와 수신 결과. 범위 밖 메시지는 정상 처리했다고 ACK하지 않음 |
| 3 | 메시지 수신 업무와 트랜잭션·중복·순서 규칙 | 수신 이력·사건·snapshot·view_revision 일관성 |
| 4 | 커밋 후 응용 ACK | STORED/DUPLICATE/REJECTED 및 실패 경로 확인 |
| 5 | 상태 조회 API와 개발 조회 권한 구성 | 기존 OpenAPI의 ZoneView. 제품 로그인은 후속 구현임을 구분 |
| 6 | 가상 발행 도구와 통합 시험 | 재현 명령·기대값·실제값·미수행 항목 |

## 첫 입력과 출력

| 항목 | 기준 |
|---|---|
| 입력 | 기존 examples.json의 guidance_complete. 과거 사건 재생으로 사용 |
| 토픽 | parking/v2/devices/{device_id}/events, QoS 1, retain=false |
| 응용 ACK | 같은 장치 /acks, 원래 message_id 유지 |
| 조회 | GET /api/zones/{id}/status, 기존 ZoneView 전체 계약 |
| 선행 데이터 | 등록 장치·구역·정책. 원본 예제의 참조 대상과 일치하는 개발 fixture |

이력 재생만으로 현재 장치가 ONLINE이거나 실제 안내가 성공했다고 표시하지 않습니다. heartbeat의 연결 최신성·camera 관측 최신성은 별개입니다. 상태 조회에 필요한 초기값은 정식 계약에 맞는 미확인/빈 상태로 구성하고, 성공처럼 보이는 임의 값을 채우지 않습니다.

## 최소 인수 시험

- 정상 1건: DB 반영과 커밋 후 ACK, 조회의 ID·상태·revision 일치.
- 같은 ID·같은 내용: 두 번째는 DUPLICATE, 이력 중복 없음.
- 같은 ID·다른 내용: 격리 후 REJECTED, 현재 상태 훼손 없음.
- seq 12 → 10: 사건·snapshot 각각의 순서 조건 확인. 무조건 모든 과거 메시지를 버리지 않음.
- DB 저장 실패: 성공 ACK 없음. 동일 메시지 재전송 뒤 한 번만 저장.
- 커밋 뒤 ACK 유실: 재전송에 DUPLICATE, 현재 상태 유지.
- 조회: 기존 Schema·OpenAPI에 맞는 null·필드·타입. 한 시점의 읽기 보장.

위 결과는 **PC 가상 통합 시험**입니다. 실제 보드·AI 성능·물리 출력 시험은 아닙니다.

## 이번에 구현하지 않는 것

SSE·웹 화면·관리자 명령·정식 로그인·사진 업로드·원격 배포·보드 SDK는 다음 단계입니다. 상태 조회 시험이 필요할 때 접근 제한을 전역 해제하지 않고, loopback 전용 개발 인증 방식을 명시적으로 추가합니다. 운영 로그인 계약과 구분합니다.

## 읽을 기준

[계약 원본](contracts.md), [백엔드 작업](../reference/v1.43/tasks/backend.md), [C14](../reference/v1.43/spec/protocol/C14.md), [C15](../reference/v1.43/spec/protocol/C15.md), [D5](../reference/v1.43/spec/storage/D5.md).
