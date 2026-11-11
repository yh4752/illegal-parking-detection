# API·메시지 계약 안내

공통 계약은 아래 원본 한 곳에서 관리한다. v1.43 재편에서는 원본의 경로와 bytes를 보존했다. 이 폴더는 진입 안내이며 계약을 복사한 별도 원본이 아니다.

| 용도 | 파일 |
|---|---|
| 공통 통신 의미·25개 메시지 예제 | [04 공통 계약](../개발자료/04_공통_통신계약과_메시지예제_v1.4.json) |
| 메시지·객체 필드와 자료형 | [parking.schema.json](../개발자료/09_구현설계/contracts/parking.schema.json) |
| HTTP API의 요청·응답·인증 | [openapi.json](../개발자료/09_구현설계/contracts/openapi.json) |
| 정상·오류 검증 입력 | [examples.json](../개발자료/09_구현설계/contracts/examples.json) |
| Schema 밖의 의미 검사 | [semantic_checks.json](../개발자료/09_구현설계/contracts/semantic_checks.json) |
| 적용 방법·정정·기존 검사 범위 | [contract_notes.txt](../개발자료/09_구현설계/contracts/contract_notes.txt) |
| 검증 실행 | [validate_examples.py](../개발자료/09_구현설계/contracts/validate_examples.py), [requirements.txt](../개발자료/09_구현설계/contracts/requirements.txt) |

입력의 자료형이 맞아도 권한·사건 참조·시각·중복·실제 출력이 옳다고 보장되지 않는다. [D7](../spec/storage/D7.md)·[D8](../spec/storage/D8.md)과 작업에 해당하는 C절을 함께 적용한다. MQTT 수신은 토픽에 맞는 정의를 선택하며 합집합 Schema 통과만으로 다른 종류의 메시지를 수용하지 않는다.

null, 키 생략, 0, false, 빈 배열은 서로 다르다. REST 즉시 STOP의 `starts_at:null`은 서버가 wire 명령의 `issued_at`과 같은 UTC로 변환한다. 공개 `confirmation_token`은 키를 제거하지 않고 null로 유지한다. 기존 잔여 `VALIDATION_ERROR` 설명은 `INVALID_SCHEMA` 정정을 반영해 해석한다. 상세 근거는 `contract_notes.txt`와 D8에 있다.

기존 `validation_report.json`은 생성 당시의 검증 기록이다. 재현 실행과 실제 통합 시험은 [시험 안내](../tests/README.md)를 따른다.
