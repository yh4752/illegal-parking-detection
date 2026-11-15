# 가상 장치

AI 추론 없이 v1.43 상태를 재생하는 Python 도구입니다. 실제 보드 성능이나 실제 5분 정차를 시험하지 않습니다. 첫 사건은 5분 4초 안내 완료 보고를 재생하고 5초마다 heartbeat를 보냅니다.

가장 간단한 실행은 저장소 최상위에서 `python3 tools/handoff.py simulate`입니다. 준비·중지·UI 확인은 [인계 설명서](../docs/frontend-handoff.md)를 봅니다.

| 동작 | 보장/범위 |
|---|---|
| events | MQTT 5, QoS 1, retain=false. ACK 구독 완료 후 발행 |
| 재전송 | SQLite WAL/FULL에 원문·ID를 먼저 저장. ACK가 없으면 5초 후 같은 메시지 재발행 |
| 저장 응답 | 원본 Ack Schema 검사. STORED/DUPLICATE만 삭제, REJECTED는 별도 상태로 보존 |
| 순번 | heartbeat/events가 같은 SQLite 영속 counter 사용. 재시작해도 증가 |
| heartbeat | 5초, QoS 0, retain=false. 저장 확인 ACK 대상 아님 |
| 단절 | SQLite pending은 유지. 재연결 후 ACK 구독과 재전송 |

직접 실행하려면 Python 3.10 이상과 `requirements.txt`를 설치합니다. 기본 주소는 localhost:1883이므로 실제 브로커 주소/포트를 명시하세요. 로컬 인계 Docker 브로커는 호스트에 공개되지 않습니다.

```sh
python3 -m venv .venv
.venv/bin/pip install -r simulator/requirements.txt
.venv/bin/python simulator/device.py --host 127.0.0.1 --port 11883 --state .local-state/separate-sim.sqlite --scenario guided
```

위 직접 실행 예시는 기존 실습 브로커 11883입니다. 해당 환경의 등록·정책·순번과 충돌하지 않는지 먼저 확인하세요. 팀 프론트 인계에는 격리된 `tools/handoff.py`를 권장합니다. 지원 시나리오는 guided/held/exit이며 실물 SDK의 상태 기계·명령·사진 처리는 구현하지 않습니다.

시험: `python -m unittest discover -s simulator -v` (가상환경 의존성 필요).
