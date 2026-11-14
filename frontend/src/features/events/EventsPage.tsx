import {useState,type FormEvent} from 'react';
import {useParams} from 'react-router-dom';
import {api} from '../../api/client';
import {useQuery} from '../../api/useQuery';
import {states} from '../../components/labels';
import {ErrorNotice,Loading} from '../../components/Feedback';
import {EventTable} from './EventTable';
import styles from '../../styles/Console.module.css';
export function seoulFilter(value:string):string {return new Date(`${value}:00+09:00`).toISOString();}
export function EventsPage(){const {zoneId=''}=useParams();const [state,setState]=useState(''),[from,setFrom]=useState(''),[to,setTo]=useState(''),[filter,setFilter]=useState({state:'',from:'',to:''}),[cursors,setCursors]=useState<string[]>([]),[formError,setFormError]=useState('');
 const query=new URLSearchParams({zone_id:zoneId,limit:'20'});if(filter.state)query.set('state',filter.state);if(filter.from)query.set('from',seoulFilter(filter.from));if(filter.to)query.set('to',seoulFilter(filter.to));if(cursors.length)query.set('cursor',cursors[cursors.length-1]);
 const result=useQuery(`events:${query}`,signal=>api.events(query,signal),10000);
 function submit(e:FormEvent){e.preventDefault();if(from&&to&&from>=to){setFormError('종료 시각은 시작 시각보다 뒤여야 합니다.');return;}setFormError('');setFilter({state,from,to});setCursors([]);result.refresh();}
 return <><div className={styles.pageHeading}><div><span className={styles.eyebrow}>EVENT HISTORY</span><h1>사건 이력</h1><p>서버가 처음 수신한 순서로 사건을 확인합니다.</p></div><button className={styles.secondary} onClick={()=>{setCursors([]);result.refresh();}}>↻ 처음부터 새로고침</button></div><form className={styles.filters} onSubmit={submit}><label>사건 상태<select value={state} onChange={e=>setState(e.target.value)}><option value="">모든 상태</option>{Object.entries(states).map(([value,label])=><option key={value} value={value}>{label}</option>)}</select></label><label>서버 최초 수신 시작 (한국 시각)<input type="datetime-local" value={from} onChange={e=>setFrom(e.target.value)}/></label><label>서버 최초 수신 종료 (미포함)<input type="datetime-local" value={to} onChange={e=>setTo(e.target.value)}/></label><button className={styles.primary}>조회</button></form>{formError&&<p role="alert" className={styles.warning}>{formError}</p>}{result.error&&<ErrorNotice error={result.error} retry={result.refresh}/>}
 <section className={styles.panel}><div className={styles.panelHeader}><h2>사건 목록</h2><span className={styles.muted}>현재 페이지 {result.data?.items.length??0}건 · {cursors.length+1}페이지</span></div>{result.loading&&!result.data?<Loading/>:result.data?<EventTable items={result.data.items}/>:null}<div className={styles.pagination}><span>기간·상태가 바뀌면 첫 페이지부터 조회합니다.</span><button className={styles.secondary} disabled={!cursors.length||result.loading} onClick={()=>setCursors(x=>x.slice(0,-1))}>이전</button><button className={styles.secondary} disabled={!result.data?.next_cursor||result.loading||!!result.error} onClick={()=>{const cursor=result.data?.next_cursor;if(cursor)setCursors(x=>[...x,cursor]);}}>다음</button></div></section></>;
}
