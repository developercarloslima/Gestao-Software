import { useEffect,useState } from 'react';
import { api } from '../lib/api';

type TimerState='NOT_STARTED'|'WORKING'|'BREAK'|'PAUSE'|'CLOSED';
type TodaySummary={
 generatedAt?:string;
 timers?:{workSeconds:number;breakSeconds:number;pauseSeconds:number;state:TimerState;firstClockInAt?:string|null;lastPunchAt?:string|null};
};

function formatDuration(totalSeconds:number){
 const total=Math.max(0,Math.floor(totalSeconds));
 const h=Math.floor(total/3600),m=Math.floor((total%3600)/60),s=total%60;
 return `${String(h).padStart(2,'0')}:${String(m).padStart(2,'0')}:${String(s).padStart(2,'0')}`;
}

function stateCopy(state:TimerState){
 if(state==='WORKING')return {icon:'▶',title:'Jornada em andamento',text:'O tempo efetivo de trabalho está sendo contabilizado pelas marcações da jornada.'};
 if(state==='BREAK')return {icon:'Ⅱ',title:'Intervalo em andamento',text:'O intervalo está sendo contabilizado separadamente do tempo efetivo de trabalho.'};
 if(state==='PAUSE')return {icon:'Ⅱ',title:'Pausa em andamento',text:'A pausa está sendo contabilizada separadamente do tempo efetivo de trabalho.'};
 if(state==='CLOSED')return {icon:'✓',title:'Jornada encerrada',text:'Os tempos abaixo representam as marcações consolidadas de hoje.'};
 return {icon:'◷',title:'Jornada ainda não iniciada',text:'Os contadores começam automaticamente após a marcação de entrada.'};
}

export default function WorkMonitoringStatus({enabled}:{enabled:boolean}){
 const [summary,setSummary]=useState<TodaySummary|null>(null),[now,setNow]=useState(Date.now()),[snapshotAt,setSnapshotAt]=useState(Date.now()),[message,setMessage]=useState('');
 useEffect(()=>{
   if(!enabled)return;
   let cancelled=false;
   const load=async()=>{
     try{
       const data=await api<TodaySummary>('/monitoring/me/today');
       if(!cancelled){setSummary(data);setSnapshotAt(Date.now());setMessage('');}
     }catch(e){if(!cancelled)setMessage(e instanceof Error?e.message:'Não foi possível carregar os contadores da jornada.');}
   };
   void load();
   const timer=window.setInterval(load,3000);
   return()=>{cancelled=true;window.clearInterval(timer);};
 },[enabled]);
 useEffect(()=>{const timer=window.setInterval(()=>setNow(Date.now()),1000);return()=>window.clearInterval(timer);},[]);
 if(!enabled)return null;
 const base=summary?.timers??{workSeconds:0,breakSeconds:0,pauseSeconds:0,state:'NOT_STARTED' as TimerState};
 const liveDelta=Math.max(0,Math.floor((now-snapshotAt)/1000));
 const workSeconds=base.workSeconds+(base.state==='WORKING'?liveDelta:0);
 const breakSeconds=base.breakSeconds+(base.state==='BREAK'?liveDelta:0);
 const pauseSeconds=base.pauseSeconds+(base.state==='PAUSE'?liveDelta:0);
 const copy=stateCopy(base.state);
 return <section className={`work-monitor-panel time-counter-panel state-${base.state.toLowerCase()}`}>
   <div className="work-monitor-main">
     <span className="work-monitor-muted">{copy.icon}</span>
     <div><b>{copy.title}</b><small>{copy.text} Estes contadores são apenas gerenciais e não alteram o banco de horas.</small></div>
   </div>
   <div className="work-time-counters time-counter-grid">
     <div><span>Tempo efetivo de trabalho</span><strong>{formatDuration(workSeconds)}</strong></div>
     <div><span>Tempo de intervalo</span><strong>{formatDuration(breakSeconds)}</strong></div>
     <div><span>Tempo de pausa</span><strong>{formatDuration(pauseSeconds)}</strong></div>
   </div>
   {message&&<p className="work-monitor-message">{message}</p>}
 </section>;
}
