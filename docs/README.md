# 문서 사용·수정

프로젝트 이름은 **엣지 AI 기반 불법주정차 감지·안내 시스템**, 저장소 이름은 `illegal-parking-detection`입니다. 목적과 현재 구현 상태는 [프로젝트 README](../README.md), 이름 변경과 기존 기록의 보존 기준은 [ADR-011](adr/011-project-identity.md)을 확인합니다.

## 읽는 순서

1. 팀 전체: `architecture.md` 또는 `index.html`에서 핵심 구조와 역할 확인.
2. 백엔드: `backend.md`의 L3 → `first-slice.md` → 필요한 정식 계약 확인.
3. 코드 작업 전: `status.html`로 작성·실행·미구현 범위 확인.
4. 프론트 인계: [체크리스트](frontend-handoff-checklist.md)에서 완료 범위를, [시안 적용 기준](frontend-design-alignment.md)에서 이번 UI 작업 범위를 확인.
5. 전체 다음 구현: [웹 인계 계획](public-demo.md)으로 범위를 보고, [상세 설계](design/public-demo-plan.md)와 [작업 JSON](design/public-demo-workplan.json)으로 착수.
6. HTTPS 운영 흐름 실습: [로컬 HTTPS 안내](local-https.md)의 그림 → 실행 → 오류 해석 순서로 확인.

HTML·SVG는 이미 생성되어 있어 보는 사람은 설치할 것이 없습니다. 파일을 수정하고 다시 만들 때만 아래 도구가 필요합니다.

[발표·블로그용 아키텍처 그림](architecture-figure.md): SVG·PNG·PDF와 원본, 아이콘 출처, 수정·내보내기 방법.

## 원본과 생성물

| 원본 | 생성물 | 변경할 때 |
|---|---|---|
| architecture.md | index.html | 목표·역할·구조·흐름·제약 변경 |
| backend.md, first-slice.md, status.md, contracts.md, review.md, adr/*.md | 같은 이름의 HTML | 작업 범위·상태·결정 기록 |
| public-demo.md, design/public-demo-plan.md, design/public-demo-review.md | 같은 이름의 HTML | 첫 공개 조회 데모의 범위·상세 설계·검토 |
| deployment-operations.md, local-https.md, design/local-https-plan.md | 같은 이름의 HTML | 배포 단계와 로컬 HTTPS 실습·검증 |
| deployment-readiness.md | deployment-readiness.html | O5 서버·예산·도메인·독립 저장소·키 보관 추천과 결정표 |
| ../tools/docs/build-overview.py + 보존한 아이콘 | diagrams/presentation-overview.svg | 발표용 개요 그림의 문구·좌표·색·화살표 변경. PNG/PDF도 별도 재출력 |
| diagrams/*.dot | diagrams/*.svg | C4 요소·통신·책임 변경 |

DOT는 Graphviz의 텍스트 그림 형식입니다. C4의 System/Container/Component 구분을 라벨과 경계로 표현했습니다. 그림은 소스에서 자동 배치되며, 손으로 위치를 다시 맞출 필요가 없습니다. C4는 특정 그리기 도구에 종속되지 않습니다.

## 다시 생성하기

Node.js 24 LTS와 Python 3를 사용합니다. 프로젝트 루트에서 실행합니다.

```sh
npm --prefix tools/docs ci
npm --prefix tools/docs run build
```

첫 명령은 잠금 파일에 고정된 문서 도구만 설치합니다. 실제 서비스 의존성에 추가되지 않습니다. 생성은 로컬에서 수행하며 외부 CDN·이미지 생성 API·별도 Graphviz 설치가 필요 없습니다. 생성된 SVG와 HTML도 함께 공유하거나 커밋합니다.

## 수정 순서

설명만 바꾸면 해당 Markdown을 수정합니다. 역할·통신·저장 경계가 달라지면 `adr/`에 이유와 영향을 적고 DOT·관련 설명·코드를 함께 수정합니다. wire 계약이나 상태 규칙 변경은 [설계 변경 절차](../CONTRIBUTING.md)를 따릅니다. v1.43 보존본을 임의로 고치지 않습니다.

마지막으로 `python3 tools/check_structure.py`를 실행해 상대 링크·원본 보존·공유본 구성을 확인합니다. 이 검사는 컴파일·MQTT·DB·실차 시험을 대신하지 않습니다.

[로컬 모니터링 실습](local-monitoring.md): Prometheus·Grafana 역할, 지표 해석, 가상 사건과 장애 확인.

백업과 독립 revision 기록의 차이, 복원 순서와 직접 실습은 [O4 로컬 백업·복원](local-backup.md)을 읽습니다.

실제 자원을 고르기 전에는 [O5 배포 준비](deployment-readiness.md)를 읽습니다. 과거 월 단위 비교와 현재 단기 계획·준비 항목을 구분했으며 외부 배포는 하지 않았습니다.

2026-11-17 승인한 단기 운영의 [백업·증거 보존·복구 목표와 단독 관리](backup-recovery-policy.md)를 함께 읽습니다. 목표 승인과 실제 외부 자동화·회수 시험 완료를 구분합니다.

운영 학습은 [O1 개념 안내](production-basics.md)부터 시작합니다. [학습·블로그 근거 모음](operations-learning-evidence.md)에 자기 설명 질문과 Git으로 보존한 로그·캡처·측정 조건이 있습니다. 두 문서의 같은 이름 HTML도 자동 생성합니다.

[4GB 사전 측정](preflight-4gb.md) · [팀 통합 조기 배포 준비](team-integration-preparation.md): 총 5만 원 방향과 로컬 측정, 외부 배포 전 남은 구현.
