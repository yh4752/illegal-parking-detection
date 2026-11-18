# 30분 연속 로컬 자원 시험 — 완료

2026-11-18 · 실행 `c4207530` · 실제 4GB VM·2 vCPU·외부 배포 인수는 아님

[집계와 판정 조건](summary.json)의 `local_scenario_decision.gates` 11개를 모두 확인했다. 5개 로그인 세션이 정상10분·백업포함10분·회복10분 동안 구역 상태를 조회했다. 시간 단절 없이 끝났고, 정상/회복 구간은 모두 HTTP200이었다. 중단형 백업 때문에 발생한 122건의 실패를 제외하거나 정상 응답으로 바꾸지 않았다. 전체 요청은 8,970건이다.

- [원본 표본](observations.jsonl), [cgroup 메모리](cgroups.jsonl), [Grafana 질의·SSE](dashboard-queries.jsonl), [백업 중 Prometheus 값](monitoring-backup-window.json).
- [실행 환경·소스 해시](environment.json), [실제 메모리·스왑·포트 설정](effective-memory-limits.json), [백업 데이터 규모](backup-scope.json), [기존 환경 복구](preservation.json).
- [PNG 그래프](measurements.png), [SVG 그래프](measurements.svg), [그래프 생성 원본](render-chart.py). Matplotlib3.11.2로 `python3 render-chart.py <이 증거 폴더>`를 실행하면 다시 생성한다. 그래프는 30초 묶음 p95, 집계는 각10분 구간 p95이므로 숫자가 다를 수 있다.
- [운영 도구 테스트43개](unit-tests.txt). 실행 당시 소스는 `*.source.txt`에 보존했다. 텍스트 사본은 실행용 도구가 아니다.

사건 생성147건 = DB147건 = STORED ACK147건, 최종pending0이었다. 종료 확인용 사건1건이 포함된다. 백업 당시 사건50건을 포함한 public테이블20개의 행 수·정렬된 행 해시를 실제 격리 복원본과 비교했다. 백업은 약31.528초, 실패 시작~마지막 실패 후 성공 구간은 약25.606초였다. 전체 호스트 유실 RTO가 아니다.

메모리는 Docker 표시 합계 최고986.615MiB, 캐시 포함 cgroup 순차 합계 최고1,228.680MiB였다. 두 수치는 서로 다른 표본의 최고치다. OS·Docker·호스트 Python 등을 포함한 전체 서버 사용량이 아니다. 주 수집기의 실패33회·최대 표본 간격15.259초, 보조 수집기 개별 읽기 누락4건도 원본에 보존한다. 복구 도구의 관측 peak는268.629MiB였다. Grafana는512MiB 한도에 가까웠으므로 운영 여유도 다시 확인한다.

SSE 단발 확인61회는 모두 성공했지만 실제 백업 중단 구간을 놓쳤다. 이것으로 SSE 연결이 끊기지 않았다고 주장할 수 없다. 1초 간격 API 요청과15초 간격 Prometheus 관측을 함께 본다. 이미지 빌드와 초기 설치는 측정 구간 앞의 준비 단계다.

이전 [절전 포함 실행](../preflight-4gb-interrupted-2026-11-18/README.md), [자동 중단된 재시험](../preflight-4gb-retry-paused-2026-11-18/README.md), [당시 보류 판정](../preflight-4gb-2026-11-18/README.md)은 과거 근거로 그대로 보존한다. 사건 수와 성능 수치를 합산하거나 개선 배수로 비교하지 않는다.

기존 컨테이너78개(중지된 이전 시험 포함)의 ID·볼륨 연결·실행 상태가 일치했고 학습 컨테이너12개를 재시작했다. 새 시험 컨테이너는 중지했다. DB 바이트 동일성·독립 외부 사본 검증은 아니다. 실제 암호화 백업·키·계정 값은 비공개 로컬 상태에 남겼다. 서버 생성·구매·DNS 등록·외부 배포·원격 push는 수행하지 않았다.

[SHA-256 파일 목록](manifest.json)은 현재 로컬 사본의 무결성 확인용이다.
