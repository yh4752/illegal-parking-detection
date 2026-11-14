import {Routes,Route,Navigate,useLocation} from 'react-router-dom';
import {useEffect} from 'react';
import {AuthProvider,Protected} from './features/auth/Auth';
import {LoginPage} from './features/auth/LoginPage';
import {ZonePage} from './features/zones/ZonePage';
import {EventsPage} from './features/events/EventsPage';
import {EventDetailPage} from './features/events/EventDetailPage';
import {Shell} from './components/Shell';
import {demoZone} from './components/labels';
function RouteFocus(){const location=useLocation();useEffect(()=>{document.getElementById('main')?.setAttribute('tabindex','-1');document.getElementById('main')?.focus({preventScroll:true});window.scrollTo(0,0);},[location.pathname]);return null;}
export function App(){return <AuthProvider><RouteFocus/><Routes><Route path="/login" element={<LoginPage/>}/><Route element={<Protected/>}><Route element={<Shell/>}><Route path="/zones/:zoneId" element={<ZonePage/>}/><Route path="/zones/:zoneId/events" element={<EventsPage/>}/><Route path="/events/:eventId" element={<EventDetailPage/>}/></Route></Route><Route path="/" element={<Navigate to={`/zones/${encodeURIComponent(demoZone)}`} replace/>}/><Route path="*" element={<main className="boot"><h1>페이지를 찾을 수 없습니다.</h1><a href="/">처음으로 이동</a></main>}/></Routes></AuthProvider>;}
