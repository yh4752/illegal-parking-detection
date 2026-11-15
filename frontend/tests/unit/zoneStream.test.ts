import { it, expect, vi } from 'vitest';
import { acceptSnapshot, ZoneStream } from '../../src/features/zones/zoneStream';
import type { ZoneView } from '../../src/api/types';
import { ApiError } from '../../src/api/client';
const zone = (revision = 0): ZoneView => ({
  zone_id: 'zone-01',
  view_revision: revision,
  received_at: null,
  last_heartbeat_at: null,
  connection_state: 'OFFLINE',
  snapshot: null,
  commands: [],
  image_refs: [],
});
class Source {
  readyState = 0;
  onopen: (() => void) | null = null;
  onerror: (() => void) | null = null;
  listener: ((event: MessageEvent) => void) | null = null;
  closed = false;
  close() {
    this.closed = true;
  }
  addEventListener(_name: string, listener: (event: MessageEvent) => void) {
    this.listener = listener;
  }
  open() {
    this.readyState = 1;
    this.onopen?.();
  }
  send(view: ZoneView, id = String(view.view_revision)) {
    this.listener?.(
      new MessageEvent('zone_snapshot', { data: JSON.stringify(view), lastEventId: id }),
    );
  }
}
const settle = async () => {
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
  await Promise.resolve();
};
function harness(read = vi.fn().mockResolvedValue(zone())) {
  const sources: Source[] = [];
  const notify = vi.fn();
  const me = vi.fn().mockResolvedValue({});
  const stream = new ZoneStream('zone-01', notify, {
    zone: read,
    me,
    random: () => 0.5,
    source: () => {
      const s = new Source();
      sources.push(s);
      return s as unknown as EventSource;
    },
  });
  return { stream, sources, notify, read, me };
}
it('accepts first revision zero but never rolls back or crosses a zone', () => {
  const first = zone();
  expect(acceptSnapshot(null, first, 'zone-01')).toBe(first);
  const now = zone(12);
  expect(acceptSnapshot(now, zone(11), 'zone-01')).toBe(now);
  expect(acceptSnapshot(now, zone(12), 'zone-01')).toBe(now);
  expect(acceptSnapshot(now, { ...zone(13), zone_id: 'other' }, 'zone-01')).toBe(now);
});
it('a delayed REST response cannot replace a newer SSE snapshot', async () => {
  let resolve!: (value: ZoneView) => void;
  const h = harness(
    vi.fn(
      () =>
        new Promise<ZoneView>((r) => {
          resolve = r;
        }),
    ),
  );
  h.stream.start();
  h.sources[0].open();
  h.sources[0].send(zone(12));
  resolve(zone(11));
  await settle();
  expect(h.notify.mock.calls.at(-1)?.[0].view_revision).toBe(12);
  h.stream.stop();
});
it('closing the route discards callbacks and in-flight REST', async () => {
  let resolve!: (value: ZoneView) => void;
  const h = harness(
    vi.fn(
      () =>
        new Promise<ZoneView>((r) => {
          resolve = r;
        }),
    ),
  );
  h.stream.start();
  h.sources[0].open();
  h.stream.stop();
  const count = h.notify.mock.calls.length;
  resolve(zone(12));
  h.sources[0].send(zone(13));
  await settle();
  expect(h.notify).toHaveBeenCalledTimes(count);
  expect(h.read.mock.calls[0][1].aborted).toBe(true);
  expect(h.sources[0].closed).toBe(true);
});
it('rechecks auth and status after an opaque SSE error, reconnects and reads REST again', async () => {
  vi.useFakeTimers();
  const h = harness();
  h.stream.start();
  h.sources[0].open();
  await settle();
  h.sources[0].onerror?.();
  await settle();
  expect(h.me).toHaveBeenCalledOnce();
  expect(h.sources[0].closed).toBe(true);
  await vi.advanceTimersByTimeAsync(1000);
  expect(h.sources.length).toBe(2);
  h.sources[1].open();
  await settle();
  expect(h.read).toHaveBeenCalledTimes(3);
  h.stream.stop();
});
it.each([401, 403, 404, 410])(
  'stops and clears displayed data after %s during permission probe',
  async (status) => {
    vi.useFakeTimers();
    const h = harness();
    h.stream.start();
    h.sources[0].open();
    await settle();
    h.me.mockRejectedValueOnce(new ApiError(status, 'DENIED', '중단'));
    h.sources[0].onerror?.();
    await settle();
    expect(h.notify).toHaveBeenLastCalledWith(null, 'blocked', expect.objectContaining({ status }));
    await vi.advanceTimersByTimeAsync(60000);
    expect(h.sources.length).toBe(1);
    h.stream.stop();
  },
);
it('honors Retry-After from the REST probe', async () => {
  vi.useFakeTimers();
  const h = harness();
  h.stream.start();
  h.me.mockRejectedValueOnce(new ApiError(429, 'LIMIT', '대기', 40));
  h.sources[0].onerror?.();
  await settle();
  await vi.advanceTimersByTimeAsync(39999);
  expect(h.sources.length).toBe(1);
  await vi.advanceTimersByTimeAsync(1);
  expect(h.sources.length).toBe(2);
  h.stream.stop();
});
it('rejects mismatched SSE id and payload revision', async () => {
  const h = harness();
  h.stream.start();
  h.sources[0].send(zone(7), '8');
  await settle();
  expect(h.sources[0].closed).toBe(true);
  expect(h.notify.mock.calls.some((x) => x[0]?.view_revision === 7)).toBe(false);
  h.stream.stop();
});

it('manual refresh can reconnect after a revoked grant is restored', async () => {
  const h = harness();
  h.stream.start();
  h.sources[0].open();
  await settle();
  h.me.mockRejectedValueOnce(new ApiError(403, 'FORBIDDEN', '권한 없음'));
  h.sources[0].onerror?.();
  await settle();
  expect(h.notify.mock.calls.at(-1)?.[1]).toBe('blocked');
  h.stream.refresh();
  expect(h.sources.length).toBe(2);
  h.sources[1].open();
  await settle();
  expect(h.notify.mock.calls.at(-1)?.[1]).toBe('live');
  h.stream.stop();
});
