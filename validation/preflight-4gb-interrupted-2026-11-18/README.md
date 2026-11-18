# 절전이 포함된 첫 4GB 예산 시험 — 연속 성능 통과 아님

2026-11-18 · 실행 `2eb7351e` · LOCAL_ONLY

측정용 시계는 약 30분 12초를 기록했지만 실제 시계 구간은 약 37분 44초였다. 두 시계의 차이는 최대 452.69초였고, Mac 전원 기록에서 덮개 닫힘에 따른 잠자기와 이후 유지보수 잠자기·깨우기를 확인했다. 따라서 이 실행의 전체 응답 수치를 30분 연속 성능 결과로 채택하지 않는다.

- [집계](summary.json): `valid_continuous_timing=false`. 전체 8,935건 중 백업·재로그인 구간 실패 168건. 성공 p95만으로 가용성을 주장하지 않음.
- [원본 표본](observations.jsonl), [cgroup 표본](cgroups.jsonl), [대시보드·SSE](dashboard-queries.jsonl), [절전 시각](host-sleep-events.json)을 보존한다. 대시보드의 `final=true` 1회는 측정 종료 후 정리 구간으로 원본에는 남기고 집계에서 제외했다.
- 기능 관찰: 생성 142건 = DB 142건 = STORED ACK 142건, pending 0. 백업은 첫 잠자기 이전에 완료됐고 49건 사건을 포함한 public 테이블 20개의 격리 복원 내용 비교를 통과했다. 약 40.74초의 백업 작업·34.88초의 실패~재개 구간을 관측했다. 이 역시 실제 클라우드 RTO 인수는 아니다.
- [기존 환경 대조](preservation.json): 기존 컨테이너 45개의 ID·볼륨 연결·실행 상태 일치, 학습 컨테이너 12개 재시작. DB 바이트 동일성 시험은 아니다. 이후 재시험을 위해 같은 학습 컨테이너 12개를 다시 임시 중지했다.
- [초기 문제](startup-notes.json), [384MiB 조사](grafana-384m.json), [512MiB 상세](grafana-512m-detail.json), [통계 EOF 재현](sampler-race-diagnosis.json), [빈 표본 처리 확인](sampler-resilience-check.json)을 보존한다. EOF의 직접 원인은 확정하지 않는다.
- [실행 원본](executed-runner-source.txt)과 [메모리 오버레이](executed-memory-overlay.yaml)는 당시 자료다. 실행용 도구는 `tools/production`의 최신 파일이며 이 텍스트 사본을 실행하지 않는다. 이후 명시적 컨테이너 재시작 입력·통계 부분 행 보존·시간 단절 거부를 보강했다.

실제 암호화 DB 백업·인증서 개인키·계정 정보는 비공개 로컬 상태에만 보존했다. 이 공개 증거 폴더를 키·DB 복원본이라고 부르지 않는다. 독립 외부 사본의 회수 검증도 수행하지 않았다.

[SHA-256 파일 목록](manifest.json)은 현재 로컬 사본의 무결성 확인용이며 독립 외부 사본 검증 완료를 뜻하지 않는다.
