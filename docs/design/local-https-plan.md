# O2 로컬 HTTPS 구현 계획

2026-11-16 · O1 구현 위에서 실행 · 외부 배포 없음

목표는 같은 출처의 HTTPS 화면/API/SSE를 실제로 검증하고, 운영자가 요청 경로와 실패 지점을 이해할 수 있게 만드는 것이다. 기존 제품 계약·화면 기능·DB 마이그레이션은 유지한다.

## 구현 결정

| 파일 | 책임 |
|---|---|
| `infra/deploy/compose.yaml` | 실행 이미지 고정, 파일 secrets, web/app/data/mqtt 네트워크, localhost 포트 |
| `infra/deploy/compose.local.yaml` | 빈 로컬 DB에만 가상 구역/장치를 등록하는 명시적 fixture 도구 |
| `infra/deploy/nginx/default.conf.template` | HTTPS, HTTP 전환, 정적 화면, API/SSE proxy, 관리 경로 차단, query 없는 접근 로그 |
| `infra/deploy/init-db.sql`, `initialize-local.sh`, `operator.sh` | 새 DB 역할 생성, 로컬 fixture, 별도 운영자 계정 관리 |
| `tools/production/local_https.py` | 로컬 준비/기동/중지/가상 송신/계정 관리/인증서 갱신. 기존 데이터 자동 초기화 금지 |
| `tools/production/verify_o2.py` | 임시 별도 환경에서 엄격한 TLS·HTTP·SSE·장애·보존 인수 |
| `frontend/playwright.https.config.ts`, `frontend/tests/https/https.spec.ts` | 기존 Playwright 기반의 실제 HTTPS 화면·재연결·로그아웃 검사 |
| `tools/production/open_https.mjs` | 시스템 신뢰 저장소를 바꾸지 않는 별도 학습용 Chromium 세션 |
| `docs/local-https.md` | 요청 흐름, 설정 이유, 직접 따라 하기, 오류별 확인 지점 |

Compose에는 build 대신 검증할 이미지 ID를 주입한다. 로컬 release manifest는 이미지 ID·원본 커밋·미커밋 소스 해시·설정 해시를 기록하고 실행 시 대조한다. 레지스트리 digest가 없는 로컬 이미지 ID를 배포용 레지스트리 digest로 표시하지 않는다. 외부 서버용 release publication은 후속 단계다.

Nginx만 localhost에 HTTPS/HTTP 포트를 연다. web/app 경로와 DB·MQTT 네트워크를 나누고 DB·MQTT는 내부 네트워크만 쓴다. HTTP는 고정 HTTPS 주소로 전환한다. 관리 경로는 SPA 화면으로 대체하지 않고 404를 반환한다. SSE의 proxy buffering을 끄고 backend가 재생성되어도 Docker DNS를 다시 조회한다.

로컬 CA는 `.local-state` 안에만 생성한다. 시스템·사용자 계정의 인증서 신뢰 설정은 변경하지 않는다. 엄격한 HTTPS 클라이언트로 CA/SAN을 검사하고, 별도 Chromium에는 해당 인증서 공개키만 허용한다. 공개키 허용 브라우저 시험을 공인 인증서 신뢰 검증으로 설명하지 않는다.

## 실행 순서와 완료 조건

1. Compose/Nginx와 파일 권한·일회성 도구 작성 → Compose 해석·Nginx 문법 확인.
2. 준비 도구와 이미지 manifest 작성 → 새 환경 준비, 반복 기동, 기존 데이터 보존과 미초기화 상태 거부.
3. 실제 HTTPS 인수 → CA/SAN 거부, 로그인/CSRF/Secure cookie, SSE, 계정 회수, 관리 포트 비공개.
4. 장애 인수 → backend 정지 시 502, 재생성 뒤 재로그인·이력 보존, 인증서 교체와 Nginx reload.
5. 브라우저 인수 → 로그인·가상 사건·SSE 단절/재접속·로그아웃과 화면 캡처.
6. 학습 안내·검증 기록·현재 상태·작업 JSON·생성 HTML 갱신 → 구조·링크·v1.43 보존 검사.

사용자의 기존 `parking-local`·`parking-handoff`는 사용하거나 정리하지 않는다. 자동 인수는 자신의 임시 컨테이너/볼륨만 제거한다. 학습용 환경의 중지는 데이터를 보존한다. 실제 외부 배포·DNS·ACME·모니터링·복원은 이번 범위가 아니다.
