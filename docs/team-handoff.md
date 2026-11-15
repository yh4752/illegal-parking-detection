# 프론트 개발 인계 — 여기서 시작하세요

2026-11-15 · 공통 기준 브랜치 **`main`** · 제품 설계 v1.43

로그인, 구역 현황, 사건 목록·상세가 실제 백엔드와 연결된 React 앱을 전달합니다. 가상 장치가 MQTT로 보낸 상태를 Spring Boot가 PostgreSQL에 저장하고 화면에 반영합니다. 프론트 담당자는 이 코드를 실행한 뒤 디자인·배치·반응형과 사용성을 개선하면 됩니다.

## 필요한 자료

| 자료 | 용도 |
|---|---|
| [공통 개발 기준](https://github.com/yh4752/parking-system/tree/main) | 실행 코드와 문서. 각자 작업 브랜치와 PR은 main 기준 |
| [인계 설명서](frontend-handoff.md) | 설치·로그인·가상 장치·화면 수정·오류 대응 |
| [AI용 시작 JSON](frontend-handoff-context.json) | AI에게 읽을 순서·계약·수정 경계 전달. 저장소와 함께 제공 |
| [인계 판정표](frontend-handoff-checklist.md) | 확인한 기능과 남은 범위 |
| [프론트 시안 적용 기준](frontend-design-alignment.md) | v1.43과 다른 동작, 이번 작업 범위·PR 확인 항목 |

**팀 개발은 아래 명령으로 저장소를 클론해서 시작합니다.** ZIP으로 받은 `parking-system-feat-frontend-handoff` 폴더에는 Git 이력과 원격 저장소 설정이 없습니다. 클론하면 `parking-system` 폴더가 생기고, 이후 pull·push·PR로 변경 내용을 주고받을 수 있습니다.

사람은 설명서부터 읽고, 코딩 AI에는 클론한 저장소와 시작 JSON을 함께 제공합니다. 저장소를 읽을 수 없고 파일 첨부만 가능한 AI에는 [코드 ZIP](https://github.com/yh4752/parking-system/archive/refs/heads/main.zip)을 참고 자료로 사용할 수 있습니다. JSON만으로 전체 구현을 알 수는 없습니다.

## 첫 실행

Git, Python 3.10 이상, 실행 중인 Docker Desktop/Engine과 Compose v2가 필요합니다. Windows는 WSL2를 사용합니다. 프론트 수정에는 Node.js 24.13.0이 필요합니다.

```sh
git clone https://github.com/yh4752/parking-system.git
cd parking-system
git switch -c feat/console-ui
python3 tools/handoff.py prepare
```

`feat/console-ui`는 작업 브랜치 예시입니다. 본인 작업에 맞는 고유한 이름을 사용합니다. 이미 클론한 저장소가 있으면 [협업 절차](../CONTRIBUTING.md)에 따라 최신 변경을 받은 뒤 작업 브랜치를 만듭니다. ZIP 폴더에서 이미 수정했다면 그 폴더를 보존하고, 별도로 클론한 저장소의 작업 브랜치에 수정 파일만 비교해서 옮깁니다.

`http://127.0.0.1:18081`에 접속합니다. 아이디는 `handoff-viewer`, 비밀번호는 해당 PC의 `.local-state/handoff/web-password` 파일에 새로 생성됩니다. `python3 tools/handoff.py simulate`를 실행하면 가상 사건이 나타납니다. 자세한 개발 서버 명령은 설명서 3번을 따릅니다.

## 이번에 부탁드리는 확인

1. 본인 PC에서 로그인·구역 상태·사건 상세까지 실행되는지 확인해 주세요.
2. 구역 카드·메뉴·모바일 화면을 먼저 살펴보고 개선할 UI를 정해 주세요.
3. 현재 데이터는 가상 장치 보고입니다. 연결이 끊기면 마지막 관측을 유지하며, 영상·사진·제어 버튼 등 미구현 기능을 성공한 것처럼 표시하지 않습니다.
4. 수정은 본인 작업 브랜치에서 진행하고, 커밋·push 후 `main`을 기준으로 PR을 만들어 주세요. 세션·CSRF·API·SSE 계약 변경이 필요하면 백엔드 담당자와 먼저 맞춥니다.
5. 팀원은 협업자 초대를 위해 GitHub 사용자명 또는 프로필 링크를 알려주세요. 초대 수락 후 본인 브랜치를 원본 저장소에 push할 수 있습니다. 공개 저장소의 열람·클론과 로컬 작업은 초대 전에도 가능합니다.

실행 오류는 OS·Docker·Node 버전, 커밋, 실행 명령과 오류 메시지를 함께 알려주세요. 비밀번호·쿠키·환경 파일은 첨부하지 않습니다.

현재는 **로컬에서 실행하는 개발 인계판**입니다. 코드 저장소는 공개됐지만 웹 서비스의 공개 HTTPS 주소는 아직 없습니다. 실물 AI 보드·LED/스피커·실차 시험, 관리자 명령·정책 변경·사진 파일과 공개 운영 검증은 후속 범위입니다.
