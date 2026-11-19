# 외부 저장·배포 준비 로컬 검증 — 2026-11-19

결과: **LOCAL_EXTERNAL_STORAGE_PREPARATION_VERIFIED**. 검증 실행 당시 작업 브랜치의 미커밋 변경 기준이다. 후속 공유 상태는 PR/Checks를 따르며 이 기록을 원격 CI·AWS 배포 완료로 해석하지 않는다. [기계 판독 요약](summary.json), [구현·한계 설명](../../docs/external-storage-preparation.md)을 함께 읽는다.

## 실제 실행 결과

| 검사 | 결과 | 의미와 한계 |
|---|---|---|
| Python 운영 도구 회귀 | 63개 통과 | 기존 43개를 포함. [실행 로그](python-tests.txt) |
| Kotlin test + bootJar | 171개, 실패/오류/건너뜀 0 | [JUnit 합계](kotlin-tests.json). 이번에 databaseTest 전체는 재실행하지 않음 |
| 내부 HTTP gateway → 서명된 모의 S3 | 8개 확인 | 인증·CAS·동일 재시도·age·버전 지정 회수·원장 보존·head 누락 503. [실측](wire.json) |
| 실제 PostgreSQL dump → age → 모의 S3 → 회수 → 새 DB 복원 | 147행·1개 테이블 일치 | 원본 DB 불변, 기존 실행 컨테이너 30개 유지. [실측](postgres.json) |
| 큰 파일 암호화·회수 | 129MiB 일치 | 과거 128MiB 상한 초과 확인. 새 도구 4GiB 한도 전체 성능 시험은 아님 |
| 서버 Compose 생성/문법 | 통과 | tools profile 포함. 가짜 이미지 digest를 사용한 구성 검사이며 기동 아님 |
| 외부 백업 경보 규칙 | 2개 promtool 문법 검사 통과 | 실제 경보 전달·지연 목표 검증 아님 |
| S3 gateway Linux amd64 이미지 | 빌드 및 CLI 실행 통과 | 로컬 이미지. registry 게시·실서버 기동 없음 |

PostgreSQL 3.975초는 임시 컨테이너 준비·작은 시험 테이블의 두 번 복원·정리까지 포함한 이 실행 시간이다. 전체 제품 DB, 네트워크 장애, 키 회수와 사람의 대응 시간은 포함하지 않으며 **서비스 RTO로 쓰지 않는다**. 4GB 부하 시험은 이번에 반복하지 않았다.

## 재현 명령

저장소 루트에서 실행한다. Python 의존성은 전용 가상환경에 설치한다. 실제 AWS 자격증명이 필요하지 않다.

```sh
.local-state/external-python/bin/python -m unittest discover -s tools/production -p 'test_*.py' -v
.local-state/external-python/bin/python tools/production/verify_external_local.py
.local-state/external-python/bin/python tools/production/verify_external_postgres.py
.local-state/external-python/bin/python tools/production/prepare_server.py --input infra/server/inputs.example.json --output .local-state/server-new-review
docker compose -f .local-state/server-new-review/compose.json --profile tools config --quiet
```

PostgreSQL 검사는 매번 새 `parking-external-check-*` 프로젝트·tmpfs DB를 사용하고 자기 프로젝트만 정리한다. 기존 DB/볼륨을 복원 대상으로 사용하지 않는다. 생성 출력 폴더는 매번 새 경로여야 한다.

검토 중 초기화 권한·Grafana 설정·외부 백업 상태 경로를 고쳤다. 복원 디렉터리에는 마지막 완료 표식을 추가했고, 서비스 기동 요청과 readiness를 분리했다. 인증서 갱신이 검사 중 파일을 교체하는 [실패 재현](certificate-race-before-fix.txt) 후 입력 사본을 고정해 수정했다. 최종 Python 회귀가 이 재현을 포함한다.

실제 S3 IAM/버킷 정책·공인 TLS·권한 만료·실제 서버 부하·전체 제품 DB 백업 작업·주 기기 없이 키 회수·서버 유실 복구·예약 작업 설치·자동 보존 삭제는 남아 있다. 시험용 키는 임시 파일로만 사용했다. 운영 개인키·자격증명·DB 원본은 이 증거 폴더에 포함하지 않는다.

[source-sha256.json](source-sha256.json)은 이번 구현 소스의 해시이며 기존 증거를 덮어쓰지 않는다.

## PR 공유 전 재확인

[publication-checks.json](publication-checks.json)에 후속 커밋·PR 요청으로 실행한 재검사와 당시 소스 해시를 기록했다. 최초 `source-sha256.json`은 그대로 보존하며 공유 문구가 바뀐 두 문서는 새 해시와 구분한다. 실제 구현 소스는 최초 시험 이후 바뀌지 않았다.
