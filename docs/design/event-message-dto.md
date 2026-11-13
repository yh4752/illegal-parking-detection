# 사건 메시지 DTO 필드 명세

기준: v1.43 EventMessage · 통신 schema_version=2 · 2026-11-13

**현재 실행 코드는 JsonNode + JSON Schema 검증이다. 아래 Kotlin 타입명은 DTO로 옮길 때의 설계이며 아직 해당 data class가 구현된 것은 아니다.** 필드·허용값은 기존 Schema에서 가져온다. 새 wire 필드는 없다.

한 사건 메시지는 최상위 EventMessageDto와 그 안의 event/EventRecordDto, snapshot/DeviceSnapshotDto, image_update/ImageUpdateDto로 구성한다. snapshot은 ClockStatusDto와 ActivePauseDto 목록을 포함한다.

## 공통 표기

- 모든 표의 JSON 키는 필수다. `?`는 값에 null을 허용한다는 의미이며 키를 생략해도 된다는 뜻이 아니다.
- JSON 키는 snake_case, Kotlin 속성은 camelCase로 대응한다. 실제 클래스 구현 시 명시적 매핑을 적용한다.
- 시각 필드는 wire DTO에서 UTC Z 형식의 String/String?로 보존한다. DB 시각 변환과 정밀도 처리는 저장 계층에서 명시하며, 메시지 동일성은 원본의 canonical JSON을 기준으로 한다.
- ID는 Unicode 문자 기준 길이 1~128. Long 범위의 양수라는 것만으로 충분하지 않으며 순번·ms는 0~9,007,199,254,740,991, 정책 버전은 1부터 같은 상한까지 검사한다. guidance_count는 Int 0~2다.
- DTO 기본값으로 누락을 0·false·빈 배열·null로 채우지 않는다. 엄격한 파싱·Schema 확인 뒤 DTO로 변환하고 의미 검사를 진행한다. 원문과 해시는 별도로 보존한다.

## 1. EventMessageDto — 최상위 메시지

| JSON 필드 | Kotlin 타입 | 의미 |
|---|---|---|
| `schema_version` | `Int` | 통신 형식 버전. 값은 2 |
| `device_id` | `String` | 발신 장치 ID |
| `zone_id` | `String` | 해당 구역 ID |
| `message_id` | `String` | 전송 메시지 식별자. 동일 재전송에서 유지 |
| `boot_id` | `String` | 장치 프로그램 실행 세대를 구분하는 ID |
| `device_seq` | `Long` | 장치가 새 상태 메시지를 만들 때 증가시키는 순번 |
| `occurred_at` | `String?` | 발생 UTC 시각. 시각 불신이면 null |
| `clock_synced` | `Boolean` | 장치 UTC 시각 신뢰 여부 |
| `event_id` | `String?` | 이 메시지가 보고하는 사건 ID |
| `event_type` | `EventType` | 발생한 일의 종류. 예: 안내 재생 완료 |
| `event` | `EventRecordDto?` | 보고 대상 사건 요약 |
| `snapshot` | `DeviceSnapshotDto` | 메시지를 만들 당시 장치의 전체 현재 상태 |
| `image_update` | `ImageUpdateDto?` | 사진 상태 변화. JPEG 본문 아님 |

## 2. EventRecordDto — 보고 대상 사건 요약

| JSON 필드 | Kotlin 타입 | 의미 |
|---|---|---|
| `event_id` | `String` | 이 메시지가 보고하는 사건 ID |
| `state` | `IncidentState` | 사건 또는 시계 상태 |
| `event_policy_version` | `Long` | 사건 생성 때 고정한 정책 버전 |
| `started_at` | `String?` | 사건 시작 시각. 당시 시각을 신뢰하지 못하면 null |
| `closed_at` | `String?` | 사건 종료 시각. 미종료 또는 종료 시각 불신이면 null |
| `close_reason` | `CloseReason?` | 관측 이탈 / 관리자 종료. 미종료이면 null |
| `guidance_count` | `Int` | 안내 시도 횟수. 성공 재생 횟수와 다름 |
| `body_type` | `BodyType` | 차체 종류: CAR/BUS/TRUCK/UNKNOWN |
| `use_type` | `UseType` | 용도: TAXI/SCHOOL/DELIVERY/OTHER/UNKNOWN |
| `continuous_stop_ms` | `Long` | 현재 연속 정차 후보 시간(ms) |
| `overdue` | `Boolean` | 미해소 기준시간 도달 여부. 단순 정차 후보 시간과 별도 |

## 3. DeviceSnapshotDto — 장치의 전체 현재 상태

