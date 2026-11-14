import { createContext,useCallback,useContext,useEffect,useState,type ReactNode } from 'react';
import { Navigate,Outlet } from 'react-router-dom';
import { api,ApiError,sessionExpired } from '../../api/client';
import type { AuthUser } from '../../api/types';
import { ErrorNotice } from '../../components/Feedback';
const Context=createContext<{user:AuthUser|null,epoch:number,loading:boolean,error:ApiError|null,signIn:(name:string,password:string)=>Promise<void>,signOut:()=>Promise<void>,reload:()=>void}>(null!);
export function AuthProvider({children}:{children:ReactNode}) {
  const [user,setUser]=useState<AuthUser|null>(null),[epoch,setEpoch]=useState(0),[loading,setLoading]=useState(true),[error,setError]=useState<ApiError|null>(null),[reloadId,setReload]=useState(0);
  const clear=useCallback(()=>{setUser(null);setEpoch(x=>x+1);},[]);
  useEffect(()=>{const onExpired=()=>clear();sessionExpired.addEventListener('expired',onExpired);return()=>sessionExpired.removeEventListener('expired',onExpired);},[clear]);
  useEffect(()=>{
    const c=new AbortController();setLoading(true);setError(null);
    api.me(c.signal).then(u=>{if(!c.signal.aborted)setUser(u);}).catch(e=>{if(!c.signal.aborted){if(e.status===401)clear();else setError(e);}}).finally(()=>{if(!c.signal.aborted)setLoading(false);});
    return()=>c.abort();
  },[reloadId,clear]);
  return <Context value={{user,epoch,loading,error,reload:()=>setReload(x=>x+1),signIn:async(name,password)=>{const u=await api.login(name,password);setUser(u);setEpoch(x=>x+1);},signOut:async()=>{try{await api.logout();}catch(e){if(!(e instanceof ApiError && e.status===401))throw e;}clear();}}}>{children}</Context>;
}
export const useAuth=()=>useContext(Context);
export function Protected() {const auth=useAuth();if(auth.loading)return <main className="boot" role="status">계정 확인 중…</main>;if(auth.error)return <main className="boot"><ErrorNotice error={auth.error} retry={auth.reload}/></main>;return auth.user?<Outlet key={auth.epoch}/>:<Navigate to="/login" replace/>;}
