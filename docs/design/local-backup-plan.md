# O4 로컬 백업·복원 구현 계획과 검증 범위

2026-11-16 · [승인된 O4 범위](deployment-operations-plan.md)의 실행 계획. 외부 배포·외부 공급자 선정은 제외한다.

| 단위 | 구현 | 검증 |
|---|---|---|
| 독립 예약 어댑터 | 별도 내부 HTTP 서비스, 명시 init, identity·토큰, SQLite append-only 버전/CAS/FULL 동기화 | 실제 HTTP 경쟁 CAS·인증·누락·변조·재시작·단조성 |
| 서버 예약 gate | 독립 기록을 먼저 확인·확정, 로컬 두 파일 후 DB, 불확실성 latch | DB-only/동시 롤백, remote/local/DB 사이 중단 상태로 재시작 |
| 복구 경계 | 일반 사건·중복 ACK·heartbeat·정리 작업 보류, 별도 오프라인 대조 | 정상/중복/실패 회귀와 hold 중 무변경 |
| 복구 CLI | 기존 자료 adopt, 백업 fence, prepare, reconcile, 보고서 해시 accept | 상한 건너뛰기·claim 보존·숫자 오류·대조 증거·stale 보고서 |
| 백업 CLI | 읽기 전용 dump, SQLite backup API, 암호화·키 분리, 격리 restore/전 테이블 비교 | 잘못된 키·변조·내용 보존·원본 DB 보존 |
| 관찰 | 검증 기록 기반 2개 지표·2개 경보·3개 Grafana 패널 | 실제 scrape·dashboard API·합성 경보 |
| 전체 인수 | 로컬 HTTPS·MQTT·DB·별도 기록으로 복원 훈련 | ACK 이후 손실, API/SSE 차단, 대조 후 명시 인수, DUPLICATE 재시도 |

예약은 **독립 CAS → 로컬 primary → 로컬 copy → DB**다. 독립 기록이 앞서고 로컬/DB가 뒤처지거나 두 파일이 다르면 기동/읽기/수신을 차단한다. 복구 시에는 세 저장소의 최대 상한 다음 범위를 예약한다. 자동 정합화·과거 버전 덮어쓰기·누락 기록 자동 생성은 하지 않는다.

O4 overlay에서 독립 기록을 필수로 한다. O1/O2/O3 기존 학습 환경은 이번 작업에서 변환하지 않는다. 외부 운영 환경은 현재 로컬 HTTP 어댑터를 그대로 사용하지 않으며 공급자 TLS·최신 읽기·CAS/버전 내구성 계약을 별도로 인수한다.

완료 여부는 [검증 기록](../../validation/local-backup-2026-11-16.json)과 [현재 상태](../status.md)를 따른다. 명령은 [사람용 설명서](../local-backup.md)에 있다. 각 시험의 정상 경로와 실패 경로 관측을 구분한다.

실행하지 않는 항목: 실제 서버 공개, 외부 object storage, OS/디스크 전체 유실, 외부 키 복구, 실제 장치 현장 대조, PITR/WAL, 대용량 복원·SLA, 자동 보존 삭제와 운영 스케줄. 백업 24시간/RTO 60분은 초기 목표이며 로컬 측정은 해당 시험 자료에만 적용한다.
