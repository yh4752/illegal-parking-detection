import { api, ApiError } from '../../api/client';
import { validateZoneView } from '../../api/generated/validators';
import type { ZoneView } from '../../api/types';
export type StreamState = 'connecting' | 'live' | 'reconnecting' | 'blocked';
export function acceptSnapshot(
  current: ZoneView | null,
  incoming: ZoneView,
  zone: string,
): ZoneView | null {
  return incoming.zone_id === zone && incoming.view_revision > (current?.view_revision ?? -1)
    ? incoming
    : current;
}
type Dependencies = {
  source: (url: string) => EventSource;
  zone: (id: string, signal: AbortSignal) => Promise<ZoneView>;
  me: (signal: AbortSignal) => Promise<unknown>;
  random: () => number;
};
/** Owns one stream, one retry timer and abortable requests. UI styling is independent. */
export class ZoneStream {
  private source: EventSource | null = null;
  private request: AbortController | null = null;
  private timer: ReturnType<typeof setTimeout> | null = null;
  private disposed = false;
  private generation = 0;
  private attempt = 0;
  private current: ZoneView | null = null;
  constructor(
    private zoneId: string,
    private notify: (view: ZoneView | null, state: StreamState, error: ApiError | null) => void,
    private deps: Dependencies = {
      source: (url) => new EventSource(url),
      zone: api.zone,
      me: api.me,
      random: Math.random,
    },
  ) {}
  start() {
    this.connect();
  }
  stop() {
    this.disposed = true;
    this.generation++;
    this.source?.close();
    this.source = null;
    this.request?.abort();
    if (this.timer) clearTimeout(this.timer);
  }
  refresh() {
    if (!this.disposed) void this.read(this.generation);
  }
  private update(view: ZoneView, state: StreamState) {
    this.current = acceptSnapshot(this.current, view, this.zoneId);
    this.notify(this.current, state, null);
  }
  private connect() {
    if (this.disposed) return;
    const generation = ++this.generation;
    this.source?.close();
    this.request?.abort();
    this.notify(this.current, this.attempt ? 'reconnecting' : 'connecting', null);
    const source = this.deps.source(`/api/stream?zone_id=${encodeURIComponent(this.zoneId)}`);
    this.source = source;
    source.onopen = () => {
      if (this.disposed || generation !== this.generation) return;
      this.attempt = 0;
      this.notify(this.current, 'live', null);
      void this.read(generation);
    };
    source.addEventListener('zone_snapshot', (event) => {
      if (this.disposed || generation !== this.generation) return;
      try {
        const message = event as MessageEvent;
        const view: unknown = JSON.parse(message.data);
        if (
          !validateZoneView(view) ||
          view.zone_id !== this.zoneId ||
          message.lastEventId !== String(view.view_revision)
        )
          throw new Error('invalid snapshot');
        this.update(view, 'live');
      } catch {
        void this.failed(
          generation,
          new ApiError(
            502,
            'INVALID_RESPONSE',
            '실시간 응답을 확인하지 못했습니다. 다시 연결합니다.',
          ),
        );
      }
    });
    source.onerror = () => {
      void this.failed(generation);
    };
  }
  private async read(generation: number) {
    this.request?.abort();
    const request = new AbortController();
    this.request = request;
    try {
      const view = await this.deps.zone(this.zoneId, request.signal);
      if (!this.disposed && !request.signal.aborted && generation === this.generation)
        this.update(view, this.source?.readyState === 1 ? 'live' : 'reconnecting');
    } catch (error) {
      if (!this.disposed && !request.signal.aborted && generation === this.generation)
        await this.failed(generation, error as ApiError);
    }
  }
  private async failed(generation: number, error: ApiError | null = null) {
    if (this.disposed || generation !== this.generation) return;
    // Invalidate all callbacks from this connection before probing/reconnecting.
    const probeGeneration = ++this.generation;
    this.source?.close();
    this.source = null;
    this.request?.abort();
    if (this.timer) clearTimeout(this.timer);
    this.notify(this.current, 'reconnecting', error);
    const request = new AbortController();
    this.request = request;
    let failure = error;
    try {
      await this.deps.me(request.signal);
      const view = await this.deps.zone(this.zoneId, request.signal);
      if (!this.disposed && !request.signal.aborted && probeGeneration === this.generation)
        this.update(view, 'reconnecting');
    } catch (e) {
      failure = e as ApiError;
    }
    if (this.disposed || request.signal.aborted || probeGeneration !== this.generation) return;
    if (failure && [401, 403, 404, 410].includes(failure.status)) {
      this.current = null;
      this.notify(null, 'blocked', failure);
      return;
    }
    this.notify(this.current, 'reconnecting', failure);
    const wait =
      failure?.status === 429 && failure.retryAfter
        ? failure.retryAfter * 1000
        : Math.min(30000, 1000 * 2 ** Math.min(this.attempt++, 5)) *
          (0.8 + 0.4 * this.deps.random());
    this.timer = setTimeout(() => this.connect(), wait);
  }
}
