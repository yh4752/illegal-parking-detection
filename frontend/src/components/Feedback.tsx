import type { ReactNode } from 'react';
import { ApiError } from '../api/client';
import styles from '../styles/Console.module.css';
export function ErrorNotice({error,retry}:{error:ApiError,retry?:()=>void}) {return <div role="alert" className={styles.error}><div><strong>{error.message||'처리 중 오류가 발생했습니다.'}</strong>{error.retryAfter&&<p>{error.retryAfter}초 뒤 다시 시도해 주세요.</p>}{error.requestId&&<small>문의 번호 {error.requestId}</small>}</div>{retry&&<button className={styles.secondary} onClick={retry}>다시 시도</button>}</div>;}
export function Empty({title,children}:{title:string,children?:ReactNode}) {return <div className={styles.empty}><span aria-hidden="true">—</span><h3>{title}</h3>{children&&<p>{children}</p>}</div>;}
export function Loading(){return <p role="status" className={styles.muted}>불러오는 중…</p>;}
export function Badge({tone='neutral',children}:{tone?:'neutral'|'good'|'warn'|'danger',children:ReactNode}) {return <span className={`${styles.badge} ${styles[tone]}`}>{children}</span>;}