| JSON 필드 | Kotlin 타입 | 의미 |
|---|---|---|
| `current_event_id` | `String?` | snapshot이 나타내는 현재 사건 ID |
| `incident_state` | `IncidentState?` | 현재 사건의 상태 |
| `event_policy_version` | `Long?` | 사건 생성 때 고정한 정책 버전 |
| `guidance_count` | `Int` | 안내 시도 횟수. 성공 재생 횟수와 다름 |
| `continuous_stop_ms` | `Long` | 현재 연속 정차 후보 시간(ms) |
| `body_type` | `BodyType?` | 차체 종류: CAR/BUS/TRUCK/UNKNOWN |
| `use_type` | `UseType?` | 용도: TAXI/SCHOOL/DELIVERY/OTHER/UNKNOWN |
| `camera_health` | `CameraHealth` | 영상 관측 품질 상태 |
| `mode` | `OperatingMode` | 중지 목록으로 결정되는 운영 모드 |
| `output_status` | `OutputStatus` | 재생·중단 등 출력 상태 |
| `reason_codes` | `List<ReasonCode>` | 관측·출력·시각 문제 사유 목록 |
| `pauses` | `List<ActivePauseDto>` | 해제되지 않은 중지/예약 목록. 빈 목록은 [] |
| `received_policy_version` | `Long?` | 장치가 검증하고 저장한 정책 버전 |
| `active_policy_version` | `Long?` | 장치가 현재 적용 중인 정책 버전 |
| `mode_revision` | `Long` | 운영 상태의 변경 순번. 서버 view_revision과 별개 |
| `rearm_required` | `Boolean` | 종료 후 빈 구역 확인·명시 재개가 필요한지 |
| `device_seq` | `Long` | 장치가 새 상태 메시지를 만들 때 증가시키는 순번 |
| `boot_id` | `String` | 장치 프로그램 실행 세대를 구분하는 ID |
| `clock_synced` | `Boolean` | 장치 UTC 시각 신뢰 여부 |
| `observed_at` | `String?` | snapshot 관측 UTC 시각 |
| `overdue` | `Boolean` | 미해소 기준시간 도달 여부. 단순 정차 후보 시간과 별도 |
| `clock_status` | `ClockStatusDto` | 시각 신뢰의 상태와 근거 수치 |

## 4. ClockStatusDto — 시각 신뢰 정보

| JSON 필드 | Kotlin 타입 | 의미 |
|---|---|---|
| `state` | `ClockState` | SYNCED/HOLDOVER/UNTRUSTED |
| `error_bound_ms` | `Long?` | 시계 오차 상한 추정(ms). 근거 없으면 null |
| `last_sync_at` | `String?` | 마지막 외부 시각 동기 기준점 |
| `sync_age_ms` | `Long?` | 그 동기 기준점 이후 단조 경과시간(ms) |

## 5. ActivePauseDto — 해제되지 않은 중지·예약

| JSON 필드 | Kotlin 타입 | 의미 |
|---|---|---|
| `pause_id` | `String` | 중지 기록 ID |
| `source` | `PauseSource` | 중지 원인 |
| `scope` | `Scope` | DEVICE/ZONE/EVENT 적용 범위 |
| `target_event_id` | `String?` | EVENT 범위의 대상 사건. 다른 범위면 null |
| `starts_at` | `String?` | 중지 시작 UTC 시각 |
| `expires_at` | `String?` | 중지 만료 UTC 시각. 무기한이면 null |
| `status` | `ActivePauseStatus` | SCHEDULED/ACTIVE/ACK_REQUIRED. CLEARED는 snapshot에서 제외 |
| `reason` | `String` | 중지 사유 설명 |

## 6. ImageUpdateDto — 사진 상태 변화

| JSON 필드 | Kotlin 타입 | 의미 |
|---|---|---|
| `image_id` | `String` | 사진 ID |
| `image_kind` | `ImageKind` | 사진 용도 |
| `event_id` | `String?` | 이 메시지가 보고하는 사건 ID |
| `captured_at` | `String?` | 사진 촬영 UTC 시각 |
| `status` | `ImageStatus` | PENDING/AVAILABLE/FAILED/EXPIRED |
| `error_code` | `ImageErrorCode?` | 사진 실패·만료 사유. 정상 상태이면 null |

## 값 사이의 핵심 관계

