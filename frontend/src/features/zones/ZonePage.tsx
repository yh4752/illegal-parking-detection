import { useEffect } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useZoneStream } from './useZoneStream';
import { useQuery } from '../../api/useQuery';
import { api } from '../../api/client';
import { Badge, ErrorNotice, Loading } from '../../components/Feedback';
import { date, duration, states, camera, bodies, uses, reasons } from '../../components/labels';
import { EventTable } from '../events/EventTable';
import styles from '../../styles/Console.module.css';
export function ZonePage() {
  const { zoneId = '' } = useParams();
  const { view, connection, error, refresh } = useZoneStream(zoneId);
  const recent = useQuery(
    `recent:${zoneId}`,
    (signal) => api.events(new URLSearchParams({ zone_id: zoneId, limit: '5' }), signal),
    10000,
  );
  useEffect(() => {
    if (view) recent.refresh();
  }, [view?.view_revision, recent.refresh]);
  const s = view?.snapshot;
  const title = s?.incident_state
    ? states[s.incident_state]
    : s
      ? '진행 중인 사건 없음'
      : '첫 관측을 기다립니다';
  return (
    <>
      <div className={styles.pageHeading}>
        <div>
          <span className={styles.eyebrow}>ZONE OVERVIEW</span>
          <h1>구역 현황</h1>
          <p>현장 장치가 마지막으로 보고한 상태입니다.</p>
        </div>
        <button
          className={styles.secondary}
          onClick={() => {
            refresh();
            recent.refresh();
          }}
        >
          ↻ 새로고침
        </button>
      </div>
      <div className={styles.contextBar}>
        <strong>
          <span className={styles.demoDot} /> 가상 실험 구역{' '}
          <span className={styles.mono}>{zoneId}</span>
        </strong>
        <span role="status" className={styles.streamState}>
          화면 연결 ·{' '}
          {
            {
              connecting: '연결 중',
              live: '연결됨',
              reconnecting: '재연결 중',
              blocked: '접근 제한',
            }[connection]
          }
        </span>
      </div>
      {error && (
        <ErrorNotice error={error} retry={connection === 'blocked' ? undefined : refresh} />
      )}
      {connection === 'reconnecting' && view && (
        <div className={styles.warning}>
          화면 연결을 복구하고 있습니다. 아래는 마지막으로 확인한 상태입니다.
        </div>
      )}
      {view && view.connection_state !== 'ONLINE' && (
        <div className={styles.warning}>
          {view.connection_state === 'STALE'
            ? '장치의 최근 응답이 지연되고 있습니다.'
            : '장치의 최근 연결을 확인할 수 없습니다.'}{' '}
          차량 이동이나 빈 구역으로 판단하지 않습니다.
        </div>
      )}
      <section className={styles.hero}>
        <div className={styles.heroContent}>
          <div className={styles.heroLabel}>
            현재 사건 상태{' '}
            <Badge
              tone={
                view?.connection_state === 'ONLINE'
                  ? 'good'
                  : view?.connection_state === 'STALE'
                    ? 'warn'
                    : 'neutral'
              }
            >
              장치{' '}
              {view?.connection_state === 'ONLINE'
                ? '연결됨'
                : view?.connection_state === 'STALE'
                  ? '응답 지연'
                  : '연결 미확인'}
            </Badge>
          </div>
          <h2>{title}</h2>
          <p>
            {s?.current_event_id ? (
              <>
                사건 <span className={styles.mono}>{s.current_event_id}</span> ·{' '}
                {s.body_type ? bodies[s.body_type] : '미분류'} /{' '}
                {s.use_type ? uses[s.use_type] : '용도 미확인'}
              </>
            ) : s ? (
              '장치 보고에 현재 사건이 없습니다.'
            ) : (
              '아직 장치 상태를 받지 않았습니다. 차량이 없다는 의미는 아닙니다.'
            )}
          </p>
          {s?.current_event_id && (
            <Link
              className={styles.primaryLink}
              to={`/events/${encodeURIComponent(s.current_event_id)}`}
            >
              사건 자세히 보기 <span>↗</span>
            </Link>
          )}
        </div>
        <div className={styles.heroSymbol} aria-hidden="true">
          <svg width="170" height="125" viewBox="0 0 170 125">
            <path
              d="M24 101h122M35 91V57l18-27h64l18 27v34M35 57h100M55 30l-9 27m69-27 9 27M48 72h18m38 0h18M45 91v11m80-11v11"
              fill="none"
              stroke="currentColor"
              strokeWidth="4"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
            <circle cx="85" cy="78" r="4" fill="currentColor" />
          </svg>
          <span>{s?.current_event_id ? '관측 보고 있음' : '관측 대기'}</span>
        </div>
      </section>
      <div className={styles.metrics}>
        <article className={styles.metric}>
          <span>연속 정차 보고</span>
          <strong>{s ? duration(s.continuous_stop_ms) : '—'}</strong>
          <small>장치 보고값 · 자동 증가 없음</small>
        </article>
        <article className={styles.metric}>
          <span>이동 안내</span>
          <strong>{s ? `${s.guidance_count}회` : '—'}</strong>
          <small>보고된 안내 횟수</small>
        </article>
        <article className={styles.metric}>
          <span>카메라</span>
          <strong>{s ? camera[s.camera_health] : '확인 안 됨'}</strong>
          <small>
            {s?.clock_synced === false ? '장치 시각 신뢰 불가' : '관측 상태를 별도로 확인'}
          </small>
        </article>
        <article className={styles.metric}>
          <span>운영 모드</span>
          <strong>
            {s
              ? { AUTO: '자동 안내', PAUSED: '일시 중지', AWAITING_RESUME: '재개 확인 대기' }[
                  s.mode
                ]
              : '—'}
          </strong>
          <small>{s?.rearm_required ? '재무장 확인 필요' : '장치가 보고한 모드'}</small>
        </article>
      </div>
      <section className={styles.panel}>
        <div className={styles.panelHeader}>
          <h2>마지막 보고</h2>
          <span className={styles.muted}>모든 시각은 한국 시각</span>
        </div>
        <dl className={styles.reportGrid}>
          <div>
            <dt>영상 관측 시각</dt>
            <dd>{date(s?.observed_at)}</dd>
          </div>
          <div>
            <dt>서버 상태 수신</dt>
            <dd>{date(view?.received_at)}</dd>
          </div>
          <div>
            <dt>마지막 장치 응답</dt>
            <dd>{date(view?.last_heartbeat_at)}</dd>
          </div>
        </dl>
        {s?.reason_codes.length ? (
          <div className={styles.reasons}>
            <strong>확인 사유</strong>
            {s.reason_codes.map((code) => (
              <Badge key={code} tone="warn">
                {reasons[code] || code}
              </Badge>
            ))}
          </div>
        ) : null}
        {s?.overdue && <p className={styles.warning}>장치가 장기 미이동 상태를 보고했습니다.</p>}
      </section>
      <section className={styles.panel}>
        <div className={styles.panelHeader}>
          <div>
            <h2>최근 사건</h2>
            <span className={styles.muted}>서버 최초 수신 순 · 최대 5건</span>
          </div>
          <Link className={styles.textLink} to={`/zones/${encodeURIComponent(zoneId)}/events`}>
            전체 이력 ↗
          </Link>
        </div>
        {recent.error && <ErrorNotice error={recent.error} retry={recent.refresh} />}{' '}
        {recent.loading && !recent.data ? (
          <Loading />
        ) : recent.data ? (
          <EventTable items={recent.data.items} />
        ) : null}
      </section>
      {view?.commands.length ? (
        <section className={styles.panel}>
          <div className={styles.panelHeader}>
            <h2>최근 요청 결과</h2>
          </div>
          <ul className={styles.timeline}>
            {view.commands.map((c) => (
              <li key={c.command_id}>
                <strong>{c.type}</strong>
                <span>{c.server_status}</span>
                <time>{date(c.issued_at)}</time>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
      {!view && !error && connection === 'connecting' && <Loading />}
    </>
  );
}
