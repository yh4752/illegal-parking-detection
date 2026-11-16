// A disposable practice browser. No system/user trust store changes.
import { readFileSync } from 'node:fs';
import { createHash, X509Certificate } from 'node:crypto';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../../', import.meta.url));
const state = process.argv[2] === '--state'
  ? path.resolve(process.argv[3] || '')
  : path.join(root, '.local-state/https-demo');
if (!state.startsWith(path.join(root, '.local-state') + path.sep)) {
  throw new Error('State must be inside this repository local state directory');
}
const release = JSON.parse(readFileSync(path.join(state, 'release.json'), 'utf8'));
if (release.scope !== 'LOCAL_ONLY' || !/^https:\/\/localhost:\d+$/.test(release.url)) {
  throw new Error('Only the prepared localhost environment is allowed');
}
const certificate = new X509Certificate(readFileSync(path.join(state, 'web-tls/server.crt')));
const pin = createHash('sha256').update(certificate.publicKey.export({ format: 'der', type: 'spki' })).digest('base64');
const { chromium } = createRequire(path.join(root, 'frontend/package.json'))('@playwright/test');
const browser = await chromium.launch({ headless: false, args: [`--ignore-certificate-errors-spki-list=${pin}`] });
const context = await browser.newContext({ ignoreHTTPSErrors: false });
const page = await context.newPage();
await page.goto(release.url);
console.log('Opened isolated local practice browser. Username: ' + release.username);
console.log('Password file: ' + path.relative(root, release.password_file));
console.log('This browser accepts only the test certificate public key; the operating system trust store is unchanged.');
await new Promise((resolve) => browser.on('disconnected', resolve));
