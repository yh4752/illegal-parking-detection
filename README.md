# 엣지 AI 주정차 안내 시스템

현장 보드가 차량의 정차를 판단해 LED·음성으로 이동을 안내하고, 관리자는 웹에서 상태와 이력을 확인하는 캡스톤 프로젝트입니다. 임의로 구성한 구역에서 실차로 검증할 계획이며 법적 단속·과태료 부과는 수행하지 않습니다.

![실제 API에 연결된 관리자 화면: 가상 장치가 보고한 구역 상태와 사건 이력](docs/screenshots/zone-desktop.png)

*가상 장치의 MQTT 메시지를 Spring Boot가 PostgreSQL에 저장하고 React 화면에 전달한 실행 화면입니다. 실물 보드의 차량 인식·LED/스피커 출력은 아직 연결하지 않았습니다.*

## 바로 실행하기

저장소는 공개되어 누구나 열람·클론할 수 있습니다. [팀 전달 자료](docs/team-handoff.md)에서 시작하세요. 현재 인계 코드는 **`feat/frontend-handoff`** 브랜치에 있습니다. `main`에는 아직 병합하지 않았습니다. Git, Python 3.10 이상, 실행 중인 Docker Desktop/Engine과 Compose v2가 필요합니다. Windows는 WSL2를 사용합니다.

```sh
git clone --branch feat/frontend-handoff https://github.com/yh4752/parking-system.git
cd parking-system
python3 tools/handoff.py prepare
```

<http://127.0.0.1:18081>에서 `handoff-viewer`로 로그인합니다. 비밀번호는 실행한 PC의 `.local-state/handoff/web-password` 파일에 생성됩니다. `python3 tools/handoff.py simulate`로 가상 사건을 보내면 화면이 갱신됩니다.

**[프론트 인계 설명서](docs/frontend-handoff.md)** — 처음 실행하기, 화면 수정, API·SSE 규칙, 오류 대응을 한곳에 정리했습니다. 프론트 개발에는 Node.js 24.13.0을 사용합니다.

## 구현 범위

| 사용할 수 있는 기능 | 남은 범위 |
|---|---|
| 로그인·구역 권한·구역 현황·사건 목록/상세 | 공개 HTTPS 배포와 외부 PC 인수 |
| MQTT 검증·저장·중복/순서 처리·저장 ACK | 실물 보드·AI·LED/스피커·실차 시험 |
| heartbeat·통신 지연 표시·SSE·재접속 | 관리 명령·정책 변경·사진 파일 |
| 독립 Docker 데모·가상 장치·자동 시험 | 운영 백업/복원·대규모 부하 검증 |

[인계 판정표](docs/frontend-handoff-checklist.md)에서 통과한 검사와 남은 조건을 구분합니다. 현재 주소는 **로컬 실행용**이며 공개 URL은 없습니다.

## 개발 문서

| 필요한 내용 | 진입점 |
|---|---|
| 전체 구조·역할 분담 | [C4 아키텍처](docs/architecture.md) |
| 프론트 수정·AI 코딩 도구에 인계 | [프론트 README](frontend/README.md) · [인계 컨텍스트](docs/frontend-handoff-context.json) |
| 서버·장치 통신 계약 | [백엔드 구조](docs/backend.md) · [메시지·API 정의](docs/contracts.md) |
| 실행한 시험·남은 작업 | [현재 상태](docs/status.md) · [검증 기록](validation/frontend-handoff-2026-11-14.json) |
| 협업·코딩 도구 규칙 | [개발 참여 안내](CONTRIBUTING.md) · [AGENTS.md](AGENTS.md) |
| 완성 코드로 학습 | [학습 순서](docs/learning-map.md) · [백엔드만 실행](backend/README.md) |
