import { readFile, writeFile, readdir } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import { instance } from '@viz-js/viz';
import { marked } from 'marked';

const root = fileURLToPath(new URL('../../', import.meta.url));
const docs = path.join(root, 'docs');
const viz = await instance();
const diagramDir = path.join(docs, 'diagrams');
for (const file of (await readdir(diagramDir)).filter(f => f.endsWith('.dot')).sort()) {
  const dot = await readFile(path.join(diagramDir, file), 'utf8');
  const svg = viz.renderString(dot, { format: 'svg', engine: 'dot' });
  await writeFile(path.join(diagramDir, file.replace(/\.dot$/, '.svg')), svg);
}

// Markdown is trusted project documentation, not untrusted user input.
const pages = new Map([
  ['architecture.md', 'index.html'],
  ['backend.md', 'backend.html'],
  ['review.md', 'review.html'],
  ['first-slice.md', 'first-slice.html'],
  ['contracts.md', 'contracts.html'],
  ['status.md', 'status.html'],
  ['adr/001-start-small.md', 'adr/001-start-small.html'],
  ['adr/002-repository-onboarding.md', 'adr/002-repository-onboarding.html']
]);
const css = `
*{box-sizing:border-box}html{scroll-behavior:smooth}body{margin:0;background:#f4f6f8;color:#203546;font:16px/1.8 -apple-system,BlinkMacSystemFont,"Apple SD Gothic Neo","Malgun Gothic",sans-serif}
header{background:#17374b;color:white;padding:35px max(24px,calc((100vw - 1120px)/2)) 30px}header .eyebrow{color:#a5d8e5;font-size:12px;font-weight:700;letter-spacing:.08em}header h1{font-size:32px;line-height:1.4;margin:8px 0 12px}header p{color:#d1e2eb;margin:6px 0}.badge{display:inline-block;border:1px solid #7595a7;padding:3px 12px;border-radius:20px;font-size:12px;margin-top:8px}
nav{max-width:1168px;margin:auto;display:flex;flex-wrap:wrap;gap:9px;padding:20px 24px 0}nav a,nav button{font:inherit;font-size:14px;color:#254e66;border:1px solid #c7d6df;border-radius:7px;background:white;padding:8px 14px;text-decoration:none;cursor:pointer}nav a.active{background:#276880;color:white;border-color:#276880}
main{max-width:1168px;margin:22px auto;padding:0 24px 40px}.intro{background:#e8f1f6;border-left:4px solid #40839c;padding:18px 24px;margin:18px 0 24px;border-radius:7px}.intro p{margin:5px 0}.intro strong{color:#1e526e}.page{background:white;border:1px solid #dce5eb;border-radius:12px;padding:32px 40px}h1{font-size:28px;line-height:1.4}h2{font-size:23px;line-height:1.5;border-top:1px solid #e1e8ed;padding-top:27px;margin-top:35px}h3{font-size:18px}p,li{word-break:keep-all;overflow-wrap:anywhere}li{margin:7px 0}a{color:#236b91;text-underline-offset:3px}strong{font-weight:700}
.figure{margin:24px 0;background:#f8fafc;padding:18px;border:1px solid #e1e9ef;border-radius:8px}.figure img{display:block;width:100%;height:auto}.figure a{font-size:13px}.table-scroll{overflow-x:auto;margin:22px 0}table{width:100%;border-collapse:collapse;font-size:14px;line-height:1.65}th{text-align:left;background:#eaf1f5;color:#244c63}td,th{padding:12px 14px;border-bottom:1px solid #dce5ec;vertical-align:top}td:first-child{font-weight:600}td{min-width:110px}code{font:13px/1.7 ui-monospace,SFMono-Regular,Consolas,monospace;background:#eef3f6;border-radius:4px;padding:2px 5px;overflow-wrap:anywhere}pre{background:#edf3f7;border:1px solid #dbe6ee;border-radius:8px;padding:18px;overflow-x:auto}pre code{padding:0;background:transparent}footer{max-width:1168px;margin:auto;padding:0 24px 28px;color:#6f8391;font-size:12px}
@media(max-width:700px){header{padding:25px 20px}header h1{font-size:26px}main{padding:0 12px 25px}.page{padding:18px}h1{font-size:24px}h2{font-size:21px}nav{padding:16px 12px 0}.figure{padding:6px}.figure img{min-width:680px}.figure{overflow-x:auto}td,th{padding:9px}}
@media print{body{background:white;font-size:11pt}header{background:white;color:#17374b;padding:0}header p,header .eyebrow{color:#436477}header h1{font-size:21pt}nav,.intro,footer{display:none}main{padding:0;margin:0;max-width:none}.page{border:0;padding:0}.page>h1{font-size:18pt}h2{break-after:avoid;font-size:15pt}tr,.figure{break-inside:avoid}.figure{padding:3px;border:0;background:white}.figure img{min-width:0}.figure a{display:none}table{font-size:9pt}a{color:inherit}}
`;

