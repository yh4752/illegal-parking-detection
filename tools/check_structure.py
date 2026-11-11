#!/usr/bin/env python3
"""문서·경로·원본 보존 검사. 서버·DB·MQTT·실차 시험을 수행하지 않는다."""
from pathlib import Path
from html.parser import HTMLParser
from urllib.parse import unquote, urlsplit
import hashlib
import json
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []
counts = {'markdown': 0, 'html': 0, 'svg': 0, 'local_links': 0, 'baseline_files': 0}

def check_link(source, target):
    url = urlsplit(target)
    if url.scheme or url.netloc or not url.path:
        return
    counts['local_links'] += 1
    p = (source.parent / unquote(url.path)).resolve()
    if not p.exists():
        errors.append(f'{source.relative_to(ROOT)}: 없는 링크 {target}')

class Links(HTMLParser):
    def __init__(self, source):
        super().__init__()
        self.source = source
        self.ids = set()
    def handle_starttag(self, tag, attrs):
        attrs = dict(attrs)
        for key in ('href', 'src'):
            if key in attrs:
                check_link(self.source, attrs[key])
        if tag in ('script', 'img', 'link') and any(urlsplit(attrs.get(k, '')).scheme in ('http','https') for k in ('src','href')):
            errors.append(f'{self.source.name}: 외부 실행/표시 리소스 의존')
        if 'id' in attrs:
            if attrs['id'] in self.ids:
                errors.append(f'{self.source.name}: 중복 HTML id {attrs["id"]}')
            self.ids.add(attrs['id'])

for source in ROOT.rglob('*'):
    rel = source.relative_to(ROOT)
    if not source.is_file() or any(x in rel.parts for x in ('reference','node_modules','.gradle','build','.git')):
        continue
    if source.suffix == '.md':
        counts['markdown'] += 1
        for match in re.finditer(r'!?\[[^\]]*\]\(([^\s)]+)\)', source.read_text()):
            check_link(source, match.group(1))
    elif source.suffix == '.html':
        counts['html'] += 1
        Links(source).feed(source.read_text())
    elif source.suffix == '.svg':
        counts['svg'] += 1
        ET.fromstring(source.read_text())

baseline = json.loads((ROOT/'reference/source.json').read_text())
for name, expected in baseline['sha256'].items():
    f = ROOT/'reference/v1.43'/name
    if not f.is_file() or hashlib.sha256(f.read_bytes()).hexdigest() != expected:
        errors.append(f'v1.43 원본 변경: {name}')
    counts['baseline_files'] += 1

for name in ('00-overview','01-context','02-containers','03-components'):
    for ext in ('dot','svg'):
        if not (ROOT/'docs/diagrams'/f'{name}.{ext}').is_file():
            errors.append(f'C4 원본/생성물 누락: {name}.{ext}')

print(json.dumps({'status':'FAIL' if errors else 'PASS','scope':'문서 구조·링크·SVG·v1.43 원본 보존','counts':counts,'errors':errors},ensure_ascii=False,indent=2))
sys.exit(1 if errors else 0)
