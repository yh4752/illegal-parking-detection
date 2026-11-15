# 제품 구현 1단계: DB 준비

MQTT 학습에서 확인한 것은 메시지 수신·파싱·세 필드 검사다. 이번 단계는 실제 제품의 저장 구조를 준비한다. 이 문서는 초기 DB 준비 절차다. 이후 사건 저장·응용 ACK가 구현되었으며 실행은 [저장 실습](event-storage.md)을 따른다. 조회 API는 아직 없다.

## 설계와 코드의 연결

| v1.43 기준 | 이번 반영 |
|---|---|
| [C14](../reference/v1.43/spec/protocol/C14.md) | PostgreSQL + Spring JDBC + Flyway. JPA 엔티티를 별도로 만들지 않음 |
| [D4](../reference/v1.43/spec/storage/D4.md)·[원본 SQL](../reference/v1.43/개발자료/09_구현설계/db/schema.sql) | [V1 마이그레이션](../backend/src/main/resources/db/migration/V1__initial_parking_schema.sql)에 원본 DDL 유지 |
| [D5](../reference/v1.43/spec/storage/D5.md) | READ COMMITTED, lock_timeout 1초, statement_timeout 3초 |

Flyway는 DB 구조 변경 이력을 관리하는 라이브러리다. `database` 프로필로 서버를 시작하면 아직 적용하지 않은 SQL을 실행하고 `flyway_schema_history`에 기록한다. 재시작 때 같은 버전을 다시 적용하지 않으며, 적용된 파일의 내용이 달라지면 검증 오류를 낸다. **적용 후에는 V1을 고치지 않고 V2부터 새 변경을 추가한다.**

V1은 전체 설계의 17개 테이블을 준비하며 V2는 오류 격리용 inbound_quarantine을 추가한다. 명령·사진·로그인 기능까지 구현한 것은 아니다. 원본 설계 SQL의 주석은 출처 기록으로 보존했다. 실행 검증 상태는 [현재 상태](status.md)를 따른다.

| 먼저 알아둘 테이블 | 용도 |
|---|---|
| `zone`, `device`, `zone_policy` | 등록 구역·장치·판단 정책 |
| `inbound_receipt` | 이미 처리한 장치/메시지 ID와 내용 해시 |
| `event_message`, `incident` | 받은 사건 원문과 사건별 요약 |
| `zone_view` | 장치의 마지막 상태·연결 상태·화면 revision |

초기 계정·정책·사건은 자동 생성하지 않는다. 예제용 장치·구역·정책은 정식 메시지 수신 단계에서 명시적으로 등록한다. 현재 상태가 없는데 ONLINE 또는 빈 구역이라고 표시하지 않는다. DB의 중복 키 제약만으로 DUPLICATE ACK나 순서 역전 방지가 구현되지는 않는다. 구역 행 잠금·트랜잭션 업무·커밋 후 ACK는 별도 mqtt-ingest-local 프로필에서 구현한다.

## 실행

기존 서버가 8080 포트를 사용 중이면 해당 터미널에서 `Ctrl+C`로 종료한다. Docker Desktop은 켜 둔다. 아래 명령은 앞선 실습과 동일하게 **로컬 예시 비밀번호**를 사용한다. `.env`로 비밀번호를 바꿨다면 Compose와 서버 모두 그 값을 사용해야 한다. 기존 DB 볼륨의 비밀번호는 환경변수 변경만으로 바뀌지 않는다.

저장소 루트에서 DB를 시작한다.

```sh
cd infra
docker compose --env-file .env.example up -d postgres
docker compose --env-file .env.example ps
```

PostgreSQL이 `healthy`가 되면 같은 터미널에서 백엔드를 실행한다.

```sh
cd ../backend
export POSTGRES_PASSWORD=replace-with-your-local-password
./gradlew bootRun --args='--spring.profiles.active=database'
```

비밀번호는 소스나 `application.yml`에 실제 값으로 적지 않는다. Spring이 읽는 환경변수와 Compose가 읽는 `.env.example`은 서로 자동 공유되지 않으므로 위에서 별도로 전달한다.

| 확인 | 성공 기준 |
|---|---|
| 최초 시작 로그 | Flyway V1·V2 적용 성공 후 Spring 시작 완료 |
| 재시작 로그 | 적용된 V1·V2 검증 성공, 추가 적용할 마이그레이션 없음 |
| `/actuator/health` | `{"status":"UP"}`. database 프로필에서는 DB 연결 점검도 포함되지만 상세는 공개하지 않음 |
| 테이블 확인 | 아래 명령에서 설계 테이블과 flyway_schema_history 확인 |

별도 터미널의 `infra` 폴더에서:

```sh
docker compose --env-file .env.example exec postgres \
  psql -U parking -d parking -c '\dt'
```

`database`는 DB 기능을 켜는 설정 이름이며 별도 서버가 아니다. 기본 실행과 `mqtt-learning`에서는 DB를 요구하지 않는다. `database,mqtt-learning`으로 두 설정을 함께 켤 수 있지만 **학습 메시지가 자동 저장되지는 않는다.** 제품 API 접근 제한도 유지한다.

## 검사

DB를 켠 상태에서 `backend` 폴더에서 실행한다. 위 환경변수가 없는 새 터미널이라면 먼저 동일한 `POSTGRES_PASSWORD`를 설정한다.

```sh
./gradlew test databaseTest bootJar
```

- `test`: DB 없이 기본 기동·접근 제한·학습 메시지 처리 검사.
- `databaseTest`: 실제 PostgreSQL의 매번 새 `it_...` 스키마에서 전체 마이그레이션·재적용·중복 키·장치/구역 외래키·수신 이력 참조·미확인 초기값·연결 설정 검사. 시험 후 자신이 만든 스키마만 삭제한다. 연결 실패는 테스트 실패다.
- `bootJar`: 실행 파일 생성.

`databaseTest`는 Gradle 기본 명령이 아니라 이 저장소에 추가한 테스트 작업이다. `test`만 통과하면 DB 적용까지 검증한 것이 아니다. CI에도 동일한 PostgreSQL 검사 명령을 추가했다. 테스트는 초기 계정이나 개발용 장치를 public 스키마에 남기지 않는다.

Flyway는 Boot 3.5.16 BOM의 11.7.2, PostgreSQL JDBC는 42.7.11을 따른다. 연결 풀 상한 5개는 초기 개발 설정이며 동시 처리 성능을 보장하는 수치가 아니다. 현재 계정은 로컬 개발/마이그레이션 겸용이며 운영용 런타임 권한 분리는 배포 단계에서 구성한다.
