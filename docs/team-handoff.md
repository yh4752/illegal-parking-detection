# 프론트 개발 인계 — 여기서 시작하세요

2026-11-14 · 현재 브랜치 **`feat/frontend-handoff`** · 제품 설계 v1.43

로그인, 구역 현황, 사건 목록·상세가 실제 백엔드와 연결된 React 앱을 전달합니다. 가상 장치가 MQTT로 보낸 상태를 Spring Boot가 PostgreSQL에 저장하고 화면에 반영합니다. 프론트 담당자는 이 코드를 실행한 뒤 디자인·배치·반응형과 사용성을 개선하면 됩니다.

## 필요한 자료

| 자료 | 용도 |
|---|---|
| [현재 개발 브랜치](https://github.com/yh4752/parking-system/tree/feat/frontend-handoff) | 실행 코드와 문서. main은 아직 초기 골격 |
| [인계 설명서](frontend-handoff.md) | 설치·로그인·가상 장치·화면 수정·오류 대응 |
| [AI용 시작 JSON](frontend-handoff-context.json) | AI에게 읽을 순서·계약·수정 경계 전달. 저장소와 함께 제공 |
| [인계 판정표](frontend-handoff-checklist.md) | 확인한 기능과 남은 범위 |
| [전체 코드 ZIP](https://github.com/yh4752/parking-system/archive/refs/heads/feat/frontend-handoff.zip) | Git을 쓰기 어려울 때 내려받기. 코드·설계·그림 포함, 로컬 비밀값 제외 |

사람은 설명서부터 읽고, AI에는 ZIP 또는 클론한 저장소와 시작 JSON을 함께 제공합니다. JSON만으로 전체 구현을 알 수는 없습니다. 지속적으로 개발할 때는 Git clone을 권합니다.

## 첫 실행

Git, Python 3.10 이상, 실행 중인 Docker Desktop/Engine과 Compose v2가 필요합니다. Windows는 WSL2를 사용합니다. 프론트 수정에는 Node.js 24.13.0이 필요합니다.

```sh
git clone --branch feat/frontend-handoff https://github.com/yh4752/parking-system.git
cd parking-system
python3 tools/handoff.py prepare
```

`http://127.0.0.1:18081`에 접속합니다. 아이디는 `handoff-viewer`, 비밀번호는 해당 PC의 `.local-state/handoff/web-password` 파일에 새로 생성됩니다. `python3 tools/handoff.py simulate`를 실행하면 가상 사건이 나타납니다. 자세한 개발 서버 명령은 설명서 3번을 따릅니다.

## 이번에 부탁드리는 확인

1. 본인 PC에서 로그인·구역 상태·사건 상세까지 실행되는지 확인해 주세요.
2. 구역 카드·메뉴·모바일 화면을 먼저 살펴보고 개선할 UI를 정해 주세요.
3. 현재 데이터는 가상 장치 보고입니다. 연결이 끊기면 마지막 관측을 유지하며, 영상·사진·제어 버튼 등 미구현 기능을 성공한 것처럼 표시하지 않습니다.
4. 수정은 인계 브랜치에서 새 작업 브랜치를 만들어 진행해 주세요. 세션·CSRF·API·SSE 계약 변경이 필요하면 백엔드 담당자와 먼저 맞춥니다.
5. 원본 저장소에 직접 push할 분은 GitHub 사용자명을 알려주세요. 공개 저장소는 열람·클론에 초대가 필요하지 않습니다. 쓰기 권한이 없으면 fork와 PR도 가능합니다.

실행 오류는 OS·Docker·Node 버전, 커밋, 실행 명령과 오류 메시지를 함께 알려주세요. 비밀번호·쿠키·환경 파일은 첨부하지 않습니다.

현재는 **로컬에서 실행하는 개발 인계판**입니다. 코드 저장소는 공개됐지만 웹 서비스의 공개 HTTPS 주소는 아직 없습니다. 실물 AI 보드·LED/스피커·실차 시험, 관리자 명령·정책 변경·사진 파일과 공개 운영 검증은 후속 범위입니다.
