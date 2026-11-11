# 함께 개발하는 방법

## 처음 참여할 때

개인 계정 `yh4752`의 비공개 [parking-system](https://github.com/yh4752/parking-system)을 하나의 개발 저장소로 사용합니다. 소유자에게 GitHub 사용자명을 전달해 초대를 받고, 초대 수락 후 본인 계정으로 클론합니다. 팀원 초대와 브랜치 보호 설정은 저장소 생성과 별개의 작업입니다.

[README](README.md)의 도구·클론·기동 절차부터 따라 하세요. 백엔드 기동만 확인한다면 Docker나 Node.js 설치는 필요하지 않습니다. GitHub CLI를 사용한다면 `gh auth login`으로 인증할 수 있습니다. 토큰을 URL·명령 예제·설정 파일에 적어 공유하지 않습니다.

## 작업 하나를 끝내는 흐름

1. `main`에서 `git pull --ff-only`로 최신 변경을 받습니다. 미커밋 변경이 있으면 먼저 확인하고 보존합니다.
2. `git switch -c feat/mqtt-ingest`처럼 목적이 드러나는 브랜치를 만듭니다. 문서 작업은 `docs/`, 수정은 `fix/`를 사용할 수 있습니다.
3. 관련 작업표와 계약을 읽고 구현합니다. 영향을 받는 문서·예제·검증도 함께 수정합니다.
4. 아래 표의 해당 검사를 실행합니다. `git diff --check`와 `git diff`로 불필요한 변경·민감정보를 확인합니다.
5. 변경 파일을 선택해 커밋하고 `git push -u origin 브랜치명`으로 올립니다. GitHub에서 `main` 대상 PR을 만듭니다.
6. 변경 이유·실행한 검사·한계·다른 담당자 영향이 PR에 드러나도록 작성하고 관련 담당자 검토와 CI 결과를 확인한 뒤 병합합니다.

기능 구현을 위해 모든 설계 문서를 다시 쓸 필요는 없습니다. 첫 개발 범위는 [첫 구현 작업표](docs/first-slice.md), 현재 완료 상태는 [현재 상태](docs/status.md)를 확인합니다.

## 무엇을 검사할까요?

명령은 별도 설명이 없으면 저장소 루트에서 실행합니다. Windows에서는 `python3` 대신 환경에 맞는 `py -3` 또는 `python`을 사용할 수 있습니다.

| 변경 대상 | 실행할 검사 | 확인 범위 |
|---|---|---|
| 모든 변경 | `git diff --check` | 공백 오류 |
| 백엔드 | `cd backend` 후 `./gradlew test bootJar` | 컴파일·테스트·실행 파일 생성 |
| 문서·그림 | 아래 문서 검사 명령 | 링크·그림·기존 원본 보존·생성물 일치 |
| Compose 설정 | `cd infra` 후 `docker compose --env-file .env.example config --quiet` | 설정 문법. 컨테이너 기동을 뜻하지 않음 |
| 통신·저장 기능 | 해당 작업표의 실제 통합 시험 | 브로커·DB를 사용한 흐름. 실행 조건과 결과 기록 |

```sh
npm --prefix tools/docs ci
npm --prefix tools/docs run build
python3 tools/check_structure.py
```

Markdown·DOT 원본을 고친 뒤 생성된 HTML·SVG도 커밋합니다. `git diff -- docs`로 생성 결과를 확인하세요. CI는 문서 재생성 후 미커밋 차이가 없는지 검사하므로 생성물을 빠뜨리면 실패합니다.

[GitHub Actions](.github/workflows/verify.yml)는 PR·main push에서 문서/설정 검사와 백엔드 빌드를 따로 실행합니다. 실행 결과는 GitHub의 Actions 탭에서 확인합니다. 기동 테스트가 통과해도 MQTT·DB·실차 시험까지 통과한 것은 아닙니다.

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
| Repository not found | 비공개 저장소 초대 수락 여부와 인증된 GitHub 계정 |
| JDK를 찾지 못함·버전 오류 | `java -version`이 17인지, IDE의 Gradle JVM도 같은지 |
| Gradle 다운로드 실패 | 인터넷·프록시·방화벽. 실패한 주소와 오류를 공유하되 비밀값 제외 |
| macOS/Linux에서 gradlew 실행 권한 오류 | `bash ./gradlew test bootJar`로 확인 |
| 8080 포트 사용 중 | 다른 서버를 확인하거나 `./gradlew bootRun --args='--server.port=8081'` 후 8081로 접속 |
| Docker 연결 실패 | Docker 실행 여부. 백엔드 기동만 확인한다면 Docker 없이 진행 가능 |
| 문서 검사에서 원본 해시 불일치 | reference 파일 변경 여부. 검사를 끄거나 해시만 재작성하지 않음 |

AI 도구를 쓰는 경우 [루트 AGENTS.md](AGENTS.md)와 작업 경로의 추가 지침을 읽게 합니다. 개인에게 설치된 스킬·플러그인이 없어도 이 저장소의 문서와 명령으로 개발할 수 있어야 합니다.
