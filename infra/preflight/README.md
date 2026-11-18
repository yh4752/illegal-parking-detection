# 로컬 4GB 예산 사전 시험

이 오버레이는 **실제 4GB VM을 만들지 않는다.** 공유 Docker 설정은 바꾸지 않고 서비스별 메모리/스왑 상한을 적용한다. CPU 2개 제한도 아니다. [결과·측정 한계](../../docs/preflight-4gb.md)를 먼저 읽는다.

저장소 루트에서 Python 가상 환경에 `tools/production/requirements-backup.txt`를 설치한 뒤 실행한다. Docker와 로컬 O2~O4 이미지 빌드 환경이 필요하다.

```sh
python3 tools/production/preflight_4gb.py
```

기본 측정은 준비 시간과 별도로 30분이다. 실제 시계와 측정용 시계가 2초 이상 어긋나면 절전/시계 단절로 실패 처리한다. Mac에서는 실행 명령 앞에 `caffeinate -i`를 붙여 작업 중 자동 유휴 절전을 막을 수 있지만, 덮개를 닫는 잠자기는 막지 못한다. 덮개를 열고 깨어 있는 상태를 약 35분 이상 유지해야 한다. `--minutes 3`은 도구 확인용 짧은 실행이며 30분 인수를 대신하지 않는다. 새 `.local-state/preflight-<id>`와 별도 Compose 프로젝트·볼륨·키·포트를 만든다. 공유 Docker의 기존 네트워크와 겹치지 않는 주소 범위를 고르지만 호스트/VPN 라우팅은 별도로 확인해야 한다. 이번 실행에서는 호스트 라우팅도 확인했다. 외부 주소 등록·포트 공개·클라우드 작업은 하지 않는다.

종료 시 시험 프로젝트만 중지하고 볼륨·암호화 백업·개인키를 비공개 로컬 상태에 보존한다. 반복 실행은 디스크를 사용하므로 남은 공간을 확인한다. 도구는 기존 컨테이너를 자동 중지하지 않는다. 기존 학습 환경을 별도로 중지한 경우에만, 사전에 저장한 실행 중 컨테이너 ID JSON 배열을 `--restore-containers-list`로 명시해 종료 정리 단계에서 재시작을 시도할 수 있다. 공유 작업자의 환경을 임의로 중지하지 않는다.

`PREFLIGHT_MEASURING` 이후 출력된 실행 폴더를 인자로 보조 관측을 별도 실행할 수 있다. cgroup 수치는 캐시 포함 사용량, 대시보드 관측은 Grafana를 통한 실제 Prometheus 질의와 SSE 초기 상태 확인이다. 시작/종료 범위가 주 시험과 다르면 결과에 표시한다.

```sh
python3 tools/production/observe_preflight_cgroups.py .local-state/preflight-<id>
python3 tools/production/observe_preflight_dashboard.py .local-state/preflight-<id>
```

측정 종료 후 공개 가능한 필드만 새 증거 폴더로 추출한다. 대상 폴더가 이미 있으면 덮어쓰지 않는다. release·암호화 키·비밀번호·인증 쿠키·복원 원본은 Git에 넣지 않는다. 보조 관측 파일도 내용을 검토한 뒤 공유한다.

```sh
python3 tools/production/summarize_preflight.py .local-state/preflight-<id>/measurements.jsonl validation/preflight-<new-run>
python3 -m unittest discover -s tools/production -p test_preflight_summary.py -v
```

성능 그래프는 연속 시간 검증을 통과한 실행에서만 만든다. 앞선 두 실행은 절전으로 중단돼 성능 통과 그림을 만들지 않았다. 사용자 재개 후 새 실행에서는 30분 연속 시나리오를 완료하고 [그래프·원본·판정](../../validation/preflight-4gb-continuous-2026-11-18/README.md)을 보존했다. 실제 4GB 호스트 인수와는 구분한다. 성공 p95만 보고 실패 요청을 숨기지 않고, 서로 다른 시점의 서비스별 최고 메모리를 합산하지 않는다. 최종 DB 행·응용 ACK·pending·백업 검증·기존 환경 재시작은 보고서에서 별도로 판정한다. 도구 종료 코드만으로 외부 배포를 승인하지 않는다.
