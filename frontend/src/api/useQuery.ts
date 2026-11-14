import { useEffect, useReducer, useRef, useState } from 'react';
import { ApiError } from './client';
export function useQuery<T>(
  key: string,
  fetcher: (signal: AbortSignal) => Promise<T>,
  interval = 0,
) {
  const latest = useRef(fetcher);
  latest.current = fetcher;
  const [refreshId, refresh] = useReducer((x: number) => x + 1, 0);
  const [state, set] = useState<{
    key: string;
    data: T | null;
    error: ApiError | null;
    loading: boolean;
  }>({ key, data: null, error: null, loading: true });
  useEffect(() => {
    const controller = new AbortController();
    set((s) => ({ key, data: s.key === key ? s.data : null, error: null, loading: true }));
    latest
      .current(controller.signal)
      .then((data) => {
        if (!controller.signal.aborted) set({ key, data, error: null, loading: false });
      })
      .catch((error) => {
        if (!controller.signal.aborted)
          set((s) => ({
            key,
            data: s.key === key && ![401, 403, 404, 410].includes(error.status) ? s.data : null,
            error,
            loading: false,
          }));
      });
    return () => controller.abort();
  }, [key, refreshId]);
  const terminal = state.error && [400, 401, 403, 404, 410].includes(state.error.status);
  useEffect(() => {
    if (!interval || terminal) return;
    const check = () => {
      if (document.visibilityState === 'visible') refresh();
    };
    const timer = setInterval(check, interval);
    window.addEventListener('focus', check);
    document.addEventListener('visibilitychange', check);
    return () => {
      clearInterval(timer);
      window.removeEventListener('focus', check);
      document.removeEventListener('visibilitychange', check);
    };
  }, [interval, key, terminal]);
  return {
    data: state.key === key ? state.data : null,
    error: state.key === key ? state.error : null,
    loading: state.key !== key || state.loading,
    refresh,
  };
}
