# 장치 메시지 기능의 위치

현재 코드 없음. 첫 수신 기능을 만들 때 `adapter/mqtt`, `application`, `domain`, `adapter/jdbc`를 실제 코드와 함께 생성한다.

- MQTT 어댑터는 토픽·전송 형식을 다루고 업무 처리를 호출한다.
- application은 검증·중복·순서·트랜잭션을 조정한다.
- domain은 Spring에 의존하지 않는 동일성·순서 규칙을 담는다. 차량의 정차 여부를 다시 판단하지 않는다.
- JDBC 어댑터는 SQL·원자 저장·구역 잠금을 수행한다. 통신 I/O를 DB 잠금 안에 넣지 않는다.
- 응용 ACK는 DB 커밋 후 보낸다. PUBACK와 같은 것으로 취급하지 않는다.

Schema·의미 검사·D5를 함께 확인한다. 의미 없는 공통 BaseService나 추상 Repository는 미리 만들지 않는다.
