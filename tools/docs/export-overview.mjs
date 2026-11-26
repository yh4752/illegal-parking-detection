// Optional raster/print exports. The regular docs build does not need a browser.
import { readFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import { createRequire } from 'node:module';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const require = createRequire(path.join(root, 'frontend/package.json'));
const { chromium } = require('playwright');
const base = path.join(root, 'docs/diagrams/presentation-overview');
const svg = await readFile(`${base}.svg`, 'utf8');
const [, svgWidth, svgHeight] = svg.match(/<svg[^>]*width="(\d+)"[^>]*height="(\d+)"/);
const width = Number(svgWidth), height = Number(svgHeight);
const browser = await chromium.launch({ headless: true });
try {
  const page = await browser.newPage({ viewport: { width, height }, deviceScaleFactor: 1 });
  await page.setContent(`<!doctype html><html lang="ko"><meta charset="utf-8"><style>html,body{margin:0}svg{display:block}@page{size:${width}px ${height}px;margin:0}</style>${svg}</html>`);
  await page.evaluate(() => document.fonts.ready);
  const clipped = await page.locator('svg text').evaluateAll((nodes, {width, height}) => nodes.filter(node => {
    const box = node.getBBox();
    return box.x < 0 || box.y < 0 || box.x + box.width > width || box.y + box.height > height;
  }).map(node => node.textContent), {width, height});
  if (clipped.length) throw new Error(`Text outside canvas: ${clipped.join(', ')}`);
  await page.screenshot({ path: `${base}.png`, fullPage: true });
  await page.pdf({ path: `${base}.pdf`, width: `${width}px`, height: `${height}px`, printBackground: true, preferCSSPageSize: true });
  console.log('Exported presentation-overview.png and presentation-overview.pdf; inspect layout before committing.');
} finally {
  await browser.close();
}
