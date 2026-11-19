# PR #12 CI 복구와 전체 서비스 외부 저장 연결

2026-11-19 · 로컬/CI 시험. 실제 AWS 계정·버킷·서버 생성·구매·DNS·외부 배포 없음.

## 실패 원인과 수정

- 최초 PR CI: 8개 중 5개 통과. 두 이미지 빌드는 Maven 요청 제한(429), 새 PostgreSQL 시험은 준비 시간 초과로 실패했다. [원본 실패 발췌](initial-ci-failures.txt)를 보존한다.
- PostgreSQL fixture는 호스트 소유의 0700 디렉터리/0600 파일을 통째로 마운트했다. Linux의 PostgreSQL UID가 읽지 못하는 것을 컨테이너에서 재현했다. 호스트 부모는 비공개로 유지하면서 각 파일만 읽기 전용으로 마운트하도록 변경했다. [수정 기록](ci-postgres-fix.json).
- 수정 후 [Linux CI](https://github.com/yh4752/illegal-parking-detection/actions/runs/44472397198)의 문서/설정 작업에서 새 PostgreSQL 왕복이 통과했다. 다른 작업에서는 Gradle Plugin Portal 경유 의존성 조회가 일시적으로 누락 오류를 반환했다. 해당 POM의 Maven Central 직접 조회와 Portal 경유 조회는 조사 시 모두 HTTP 200이었다. 플러그인 저장소에 Maven Central을 먼저 명시해 불필요한 리다이렉트를 줄인다. 모든 버전·TLS 검증은 유지한다. [Gradle 공식 저장소 설명](https://docs.gradle.org/current/userguide/declaring_repositories_basics.html).
- 첫 전체 연결 실행은 이미지 빌드 진행 확인을 위해 자체 빌드 프로세스만 종료했고, 두 번째는 Docker 자동 주소 범위 소진으로 서비스 생성 전에 실패했다. 기존 네트워크/컨테이너/볼륨을 정리하지 않고, 기존 사전 시험과 같은 방식으로 빈 사설 대역을 골라 시험별 작은 subnet을 배정했다.
- 세 번째와 다섯 번째 실행은 첫 상태 조회가 503으로 차단되었다. 원장 접근 지연은 관측했으나 당시 로그만으로 정확한 원인은 확정하지 않았다. 시험 gateway에 오류 종류·지연 진단을 추가했고, 재현 후 backend에도 비밀값 없는 예외 클래스 로그를 추가했다. 토큰·응답·예외 원문을 기록하지 않는 테스트를 포함했다. 실패를 통과로 덮어쓰거나 자동 재시작으로 숨기지 않았다.
- 네 번째 실행은 제품 DB dump·별도 복원·fence 이후 업로드에서 `EndpointConnectionError`로 실패했다. 모의 S3가 내부 전용 네트워크에만 있어 호스트의 loopback 포트에 연결되지 않았다. 모의 S3에만 별도 시험 네트워크를 추가하고, 포트는 `127.0.0.1`로 유지했다. 시작 전 연결 확인과 SDK 시간 제한도 추가했다. 실제 배포 네트워크는 바꾸지 않았다.
- Linux 시험용 백업 상태 파일은 일반 서버의 UID 10001 실행과 달리 호스트 사용자로 생성된다. 비밀값 없는 상태 파일만 backend가 읽도록 하고, 원본 보존 비교는 수신 중지 후 수행한다.

## 시험 경계

`verify_external_stack.py`는 기존 격리 HTTPS/MQTT/제품 DB 스택과 실제 `S3Ledger`, `server_backup.backup`, age 도구를 연결한다. 내부 모의 S3 endpoint는 `fixtures/`에서만 주입하며 실제 gateway의 고정 AWS HTTPS·명시 credential 정책은 완화하지 않는다. `AWS_S3` 표기는 저장 어댑터 형식이며 실제 AWS 인수를 뜻하지 않는다.

이 시험은 서버용 생성 Compose 자체, 실제 IAM/TLS, 주 기기 없는 개인키 회수, 전체 호스트 유실 RTO, 실물 보드를 검증하지 않는다. 기존 작은 147행 시험 결과와 섞거나 과거 로그를 새 성공으로 덮어쓰지 않는다. 새 성공 결과와 실행 시간은 완료 후 별도 JSON에 기록한다.
