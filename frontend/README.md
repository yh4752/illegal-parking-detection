# 관리자 화면

실제 Spring API에 연결된 React + TypeScript + Vite 앱입니다. 로그인, 구역 상태, 사건 목록·상세, SSE와 재연결을 제공합니다.

**처음 받았다면 [프론트 인계 설명서](../docs/frontend-handoff.md)부터 읽으세요.** 별도 Docker 환경을 준비하며 기존 실습 DB를 변경하지 않습니다. 최신 검사 결과는 [검증 기록](../validation/frontend-handoff-2026-11-14.json)에 있습니다.

```sh
# 저장소 최상위: Docker 실행 필요
python3 tools/handoff.py prepare
python3 tools/handoff.py simulate

# frontend/: Node 24.13.0 (frontend/.nvmrc)
npm ci
npm run generate
PARKING_API_PROXY=http://127.0.0.1:18080 npm run dev
```

개발 주소: http://127.0.0.1:5173 · 인계 빌드 주소: http://127.0.0.1:18081

| 역할 | 위치 |
|---|---|
| 화면·스타일 | `src/features/*/*Page.tsx`, `src/components/`, `src/styles/` |
| 세션·HTTP·오류 | `src/features/auth/Auth.tsx`, `src/api/client.ts` |
| 최신 상태·재연결 | `src/features/zones/zoneStream.ts` |
| 생성 타입·검증기 | `src/api/generated/` — 직접 편집 금지 |

검사: `npm test`, `npm run build`. 깨끗한 별도 환경의 브라우저 검사: `npx playwright install chromium`, `npm run test:e2e`.

현재 코드의 공통 기준은 `main`입니다. 최신 main에서 본인 작업 브랜치를 만들고 main 대상 PR로 올립니다. 코딩 AI에는 [인계 컨텍스트](../docs/frontend-handoff-context.json)와 [시안 적용 기준](../docs/frontend-design-alignment.md)도 함께 읽게 합니다.

미구현 제어·사진·지도를 넣지 않습니다. [AGENTS.md](AGENTS.md)의 계약·상태 표시 규칙을 유지합니다. 프론트 담당자는 UI/사용성을 개선하고, 계약·인증·배포 변경은 백엔드 담당자와 협의합니다.
