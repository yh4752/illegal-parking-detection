# 엣지 AI 기반 불법주정차 감지·안내 시스템

**불법주정차 상황을 현장에서 감지해 운전자에게 이동을 안내하고, 관리자가 웹에서 상태와 이력을 확인하는 캡스톤 프로젝트입니다.**

목표는 현장 보드의 AI가 지정 구역에서 차량의 정차 상태를 판단하고 LED·음성 안내로 연결하는 것입니다. 서버는 장치가 보낸 결과를 기록하고 관리자 화면에 전달합니다. 임의로 구성한 구역에서 실차로 검증할 계획이며, 법적 단속·과태료 부과는 수행하지 않습니다.

## 현재 어디까지 구현했나요?

**가상 장치 → MQTT → Spring Boot → PostgreSQL → React 관리자 화면을 연결하고, 로컬 HTTPS·모니터링·백업/복원을 검증했습니다. 실물 보드의 AI 감지·LED/스피커 연동과 외부 배포는 아직 완료하지 않았습니다.**

| 영역 | 구현·검증한 범위 | 남은 범위 |
|---|---|---|
| 현장 감지·안내 | 가상 장치, 장치 SDK의 메시지·재전송·저장 ACK 흐름 | 실물 보드의 AI 추론·차량 판단, LED/스피커 출력, 실차 성능 시험 |
| 수신·저장 | MQTT 메시지 검증, 중복·역순 처리, DB 커밋 후 ACK, heartbeat | 실물 장치와의 통합 검증 |
| 관리자 화면 | 로그인·구역 권한, 구역 현황, 사건 목록·상세, SSE 자동 갱신·재접속 | 관리 명령·정책 변경·사진 파일, 팀원 PC 인수 |
| 운영 기반 O1~O4 | 운영 프로필·DB 역할 분리·MQTT TLS/인증, Nginx HTTPS, Prometheus·Grafana, 암호화 백업·별도 로컬 revision 서비스·복원 | 실제 외부 저장소·키 회수·서버 유실 복구, 외부 알림·실서버 부하 검증 |
| 배포 준비 O5 | 단기 예산·후보·백업/복구 목표·본인 단독 관리 일부 확정 | 실제 계정·저장소·금고 선택과 복구 시험, 공개 HTTPS 배포·외부 접속 인수 |

현재 공개 웹 주소는 없습니다. 로컬 검증은 실제 AI 감지 정확도나 운영 서버의 복구 성능을 입증하지 않습니다. 자세한 완료 조건은 [현재 상태](docs/status.md), 남은 운영 작업은 [후속 이슈 #6](https://github.com/yh4752/illegal-parking-detection/issues/6)에서 확인합니다.

## 실행 화면

![가상 장치가 보고한 구역 상태와 사건 이력을 실제 API로 조회하는 관리자 화면](docs/screenshots/zone-desktop.png)

*가상 장치의 MQTT 메시지를 Spring Boot가 PostgreSQL에 저장하고 React 화면에 전달한 실행 화면입니다. 실물 AI 감지 결과나 실제 LED·음성 출력의 증거는 아닙니다.*

## 로컬에서 실행하기

저장소는 공개되어 누구나 열람·클론할 수 있습니다. **팀 개발은 ZIP 다운로드 대신 아래 명령으로 클론해서 시작합니다.** Git, Python 3.10 이상, 실행 중인 Docker Desktop/Engine과 Compose v2가 필요합니다. Windows는 WSL2를 사용합니다.

```sh
git clone https://github.com/yh4752/illegal-parking-detection.git
cd illegal-parking-detection
python3 tools/handoff.py prepare
```

<http://127.0.0.1:18081>에서 `handoff-viewer`로 로그인합니다. 비밀번호는 실행한 PC의 `.local-state/handoff/web-password` 파일에 생성됩니다. 별도 터미널에서 가상 사건을 보내면 화면이 갱신됩니다.

```sh
python3 tools/handoff.py simulate
```

실습을 마치면 `python3 tools/handoff.py stop`으로 이 인계 환경을 중지합니다. 데이터는 보존됩니다. 이 실행 절차는 로컬 HTTP 인계 환경이며, HTTPS 실습은 [O2 안내](docs/local-https.md)를 따릅니다.

팀의 공통 개발 기준은 **`main`**입니다. 최신 main에서 작업 브랜치를 만들고 PR도 main을 대상으로 올립니다. 기존 `parking-system` 클론을 사용 중이라면 [원격 주소 갱신 안내](CONTRIBUTING.md#저장소-이름-변경-후-기존-클론-연결하기)를 확인하세요. 로컬 폴더와 기존 데이터는 그대로 사용할 수 있습니다.

**[프론트 인계 설명서](docs/frontend-handoff.md)** — 처음 실행하기, 화면 수정, API·SSE 규칙, 오류 대응을 한곳에 정리했습니다. 프론트 개발에는 Node.js 24.13.0을 사용합니다.

## 개발·학습 자료

| 필요한 내용 | 진입점 |
|---|---|
| 전체 구조·역할 분담 | [C4 아키텍처](docs/architecture.md) |
| 프론트 수정·AI 코딩 도구에 인계 | [팀 전달 자료](docs/team-handoff.md) · [프론트 README](frontend/README.md) · [인계 컨텍스트](docs/frontend-handoff-context.json) |
| 서버·장치 통신 계약 | [백엔드 구조](docs/backend.md) · [메시지·API 정의](docs/contracts.md) |
| 운영 배포를 준비할 때 | [배포·운영 설계](docs/deployment-operations.md) · [배포 준비](docs/deployment-readiness.md) · [승인된 복구 목표](docs/backup-recovery-policy.md) |
| 실행한 시험·남은 작업 | [현재 상태](docs/status.md) · [인계 판정표](docs/frontend-handoff-checklist.md) |
| 협업·코딩 도구 규칙 | [개발 참여 안내](CONTRIBUTING.md) · [AGENTS.md](AGENTS.md) |
| 완성 코드로 학습 | [학습 순서](docs/learning-map.md) · [O1 개념·질문](docs/production-basics.md) |
| 블로그용 결정·수치·캡처 | [운영 학습·증거 모음](docs/operations-learning-evidence.md) · [이름을 정한 이유](docs/adr/011-project-identity.md) |
