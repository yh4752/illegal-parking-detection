# 로컬 실행 환경

이 설정은 PostgreSQL과 Mosquitto를 준비한다. 백엔드는 `mqtt-learning` 프로필에서 학습용 MQTT에, `database` 프로필에서 PostgreSQL에 연결하도록 구성했다. [DB 실행 안내](../docs/database.md)에서 서버에 비밀번호를 전달하는 방법을 확인한다. 실제 검증 범위는 [현재 상태](../docs/status.md)를 따른다.

```sh
cd infra
cp .env.example .env
# .env의 예시 비밀번호를 로컬용 값으로 수정
docker compose config --quiet
docker compose up -d
docker compose ps
```

| 대상 | 호스트의 백엔드/시뮬레이터에서 접근 | Compose 내부 |
|---|---|---|
| PostgreSQL | localhost:15432, DB parking | postgres:5432 |
| Mosquitto | localhost:11883 | mosquitto:1883 |

서버는 현재 호스트의 JVM에서 실행한다. 추후 컨테이너화하면 `localhost` 주소를 그대로 쓰지 않는다.

`docker compose down`은 서비스를 종료하되 명명 볼륨을 남긴다. 데이터를 버릴 의도가 없다면 `-v`를 붙이지 않는다.

개발 편의를 위해 이 로컬 브로커는 익명·평문 접속을 허용하며 호스트의 루프백에만 포트를 노출한다. 운영 설계의 TLS·장치 인증·ACL을 대체하지 않는다. 다른 장치나 PC에서 접근하도록 포트를 공개하기 전에 D1/D2의 인증 설정을 적용한다. 여기에는 인증서나 실물 장치 계정이 없다.

이미지 태그와 매니페스트 digest는 2026-11-11 공식 Docker Hub 조회값으로 고정했다. 실제 이미지 pull·컨테이너 기동은 Docker 사용 가능한 환경에서 별도 확인한다.

사건 저장 시험용 정책은 `sql/register-local-policy.sql`로 명시 등록합니다. 비활성 작성자 계정과 원본 예제 정책 3을 만들며 기존 다른 내용은 덮어쓰지 않습니다. [저장·응용 ACK 실습](../docs/event-storage.md)을 따르세요.
