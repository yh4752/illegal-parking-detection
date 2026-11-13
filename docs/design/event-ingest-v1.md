# 사건 수신·저장 전 검증 기준 v1

2026-11-13 · 상태: V01~V12·D01~D08의 저장 구현 및 일반·PostgreSQL 시험 완료. 새 저장 프로필의 정상 1건 브로커 왕복 확인. 동일 메시지의 DUPLICATE 수신 확인. SQL로 원문 1건 확인. 추가 수신 시험·조회 API는 다음 단계

대상은 `parking/v2/devices/{device_id}/events`의 EventMessage다. v1.43의 필드·타입·토픽·`schema_version=2`는 유지한다. 이 문서는 상세 명세에서 빠뜨리기 쉬운 조건과 서버의 처리 순서를 고정한다. heartbeat·명령·사진 업로드 전체 설계를 확정했다는 뜻은 아니다.

**메시지 형식이 맞는 것, 업무 조건을 만족하는 것, 저장된 것은 각각 다른 결과다.** 현재 `EVENT_CONTRACT_VALIDATED` 로그만으로 아래 전체 기준을 충족했다고 판단하지 않는다.

필드·타입·nullable·중첩 구조는 [DTO 필드 명세](event-message-dto.md)를 먼저 본다. 현재 JsonNode 검증과 아직 미구현인 Kotlin DTO 클래스를 구분했다.

## 1. 책임과 검사 단계

| 단계 | 실행 위치·담당 | 확인하는 것 | 확인하지 않는 것 |
|---|---|---|---|
| 입력·형식 | 서버 MQTT 어댑터·검증기 / 백엔드 | 전송 옵션, UTF-8, JSON, Schema, 한 메시지의 모순 | 실제 차량 움직임·촬영 시각 정확성 |
| 등록·권한 | 브로커 및 서버 / 백엔드 | 운영 계정 ACL, 토픽/본문/등록 장치 관계 | 토픽 문자열만으로 발신자 인증 |
| 저장 판단 | 서버 트랜잭션 / 백엔드 | 정책 존재, 사건 소유권, 중복·역순·종료 보호 | 영상 재분석, 서버 수신 시각으로 정차 시간 재계산 |
| 현장 판단 | AI 제어 루프·플랫폼 어댑터 / AI | 실제 관측·동일성·정차·시각 신뢰·안내 허용·출력 결과 | 서버 저장 완료 추정 |
| 전달·내구성 | 보드 SDK / 백엔드, 보드 결합 / AI | 원자적 로컬 저장, 순번, outbox, 재전송, 응용 ACK | AI 판단을 SDK가 새로 만들기 |

문서상 규칙은 엣지가 먼저 지킨다. 서버는 전송된 값만으로 증명 가능한 모순을 추가 차단한다. 서버가 경고를 보내기 전에도 엣지는 안전 중단을 수행해야 한다.

## 2. 메시지 한 건으로 확정할 검사

`event`는 메시지가 보고하는 사건 요약, `snapshot`은 그 메시지를 만들 당시의 전체 현재 상태다. 서버가 받은 현재 시각의 상태라는 뜻은 아니다.

