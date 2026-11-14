# 현재 구현 범위

2026-11-14 · 이 파일이 현재 개발 저장소의 현황입니다. reference/v1.43의 상태표는 설계 작성 당시 기록입니다.

개인 비공개 저장소 [yh4752/parking-system](https://github.com/yh4752/parking-system)에 코드·설계·협업 지침을 올렸습니다. 팀원 초대와 브랜치 보호는 별도 설정입니다.

| 항목 | 작성 여부 | 실행 확인 |
|---|---|---|
| README·협업 절차·AI 지침·C4·첫 작업 안내 | 작성·독립 검토 반영 | 새 클론에서 문서 생성·링크·SVG·원본 보존 검사 통과 |
| Spring Boot 엔트리포인트·설정 | 작성 | GitHub CI의 test·bootJar 통과. 사용자가 11월 13일 로컬 BUILD SUCCESSFUL 및 브라우저 UP 응답을 공유. 아래 CI 기록은 이전 골격 기준 |
| 기동 확인만 공개하는 접근 제한 | 작성 | 애플리케이션 컨텍스트를 사용하는 MockMvc 테스트 통과: health 공개, 제품·관리 상세 경로 차단 |
| Gradle Wrapper·버전 고정 | 작성 | Kotlin 2.1.21·Gradle 8.12.1로 수정 후 CI 다운로드·컴파일·패키징 통과 |
| PostgreSQL·Mosquitto Compose | 작성 | 사용자가 Mosquitto Up 및 CLI 송수신·토픽 필터 실습 결과를 공유. PostgreSQL 17.11 기동·연결·최초 V1 적용을 11:41 사용자 로그로 확인 |
| PR 양식·GitHub Actions | 작성 | 개인 저장소 생성·push 완료. 문서/설정·백엔드 두 작업 통과 |
| 학습용 MQTT 수신 로그 | 코드·기본 프로필 격리 테스트 작성 | 사용자가 11월 13일 09:57 READY 및 10:01 RECEIVED(topic=learning/hello, payload=hello spring) 로그 공유. 기동·정상 수신 및 다른 토픽 미수신을 사용자 실습으로 확인. 부하·전달 보장 시험은 미수행 |
| 학습용 JSON 파싱 | 코드·단위 테스트 작성 | 사용자가 10:37 정상(seq=10) → 문법 오류 → 정상(seq=11) 로그 공유. 같은 서버 프로세스에서 처리 지속 확인. 빌드·READY 성공은 사용자 보고이며 단위 테스트는 이후 로컬 보고서에서 통과 확인 |
| 학습용 세 필드 유효성 검증 | 코드·경계/오류 테스트 작성 | 사용자가 10:47 음수 순번 out_of_range, 10:53 문자열 순번 integer_required, 정상 seq=11 VALIDATED 로그 공유. 학습 단위 테스트 27개 통과 확인 |
| 제품 DB 연결·v1.43 초기 마이그레이션 | JDBC·Flyway·database 프로필·PostgreSQL 검사·CI 단계 작성 | 11:41 최초 V1 적용, 11:48 사용자의 전체 빌드 성공 및 DB 검사 6개 통과. 11:58 에이전트 실행에서 등록 조회 포함 DB 검사 7개 통과 |
| 정식 EventMessage 검증·등록 조회·로컬 MQTT 수신 연결 | V01~V12 구현 | 최신 일반 테스트 122개 통과, bootJar 생성 확인. DB 테스트 7개는 이전 실행 결과. 13:37~13:40 사용자 실습에서 실제 브로커 정상→모순 거부→정상 처리 확인. 15:53 E11 거부 → 15:57 E09 통과를 같은 PID에서 확인. 추가 의미 검사의 실제 브로커 확인 범위는 이 두 사례 |
| 사건 저장·중복/역순 처리·커밋 후 응용 ACK | mqtt-ingest-local·V2·revision 예약·보관 정리·정책 fixture 구현 | 일반 126개·실제 PostgreSQL 30개 전체 통과. 커밋 실패·ACK 발행 예외·재전송 확인. 16:51 사용자 로그로 정상 1건의 실제 Mosquitto 왕복 확인. 16:54 동일 메시지의 DUPLICATE 응답 수신 확인. SQL로 해당 원문 1건 확인. 충돌 거부 ACK 수신 확인. 충돌 후 SQL에서 원문 seq=10·receipt STORED·격리 오류 1행 확인. 역순 12→11의 STORED 수신 및 SQL에서 이력 2행·사건/구역 seq=12 유지 확인. 실패 경로의 실제 브로커 시험은 남음 |
| 공개 데모 기반 M0 | REST 유효 계약·V3/V4·신규 초기화 CLI 구현 | GitHub 전체 CI·계약 보호 시험 6개·별도 CLI 프로세스 5개 사례 통과. [범위와 실행 안내](demo-foundation.md) |
| 제품 로그인·조회 API | M1 구현: 세션·CSRF·구역 권한·7개 경로·계정 CLI | 실제 PostgreSQL·HTTP·CLI 시험. [실행 안내](authenticated-read.md) |
| 사건 예제 출력 도구 | tools/event_fixture.py 작성 | 원본/오류 변형, --design-case E01~E26 출력이 설계 사례와 일치함을 확인. 장치 SDK·재전송 시뮬레이터는 아직 아님 |
| React·SSE·명령·사진 파일 | 미구현 | 다음 M2부터 진행 |
| 장치 SDK·실차 시험 | 미구현/미수행 | 언어·장소·카메라 등 확인 필요 |

## 확인 근거

- [11월 13일 JSON 파싱 실습 기록](mqtt-learning.md#실습-결과): 사용자가 공유한 실제 서버 로그에 근거.

- [성공한 CI](https://github.com/yh4752/parking-system/actions/runs/42995478170): 커밋 `b22f3b88fb92f2be6aba02c93b010ac9aae1a1dd`, JDK 17, `./gradlew test bootJar --no-daemon`와 문서·Compose 검사.
- [이번 검증 기록](../validation/onboarding-2026-11-11.json): 새 클론 재현, 독립 검토, 첫 CI 실패 원인과 수정, 미검증 범위.
- [첫 CI 실패와 버전 수정 이유](adr/001-start-small.md): Kotlin 컴파일러·라이브러리 메타데이터 충돌을 수정했다. 이전 validation/report.json·bootstrap-v0.1.json은 당시 기록으로 보존한다.

기동 테스트 통과는 제품 기능 완성을 뜻하지 않습니다. [첫 기능 작업표](first-slice.md)의 수신→저장 다음으로, [웹 인계 계획](public-demo.md)에 따라 로그인·조회·실시간 화면·외부 배포를 먼저 구현하고 완성된 코드로 학습합니다. M0/M1 소스 구현·시험 결과는 아래 최신 기록을 봅니다.

11월 13일 추가 코드의 로컬 검증 시도는 에이전트 환경의 Gradle 캐시·Docker 소켓 접근 제한으로 실행되지 않았다. Spring 수신 성공은 이후 사용자가 공유한 READY·RECEIVED 로그를 근거로 기록했다. 이전 CI 통과를 추가 코드 전체의 테스트 통과로 간주하지 않는다.

제품 DB 단계 검증: `npm --prefix tools/docs run build`, `python3 tools/check_structure.py`(원본 127개 보존·링크 포함), `git diff --check` 통과. `./gradlew test databaseTest bootJar --no-daemon`은 Gradle 배포 캐시의 `.zip.lck` 파일 접근 오류로 테스트 시작 전에 중단됐다. CI에는 실제 PostgreSQL 검사 단계를 추가했으나 이번 변경의 CI 실행 결과는 아직 없다. 이후 사용자가 11:48 실행 성공을 공유했고 XML에서 일반 31개·DB 6개 통과를 확인했다. 이 문단의 접근 오류는 이전 시도 기록이다.

정식 사건 검증 단계: 11:58 에이전트가 격리된 Gradle 캐시·임시 의존성 저장소·JVM 시작 시 Mockito agent를 사용하여 `test databaseTest bootJar` 실행에 성공했다. 일반 65개·DB 7개 모두 실패/오류/건너뜀 0이다. [환경·시험 기록](../validation/event-validation-2026-11-13.json)에 표준 실행과 다른 임시 설정을 남겼다. 프로젝트의 기본 실행 명령은 바꾸지 않았다. 이후 [정식 사건 발행](event-validation.md) 사용자 실습에서 로컬 브로커 수신을 확인했다. 실제 보드·저장 완료 시험은 아니며 아래 저장 단계 기록과 구분한다.

저장 전 설계 확정 당시: [검증 기준](design/event-ingest-v1.md)·ADR-003·26개 단일 메시지 예제와 12개 DB 인수 시나리오를 확정했다. 당시에는 26개 예제의 JSON 경로와 원본 Schema 예상 결과만 확인했으며 추가 의미 검사·DB 시나리오는 실행 전이었다. 아래는 그 후 진행한 구현 결과다.

추가 의미 검사 단계: `EventMessageSemantics`에 V04~V12를 구현하고 기존 수신 검증기에 연결했다. 14:48 실행에서 일반 테스트 122개가 실패·오류·건너뜀 없이 통과했다. 의미 검사 56개는 보존된 설계 예제 26개와 추가 경계·정상 반례를 포함한다. MQTT 핸들러에서는 규칙 ID 진단과 오류 뒤 정상 처리 지속을 확인했다. [실행 기록](../validation/event-semantics-2026-11-13.json)에 검사 범위와 미수행 항목을 기록했다. 이후 사용자가 공유한 로그에서 15:48:49 READY, 15:53:45 E11의 V04 거부, 15:57:52 E09 통과를 같은 PID 38291에서 확인했다. 실제 로컬 브로커를 통한 두 사례와 오류 이후 처리 지속을 확인한 것이며, 모든 의미 규칙의 브로커 시험을 뜻하지 않는다. DB 업무 처리, 응용 ACK, 실보드 시험은 아직 수행하지 않았다. DB 구조와 등록 조회 코드는 변경하지 않아 databaseTest는 재실행하지 않았다.

## 사건 저장·응용 ACK 단계

`mqtt-ingest-local`에서 D01~D08과 정책·사건 소유권·중복·역순·종료 보호를 구현했다. 원문·receipt·사건·snapshot·pause·view_revision은 함께 커밋하고, 이후 응용 ACK를 발행한다. V2 quarantine과 30일 원문 정리, tombstone, revision 범위의 정상 예약·재시작 및 불일치 중단도 포함했다. [실행 안내](event-storage.md), [ADR-004](adr/004-event-storage-and-ack.md), [시험 기록](../validation/event-storage-2026-11-13.json)을 함께 본다.

최종 실행: 일반 테스트 126개·실제 PostgreSQL 테스트 30개가 실패·오류·건너뜀 없이 통과했고 bootJar를 생성했다. DB 30개는 기존 마이그레이션·등록 7개와 새 저장 시험 23개다. 새로운 `it_...` 스키마에서 실제 커밋을 사용하고 시험 후 해당 스키마만 삭제했다. ACK는 발행 함수를 대체해 커밋 뒤 호출·전송 예외를 검증했다. 자동 시험 당시 Docker 소켓 접근 제한으로 별도 브로커를 기동하지 못했다. 이후 사용자 실습의 정상 1건 왕복 결과는 아래 별도 기록을 따른다. 사용자가 실행 중인 서버와 public 데이터를 변경하지 않았다.

사진 상태 보고는 부수효과를 구현하기 전까지 ACK하지 않는다. heartbeat·명령·API·실보드·부하·배포 검증은 남아 있다. 복원 파일 불일치는 중단하지만 D6의 운영자 인수·새 범위 건너뛰기는 미구현이며 같은 범위 안의 과거 DB 복원을 자동 감지하지 못한다. 16:29 자동 검증을 마친 당시에는 로컬 작업 트리에서 확인했으며, 해당 검증 단계에서는 GitHub push를 수행하지 않았다. 이후 커밋·공유 상태는 Git 이력과 PR을 기준으로 확인한다.

### 사용자 실습: 정상 사건 저장·ACK 수신

2026-11-13, PID 48082의 16:40:28.828 KST EVENT_INGEST_READY 후, 16:51:42.637 EVENT_COMMITTED(message_id=storage-001, status=STORED, error_code=null), 16:51:42.642 EVENT_ACK_PUBLISHED(ack_sent=true)를 확인했다. 별도 mosquitto_sub에서 parking/v2/devices/edge-01/acks의 동일 message_id·STORED·committed_at=2026-11-13T07:51:42.638607Z를 수신했다. 근거는 사용자가 공유한 서버·구독자 로그다. 정상 1건의 수신·커밋·ACK 발행·구독자 수신을 확인했으며, SQL 직접 조회·중복·충돌·장애의 실제 브로커 시험까지 확인한 것은 아니다. 이 시점에는 중복 응답 확인 전이었다. 이후 결과는 아래 기록을 따른다.

사용자 중복 재전송 결과: 같은 ACK 토픽에서 storage-001 / DUPLICATE / error_code=null / committed_at=2026-11-13T07:54:14.508581Z(한국 시각 16:54:14.508581) 응답을 확인했다. 별도 재전송 서버 로그와 SQL 조회 결과는 아직 제공되지 않았다. 이번 증거는 실제 브로커를 통한 중복 응답 수신이며, public DB의 저장 행 수는 다음 SQL 직접 조회로 확인한다.

사용자 SQL 확인 결과: event_message에서 device_id=edge-01, message_id=storage-001을 조건으로 COUNT(*)를 조회하여 stored_rows=1을 확인했다. 최초 STORED와 동일 메시지 재전송 DUPLICATE 이후에도 해당 원문이 1건임을 사용자 public DB에서 검증했다. 다른 테이블의 행 수·충돌 원문 격리·실제 브로커 장애 경로를 이 결과로 확대 해석하지 않는다. 다음 실습은 같은 ID에 다른 내용을 보낸 MESSAGE_ID_CONFLICT다.

사용자 충돌 재전송 결과: storage-001을 유지하고 device_seq를 10에서 11로 바꾼 입력에 대해 REJECTED / MESSAGE_ID_CONFLICT / committed_at=2026-11-13T07:57:46.952358Z(한국 시각 16:57:46.952358)를 구독자 로그로 확인했다. 원래 원문의 seq=10 및 기존 receipt의 STORED 보존, inbound_quarantine의 충돌 행은 다음 SQL 조회에서 별도로 확인한다. 현재 증거는 충돌 거부 ACK 수신이다.

사용자 충돌 후 SQL 확인: edge-01/storage-001에 대해 event_message.device_seq=10(1행), inbound_receipt.outcome=STORED(1행), inbound_quarantine.error_code=MESSAGE_ID_CONFLICT(1행)를 확인했다. 정상·중복·내용 충돌의 실제 MQTT 응답과 해당 SQL 검증을 완료했다. 전체 원문/hash를 조회한 것은 아니다. 다음 실습은 새 ID로 seq 12를 먼저, seq 11을 나중에 수신하여 원문 저장과 최신 상태 갱신을 분리하는지 확인한다.

사용자 역순 수신 로그: PID 48082에서 storage-012(seq 12)는 17:02:32.185 KST 커밋 후 .186 ACK 발행, storage-011(seq 11)은 17:02:38.914 커밋 후 .916 ACK 발행을 확인했다. 두 응답은 모두 STORED/error_code=null이며 구독자도 각각 수신했다(ACK committed_at: 08:02:32.185887Z, 08:02:38.915496Z). 이는 역순 두 메시지의 커밋·응답 확인이다. event_message의 두 원문과 incident·zone_view의 last_device_seq=12 유지는 다음 SQL 조회로 확인한다.

사용자 역순 후 SQL 확인: event_message에서 storage-012/12와 storage-011/11 두 행, incident의 evt-001/last_device_seq=12, zone_view의 zone-01/last_device_seq=12를 확인했다. 정상·중복·내용 충돌·역순의 실제 MQTT/SQL 실습을 완료했다. 다음 구현은 기존 OpenAPI/ZoneView에 맞는 GET /api/zones/{id}/status와 명시적 로컬 조회 접근 제어다. SQL 조회 성공을 HTTP API 구현 완료로 취급하지 않는다. 실제 브로커 장애·재시작·보드·부하 검증은 별도로 남아 있다.

## 공개 조회 데모 M0 — 2026-11-14

[구현 PR #1](https://github.com/yh4752/parking-system/pull/1)은 `feat/mqtt-learning` 기준의 별도 브랜치입니다. 기존 MQTT 원본·성공 DTO·V1/V2를 보존하고, REST 전용 유효 계약과 V3/V4, 신규 단일 구역 데모 초기화 CLI를 추가했습니다. 초기화는 기본 검사 모드이며, 명시 적용 시 외부 manifest claim을 먼저 저장한 뒤 hold와 audit를 커밋합니다. 기존 실습 DB에는 적용하지 않았습니다.

코드 커밋 `db939572`의 [GitHub CI](https://github.com/yh4752/parking-system/actions/runs/43568432860)에서 문서·계약·Compose 검사와 `test databaseTest bootJar`가 모두 통과했습니다. 로컬 최종 bootJar 생성도 성공했고, 그 산출물을 사용한 별도 CLI 프로세스 5개 사례를 PostgreSQL 17.11에서 확인했습니다. 미마이그레이션 DB 거부, 검사 모드 무변경, 신규 초기화, 재적용 거부, 최신 manifest를 유지한 초기 DB 복원 뒤 claim 재사용 거부입니다. 오류를 기대한 사례의 종료 코드 1은 성공적인 거부 결과입니다.

[시험 기록](../validation/demo-foundation-2026-11-14.json)에 환경·실패한 초기 시도·검증 한계를 남겼습니다. 원본 127개 보존과 계약 보호 시험 6개도 통과했습니다. 로컬 전체 테스트가 통과했다고 보고하는 것은 아니며 전체 회귀의 근거는 위 GitHub CI입니다. 이전 Mac 설계 커밋 `052fa95`의 문서 병합은 남아 있습니다.

다음 작업은 **M1 로그인·세션·CSRF·구역 권한·조회 API**입니다. M0의 권한 테이블 추가는 HTTP 권한 검사 구현 완료를 뜻하지 않습니다. heartbeat 수신·SSE·React·실제 배포는 후속 단계입니다.

2026-11-14 Mac 작업 재개: 로컬 설계 커밋 `052fa95`와 원격 M0 `7ec0792`를 `feat/authenticated-read-api`에서 통합했다. 아래 후속 작업은 M1이며, 과거 기록의 “설계 병합 필요”는 당시 상황이다. 기존 사용 DB를 초기화하지 않는다.


## 인증·조회 M1 — 2026-11-14

**로그인·세션·CSRF·구역 권한과 조회 API 7개를 구현했습니다.** 상태·목록·상세는 실제 저장 자료와 유효 계약을 사용합니다. 계정/권한 CLI, 만료 자료 제외, 확인 토큰 마스킹, 서명 cursor, 복구 gate 및 오류 응답을 포함합니다. [실행 안내](authenticated-read.md)와 [ADR-007](adr/007-authenticated-read-api.md)을 함께 봅니다.

Mac에서 일반 테스트 **140개**, 실제 PostgreSQL 테스트 **64개**, 계약 보호 시험 **6개**가 실패·오류·건너뜀 없이 통과했고 bootJar를 생성했습니다. 신규 시험은 일반 10개·DB 18개이며 DB 18개에 웹 통합 17개와 파일 조회 gate 1개가 포함됩니다. 실제 HTTP 쿠키·세션 교체와 별도 계정 CLI 프로세스 4회도 DB 시험 안에서 검증했습니다. 테스트용 스키마·파일만 생성/정리했으며 기존 사용자 public 데이터는 변경하지 않았습니다.

[검증 기록](../validation/authenticated-read-2026-11-14.json)에 실행 환경·실패 수정·코드 해시·범위를 남겼습니다. 현재 계정·세션은 단일 서버용입니다. 공개 HTTPS·실제 MQTT 장애·브라우저·부하·실보드 시험을 완료했다는 뜻은 아닙니다. heartbeat와 SSE는 **다음 M2**, React는 M3입니다. 전체 인계판은 아직 완성되지 않았습니다.
