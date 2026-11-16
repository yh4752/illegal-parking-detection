import { defineConfig, devices } from '@playwright/test';
import { readFileSync } from 'node:fs';
import path from 'node:path';

const state =
  process.env.PARKING_MONITORING_STATE ||
  path.resolve(import.meta.dirname, '../.local-state/monitoring');
const release = JSON.parse(readFileSync(path.join(state, 'release.json'), 'utf8'));

export default defineConfig({
  testDir: './tests/monitoring',
  workers: 1,
  retries: 0,
  timeout: 120000,
  reporter: 'list',
  outputDir: '../output/playwright/monitoring',
  use: {
    baseURL: release.monitoring.grafana_url,
    viewport: { width: 1600, height: 1000 },
    trace: 'off',
    screenshot: 'only-on-failure',
  },
  projects: [
    {
      name: 'grafana-chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1600, height: 1000 } },
    },
  ],
});
