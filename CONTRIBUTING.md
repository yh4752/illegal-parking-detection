# 함께 개발하는 방법

## 처음 참여할 때

개인 계정 `yh4752`의 공개 [illegal-parking-detection](https://github.com/yh4752/illegal-parking-detection)을 하나의 개발 저장소로 사용합니다. 누구나 열람·클론할 수 있습니다. 원본 저장소에 직접 push할 팀원은 소유자에게 GitHub 사용자명을 전달해 협업자 초대를 받습니다. 쓰기 권한이 없으면 fork에서 작업한 뒤 PR로 제안할 수 있습니다. 공개 전환이 쓰기 권한을 부여하지는 않습니다.

[README](README.md)의 도구·클론·기동 절차부터 따라 하세요. 백엔드 기동만 확인한다면 Docker나 Node.js 설치는 필요하지 않습니다. GitHub CLI를 사용한다면 `gh auth login`으로 인증할 수 있습니다. 토큰을 URL·명령 예제·설정 파일에 적어 공유하지 않습니다.

팀 개발은 `git clone`으로 시작합니다. ZIP은 Git 이력·원격 연결이 없는 코드 사본이므로 AI에 첨부하거나 내용을 살펴보는 용도로만 사용합니다. ZIP 폴더에서 이미 수정했다면 보존한 뒤, 별도로 클론한 저장소의 작업 브랜치에 변경 파일만 비교해 옮깁니다.

## 작업 하나를 끝내는 흐름

**팀 공통 기준은 `main`입니다.** 프론트·백엔드 모두 최신 main에서 목적별 작업 브랜치를 만들고 main 대상 PR로 합칩니다. main에 직접 push하지 않습니다. 이전 인계 브랜치는 과거 작업을 찾아보기 위해 보존하며 새 작업의 기준으로 사용하지 않습니다.

1. `main`에서 `git pull --ff-only`로 최신 변경을 받습니다. 미커밋 변경이 있으면 먼저 확인하고 보존합니다.
2. `git switch -c feat/mqtt-ingest`처럼 목적이 드러나는 브랜치를 만듭니다. 문서 작업은 `docs/`, 수정은 `fix/`를 사용할 수 있습니다.
3. 관련 작업표와 계약을 읽고 구현합니다. 영향을 받는 문서·예제·검증도 함께 수정합니다.
4. 아래 표의 해당 검사를 실행합니다. `git diff --check`와 `git diff`로 불필요한 변경·민감정보를 확인합니다.
5. 변경 파일을 선택해 커밋하고 `git push -u origin 브랜치명`으로 올립니다. GitHub에서 `main` 대상 PR을 만듭니다.
6. 변경 이유·실행한 검사·한계·다른 담당자 영향이 PR에 드러나도록 작성하고 관련 담당자 검토와 CI 결과를 확인한 뒤 병합합니다.

기능 구현을 위해 모든 설계 문서를 다시 쓸 필요는 없습니다. 첫 개발 범위는 [첫 구현 작업표](docs/first-slice.md), 현재 완료 상태는 [현재 상태](docs/status.md)를 확인합니다.

## 저장소 이름 변경 후 기존 클론 연결하기

2026-11-17부터 저장소 이름은 `illegal-parking-detection`입니다. 이미 클론한 `parking-system` 폴더에서 다음 명령으로 원격 주소를 갱신합니다. 새로 클론하거나 로컬 폴더·DB·Docker 볼륨 이름을 바꿀 필요는 없습니다.

```sh
git remote set-url origin https://github.com/yh4752/illegal-parking-detection.git
git remote -v
git fetch origin
```

위 주소는 원본 저장소를 origin으로 쓰는 팀원 기준입니다. fork로 작업한다면 본인의 origin은 유지하고 원본을 가리키는 원격(보통 `upstream`)만 갱신합니다. 예전 이름으로 된 ZIP·검증 로그는 당시 기록이며, 새 클론의 기본 폴더 이름은 `illegal-parking-detection`입니다. 이름의 선택과 보존 범위는 [ADR-011](docs/adr/011-project-identity.md)에 기록했습니다.

## 이전 인계 브랜치에서 작업 중이라면

미커밋 변경은 현재 작업 브랜치에 커밋하거나 별도로 보존한 뒤 진행합니다. 새 작업은 아래처럼 시작합니다.

```sh
git fetch origin
git switch main
git pull --ff-only origin main
git switch -c feat/console-ui
```

이미 본인 작업 브랜치에서 수정했다면 그 브랜치에서 `git fetch origin` 후 `git merge origin/main`으로 공통 변경을 받아 충돌을 해결합니다. 기존 작업을 버리거나 강제 push할 필요가 없습니다. 기존 PR의 대상이 인계 브랜치였다면 `main`으로 바꾸고 변경 목록을 확인합니다. `pull --ff-only`가 실패하면 이력을 확인하고 협의하며 `reset --hard`로 덮어쓰지 않습니다.

## 무엇을 검사할까요?

명령은 별도 설명이 없으면 저장소 루트에서 실행합니다. Windows에서는 `python3` 대신 환경에 맞는 `py -3` 또는 `python`을 사용할 수 있습니다.

| 변경 대상 | 실행할 검사 | 확인 범위 |
|---|---|---|
| 모든 변경 | `git diff --check` | 공백 오류 |
| 백엔드 | `cd backend` 후 `./gradlew test bootJar` | 컴파일·DB 없는 테스트·실행 파일 생성 |
| DB 마이그레이션·저장 | DB 실행·환경변수 설정 후 `cd backend`에서 `./gradlew databaseTest` | 실제 PostgreSQL 적용·제약 검사. [실행 안내](docs/database.md) |
| 문서·그림 | 아래 문서 검사 명령 | 링크·그림·기존 원본 보존·생성물 일치 |
| 웹 계약·생성기 | `python3 tools/build_contracts.py --check` 및 `python3 -m unittest discover -s tools/tests -v` | 보존 원본 해시·REST 변경 범위·생성물 일치. 변경 시 생성 명령을 먼저 실행 |
| Compose 설정 | `cd infra` 후 `docker compose --env-file .env.example config --quiet` | 설정 문법. 컨테이너 기동을 뜻하지 않음 |
| 통신·저장 기능 | 해당 작업표의 실제 통합 시험 | 브로커·DB를 사용한 흐름. 실행 조건과 결과 기록 |
| 프론트 | `cd frontend` 후 `npm run generate && npm run format:check && npm test && npm run build` | 계약 생성·코드 서식·단위 시험·타입·빌드 |
| 프론트 통신·흐름 | [인계 설명서](docs/frontend-handoff.md)의 `npm run test:e2e` | 빈 별도 Docker 환경에서 실제 브라우저·MQTT·DB 시험 |

```sh
npm --prefix tools/docs ci
npm --prefix tools/docs run build
python3 tools/check_structure.py
```

Markdown·DOT 원본을 고친 뒤 생성된 HTML·SVG도 커밋합니다. `git diff -- docs`로 생성 결과를 확인하세요. CI는 문서 재생성 후 미커밋 차이가 없는지 검사하므로 생성물을 빠뜨리면 실패합니다.

[GitHub Actions](.github/workflows/verify.yml)는 PR·main push에서 문서/설정, 백엔드·PostgreSQL, 프론트, 새 환경 Docker·브라우저 인계를 각각 검사합니다. 실행 결과는 GitHub의 Actions 탭에서 확인합니다. 가상 장치 시험을 실물 보드·실차 시험으로 표시하지 않습니다.

## 설계를 바꿀 때

- 제품 메시지·API·상태 규칙은 [정식 계약](docs/contracts.md)을 먼저 확인합니다. 구현 편의로 필드를 줄이거나 별도 계약을 만들지 않습니다.
- `reference/v1.43`은 보존본입니다. 수정이 필요하면 변경 이유·영향·새 기준 위치를 ADR에 기록하고, 계약·예제·검증을 함께 갱신하는 별도 변경으로 진행합니다. 해시 검사를 통과시키려고 기존 기준 파일이나 `reference/source.json`만 고치지 않습니다.
- 책임·통신·저장 경계가 바뀌면 ADR과 C4 그림 원본·관련 코드를 함께 수정합니다. 이후 작업자가 새 기준을 찾을 수 있도록 `docs/contracts.md`도 연결합니다.
- 기능을 구현하거나 검증 범위가 바뀌면 `docs/status.md`를 갱신합니다. 이전 검증 기록은 당시 기록으로 남깁니다.

## 커밋할 파일

코드, Gradle Wrapper, 설정 예시, 문서 원본·생성물, 검증 기록을 공유합니다. 실제 `.env`, 개인키, 로컬 DB, 빌드 산출물, 설치된 의존성, 실차 원본 영상, 학습 데이터·가중치는 제외합니다. 데이터는 위치·접근 방법·라벨 기준을 별도로 인계합니다. 제외 규칙은 [.gitignore](.gitignore)에 있습니다.

로컬 브로커의 익명·평문 설정은 같은 PC에서만 사용합니다. 외부 보드 연결이나 원격 배포를 진행할 때는 별도의 인증·암호화 구성을 적용해야 합니다.

## 막혔을 때

| 증상 | 먼저 확인할 것 |
|---|---|
| Repository not found | 저장소 URL·브랜치 이름·네트워크 확인. push 거부는 협업자 권한 또는 fork 사용 여부 확인 |
| JDK를 찾지 못함·버전 오류 | `java -version`이 17인지, IDE의 Gradle JVM도 같은지 |
| Gradle 다운로드 실패 | 인터넷·프록시·방화벽. 실패한 주소와 오류를 공유하되 비밀값 제외 |
| macOS/Linux에서 gradlew 실행 권한 오류 | `bash ./gradlew test bootJar`로 확인 |
| 8080 포트 사용 중 | 다른 서버를 확인하거나 `./gradlew bootRun --args='--server.port=8081'` 후 8081로 접속 |
| Docker 연결 실패 | Docker 실행 여부. 백엔드 기동만 확인한다면 Docker 없이 진행 가능 |
| 문서 검사에서 원본 해시 불일치 | reference 파일 변경 여부. 검사를 끄거나 해시만 재작성하지 않음 |

AI 도구를 쓰는 경우 [루트 AGENTS.md](AGENTS.md)와 작업 경로의 추가 지침을 읽게 합니다. 개인에게 설치된 스킬·플러그인이 없어도 이 저장소의 문서와 명령으로 개발할 수 있어야 합니다.

README·협업 절차·AI 지침을 나눈 이유는 [ADR-002](docs/adr/002-repository-onboarding.md)에 기록했습니다.
