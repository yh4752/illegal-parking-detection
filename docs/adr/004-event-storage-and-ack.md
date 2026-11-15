# ADR-004 사건 저장과 커밋 후 ACK

2026-11-13 · 채택 · v1.43 및 ADR-003의 저장 단계 구현

## 결정

별도 `mqtt-ingest-local` 프로필에서 사건 저장을 활성화한다. 기존 검증 전용 프로필은 유지한다. 토픽·wire DTO·ACK 필드와 schema_version=2는 바꾸지 않는다. `inbound_quarantine`을 V2로 추가하며 적용된 V1과 보존본은 그대로 둔다.

1. 엄격한 파싱 후 device_id·zone_id·message_id와 토픽 관계를 확인하고, RFC8785 canonical JSON SHA-256을 계산한다. 저장 계층이 등록·활성 상태를 잠금 아래 재검사한다.
2. 기존 receipt를 먼저 조회한다. 확정된 재전송에 새 검증 규칙을 소급하지 않는다. 새 입력만 Schema·V01~V12·D01~D08을 적용한다.
3. 원문·receipt·사건 요약·snapshot·pause projection·view_revision을 하나의 READ COMMITTED 트랜잭션에서 갱신한다. 과거 사건과 현재 snapshot은 각각의 순번으로 갱신 여부를 판단한다.
4. `TransactionTemplate.execute`가 반환된 뒤 응용 ACK를 발행한다. 커밋 실패·결과 불명은 ACK하지 않는다. ACK 전송 실패는 이미 끝난 커밋을 되돌리지 않는다.

입력 상관 ID는 문자열 1~128 Unicode 문자이며 NUL을 허용하지 않는다. 이 세 ID가 없거나 엄격한 JSON/canonical 표현이 불가능하면 ACK를 만들지 않는다. 그 외 Schema 오류는 상관·등록이 확인된 경우 격리 후 REJECTED다. 따라서 기존 문서의 ‘상관 가능’은 이 순서로 구현한다.

canonical 구현에는 [java-json-canonicalization 1.1](https://github.com/erdtman/java-json-canonicalization)을 사용한다. [RFC8785](https://www.rfc-editor.org/rfc/rfc8785)의 숫자·Unicode·키 순서 규칙을 자체 키 정렬로 대체하지 않는다. 지원 정수와 반올림으로 충돌할 수 있는 표현 및 짝 없는 surrogate는 상관 불가로 처리한다. JSONB의 출력 문자열로 hash를 만들지 않는다.

## 오류 원문과 보관

원래 receipt는 수정하지 않는다. 동일 message_id의 다른 내용은 별도 quarantine 행으로 남긴다. 격리 원문은 첫 수신부터 30일 뒤 null로 지우되 ID/hash/rule/최초 기한의 최소 기록을 유지한다. 같은 오류를 다시 받아도 원문과 기한이 되살아나지 않는다. 정상 원문은 30일 후 삭제하고, 종료 요약 삭제 전에 tombstone을 남긴다. 영구 보존되는 최소 메타데이터의 운영 용량·접근 정책은 배포 단계에서 검토한다.

스케줄러는 시작 60초 후, 이후 1시간 간격으로 정리한다. 아직 조회 API가 없으며 이후 조회 구현은 보관 기한이 지난 요약을 배치 삭제 전에도 노출하지 않아야 한다. zone_view의 마지막 snapshot은 새 보고 전까지 과거 관측임을 유지하며 온라인·현재 관측으로 바꾸지 않는다.

## 번호 예약과 복구 경계

DB 제약을 완화하지 않고 100만 개 revision 범위를 파일에 먼저 내구 저장한다. temp 쓰기·fsync·rename·디렉터리 fsync 후 DB의 범위를 반영한다. 파일 I/O 중 DB 잠금을 잡지 않는다. 단일 프로세스 gate와 파일 lock을 사용한다. 일반 재시작은 같은 범위를 재사용한다.

파일 또는 DB 범위가 불일치하면 중단한다. 예약 직후 DB 반영 전 종료도 자동으로 추정 복구하지 않는다. D6의 운영자 복구 절차와 새 범위 건너뛰기는 후속 작업이다. 동일 범위 안의 과거 DB 복원을 자동 탐지한다고 보장하지 않는다. 기본 복사본은 같은 디스크에 있으므로 호스트 전체 유실을 견디지 못한다. `recovery_hold`는 임의로 해제하지 않는다.

## 범위와 검증

사진 메타데이터 저장이 없는 `IMAGE_STATUS_CHANGED`에는 STORED를 보내지 않는다. heartbeat·명령 결과·사진 업로드·API·운영 인증은 이번 범위 밖이다. 고정 client ID를 공유하는 기존 검증 서버와 새 수신 서버를 동시에 실행하지 않는다.

새 백엔드 서버나 네트워크 경계는 추가하지 않는다. [C4 L3](../diagrams/03-components.svg)에 실제 저장 컴포넌트와 DB 밖 예약 파일을 표시했다. [실행 안내](../event-storage.md)와 [시험 기록](../../validation/event-storage-2026-11-13.json)에 완료 범위와 실제 브로커 미확인 범위를 분리했다.
