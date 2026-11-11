# 현재 구현 범위

2026-11-11 · 이 파일이 현재 개발 저장소의 현황입니다. reference/v1.43의 상태표는 설계 작성 당시 기록입니다.

개인 비공개 저장소 [yh4752/parking-system](https://github.com/yh4752/parking-system)에 코드·설계·협업 지침을 올렸습니다. 팀원 초대와 브랜치 보호는 별도 설정입니다.

| 항목 | 작성 여부 | 실행 확인 |
|---|---|---|
| README·협업 절차·AI 지침·C4·첫 작업 안내 | 작성·독립 검토 반영 | 새 클론에서 문서 생성·링크·SVG·원본 보존 검사 통과 |
| Spring Boot 엔트리포인트·설정 | 작성 | GitHub CI의 test·bootJar 통과. 로컬 bootRun의 실제 HTTP 접속은 별도 미수행 |
| 기동 확인만 공개하는 접근 제한 | 작성 | 애플리케이션 컨텍스트를 사용하는 MockMvc 테스트 통과: health 공개, 제품·관리 상세 경로 차단 |
| Gradle Wrapper·버전 고정 | 작성 | Kotlin 2.1.21·Gradle 8.12.1로 수정 후 CI 다운로드·컴파일·패키징 통과 |
| PostgreSQL·Mosquitto Compose | 작성 | 로컬·CI 설정 검사 통과. 컨테이너 기동·DB 연결은 미수행 |
| PR 양식·GitHub Actions | 작성 | 개인 저장소 생성·push 완료. 문서/설정·백엔드 두 작업 통과 |
| MQTT 수신·DB 저장·제품 조회 API | 미구현 | 미수행 |
| 가상 장치 실행 코드 | 미구현 | 미수행 |
| React·SSE·명령·사진·로그인 | 미구현 | 미수행 |
| 장치 SDK·실차 시험 | 미구현/미수행 | 언어·장소·카메라 등 확인 필요 |

## 확인 근거

- [성공한 CI](https://github.com/yh4752/parking-system/actions/runs/42995478170): 커밋 `b22f3b88fb92f2be6aba02c93b010ac9aae1a1dd`, JDK 17, `./gradlew test bootJar --no-daemon`와 문서·Compose 검사.
- [이번 검증 기록](../validation/onboarding-2026-11-11.json): 새 클론 재현, 독립 검토, 첫 CI 실패 원인과 수정, 미검증 범위.
- [첫 CI 실패와 버전 수정 이유](adr/001-start-small.md): Kotlin 컴파일러·라이브러리 메타데이터 충돌을 수정했다. 이전 validation/report.json·bootstrap-v0.1.json은 당시 기록으로 보존한다.

기동 테스트 통과는 제품 기능 완성을 뜻하지 않습니다. 다음 구현은 [첫 기능 작업표](first-slice.md)의 가상 사건 수신 → 저장 → 조회입니다.
