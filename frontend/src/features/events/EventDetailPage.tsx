import { Link, useParams } from 'react-router-dom';
import { useState } from 'react';
import { api } from '../../api/client';
import { useQuery } from '../../api/useQuery';
import { Badge, ErrorNotice, Loading } from '../../components/Feedback';
import { date, duration, states, stateTone, bodies, uses, demoZone } from '../../components/labels';
import styles from '../../styles/Console.module.css';
export function EventDetailPage() {
  const { eventId = '' } = useParams();
  const result = useQuery(`event:${eventId}`, (signal) => api.event(eventId, signal), 10000);
  const [shown, setShown] = useState(20);
  const data = result.data;
  const event = data?.event;
  return (
    <>
      <Link className={styles.textLink} to={`/zones/${encodeURIComponent(demoZone)}/events`}>
        ← 사건 목록
      </Link>
      <div className={styles.pageHeading}>
        <div>
          <span className={styles.eyebrow}>EVENT DETAIL</span>
          <h1>사건 상세</h1>
          <p className={styles.mono}>{eventId}</p>
        </div>
        <button className={styles.secondary} onClick={result.refresh}>
          ↻ 새로고침
        </button>
      </div>
      {result.error && <ErrorNotice error={result.error} retry={result.refresh} />}{' '}
      {result.loading && !data && <Loading />}
      {data && event && (
        <>
          <section className={styles.panel}>
            <div className={styles.panelHeader}>
              <h2>사건 요약</h2>
              <Badge tone={stateTone(event.state)}>{states[event.state]}</Badge>
            </div>
            <dl className={styles.detailGrid}>
              <div>
                <dt>차량 분류</dt>
                <dd>
                  {bodies[event.body_type]} · {uses[event.use_type]}
                </dd>
              </div>
              <div>
                <dt>관리자 용도 분류</dt>
                <dd>{data.manual_use_type ? uses[data.manual_use_type] : '별도 분류 없음'}</dd>
              </div>
              <div>
                <dt>관측 시작</dt>
                <dd>{date(event.started_at)}</dd>
              </div>
              <div>
                <dt>관측 종료</dt>
                <dd>{date(event.closed_at)}</dd>
              </div>
              <div>
                <dt>연속 정차 보고</dt>
                <dd>{duration(event.continuous_stop_ms)}</dd>
              </div>
              <div>
                <dt>안내 횟수</dt>
                <dd>{event.guidance_count}회</dd>
              </div>
              <div>
                <dt>장기 미이동 보고</dt>
                <dd>{event.overdue ? '있음' : '없음'}</dd>
              </div>
              <div>
                <dt>적용 정책 버전</dt>
                <dd>v{event.event_policy_version}</dd>
              </div>
            </dl>
          </section>
          <section className={styles.panel}>
            <div className={styles.panelHeader}>
              <div>
                <h2>상태 보고 이력</h2>
                <span className={styles.muted}>
                  장치 순번 내림차순 · 보관 중인 {data.messages.length}건
                </span>
              </div>
            </div>
            {!data.messages.length ? (
              <p className={styles.emptyText}>
                보관 중인 수신 원문이 없습니다. 사건 요약은 위에서 확인할 수 있습니다.
              </p>
            ) : (
              <ol className={styles.timeline}>
                {[...data.messages]
                  .reverse()
                  .slice(0, shown)
                  .map((m) => (
                    <li key={m.message_id}>
                      <span className={styles.timelineDot} />
                      <div>
                        <strong>{m.event ? states[m.event.state] : m.event_type}</strong>
                        <small className={styles.mono}>
                          #{m.device_seq} · {m.message_id}
                        </small>
                      </div>
                      <time>{date(m.occurred_at)}</time>
                      <details>
                        <summary>수신 내용</summary>
                        <pre>{JSON.stringify(m, null, 2)}</pre>
                      </details>
                    </li>
                  ))}
              </ol>
            )}
            {data.messages.length > shown && (
              <button className={styles.secondary} onClick={() => setShown((x) => x + 20)}>
                이력 더 보기 ({Math.min(shown, data.messages.length)}/{data.messages.length})
              </button>
            )}
          </section>
          {!!data.reviews.length && (
            <section className={styles.panel}>
              <div className={styles.panelHeader}>
                <h2>관리자 검토 기록</h2>
              </div>
              <ul className={styles.timeline}>
                {data.reviews.map((r) => (
                  <li key={r.review_id}>
                    <div>
                      <strong>{r.note}</strong>
                      <small>{r.use_type ? uses[r.use_type] : '분류 변경 없음'}</small>
                    </div>
                    <time>{date(r.created_at)}</time>
                  </li>
                ))}
              </ul>
            </section>
          )}
          {!!data.images.length && (
            <section className={styles.panel}>
              <div className={styles.panelHeader}>
                <h2>사진 기록</h2>
                <span>메타데이터 {data.images.length}건</span>
              </div>
              <ul className={styles.timeline}>
                {data.images.map((i) => (
                  <li key={i.image_id}>
                    <span className={styles.mono}>{i.image_id}</span>
                    <Badge>{i.status}</Badge>
                    <time>{date(i.captured_at)}</time>
                  </li>
                ))}
              </ul>
            </section>
          )}
        </>
      )}
    </>
  );
}
