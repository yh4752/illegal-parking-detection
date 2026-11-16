import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { createHash, X509Certificate } from 'node:crypto';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '..');
const state = process.env.PARKING_HTTPS_STATE || path.join(root, '.local-state/https-demo');
const release = JSON.parse(readFileSync(path.join(state, 'release.json'), 'utf8'));
const certificate = new X509Certificate(readFileSync(path.join(state, 'web-tls/server.crt')));
const pin = createHash('sha256')
  .update(certificate.publicKey.export({ format: 'der', type: 'spki' }))
  .digest('base64');

export default defineConfig({
  testDir: './tests/https',
  workers: 1,
  retries: 0,
  timeout: 90000,
  reporter: 'list',
  outputDir: '../output/playwright/https',
  use: {
    baseURL: release.url,
    ignoreHTTPSErrors: false,
    // Only this temporary certificate's public key is accepted in this browser.
    // verify_o2.py separately verifies the CA chain and rejects a mismatched SAN.
    launchOptions: { args: [`--ignore-certificate-errors-spki-list=${pin}`] },
    screenshot: 'only-on-failure',
    trace: 'off', // Do not persist login bodies/session tokens in traces.
  },
  projects: [{ name: 'https-chromium', use: { ...devices['Desktop Chrome'] } }],
});