| 관계 | 조건 |
|---|---|
| envelope ↔ snapshot | device_seq, boot_id, clock_synced 일치 |
| envelope ↔ event/image_update | 해당 객체가 있으면 event_id 일치 |
| event ↔ snapshot | 같은 사건이면 공통 요약 값 일치. 서로 다른 사건이면 별도로 해석 |
| 현재 사건 없음 | 사건 관련 ID·상태·버전·차종/용도는 null, 횟수·정차시간은 0, overdue=false |
| 사진 상태 사건 | event_type=IMAGE_STATUS_CHANGED일 때 event=null, image_update 필수 객체. 그 외 image_update=null |
| 시각 불신 | occurred_at/observed_at=null. 과거에 신뢰했던 event.started_at 등을 소급해 지우지 않음 |
| 일반 상태와 예외 | 중지 목록·모드·시각 불신·정차시간 등의 전체 조건은 아래 검증 설계를 따름 |

## Enum 허용값

유형명은 Kotlin 구현용 이름이며 wire 문자열은 아래 대문자 값을 그대로 사용한다.

- `EventType`: `EVENT_STARTED`, `STOP_CONFIRMED`, `GUIDANCE_ATTEMPTED`, `GUIDANCE_PLAYBACK_COMPLETED`, `GUIDANCE_PLAYBACK_STOPPED`, `GUIDANCE_PLAYBACK_FAILED`, `OBSERVATION_HELD`, `OBSERVATION_RECOVERED`, `LONG_UNRESOLVED`, `EVENT_CLOSED_EXIT`, `EVENT_CLOSED_MANUAL`, `PAUSE_CHANGED`, `CONFIG_RECEIVED`, `CONFIG_ACTIVATED`, `DEVICE_RESTARTED`, `IMAGE_STATUS_CHANGED`
- `IncidentState`: `OBSERVING`, `STOP_CONFIRMED`, `GUIDING`, `GUIDED`, `LONG_UNRESOLVED`, `HELD`, `CLOSED_EXIT`, `CLOSED_MANUAL`
- `CameraHealth`: `OK`, `SUSPECT`, `BLOCKED`, `NO_FRAME`, `RECONFIGURE`
- `OperatingMode`: `AUTO`, `PAUSED`, `AWAITING_RESUME`
- `OutputStatus`: `IDLE`, `PLAYING`, `STOPPING`, `FAILED`, `UNKNOWN`
- `BodyType`: `CAR`, `BUS`, `TRUCK`, `UNKNOWN`
- `UseType`: `TAXI`, `SCHOOL`, `DELIVERY`, `OTHER`, `UNKNOWN`
- `ReasonCode`: `FRAME_STALE`, `FRAME_GAP`, `INVALID_FRAME`, `OBSERVATION_MISSING`, `OCCLUSION_SUSPECT`, `IMAGE_DARK`, `IMAGE_BRIGHT`, `IMAGE_BLUR`, `BACKGROUND_UNRELIABLE`, `CAMERA_SHIFT`, `CALIBRATION_REQUIRED`, `IDENTITY_UNCERTAIN`, `MULTIPLE_TARGETS`, `ROI_BOUNDARY`, `VEHICLE_MOVING`, `OUTPUT_FAULT`, `CLOCK_UNTRUSTED`
- `PauseSource`: `USER`, `SCHEDULE`, `PHYSICAL`, `RESTART`, `RECONFIGURE`, `REARM_REQUIRED`, `OUTPUT_FAULT`, `OBSERVATION_REVIEW`
- `Scope`: `DEVICE`, `ZONE`, `EVENT`
- `ImageKind`: `FIRST_GUIDANCE`, `EVENT_CLOSE`, `VIEW`, `RESUME_CONFIRM`, `CLOSE_CONFIRM`
- `ImageStatus`: `PENDING`, `AVAILABLE`, `FAILED`, `EXPIRED`
- `ImageErrorCode`: `QUEUE_FULL`, `IMAGE_STORAGE_FULL`, `NO_VALID_FRAME`, `ENCODE_FAILED`, `IMAGE_TOO_LARGE`, `HTTP_REJECTED`, `IMAGE_ID_CONFLICT`, `RETRY_EXHAUSTED`, `AGE_UNKNOWN`, `RETENTION_EXPIRED`, `RESTART_INCOMPLETE`
- `ClockState`: `SYNCED`, `HOLDOVER`, `UNTRUSTED`
- `CloseReason`: `OBSERVED_EXIT`, `MANUAL` (필드 자체는 nullable)
- `ActivePauseStatus`: `SCHEDULED`, `ACTIVE`, `ACK_REQUIRED`

전체 조건: [사건 수신·저장 전 검증](event-ingest-v1.md). 원본: [parking.schema.json](../../reference/v1.43/개발자료/09_구현설계/contracts/parking.schema.json).