| ID | 확정 규칙 | 근거 | 현재 코드 |
|---|---|---|---|
| V01 | events 경로·QoS 1·retained=false. 본문은 65,536바이트 이하, 엄격한 UTF-8/JSON. 중복 키·NaN·Infinity·후행 JSON 거부 | C2, D7, SC06 | 있음 |
| V02 | 원본 EventMessage Schema 전체 적용. nullable 키도 필수이며 숫자 범위·정수 의미·UTC Z·실재 날짜·추가 필드·enum 검사 | C1, D7, Schema | 있음 |
| V03 | envelope/snapshot의 device_seq·boot_id·clock_synced 일치. event와 image_update가 있으면 각 event_id가 envelope와 일치 | C1, SC01 | 있음 |
| V04 | snapshot.clock_synced=false이면 continuous_stop_ms=0이고 reason_codes에 CLOCK_UNTRUSTED 포함. 기존 Schema의 UNTRUSTED·관측/발생 시각 null 조건도 유지 | A2, C12 | 있음 |
| V05 | pauses의 pause_id는 중복 불가. ACTIVE가 하나라도 있으면 PAUSED, 없고 ACK_REQUIRED가 있으면 AWAITING_RESUME, 둘 다 없으면 AUTO. SCHEDULED만 있는 경우 AUTO | C5의 집합·mode 산출 | 있음 |
| V06 | active_policy_version=null이면 snapshot.current_event_id=null, camera_health=RECONFIGURE. 사건 없음에 따른 0/null은 Schema로 검사. active가 있으면 received도 있어야 함 | C1 초기 설정, C3 수신 후 적용 | 있음 |
| V07 | received/active가 모두 있으면 received>=active. 현재 사건의 event_policy_version을 received나 active와 무조건 같게 강제하지 않음 | C3 | 있음 |
| V08 | event 및 snapshot의 OBSERVING/STOP_CONFIRMED는 guidance_count=0, GUIDING/GUIDED는 1~2. LONG_UNRESOLVED는 overdue=true | A2 | 있음 |
| V09 | overdue=true인 사건의 표시 상태는 HELD/LONG_UNRESOLVED/CLOSED_EXIT/CLOSED_MANUAL 중 하나. 출력 상태를 IDLE로 강제하지 않음 | A2 표시 우선순위·기존 재생 허용 | 있음 |
| V10 | EVENT_CLOSED_EXIT/MANUAL은 event가 있고 각각 CLOSED_EXIT/MANUAL 상태여야 함. close_reason은 기존 Schema 검사 유지. 다른 event_type을 상태 하나에 일괄 대응시키지 않음 | A2, C5, C9의 durable 종료 | 있음 |
| V11 | event와 snapshot이 같은 사건을 나타내면 공통 요약 필드 일치. 다른 사건이면 비교하지 않음 | C9의 요약 반영을 위한 이번 보완 결정. 아래 공통 필드 목록 참조 | 있음 |
| V12 | 현재 사건이 있고 mode≠AUTO, camera_health≠OK, clock_synced=false, CLOCK_UNTRUSTED 사유, output_status가 FAILED/UNKNOWN 중 하나라도 있으면 incident_state는 HELD 또는 종료 상태여야 함. 출력의 과도 상태까지 IDLE로 강제하지 않음 | A2의 보류 우선순위, A5, C12 | 있음 |

V04·V06은 **snapshot에 대한 조건**이다. 과거 사건 요약이나 사진의 이미 신뢰된 시각을 현재 시각 불신 때문에 null로 덮어쓰지 않는다. V08·V09는 사건 요약과 snapshot 각각에 적용하며 사건이 없는 snapshot은 제외한다.

V06에서 과거 `event`가 있다고 무조건 거부하지 않는다. 과거 기록과 현재 snapshot은 다를 수 있다. 현재 유효 정책이 없는 snapshot에서 새 사건을 나타내거나 안내하도록 보고하는 모순을 막는다.

시각이 회복되어 clock_synced=true여도 정상 5초를 기다리는 동안 CLOCK_UNTRUSTED가 남을 수 있다. 따라서 V04를 역방향까지 적용하지 않는다. 안내 여부를 서버가 다시 결정하거나 명령하지 않는다.

## 3. 정상으로 허용할 조합

| 조합 | 허용 이유 |
|---|---|
| event.event_id=A, snapshot.current_event_id=B 또는 null | 과거 사건 종료와 현재 상태를 구분. 두 사건을 각각 처리 |
| GUIDED, guidance_count=1, continuous_stop_ms=0 | 안내 이력은 남고 이동·관측 실패로 정차 후보만 초기화될 수 있음 |
| GUIDING, output_status=IDLE | 안내 시도 선저장과 실제 play 시작 회신 사이일 수 있음 |
| LONG_UNRESOLVED, overdue=true, output_status=PLAYING | 10분 도달은 새 안내를 막지만 이미 재생 중인 안내를 중단할 사유는 아님 |
| HELD, overdue=true | 보류가 화면 상태에서 우선해도 미해소 사실은 유지 |
| clock_synced=true, CLOCK_UNTRUSTED 포함, 정차시간 0 | 신뢰 회복 후 연속 정상 5초 대기 가능 |
| received=4, active=3, event=3 | 시간 정책을 받고 현재 사건 종료까지 적용 대기 |
| envelope.clock_synced=false, event.started_at가 과거 UTC | 과거 신뢰 시각 보존. occurred_at/observed_at만 현재 생성 시각 규칙 적용 |
| 큰 device_seq 점프 또는 boot_id 변경 | heartbeat 등 다른 메시지가 순번을 사용하며 재시작 후에도 순번은 지속됨 |

