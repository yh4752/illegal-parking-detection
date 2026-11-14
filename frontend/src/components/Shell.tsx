import { useState } from 'react';
import { Link,NavLink,Outlet,useParams } from 'react-router-dom';
import { useAuth } from '../features/auth/Auth';
import { demoZone } from './labels';
import { ErrorNotice } from './Feedback';
import type { ApiError } from '../api/client';
import styles from '../styles/Console.module.css';
export function Shell(){const auth=useAuth();const {zoneId}=useParams();const zone=zoneId||demoZone;const [error,setError]=useState<ApiError|null>(null),[busy,setBusy]=useState(false);return <div className={styles.shell}>
  <a href="#main" className={styles.skip}>본문으로 이동</a>
  <aside className={styles.sidebar}><Link to={`/zones/${encodeURIComponent(zone)}`} className={styles.brand}><span className={styles.brandMark}>P<span>·</span></span><span>현장 안내 관제<small>PARKING CONSOLE</small></span></Link><div className={styles.sideCaption}>WORKSPACE</div><nav aria-label="주 메뉴"><NavLink end to={`/zones/${encodeURIComponent(zone)}`} className={({isActive})=>isActive?styles.active:''}><span aria-hidden="true">▦</span>구역 현황</NavLink><NavLink to={`/zones/${encodeURIComponent(zone)}/events`} className={({isActive})=>isActive?styles.active:''}><span aria-hidden="true">≡</span>사건 이력</NavLink></nav><div className={styles.sideFoot}><span className={styles.demoDot}/>가상 장치 데모<p>임의로 구성한 구역의<br/>안내 상태를 확인합니다.</p></div></aside>
  <div className={styles.workspace}><header className={styles.topbar}><span>운영 공간 <b>/</b> <strong>{zone}</strong></span><div className={styles.account}><span title={auth.user?.user_id}>{auth.user?.role==='OPERATOR'?'관리자':'조회자'} 계정</span><button disabled={busy} onClick={async()=>{setBusy(true);setError(null);try{await auth.signOut();}catch(e){setError(e as ApiError);}finally{setBusy(false);}}}>{busy?'종료 중…':'로그아웃'}</button></div></header><main id="main" className={styles.main}>{error&&<ErrorNotice error={error}/>}<Outlet/></main><footer className={styles.footer}>가상 장치의 보고를 표시합니다. 법적 단속·과태료 부과 결과가 아닙니다.</footer></div></div>;}
