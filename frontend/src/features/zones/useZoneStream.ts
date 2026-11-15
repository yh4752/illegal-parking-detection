import { useEffect, useRef, useState } from 'react';
import { ZoneStream, type StreamState } from './zoneStream';
import type { ZoneView } from '../../api/types';
import type { ApiError } from '../../api/client';
export function useZoneStream(zone: string) {
  const [state, set] = useState<{
    zone: string;
    view: ZoneView | null;
    connection: StreamState;
    error: ApiError | null;
  }>({ zone, view: null, connection: 'connecting', error: null });
  const stream = useRef<ZoneStream | null>(null);
  useEffect(() => {
    const s = new ZoneStream(zone, (view, connection, error) =>
      set({ zone, view, connection, error }),
    );
    stream.current = s;
    s.start();
    return () => s.stop();
  }, [zone]);
  return {
    view: state.zone === zone ? state.view : null,
    connection: state.zone === zone ? state.connection : ('connecting' as StreamState),
    error: state.zone === zone ? state.error : null,
    refresh: () => stream.current?.refresh(),
  };
}
