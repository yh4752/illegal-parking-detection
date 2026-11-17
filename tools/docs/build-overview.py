"""Generate the presentation overview using vendored icons; no network access."""
from pathlib import Path
import re
import html

ROOT = Path(__file__).resolve().parents[2]
P = ROOT / 'docs' / 'diagrams'
ICONS = ROOT / 'tools' / 'docs' / 'assets' / 'simple-icons'
logos={'react':'61DAFB','springboot':'6DB33F','postgresql':'4169E1','nginx':'009639','docker':'2496ED','github':'24292F','githubactions':'2088FF','prometheus':'E6522C','grafana':'F46800','eclipsemosquitto':'3C5280'}
s=[]
def raw(t): s.append(t)
def rect(x,y,w,h,fill='white',stroke='#D9E3ED',r=18,dash=False):raw(f'<rect x="{x}" y="{y}" width="{w}" height="{h}" rx="{r}" fill="{fill}" stroke="{stroke}" stroke-width="1.6"'+(' stroke-dasharray="7 6"' if dash else '')+'/>')
def text(x,y,t,size=21,color='#334155',weight=400):raw(f'<text x="{x}" y="{y}" font-size="{size}" fill="{color}" font-weight="{weight}">{html.escape(t)}</text>')
def icon(k,x,y,size=44):
 a=(ICONS/f'{k}.svg').read_text(); paths=''.join(re.findall(r'<path[^>]*>',a));raw(f'<g transform="translate({x} {y}) scale({size/24})" fill="#{logos[k]}">{paths}</g>')
def badge(x,y,w,t,planned=False):
 rect(x,y,w,29,'#FFF2DF' if planned else '#EAF4F0','none',15);text(x+13,y+20,t,16,'#906021' if planned else '#35705A',500)
def line(d,dash=False,both=False):raw(f'<path d="{d}" fill="none" stroke="{"#A7AEB7" if dash else "#7B91AA"}" stroke-width="2.3" marker-end="url(#arrow)"'+(' stroke-dasharray="8 7"' if dash else '')+(' marker-start="url(#back)"' if both else '')+'/>')
def card(x,y,w,h,title,sub,logo=None,planned=False):
 rect(x,y,w,h,stroke='#DACDBA' if planned else '#D3E1EF',dash=planned)
 if logo: icon(logo,x+24,y+26);tx=x+84
 else:tx=x+24
 text(tx,y+48,title,27,'#233246',600);text(x+24,y+88,sub,19)