금지할 추론: 5분 경과를 서버 시각으로 재판정, `continuous_stop_ms`로 overdue 계산, GUIDED를 근거로 실제 음성 성공 확정, event_type과 현재 snapshot 상태를 단순 1:1 매핑, 정차시간·모든 정책 버전을 무조건 단조 증가/동일값으로 검사.

## 4. DB 상태와 함께 검사할 조건

| ID | 트랜잭션에서 확인할 조건 | 처리 원칙·근거 |
|---|---|---|
| D01 | 장치 활성·구역 소유권을 잠금 후 재확인 | 앞선 읽기 결과만 믿지 않음. C1, D5 |
| D02 | 메시지에 나온 nonnull 정책 버전은 해당 구역에 존재. 기존 사건의 event_policy_version은 불변 | received/active/current event 및 event 요약 버전 각각 확인. 최신 정책과 같을 필요 없음. C3 |
| D03 | event_id의 장치·구역 소유권과 tombstone 확인 | 다른 장치 사건에 덮어쓰기 금지. 삭제된 종료 사건 부활 금지. C9, D6 |
| D04 | (device_id,message_id)와 RFC8785 canonical JSON SHA-256 비교 | 같은 내용이면 기존 결과 재현. ID만 같고 내용 다르면 충돌 격리. C2, D5 |
| D05 | 사건 요약은 사건별 last_device_seq, snapshot은 장치별 last_device_seq 기준 | 낮은 순번의 정상 원문은 이력 저장 가능. 현재 상태의 역행은 금지. C9, D5 |
| D06 | 기존 사건의 종료 사실·안내 횟수 보존 | 더 최신 요약도 종료 사건을 재개하거나 안내 횟수를 줄이지 못함. 오래된 요약은 새 상태로 채택하지 않음. A2, C9 |
| D07 | 상태 갱신·view_revision 증가·처리 결과를 원자 저장 | READ COMMITTED, zone_view 행 잠금, 잠금 순서 zone→device→incident→command→image. D5 |
| D08 | ACK는 성공/오류 격리 트랜잭션 커밋 뒤 전송 | PUBACK와 별개. DB 실패·커밋 결과 불명은 ACK하지 않음. C2, D5 |

정상 과거 원문과 잘못된 최신 상태를 구별한다. 더 낮거나 같은 seq는 요약·snapshot을 갱신하지 않는다. 같은 seq로 다른 내용을 보낸 새 message_id는 이력·감사에서 추적하되 임의로 최신값을 선택하지 않는다. D04의 message_id 중복 검사와는 별개다. 상충하는 더 최신 사건 요약은 격리하며 정상 현재 상태를 부분 갱신하지 않는다.

종료 사건에 뒤늦은 비종료 과거 보고가 와도 정상 과거 이력으로 남길 수 있다. 종료보다 큰 사건 seq로 재개를 주장하거나 같은 사건의 정책을 바꾸는 보고는 정상 갱신으로 받지 않는다. 신규 사건 ID의 최초 수신이 종료 보고여도 종료 상태로 기록할 수 있어야 한다.

snapshot만 보고된 현재 사건도 C9에 따라 사건 요약에 반영한다. 단, snapshot에 없는 started_at/closed_at 등을 서버 수신 시각으로 만들어 넣지 않는다. event 요약과 동일한 사건을 나타내는데 공통 상태값이 상충하면 격리한다. 비교 공통 필드는 state/incident_state, event_policy_version, guidance_count, continuous_stop_ms, body_type, use_type, overdue다. 이 조건은 원자적으로 같은 사건 상태를 만든다는 구현 보완 결정이며, 새 메시지 생성 시 SDK에서도 보장해야 한다.

사건 수신은 heartbeat 수신 시각이나 ONLINE 상태를 갱신하지 않는다. 예전 메시지를 받았다는 이유로 장치가 현재 온라인이라고 표시하지 않는다.

## 5. 거부·재시도·응용 ACK의 구분

