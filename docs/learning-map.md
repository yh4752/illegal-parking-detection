# 구현을 읽는 순서

제품 판단의 기준은 v1.43, 현재 인계 범위는 [프론트 설명서](frontend-handoff.md)입니다. 코드를 처음부터 전부 읽기보다 메시지 한 건을 따라갑니다.

| 순서 | 볼 파일 | 확인할 질문 |
|---|---|---|
| 1 | `simulator/device.py` | 사건을 보내기 전에 어디에 기록하고, 언제 지우는가? |
| 2 | `configuration/EventIngestionConfiguration.kt` | 브로커 주소·구독 topic·QoS가 어떻게 연결되는가? |
| 3 | `device/adapter/mqtt/EventIngestionMessageHandler.kt` | 브로커 ACK와 저장 완료 ACK가 어떻게 다른가? |
| 4 | `device/application/EventMessageValidator.kt`, `EventMessageSemantics.kt` | JSON이 맞아도 거부하는 모순은 무엇인가? |
| 5 | `device/adapter/jdbc/EventTransactionStore.kt` | 중복·충돌·역순을 어떻게 다르게 저장하는가? |
| 6 | `zone/adapter/jdbc/JdbcReadStore.kt` | 읽기 권한·현재 상태·사건 이력을 어떻게 조회하는가? |
| 7 | `zone/adapter/sse/ZoneStreams.kt`, `NonBlockingSse.kt` | 커밋된 상태만 전달하고 느린 연결을 제한하는 방법은? |
| 8 | `frontend/src/api/client.ts` | 로그인/로그아웃의 CSRF·쿠키·오류 처리는 어디 있는가? |
| 9 | `frontend/src/features/zones/zoneStream.ts` | REST보다 SSE가 먼저 도착해도 어떻게 최신값을 유지하는가? |
| 10 | `frontend/src/features/zones/ZonePage.tsx` | 실제 보고와 마지막 관측·연결 지연을 어떻게 표시하는가? |

2~7의 경로는 `backend/src/main/kotlin/kr/ac/sejong/parking/` 아래입니다. 비-MQTT 부분은 익숙한 Spring HTTP/JDBC와 React이며, 우선 저장 ACK와 순번·revision 차이를 이해하면 전체 흐름을 따라가기 쉽습니다.

블로그에는 ‘MQTT 연결 성공’과 ‘DB 저장 성공’을 구분해 기록합니다. 테스트 로그·코드·설계 이유를 근거로 쓰되 계정/비밀번호·세션·실제 영상은 제외합니다. 실제 보드·공개 배포·실차 성능을 아직 확인하지 않은 결과로 표현하지 않습니다.

운영 학습은 [O1 개념·이해 확인 질문](production-basics.md)에서 시작합니다. TLS·계정·ACL·DB 역할을 구분한 뒤 설정 파일을 읽습니다.

HTTPS 운영 경로는 [로컬 HTTPS 실습](local-https.md)으로 이어갑니다. 브라우저 → Nginx → Spring → DB를 먼저 따라가고, 가상 장치 → TLS MQTT → DB 커밋 → SSE 경로와 연결해 봅니다. 인증서 검증과 사용자 로그인, 화면 연결과 장치 생존을 구별하는 것이 이번 단계의 목표입니다.

[모니터링 학습](local-monitoring.md)에서 수집·저장·그래프의 역할과 커밋/ACK 차이를 익힌다.

O3 다음에는 [O4 백업·복원](local-backup.md)을 읽습니다. DB 백업과 독립 기록의 역할을 구분하고, 복구 보류 → 최신 장치 대조 → 명시 인수 순서로 실습합니다.

O4 다음에는 [O5 배포 준비](deployment-readiness.md)를 읽습니다. 서버 크기·월 비용의 계산, 도메인과 DNS의 차이, 백업/최신 기록/키의 보관 책임을 이해한 뒤 비밀값 없는 결정표를 채웁니다. 아직 외부에 배포하지 않습니다.

단계별 자기 설명 질문과 블로그에 인용할 수 있는 로그·캡처·수치는 [운영 학습·증거 모음](operations-learning-evidence.md)에 있습니다. 수치의 측정 범위와 미검증 사항을 함께 읽습니다.
