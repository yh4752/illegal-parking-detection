import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { it, expect, vi } from 'vitest';
import { AuthProvider } from '../../src/features/auth/Auth';
import { LoginPage } from '../../src/features/auth/LoginPage';
import { EventTable } from '../../src/features/events/EventTable';
import { seoulFilter } from '../../src/features/events/EventsPage';
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
