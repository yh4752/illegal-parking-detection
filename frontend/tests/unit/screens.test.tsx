import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Routes, Route, Link } from 'react-router-dom';
import { it, expect, vi } from 'vitest';
import { AuthProvider } from '../../src/features/auth/Auth';
import { LoginPage } from '../../src/features/auth/LoginPage';
import { EventTable } from '../../src/features/events/EventTable';
import { EventsPage, seoulFilter } from '../../src/features/events/EventsPage';
import { api, ApiError } from '../../src/api/client';
it('prevents login racing the initial session check and offers labeled keyboard inputs', async () => {
  let reject!: (e: unknown) => void;
  vi.spyOn(api, 'me').mockReturnValue(
    new Promise((_, r) => {
      reject = r;
    }),
  );
  const login = vi.spyOn(api, 'login');
  render(
    <MemoryRouter>
      <AuthProvider>
        <LoginPage />
      </AuthProvider>
    </MemoryRouter>,
  );
  fireEvent.change(screen.getByLabelText('아이디'), { target: { value: 'viewer' } });
  fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'secret' } });
  expect(screen.getByRole('button', { name: '계정 확인 중…' })).toBeDisabled();
  expect(login).not.toHaveBeenCalled();
  reject(new ApiError(401, 'UNAUTHENTICATED', '로그인'));
  await waitFor(() => expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled());
});
it('distinguishes an empty history from a processing error', () => {
  render(
    <MemoryRouter>
      <EventTable items={[]} />
    </MemoryRouter>,
  );
  expect(screen.getByText('표시할 사건이 없습니다')).toBeVisible();
  expect(screen.queryByRole('alert')).toBeNull();
});
it('uses KST for date filters independently of the browser timezone', () => {
  expect(seoulFilter('2026-11-14T09:30')).toBe('2026-11-14T00:30:00.000Z');
});

it('changing zones discards the old signed cursor and filters', async () => {
  const events = vi.spyOn(api, 'events').mockImplementation(async (query) => ({
    items: [],
    next_cursor:
      query.get('zone_id') === 'zone-01' && !query.has('cursor') ? 'signed-for-zone-one' : null,
  }));
  render(
    <MemoryRouter initialEntries={['/zones/zone-01/events']}>
      <Link to="/zones/zone-02/events">다른 구역</Link>
      <Routes>
        <Route path="/zones/:zoneId/events" element={<EventsPage />} />
      </Routes>
    </MemoryRouter>,
  );
  await waitFor(() => expect(screen.getByRole('button', { name: '다음' })).toBeEnabled());
  fireEvent.click(screen.getByRole('button', { name: '다음' }));
  await waitFor(() =>
    expect(events.mock.calls.at(-1)?.[0].get('cursor')).toBe('signed-for-zone-one'),
  );
  fireEvent.click(screen.getByRole('link', { name: '다른 구역' }));
  await waitFor(() => expect(events.mock.calls.at(-1)?.[0].get('zone_id')).toBe('zone-02'));
  expect(events.mock.calls.at(-1)?.[0].has('cursor')).toBe(false);
});