다음 동작을 mqtt-ingest-local에 구현했다. 기존 mqtt-validation-local은 검증 전용으로 유지하여 ACK를 보내지 않는다. 사진 상태 보고는 아래 별도 범위를 따른다.

| 상황 | 저장 및 ACK |
|---|---|
| 정상 새 메시지 | 관련 저장 전부 커밋 후 STORED, error_code=null |
| 앞서 STORED인 같은 ID·같은 내용 | 상태/보관 기한을 다시 바꾸지 않고 DUPLICATE |
| 앞서 REJECTED인 같은 ID·같은 내용 | 최초 오류를 유지한 REJECTED. 성공처럼 보이는 DUPLICATE로 바꾸지 않음 |
| 같은 ID·다른 내용 | 기존 성공/오류 receipt 보존. 별도 충돌 원문 격리 후 REJECTED/MESSAGE_ID_CONFLICT |
| Schema 또는 V04~V10/V12 위반 | 상관 가능한 메시지만 오류 원문 격리 후 REJECTED/INVALID_SCHEMA |
| 지원하지 않는 schema_version | 상관 가능하면 격리 후 REJECTED/UNSUPPORTED_SCHEMA |
| 존재하지 않거나 변경된 정책 버전 | 격리 후 REJECTED/VERSION_CONFLICT. 미등록 개발 정책은 먼저 등록하고 발행 |
| 사건 소유권/같은 사건의 요약 모순 | 격리 후 REJECTED/EVENT_MISMATCH |
| 더 최신 보고의 종료 재개·횟수 감소 | 격리 후 REJECTED/INVALID_SCHEMA |
| JSON/UTF-8 손상·중복 키·크기 초과·안전하게 상관 불가 | 상관 ACK 없음. 크기를 제한한 운영 진단만 기록 |
| 토픽/등록/계정 불일치·비활성 장치 | 정상 수신 경로로 진입하지 않음. 임의 장치 토픽에 ACK하지 않음 |
| DB 오류·격리 저장 실패·커밋 결과 불명 | ACK 없음. 동일 메시지 재전송으로 처리 결과 확인 |

상관 가능한 입력: 엄격한 JSON 파싱이 성공하고, 등록·토픽 관계가 맞는 device_id/zone_id와 유효한 message_id가 있으며, 기존 동일성 규칙으로 내용을 식별할 수 있는 경우다. 구체적으로 세 상관 ID는 NUL 없는 1~128 Unicode 문자다. 세 ID를 해석할 수 없거나 canonical 표현을 안전하게 만들 수 없으면 ACK를 추정하지 않는다. 나머지 Schema 오류는 상관·등록이 확인된 경우 격리 후 거부한다. 오류의 `committed_at`은 격리 커밋을 확인한 뒤 서버 시각으로 만든다.

검사 순서: 전송/크기/엄격한 파싱 → 상관 가능성·등록/권한 → canonical hash 및 기존 receipt 조회 → 미처리 입력의 Schema·V 규칙·D 규칙 → 정상 저장 또는 격리 → 커밋 → ACK. 이미 확정된 동일 재전송을 새 정책·새 검증 규칙으로 재판정하지 않는다. 실제 쓰기 전 잠금과 등록 재확인은 항상 적용한다.

## 6. 저장 전에 해결할 DB 구조 차이

C2/D5는 같은 ID의 다른 원문을 격리하라고 요구하지만, V1의 event_message는 `(device_id,message_id)` 기본키이고 payload도 JSONB object만 허용한다. 이 테이블에 충돌 원문을 덮어쓰거나 malformed JSON을 넣는 방식은 쓰지 않는다.

**설계 결정:** 저장 단계에서 V2로 별도 `inbound_quarantine` 테이블을 추가한다. V1과 보존본 SQL은 수정하지 않는다. 필수 저장 항목은 서버 생성 quarantine_id, 등록 장치 ID, message_id(해석 가능 시), 토픽, 원본 바이트(최대 65,536), raw SHA-256, canonical SHA-256(계산 가능 시), error_code, 내부 rule_id, 최초 received_at이다. 동일 오류 재시도는 장치·message_id·raw hash·rule_id 기준으로 한 번만 보관하며 기한을 연장하지 않는다. 대용량·상관 불가 입력을 이 테이블에 무제한 적재하지 않는다.

