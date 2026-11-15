import * as v from './generated/validators';
import type { AuthUser, EventDetail, EventList, ZoneView } from './types';
export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly retryAfter: number | null = null,
    readonly requestId: string | null = null,
  ) {
    super(message);
  }
}
export const sessionExpired = new EventTarget();
export function retryDelay(header: string | null, now = Date.now()): number | null {
  if (!header) return null;
  const seconds = /^\d+$/.test(header) ? Number(header) : (Date.parse(header) - now) / 1000;
  return Number.isFinite(seconds) ? Math.max(1, Math.min(3600, Math.ceil(seconds))) : null;
}
export async function request<T>(
  path: string,
  options: RequestInit = {},
  decode?: (value: unknown) => value is T,
  notify401 = true,
): Promise<T> {
  let response: Response;
  try {
    response = await fetch(`/api${path}`, {
      ...options,
      credentials: 'same-origin',
      cache: 'no-store',
      signal: options.signal
        ? AbortSignal.any([options.signal, AbortSignal.timeout(15000)])
        : AbortSignal.timeout(15000),
      headers: { Accept: 'application/json', ...options.headers },
    });
  } catch (error) {
    if (options.signal?.aborted) throw error;
    throw new ApiError(0, 'NETWORK', '서버에 연결하지 못했습니다. 잠시 후 다시 시도해 주세요.');
  }
  let body: unknown = null;
  if (response.status !== 204) {
    try {
      body = await response.json();
    } catch {
      /* A proxy can send HTML. Never display it. */
    }
  }
  options.signal?.throwIfAborted();
  if (!response.ok) {
    if (response.status === 401 && notify401) sessionExpired.dispatchEvent(new Event('expired'));
    const known = v.validateRestError(body) ? body : null;
    const fallback: Record<number, string> = {
      401: '다시 로그인해 주세요.',
      403: '이 구역의 접근 권한이 없습니다.',
      404: '대상을 찾을 수 없습니다.',
      410: '보관기한이 지난 사건입니다.',
      429: '요청이 많습니다. 잠시 후 다시 시도해 주세요.',
      503: '서버 상태를 확인하고 있습니다. 잠시 후 다시 시도해 주세요.',
    };
    throw new ApiError(
      response.status,
      known ? known.code : 'HTTP_ERROR',
      known ? known.message : fallback[response.status] || '요청을 처리하지 못했습니다.',
      retryDelay(response.headers.get('Retry-After')),
      known ? known.request_id : null,
    );
  }
  if (response.status === 204) return undefined as T;
  if (!decode || !decode(body))
    throw new ApiError(
      502,
      'INVALID_RESPONSE',
      '서버 응답을 읽지 못했습니다. 새로고침 후 다시 확인해 주세요.',
    );
  return body;
}
async function csrf() {
  return (await request('/csrf', {}, v.validateCsrfResponse, false)).token;
}
export const api = {
  me: (signal?: AbortSignal) => request<AuthUser>('/auth/me', { signal }, v.validateAuthUser),
  async login(username: string, password: string) {
    const token = await csrf();
    const user = await request<AuthUser>(
      '/auth/login',
      {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-TOKEN': token },
        body: JSON.stringify({ username, password }),
      },
      v.validateAuthUser,
      false,
    );
    await csrf();
    return user;
  },
  async logout() {
    const token = await csrf();
    await request<void>('/auth/logout', { method: 'POST', headers: { 'X-CSRF-TOKEN': token } });
  },
  zone: (id: string, signal?: AbortSignal) =>
    request<ZoneView>(`/zones/${encodeURIComponent(id)}/status`, { signal }, v.validateZoneView),
  events: (query: URLSearchParams, signal?: AbortSignal) =>
    request<EventList>(`/events?${query.toString()}`, { signal }, v.validateEventListResponse),
  event: (id: string, signal?: AbortSignal) =>
    request<EventDetail>(`/events/${encodeURIComponent(id)}`, { signal }, v.validateEventDetail),
};
