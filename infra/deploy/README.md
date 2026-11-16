# 운영 배포 준비

현재는 **설계와 입력 양식만** 있다. 실행 가능한 운영 Compose·프로필·복원 CLI는 아직 없다. 로컬 실행은 `infra/handoff`와 [인계 설명서](../../docs/frontend-handoff.md)를 따른다.

- [배포 요약](../../docs/deployment-operations.md)
- [운영 구현 명세](../../docs/design/deployment-operations-plan.md)
- [구현 순서·완료 기준](../../docs/design/deployment-operations-workplan.json)
- [입력 양식](inputs.example.json): 비밀값 없이 채울 항목. `null`은 미확정이며 운영 기본값이 아니다.

개인 입력은 저장소 루트 `.local-state/deploy/inputs.json`으로 복사해 기록한다. 자격증명은 이 JSON에도 넣지 않는다. Compose·설정은 O1 이후 실제 코드 지원과 함께 작성하며 설정 파일 존재를 배포 성공으로 표시하지 않는다.
