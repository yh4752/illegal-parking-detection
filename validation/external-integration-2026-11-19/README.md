# PR #12 CI 복구와 전체 서비스 외부 저장 연결

2026-11-19 · 로컬/CI 시험. 실제 AWS 계정·버킷·서버 생성·구매·DNS·외부 배포 없음.

## 통과한 범위

[통합 코드 CI 8개 통과](ci-integrated-head.json)를 확인했다. [Linux CI 전체 제품 시험](ci-full-stack.json)은 commit `454e718`에서 **20개 테이블**의 dump/복원 일치, 모의 S3 버전 지정 회수·age 복호화, DB 단독 및 DB+revision 쌍 과거 복원 거부, API/SSE/일반 수신 잠금, 장치 대조·명시 손실 인수, 새 revision과 SSE/ACK 재개, 원장 장애 시 차단을 통과했다. [원본 단계 로그 발췌](ci-full-stack-log.txt) · [CI 실행](https://github.com/yh4752/illegal-parking-detection/actions/runs/44477830882).

기준 사건 1개와 백업 이후 이미 ACK된 사건 1개로 손실 가능성을 의도적으로 재현했다. 20개 테이블은 제품 스키마 전체이며 큰 데이터 부하 시험은 아니다. 외부 백업 복원 지표 1, 기존 로컬 백업 지표 0을 각각 확인했다. **231.968초는 준비된 CI 환경의 시나리오 실행 시간이며 호스트/키 회수·빌드·초기 준비를 포함한 RTO가 아니다.**

노트북과 CI 결과를 합치지 않는다. CI에는 사전에 실행 중인 컨테이너가 0개였고, 노트북의 실패/부분 시험은 기존 실행 컨테이너 30개를 유지했다. 실제 계정·S3·서버·DNS 작업은 없었다.

## 실패 원인과 수정

- 최초 PR CI: 8개 중 5개 통과. 두 이미지 빌드는 Maven 요청 제한(429), 새 PostgreSQL 시험은 준비 시간 초과로 실패했다. [원본 실패 발췌](initial-ci-failures.txt)를 보존한다.
- PostgreSQL fixture는 호스트 소유의 0700 디렉터리/0600 파일을 통째로 마운트했다. Linux의 PostgreSQL UID가 읽지 못하는 것을 컨테이너에서 재현했다. 호스트 부모는 비공개로 유지하면서 각 파일만 읽기 전용으로 마운트하도록 변경했다. [수정 기록](ci-postgres-fix.json).
- 수정 후 [Linux CI](https://github.com/yh4752/illegal-parking-detection/actions/runs/44472397198)의 문서/설정 작업에서 새 PostgreSQL 왕복이 통과했다. 다른 작업에서는 Gradle Plugin Portal 경유 의존성 조회가 일시적으로 누락 오류를 반환했다. 해당 POM의 Maven Central 직접 조회와 Portal 경유 조회는 조사 시 모두 HTTP 200이었다. 플러그인 저장소에 Maven Central을 먼저 명시해 불필요한 리다이렉트를 줄인다. 모든 버전·TLS 검증은 유지한다. [Gradle 공식 저장소 설명](https://docs.gradle.org/current/userguide/declaring_repositories_basics.html).
- 첫 전체 연결 실행은 이미지 빌드 진행 확인을 위해 자체 빌드 프로세스만 종료했고, 두 번째는 Docker 자동 주소 범위 소진으로 서비스 생성 전에 실패했다. 기존 네트워크/컨테이너/볼륨을 정리하지 않고, 기존 사전 시험과 같은 방식으로 빈 사설 대역을 골라 시험별 작은 subnet을 배정했다.
- 세 번째와 다섯 번째 실행은 첫 상태 조회가 503으로 차단되었다. 원장 접근 지연은 관측했으나 당시 로그만으로 정확한 원인은 확정하지 않았다. 시험 gateway에 오류 종류·지연 진단을 추가했고, 재현 후 backend에도 비밀값 없는 예외 클래스 로그를 추가했다. 토큰·응답·예외 원문을 기록하지 않는 테스트를 포함했다. 실패를 통과로 덮어쓰거나 자동 재시작으로 숨기지 않았다.
- 네 번째 실행은 제품 DB dump·별도 복원·fence 이후 업로드에서 `EndpointConnectionError`로 실패했다. 모의 S3가 내부 전용 네트워크에만 있어 호스트의 loopback 포트에 연결되지 않았다. 모의 S3에만 별도 시험 네트워크를 추가하고, 포트는 `127.0.0.1`로 유지했다. 시작 전 연결 확인과 SDK 시간 제한도 추가했다. 실제 배포 네트워크는 바꾸지 않았다.
- 여섯 번째 실행은 backend Docker 빌드가 900초 제한에 도달해 서비스 시험에 진입하지 못했다. 이 기록을 복원 실패 시간이나 RTO로 사용하지 않는다. 로컬 선택 경로 `--backend-jar`는 먼저 `backend`에서 테스트/bootJar를 끝낸 실행 파일과 SHA-256을 사용하고, 원래 Dockerfile의 runtime stage로 이미지를 만든다. CI에서는 이 선택을 사용하지 않고 소스부터 전체 Docker 빌드를 유지한다.
- 일곱 번째 실행은 20개 제품 테이블의 원본/회수 dump 비교와 암호화 업로드를 마쳤지만, 과거 DB 시작 거부 검사에서 50초 제한에 도달했다. [부분 백업 수치](attempt7-backup-partial.json)는 전체 복구 성공이나 RTO가 아니다. 최초 실패 probe 로그를 정리 전에 보존하도록 보강했다. 이 실행에서 Java 원장 요청 `HttpTimeoutException`을 관측했다([발췌](observed-request-timeout.txt)). 원장 내부 처리 시간만으로 전체 왕복 시간 초과를 배제할 수 없으며, 과거 초기 503의 정확한 원인까지 소급 확정하지 않는다.
- 여덟 번째 실행은 두 rollback 거부와 복구 hold를 통과했으나, 장치 대조 후 재기동에서 원장 확인 실패로 HTTPS 준비가 완료되지 않았다. gateway head 내부 처리 6.315초를 관측했으며 원장 요청 제한은 3초다. 실제 AWS 또는 서버 성능으로 일반화하지 않는다. 아홉 번째는 같은 프로젝트의 기존 학습용 Grafana·Prometheus 두 컨테이너만 일시정지하는 조건으로 재검증했지만, 저장 ACK 이후 Docker simulator 명령의 34초 제한으로 실패했다. 두 개 일시정지만으로 원인이 해결됐다고 볼 수 없다. 종료 후 같은 컨테이너가 실행 중이고 paused=false인 것을 확인했다([복귀 기록](monitoring-restored.json)). DB·MQTT·다른 프로젝트는 중단하지 않는다.
- Linux 시험용 백업 상태 파일은 일반 서버의 UID 10001 실행과 달리 호스트 사용자로 생성된다. 비밀값 없는 상태 파일만 backend가 읽도록 하고, 원본 보존 비교는 수신 중지 후 수행한다.

## 공유 노트북 판정

**연속 전체 인수는 미완료다.** 작은 PostgreSQL 시험과 제품 백업/회수의 부분 성공은 있었지만, 원장 HTTP 시간 초과·Docker 명령 지연을 관측했다. 기존 실행 컨테이너 30개와 데이터를 보존했으며, 아홉 번째의 학습 모니터링 일시정지/복귀 외에는 다른 프로젝트를 중단하거나 Docker 자원을 prune하지 않았다. CPU 경합이 유일 원인이라고 확정하지 않는다. 원장 3초 제한·실패 시 차단·기동 거부를 완화해 통과시키지 않았다.

`--backend-jar`는 현재 소스와의 일치나 테스트 통과를 자동 판정하지 않는다. 이번에는 [Gradle 실행 로그](backend-build.txt)·[172개 테스트/실행 파일 및 소스 해시](backend-check.json)를 함께 기록했다. CI에서는 옵션 없이 소스부터 Docker 이미지를 빌드해 전체 절차를 통과했다. 시험 중 원본 DB의 불변을 비교하고, 종료 후 해당 시험에서 새로 만든 프로젝트·볼륨만 정리한다.

## 시험 경계

`verify_external_stack.py`는 기존 격리 HTTPS/MQTT/제품 DB 스택과 실제 `S3Ledger`, `server_backup.backup`, age 도구를 연결한다. 내부 모의 S3 endpoint는 `fixtures/`에서만 주입하며 실제 gateway의 고정 AWS HTTPS·명시 credential 정책은 완화하지 않는다. `AWS_S3` 표기는 저장 어댑터 형식이며 실제 AWS 인수를 뜻하지 않는다.

이 시험은 서버용 생성 Compose 자체, 실제 IAM/TLS, 주 기기 없는 개인키 회수, 전체 호스트 유실 RTO, 실물 보드를 검증하지 않는다. 기존 작은 147행 시험 결과와 섞거나 과거 로그를 새 성공으로 덮어쓰지 않는다. Linux CI의 전체 통과와 노트북의 부분 성공/실패를 별도 JSON·로그로 기록한다. [백엔드 172개 검사와 로컬 bootJar 해시](backend-check.json)를 함께 보존한다.

`source-sha256.json`은 공유 준비 시점의 소스 해시다. Linux CI의 실행 커밋은 각 결과 JSON에 명시하고, 노트북은 해당 시점 작업 트리와 기록된 bootJar를 사용했다. `evidence-sha256.json`은 이 폴더의 공개 근거 파일을 대조한다. 개인키·비밀번호·DB dump·민감 원본은 포함하지 않는다.