원문 격리에는 사건·조작 원문과 같은 최초 수신부터 30일 보관을 적용한다(팀의 보완 결정). 삭제 후에도 receipt의 최소 ID/hash/오류 결과는 유지한다. 운영자 접근으로 제한하고 원문을 일반 로그나 공개 API에 노출하지 않는다. V2 DDL·정리 작업은 실제 PostgreSQL 시험을 마쳤다. 만료 원문 바이트만 제거하고 충돌의 ID/hash/rule/기한 메타데이터를 남겨 재전송이 원문을 되살리지 않게 한다. 운영 권한 분리는 후속 배포 범위다.

현재 DB의 초기 revision 허용 범위는 0뿐이다. 상태를 갱신하면서 view_revision만 +1 하면 제약 위반이다. D6의 구역별 revision 범위 예약과 외부 manifest 확보를 저장 구현의 선행 조건으로 포함한다. 제약을 지우거나 무제한 범위로 바꿔 우회하지 않는다.

## 7. 확인 예제와 착수 조건

기계가 읽을 예제는 [event-validation-cases.json](event-validation-cases.json)에 있다. `base_example`은 보존본 examples.json의 키이고, `changes`는 해당 예제에 적용할 JSON Pointer 경로와 값이다. Schema 통과 예상과 새 의미 검사 예상 결과를 분리했다. DB 전제 조건은 메시지에 임의 필드로 넣지 않는다.

예제의 기대 결과는 구현해야 할 정답이다. 파일이 존재하거나 Schema를 통과했다는 이유로 아직 없는 의미 검증 코드까지 통과한 것으로 기록하지 않는다.

저장 전 선행 조건인 V04~V12 검사와 26개 허용/거부 예제의 실제 검증기 테스트를 완료했다. 추가 경계·반례를 포함한 의미 검사 테스트는 56개다. [실행 기록](../../validation/event-semantics-2026-11-13.json)을 참고한다. 12개 DB 인수 시나리오도 실제 PostgreSQL에서 확인했다. 커밋 뒤 ACK 호출은 어댑터와 DB를 연결하고 발행 함수를 대체해 검사했다. 실제 Mosquitto 왕복 완료를 뜻하지 않는다. [저장 시험 기록](../../validation/event-storage-2026-11-13.json)을 참고한다.

저장 단계 반영: 실제 예제 정책 등록 SQL, quarantine V2, revision 예약, 정상/중복/충돌/역순/실패/ACK 유실 시험을 구현했다. IMAGE_STATUS_CHANGED는 아직 저장·ACK하지 않는다. D6의 자동 복원·운영자 인수는 미구현이며 불일치는 중단한다. 자세한 범위는 [ADR-004](../adr/004-event-storage-and-ack.md)를 따른다. 미구현 메시지 계열·사진 부수효과를 조용히 버리고 STORED를 보내지 않는다. 첫 수직 기능의 지원 범위를 명시적으로 제한하고 나머지는 검증 전용 상태로 남긴다.

## 8. 근거와 적용 범위

- [원본 계약](../contracts.md), [C1](../../reference/v1.43/spec/protocol/C1.md), [C2](../../reference/v1.43/spec/protocol/C2.md), [C3](../../reference/v1.43/spec/protocol/C3.md)
- [C5](../../reference/v1.43/spec/protocol/C5.md), [C9](../../reference/v1.43/spec/protocol/C9.md), [C12](../../reference/v1.43/spec/protocol/C12.md), [A2](../../reference/v1.43/spec/protocol/A2.md)
- [D5](../../reference/v1.43/spec/storage/D5.md), [D6](../../reference/v1.43/spec/storage/D6.md), [D7](../../reference/v1.43/spec/storage/D7.md), [결정 기록](../adr/003-event-validation-boundary.md)

기존 명세의 구체화: V04~V10/V12, 과거/현재 구분, DB 순서·종료 보호. 이번 구현 결정: V11의 같은 사건 요약 일치, 거부된 재전송의 응답, 격리 저장소·보관 기간·오류 대응표. 새 wire 필드나 schema_version 변경은 없다. 배치·담당 경계는 기존 C4 구성을 유지한다.
