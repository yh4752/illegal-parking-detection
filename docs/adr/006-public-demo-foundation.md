# ADR-006 공개 데모의 계약·DB·신규 초기화 기반

2026-11-14 · M0 구현 · 기준 코드 6c6c172

## 원안과 작업 기준

공개 조회 데모의 구현 순서는 M0 계약/DB → M1 인증/조회 → M2 heartbeat/SSE → M3 React → M4 simulator/통합 → M5 운영/복구 → M6 외부 접속/인계입니다. 이 결정은 2026-11-13 대화에서 확정한 설계를 따릅니다.

원안 문서 커밋 `052fa95`는 Mac 로컬의 `docs/public-demo-plan`에만 있고 이번 Linux 작업 환경과 GitHub에는 없습니다. 이 브랜치는 GitHub에 있는 `6c6c172`의 전체 소스 235개를 파일 hash와 Git tree/commit hash로 검증해 복원한 뒤 시작했습니다. 미푸시 원안 문서를 복원했다고 주장하지 않습니다. 그 문서와 이번 구현은 나중에 병합해야 하며 ADR-005 번호를 재사용하지 않습니다.

## 채택한 구현

1. **REST 전용 overlay를 생성기로 적용합니다.** 원본을 보존하고 `contracts/overlays/public-demo-rest-v1.json` → `contracts/effective/`를 웹 기준으로 사용합니다. RATE_LIMITED/SERVICE_UNAVAILABLE/INTERNAL_ERROR만 RestError의 enum 분기에 추가합니다. MQTT 수신·ACK·성공 응답 구조는 유지합니다. 원안의 설계용 overlay와 구분되는 실행용 경로입니다.
2. **V3에 user_zone_access를 추가합니다.** user_id/zone_id 복합 PK와 FK로 구성하며 기본 권한을 넣지 않습니다. OPERATOR라고 모든 구역에 자동 접근하지 않습니다. 인증·권한 조회 서비스는 M1에서 붙입니다.
3. **V4에 내부 heartbeat 측정값을 추가합니다.** fps NUMERIC(유한한 0 이상), pending_message_count cap_seq, storage_ok BOOLEAN이며 모두 nullable입니다. 0/false는 실제 관측값이고 null은 미관측입니다. ZoneView에 새 필드를 넣지 않습니다.
4. **새 데모 DB만 초기화하는 별도 CLI를 둡니다.** 제품 HTTP/MQTT 구성은 불러오지 않습니다. 기본 실행은 DB 선행 검사이며 마이그레이션 자동 실행도 끕니다. `--apply`가 있을 때 manifest 예약·초기화 claim을 내구 저장한 뒤 recovery_hold 해제와 audit 기록을 한 트랜잭션에 반영합니다.

PostgreSQL NUMERIC은 NaN과 Infinity를 저장할 수 있으므로 `fps >= 0 AND fps < 'Infinity'::numeric`으로 비유한 값을 거부합니다. [공식 17 문서](https://www.postgresql.org/docs/17/datatype-numeric.html)의 비교 규칙을 확인했으며 실제 DB 경계 시험에 포함합니다.

## 초기화와 복원을 섞지 않습니다

초기화는 구역·장치가 하나인 데모 DB, 미관측 snapshot, 미사용 순번, 이력/최소 receipt/tombstone/audit 없음, 등록·정책·hash 일치 조건에서만 가능합니다. 이미 사용한 DB는 이 도구로 인수하지 않습니다. --force·데이터 삭제·hold 일괄 해제 옵션은 없습니다.

단순히 DB가 비었다는 사실만으로는 오래된 백업과 신규 DB를 구별할 수 없습니다. 그래서 기존 revision manifest의 구역 항목에 `demo_initialization_claimed_at`을 추가합니다. 두 사본에 claim을 먼저 저장하고 기존 파일 잠금과 구역 gate를 재사용합니다. DB를 초기 시점으로 되돌려도 최신 manifest가 남아 있다면 신규 초기화를 재사용할 수 없습니다.

claim 저장 후 DB 커밋 전에 중단되면 hold는 닫힌 상태로 남고, 다시 적용해도 중단합니다. claim을 지워 재시도하지 않고 후속 운영자 복구 절차로 넘깁니다. 이는 초기화 재사용 방어이며 **일반적인 같은 범위 DB 롤백 자동 탐지를 구현한 것은 아닙니다.** manifest 유실·파일/DB 불일치·기존 데이터의 운영 인수·새 revision 범위 건너뛰기는 D6의 별도 복구 작업입니다.

원안의 첫 인계판은 별도 가상 장치/DB를 사용하는 데모입니다. 사용자의 기존 Mac 실습 DB를 비우거나 초기화하지 않습니다. 현재 제품 API는 계속 차단돼 있고 CLI 성공도 웹 공개나 실보드 준비 완료를 뜻하지 않습니다.

## 검증과 인계

빈 DB 적용, V2 데이터 보존 업그레이드, 권한 기본 거부, FK/중복, nullable/0/false/NaN/Infinity/순번 경계, 검사 모드 무변경, 과거 이력 거부, audit 실패 롤백, 초기화 claim 재사용 거부를 확인합니다. 기존 사건 검증·ACK·중복·충돌·역순 회귀도 함께 실행합니다.

[M0 실행 안내](../demo-foundation.md)와 현재 검증 기록에서 작성·실행 범위를 구분합니다. 로그인·조회·heartbeat 수신·SSE·React는 이 단계의 구현 대상이 아닙니다.

2026-11-14 후속 기록: Mac 작업 환경에서 원안 `052fa95`를 가져와 M0와 통합했다. 당시 미푸시 원안의 병합 과제는 해소되었으며, 인증·조회는 ADR-005와 실행용 계약을 함께 따른다.