raw('<svg xmlns="http://www.w3.org/2000/svg" width="1920" height="1320" viewBox="0 0 1920 1320"><defs><marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10Z" fill="#7B91AA"/></marker><marker id="back" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M0 0L10 5L0 10Z" fill="#7B91AA"/></marker></defs>')
raw('<g font-family="Apple SD Gothic Neo, Noto Sans KR, sans-serif">')
rect(0,0,1920,1320,'#F3F6F9','none',0);rect(28,24,1864,1272,'#FFFFFF','#E0E7EF',28)
text(80,91,'불법주정차 감지·안내 시스템',43,'#233246',700)
text(82,130,'현장에서 판단하고 안내하며, 서버는 사건을 기록하고 관리자는 웹으로 확인합니다.',23)
badge(1515,64,315,'로컬 검증 완료 · 외부 배포 전',True)
raw('<path d="M80 163H1840" stroke="#E8EEF5" stroke-width="2"/>')
text(80,207,'01   제품 동작과 데이터 흐름',21,'#233246',600)
text(1480,207,'실선: 로컬 연결  /  점선: 후속 연동',18,'#63758B')
# Server boundary
rect(430,239,930,651,'#F6F9FD','#DEE8F2',22)
icon('docker',455,257,36);text(505,284,'Linux + Docker Compose',23,'#334155',600)
text(953,282,'현재는 로컬 검증 환경',18,'#63758B')
# Main cards
card(80,330,300,180,'가상 장치','사건 · 상태 · heartbeat')
text(104,454,'동일한 장치 메시지 계약',18,'#63758B');badge(104,470,150,'로컬 연결 검증')
card(470,330,280,180,'Mosquitto','MQTT 메시지 중계','eclipsemosquitto')
text(494,455,'TLS · 인증 · 토픽 권한',18,'#63758B')
card(880,330,330,180,'Spring Boot','Kotlin · 수신 검증 · 조회 API','springboot')
text(904,455,'중복·역순 처리 / 로그인·권한',18,'#63758B')
card(1480,330,360,180,'React + TypeScript','관리자의 브라우저에서 실행','react')
text(1504,455,'구역 현황 · 사건 목록과 상세',18,'#63758B')
card(80,650,300,180,'현장 보드 · AI','카메라 → 정차 판단',planned=True)
text(104,774,'LED·음성으로 이동 안내',19);badge(104,790,166,'실물 통합 미완료',True)
card(470,650,280,150,'Nginx','HTTPS · 정적 화면 전달','nginx')
text(494,775,'API / SSE 연결 중계',18,'#63758B')
card(880,650,330,150,'PostgreSQL','사건 · 상태 · 사용자 저장','postgresql')
text(904,775,'DB·관리 포트 외부 비공개',18,'#63758B')
# Main arrows
line('M380 395H470');text(384,378,'MQTT',17)
line('M750 395H880');text(773,378,'사건 수신',17)
line('M880 484H750');line('M610 510V542H230V510');text(443,567,'DB 커밋 후 저장 ACK · MQTT 경유',18,'#506982')
line('M1045 510V650',both=True);text(1061,588,'저장 / 조회',18)
line('M750 716H810V605H950V510',both=True);text(585,626,'조회 API · SSE',18)
line('M1480 410H1405V847H610V800',both=True);rect(1442,542,370,89,'#FFFFFF','none',10);text(1463,575,'HTTPS 로그인 · 상태 조회',20);text(1463,605,'SSE로 화면 자동 갱신',20)
line('M380 720H410V465H470',True);text(87,614,'실물도 같은 메시지 계약으로 연결',17,'#8B7453')
text(1480,726,'다음 제품 작업',20,'#233246',600)
text(1480,762,'관리 명령 · 정책 변경 · 사건 사진',19)
text(1480,793,'AI·실물 보드 연동과 실차 평가',19)
# Operational row
text(80,939,'02   개발·검증과 운영 지원',21,'#233246',600)
card(80,974,540,174,'GitHub · GitHub Actions','소스·변경 이력 / PR 자동 검증','github')
icon('githubactions',548,1002,40);text(104,1106,'개발 PC → Git push → CI 검사',20)
card(650,974,540,174,'Prometheus · Grafana','지표 수집 / 대시보드·경보','prometheus')
icon('grafana',1118,1002,40);text(674,1106,'수신 · DB 저장 · ACK · 연결 · 서버 상태',19)
card(1220,974,620,174,'향후 외부 배포','AWS Lightsail 4GB · 부하 시험 후 확정',planned=True)
text(1244,1106,'별도 AWS 백업 계정 / HTTPS 공개 인수 예정',19)
# footer
raw('<path d="M80 1182H1840" stroke="#E8EEF5" stroke-width="2"/>')
text(82,1219,'검증 범위',18,'#233246',600);text(208,1219,'가상 장치 → 저장 → 웹 조회 · 로컬 HTTPS · 모니터링 · 암호화 백업/복원',19)
text(82,1254,'현재 공개 배포·실물 AI 성능·LED/음성 출력은 미검증. revision·복구 내부 구조는 상세 문서를 참고합니다.',17,'#63758B')
text(1518,1254,'2026.11.17  ·  아키텍처 개요',16,'#63758B')
raw('</g></svg>');svg=''.join(s);(P/'presentation-overview.svg').write_text(svg)
