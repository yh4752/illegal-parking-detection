import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../../..');
const state = process.env.PARKING_MONITORING_STATE || path.join(root, '.local-state/monitoring');
const release = JSON.parse(readFileSync(path.join(state, 'release.json'), 'utf8'));

test('Grafana: login, actual datasource, live counters and provisioned alert panel', async ({
  page,
}) => {
  const errors: string[] = [];
  const dataResponses: number[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  page.on('response', (response) => {
    if (response.url().includes('/api/ds/query')) dataResponses.push(response.status());
  });
  await page.goto('/login');
  await expect(page.locator('input[name="user"]')).toBeVisible({ timeout: 30000 });
  await page.locator('input[name="user"]').fill(release.monitoring.username);
  await page
    .locator('input[name="password"]')
    .fill(readFileSync(release.monitoring.password_file, 'utf8').trim());
  await page.getByRole('button', { name: /^Log in$/ }).click();
  await expect(page).not.toHaveURL(/\/login/);
  const dashboard = await page.request.get('/api/dashboards/uid/parking-operations');
  expect(dashboard.status()).toBe(200);
  const model = await dashboard.json();
  expect(model.dashboard.title).toBe('주차 시스템 · 로컬 운영 관찰');
  expect(model.dashboard.panels.length).toBe(21);
  async function query(expression: string) {
    const response = await page.request.get(
      '/api/datasources/proxy/uid/parking-prometheus/api/v1/query',
      { params: { query: expression } },
    );
    expect(response.status()).toBe(200);
    const data = await response.json();
    expect(data.status).toBe('success');
    return Number(data.data.result[0]?.value[1]);
  }
  expect(await query('up{job="parking"}')).toBe(1);
  expect(await query('count({__name__=~"hikaricp_connections_(active|pending)"})')).toBe(2);
  const output = execFileSync(
    'python3',
    ['tools/production/local_monitoring.py', '--state', state, 'simulate'],
    {
      cwd: root,
      encoding: 'utf8',
      timeout: 60000,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  expect(output).toContain('"status": "STORED"');
  await expect
    .poll(() => query('parking_ingest_total{outcome="stored"}'), { timeout: 35000 })
    .toBeGreaterThan(0);
  dataResponses.length = 0;
  await page.goto('/d/parking-operations');
  await expect(page.getByText('서버 지표 수집', { exact: true })).toBeVisible();
  await expect(page.getByText('사건 수신 결과 · 초당', { exact: true })).toBeVisible();
  await expect.poll(() => dataResponses.length, { timeout: 20000 }).toBeGreaterThan(0);
  await expect(page.getByText('정상', { exact: true })).toHaveCount(4, { timeout: 30000 });
  await expect(page.getByRole('button', { name: /^Cancel$/ })).not.toBeVisible({ timeout: 30000 });
  expect(dataResponses.every((status) => status === 200)).toBe(true);
  mkdirSync(path.join(root, 'output/playwright'), { recursive: true });
  await page.screenshot({ path: path.join(root, 'output/playwright/o3-grafana-overview.png') });
  const alerts = page.getByText('현재 발생한 경보 · 외부 전송 없음', { exact: true });
  await alerts.scrollIntoViewIfNeeded();
  await expect(alerts).toBeVisible();
  // The fault suite can leave several recent-failure warnings. Match actual firing
  // alert names instead of assuming one particular warning occupies the table.
  const alertResponse = await page.request.get(
    '/api/datasources/proxy/uid/parking-prometheus/api/v1/query',
    { params: { query: 'ALERTS{alertstate="firing"}' } },
  );
  expect(alertResponse.status()).toBe(200);
  const alertData = await alertResponse.json();
  const names: string[] = alertData.data.result.map(
    (row: { metric: { alertname: string } }) => row.metric.alertname,
  );
  expect(names.length).toBeGreaterThan(0);
  await expect
    .poll(
      async () => {
        for (const name of names) {
          if (await page.getByRole('gridcell', { name, exact: true }).isVisible()) return true;
        }
        return false;
      },
      { timeout: 30000 },
    )
    .toBe(true);
  await page.screenshot({ path: path.join(root, 'output/playwright/o3-grafana-alerts.png') });
  expect(errors).toEqual([]);
});
