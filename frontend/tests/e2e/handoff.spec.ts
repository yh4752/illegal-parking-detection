import { test, expect } from '@playwright/test';
import { readFileSync } from 'node:fs';
import { execFileSync } from 'node:child_process';
import path from 'node:path';
const root = path.resolve(import.meta.dirname, '../../..');
const password = () =>
  readFileSync(path.join(root, '.local-state/handoff/web-password'), 'utf8').trim();
const demo = (command: string) =>
  execFileSync('python3', ['tools/handoff.py', command], {
    cwd: root,
    stdio: 'pipe',
    timeout: 60000,
  });
test('fresh clone: real login, MQTT persistence, SSE, history, reconnect, offline, mobile and logout', async ({
  page,
}, testInfo) => {
  const errors: string[] = [];
  page.on('pageerror', (error) => errors.push(error.message));
  const entry = await page.goto('/');
  expect(entry?.headers()['content-security-policy']).toContain("default-src 'self'");
  expect(entry?.headers()['x-frame-options']).toBe('DENY');
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible();
  await page.getByLabel('아이디').fill('handoff-viewer');
  await page.getByLabel('비밀번호').fill('wrong-password-value');
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByLabel('비밀번호').fill(password());
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByRole('heading', { name: '구역 현황', exact: true })).toBeVisible();
  await expect(page.getByRole('heading', { name: '첫 관측을 기다립니다' })).toBeVisible();
  await expect(page.getByText('화면 연결 · 연결됨')).toBeVisible();
  await page.getByRole('link', { name: '사건 이력', exact: true }).click();
  await expect(page.getByText('표시할 사건이 없습니다')).toBeVisible();
  await page.getByRole('link', { name: '구역 현황', exact: true }).click();
  demo('simulate');
  await expect(page.getByText('장치 연결됨', { exact: true })).toBeVisible({ timeout: 30000 });
  await expect(page.getByRole('heading', { name: '이동 안내 완료', exact: true })).toBeVisible();
  const actual = await page.request.get('/api/zones/zone-01/status');
  expect(actual.status()).toBe(200);
  const zone = await actual.json();
  const eventId = zone.snapshot.current_event_id;
  expect(zone.snapshot.device_seq).toBeGreaterThan(0);
  expect(zone.connection_state).toBe('ONLINE');
  await expect(page.getByRole('link', { name: `${eventId} 사건 상세 보기` })).toBeVisible();
  await page.screenshot({ path: testInfo.outputPath('zone-desktop.png'), fullPage: true });
  await page.getByRole('link', { name: '사건 자세히 보기' }).click();
  await expect(page.getByRole('heading', { name: '사건 상세' })).toBeVisible();
  await expect(page.getByText(eventId, { exact: true })).toBeVisible();
  const detail = await (await page.request.get(`/api/events/${eventId}`)).json();
  expect(detail.messages.length).toBeGreaterThan(0);
  expect(detail.messages[0].event_id).toBe(eventId);
  await page.getByText('수신 내용', { exact: true }).first().click();
  await expect(page.locator('pre').first()).toContainText('"schema_version": 2');
  await page.getByRole('link', { name: '사건 목록', exact: false }).click();
  await page.getByLabel('사건 상태').selectOption('CLOSED_EXIT');
  await page.getByRole('button', { name: '조회', exact: true }).click();
  await expect(page.getByText('표시할 사건이 없습니다')).toBeVisible();
  await page.getByLabel('사건 상태').selectOption('GUIDED');
  await page.getByRole('button', { name: '조회', exact: true }).click();
  await expect(page.getByRole('link', { name: `${eventId} 사건 상세 보기` })).toBeVisible();
  await page.getByRole('link', { name: '구역 현황', exact: true }).click();
  await expect(page.getByText('화면 연결 · 연결됨')).toBeVisible();
  await page.context().setOffline(true);
  await page.getByRole('button', { name: '새로고침', exact: false }).first().click();
  await expect(page.getByText('화면 연결 · 재연결 중')).toBeVisible();
  await page.context().setOffline(false);
  await expect(page.getByText('화면 연결 · 연결됨')).toBeVisible({ timeout: 35000 });
  demo('pause-simulator');
  await expect(page.getByText('장치 응답 지연', { exact: true })).toBeVisible({ timeout: 25000 });
  await expect(page.getByText('장치 연결 미확인', { exact: true })).toBeVisible({ timeout: 20000 });
  await expect(page.getByRole('heading', { name: '이동 안내 완료', exact: true })).toBeVisible();
  await page.setViewportSize({ width: 390, height: 844 });
  await page.screenshot({ path: testInfo.outputPath('zone-mobile.png'), fullPage: true });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(
    true,
  );
  await page.getByRole('button', { name: '로그아웃' }).click();
  await expect(page.getByRole('heading', { name: '로그인', exact: true })).toBeVisible();
  expect((await page.context().cookies()).some((c) => c.name === 'PARKING_HANDOFF_SESSION')).toBe(
    false,
  );
  expect((await page.request.get('/api/auth/me')).status()).toBe(401);
  expect(errors).toEqual([]);
  await page.getByLabel('아이디').fill('handoff-viewer');
  await page.getByLabel('비밀번호').fill(password());
  await page.getByRole('button', { name: '로그인', exact: true }).click();
  await expect(page.getByRole('heading', { name: '구역 현황', exact: true })).toBeVisible();
  await page.goto('/zones/not-granted');
  await expect(page.getByRole('alert').first()).toBeVisible();
  await expect(page.getByText('화면 연결 · 접근 제한')).toBeVisible();
});
