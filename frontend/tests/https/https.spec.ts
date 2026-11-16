import { test, expect } from '@playwright/test';
import { readFileSync, mkdirSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';

const root = path.resolve(import.meta.dirname, '../../..');
const state = process.env.PARKING_HTTPS_STATE || path.join(root, '.local-state/https-demo');
const release = JSON.parse(readFileSync(path.join(state, 'release.json'), 'utf8'));

test('HTTPS: login, secure session, MQTT/SSE update, reconnect and logout', async ({ page }) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const entry = await page.goto('/');
  expect(entry?.status()).toBe(200);
  expect(entry?.headers()['content-security-policy']).toContain("default-src 'self'");
  expect((await entry?.securityDetails())?.protocol).toMatch(/TLS 1\.[23]/);
  await page.getByLabel('아이디').fill('https-viewer');
  await page.getByLabel('비밀번호').fill(readFileSync(release.password_file, 'utf8').trim());
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByRole('heading', { name: '구역 현황', exact: true })).toBeVisible();
  await expect(page.getByText('화면 연결 · 연결됨')).toBeVisible();
  const cookie = (await page.context().cookies()).find((c) => c.name === 'PARKING_HTTPS_SESSION');
  expect(cookie).toMatchObject({ secure: true, httpOnly: true, sameSite: 'Lax' });
  const output = execFileSync(
    'python3',
    ['tools/production/local_https.py', '--state', state, 'simulate'],
    {
      cwd: root,
      encoding: 'utf8',
      timeout: 60000,
      stdio: ['ignore', 'pipe', 'pipe'],
    },
  );
  const replay = output
    .split('\n')
    .filter((line) => line.startsWith('{'))
    .map((line) => JSON.parse(line))
    .find((line) => line.kind === 'VIRTUAL_REPLAY');
  expect(output).toContain('"status": "STORED"');
  await expect(page.getByRole('heading', { name: '이동 안내 완료', exact: true })).toBeVisible();
  await expect(page.getByRole('link', { name: `${replay.event_id} 사건 상세 보기` })).toBeVisible();
  mkdirSync(path.join(root, 'output/playwright'), { recursive: true });
  await page.screenshot({
    path: path.join(root, 'output/playwright/o2-https-zone.png'),
    fullPage: true,
  });
  await page.context().setOffline(true);
  await page.getByRole('button', { name: '새로고침', exact: false }).first().click();
  await expect(page.getByText('화면 연결 · 재연결 중')).toBeVisible();
  await page.context().setOffline(false);
  await expect(page.getByText('화면 연결 · 연결됨')).toBeVisible({ timeout: 35000 });
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible();
  expect((await page.context().cookies()).some((c) => c.name === 'PARKING_HTTPS_SESSION')).toBe(
    false,
  );
  expect(errors).toEqual([]);
});