for (const [source, target] of pages) {
  const sourceDir = path.dirname(source);
  let html = marked.parse(await readFile(path.join(docs, source), 'utf8'), { gfm: true });
  html = html.replace(/href="([^"#]+\.md)(#[^"]*)?"/g, (match, link, fragment = '') => {
    if (/^https?:/.test(link)) return match;
    const key = path.posix.normalize(path.posix.join(sourceDir, link));
    if (!pages.has(key)) return match;
    return `href="${path.posix.relative(path.dirname(target), pages.get(key))}${fragment}"`;
  });
  html = html.replace(/<p><img src="([^"]+)" alt="([^"]*)"\s*\/?><\/p>/g,
    (_, src, alt) => `<figure class="figure"><img src="${src}" alt="${alt}"><a href="${src}" target="_blank" rel="noopener">그림을 크게 열기 ↗</a></figure>`);
  html = html.replaceAll('<table>', '<div class="table-scroll"><table>').replaceAll('</table>', '</table></div>');
  const prefix = target.startsWith('adr/') ? '../' : '';
  const nav = [['index.html','팀 요약'],['backend.html','서버 구조'],['first-slice.html','첫 구현'],['status.html','현재 상태'],['review.html','검토 결과']]
    .map(([f,label])=>`<a${f===target?' class="active"':''} href="${prefix}${f}">${label}</a>`).join('');
  const doc = `<!doctype html><html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1"><title>주정차 안내 시스템 · 구조와 개발 시작</title><style>${css}</style></head><body>
<header><div class="eyebrow">CAPSTONE / ARCHITECTURE START</div><h1>보드는 판단하고,<br>서버는 기록하고 연결합니다.</h1><p>지금 필요한 구조·역할·첫 개발 흐름</p><span class="badge">제품 기준 v1.43 · 시작 자료 v0.2 · 업무 기능 미구현</span></header>
<nav aria-label="문서 메뉴">${nav}<button type="button" id="print">인쇄</button></nav>
<main><aside class="intro"><p><strong>팀원은 팀 요약, 백엔드 담당은 서버 구조와 첫 구현을 보세요.</strong></p><p>그림의 연결선은 목표 설계입니다. 현재는 기동 코드·설정·문서만 작성했고 MQTT 수신·DB 저장·조회 기능은 다음에 구현합니다.</p></aside><article class="page">${html}</article></main>
<footer>Markdown와 DOT에서 생성한 공유본 · 외부 접속 없이 읽기 가능 · 수정은 원본에서 진행합니다.</footer><script>document.getElementById('print').addEventListener('click',()=>window.print());</script></body></html>`;
  await writeFile(path.join(docs, target), doc);
}
console.log(`C4 SVG 3개와 HTML ${pages.size}개 생성 완료. 제품 계약은 수정하지 않았습니다.`);
