# 장치 메시지 기능의 위치

현재 검증기와 `EventIngestionService`, `EventTransactionStore`, `RevisionReservations`, `EventIngestionMessageHandler`가 있다. 형식·의미·등록 검사에 이어 정책·소유권·중복·순서·종료 보호와 커밋 후 ACK를 구현했다. 실제 PostgreSQL 시험은 완료했고 사용자 실습에서 정상 1건의 실제 브로커 왕복을 확인했다. 동일 메시지의 DUPLICATE 응답도 실제 구독자 로그로 확인했다. SQL 직접 조회로 해당 원문 1건을 확인했다. 실행·지원 범위는 저장소의 `docs/event-storage.md`를 따른다.

- MQTT 어댑터는 토픽·전송 형식을 다루고 업무 처리를 호출한다.
- application은 검증·중복·순서·트랜잭션을 조정한다.
- 동일성 계산은 application의 EventCanonicalizer, 기존 DB 상태와 비교하는 순서 규칙은 EventTransactionStore에 있다. 빈 domain 계층은 만들지 않는다. 차량의 정차 여부를 다시 판단하지 않는다.
- JDBC 어댑터는 SQL·원자 저장·구역 잠금을 수행한다. 통신 I/O를 DB 잠금 안에 넣지 않는다.
- 응용 ACK는 DB 커밋 후 보낸다. PUBACK와 같은 것으로 취급하지 않는다.

Schema·의미 검사·D5를 함께 확인한다. 의미 없는 공통 BaseService나 추상 Repository는 미리 만들지 않는다.
