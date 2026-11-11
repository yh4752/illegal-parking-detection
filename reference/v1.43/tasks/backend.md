# 백엔드 담당 작업 안내

백엔드는 공통 계약, 장치용 저장·통신 SDK, PC 가상 장치, Mosquitto 설정·운영, Kotlin/Spring Boot 서버, DB·REST/SSE·권한·사진 API와 배포·모니터링·연동 인수를 맡는다. MQTT 브로커 자체를 새로 개발하지 않는다. 모델·상태 판단·GPIO·카메라·실물 출력은 AI 담당의 책임이다. [B1](../spec/roles/B1.md), [보드 없이 개발하는 범위 B4](../spec/roles/B4.md)

## 먼저 진행할 작업

W00: 공통 계약과 예제를 검증하고 정상 사건·중복·역순·명령·사진·시각·초기 설정을 같은 의미로 해석할 수 있게 한다. 첫 구현 단위는 격리 DB에 가상 사건을 저장하고 REST/SSE로 보여 주는 경로다.

먼저 읽을 자료: [계약 안내](../contracts/README.md), [C1](../spec/protocol/C1.md), [C2](../spec/protocol/C2.md), [C7](../spec/protocol/C7.md), [C8](../spec/protocol/C8.md), [D4](../spec/storage/D4.md), [D5](../spec/storage/D5.md).

보드 언어·ABI가 미정이어도 계약 검증·서버·DB·가상 입력 작업은 진행할 수 있다. 실제 SDK 언어와 바인딩은 W01의 지원표를 받은 뒤 AI 담당과 정한다. 서버가 Kotlin이라는 이유로 보드 SDK 언어를 Kotlin으로 정하지 않는다.

## 입력과 전달물

| 받는 것 | 만드는 것 | 전달 대상 |
|---|---|---|
| 공통 계약·기대 상태·시험 시나리오 | 계약 검증과 공통 메시지 예제 | AI·프론트·Data |
| AI의 보드 환경·내부 경계 입력 | 저장·통신 SDK, PC 모의 어댑터, 가상 장치, 설치 예제 | AI 담당 |
| 장치 사건·상태·명령 결과·사진 | DB 이력, 저장 ACK, 현재 ZoneView, 조회 API·SSE | 장치·프론트 |
| 인증된 관리자 요청 | 명령·정책 저장과 전달, 실제 결과 반영 | 장치·프론트 |
| 각 구성 요소 로그 | 배포·권한·복구·모니터링과 통합 인수 기록 | 전원 |

## 구현 순서와 필요한 명세

| 단계 | 구현·확인할 내용 | 참조 |
|---|---|---|
| 1. 계약 | 예제·Schema·OpenAPI와 의미 조건 확인 | [contracts](../contracts/README.md), W00 |
| 2. 저장과 조회 | 격리 DB 적용, 가상 사건 수신·중복·역순·ACK·REST/SSE | [db](../db/README.md), [C14](../spec/protocol/C14.md), [C15](../spec/protocol/C15.md), W05·W09 |
| 3. 명령과 권한 | 접수·outbox·결과·중지·재개·확인 종료·정책·세션 | [C4](../spec/protocol/C4.md), [C5](../spec/protocol/C5.md), [C6](../spec/protocol/C6.md), [C13](../spec/protocol/C13.md), [D8](../spec/storage/D8.md), W06·W08 |
| 4. SDK와 장애 | 단일 SQLite writer, 재전송·TTL·비동기 큐·PC 장애 주입 | [edge_sdk](../edge_sdk/README.md), [D1](../spec/storage/D1.md)·[D2](../spec/storage/D2.md)·[D3](../spec/storage/D3.md), W13 |
| 5. 사진·보관·복원 | 사진 별도 업로드, 파일/DB 복구, 보관 만료, DB 복원 gate | [C9](../spec/protocol/C9.md), [C11](../spec/protocol/C11.md), [D6](../spec/storage/D6.md) |
| 6. 배포·통합 | TLS·프록시·영속 볼륨·관찰·실보드 대조 | [C16](../spec/protocol/C16.md), [C17](../spec/protocol/C17.md), [운영 인수](../spec/plan/10.md), W09 |

이 순서는 작업을 시작하는 안내다. W13의 언어 독립 설계·PC 시험 준비와 운영 환경 준비는 앞 단계와 병행할 수 있다. 각 완료 조건과 의존관계는 [기존 작업 목록](../개발자료/05_개발작업과_시험목록_v1.4.json)을 따른다.

## 완료 확인

- TC01·TC09~TC20 중 해당 작업의 기대값을 확인한다. API 응답이 나오는 것만으로 중복·순서·권한·물리 적용을 통과했다고 보지 않는다.
- PC 가상 장치에서 BC01~BC05를 각각 기록하고, 실제 보드 인수 BC06은 AI 담당이 실행한 증거와 서버 수신 결과를 대조한다.
- OP01~OP05의 저장 실패·단절·SSE·배포·복원과 D5·D6의 DB 동시성·사진 중단 시험을 완료한다.
- HTTP 202, PUBACK, 응용 ACK, APPLIED와 DELIVERY_TIMEOUT의 의미를 유지한다. 60초 기한과 5초 결과 유예가 지나도 적용 실패로 단정하지 않는다.
- 명령과 outbox·request_receipt는 함께 저장하고, DB 커밋 뒤 ACK·SSE·접수 응답을 보낸다. 네트워크·파일 작업 동안 긴 행 잠금을 유지하지 않는다.

정확한 시험·반복 수·조건은 [시험 안내](../tests/README.md)와 원본 목록을 따른다.

## 팀원에게 넘길 결과

실행 방법, 설정 예제와 필요한 비밀값 항목, API·메시지 버전, SDK 함수 입력/출력, 가상 입력, 기대 상태, 실제 시험 로그와 미수행 범위, AI·프론트가 다음에 연결할 절차를 전달한다. 실제 계정 비밀번호나 개인키는 자료에 포함하지 않는다.
