import { useEffect,useMemo,useState } from 'react';
import { api } from '../../lib/api';

type TimerState='NOT_STARTED'|'WORKING'|'BREAK'|'PAUSE'|'CLOSED';
type Row={id:string;name:string;employeeNumber:string;department?:string|null;worksite?:string|null;workSeconds:number;breakSeconds:number;pauseSeconds:number;state:TimerState};
type Data={workDate:string;timeZone:string;generatedAt:string;employees:Row[]};
type Detail={workDate:string;timeZone:string;generatedAt:string;employee:{id:string;name:string;employeeNumber:string;department?:{name:string}|null;worksite?:{name:string}|null};timers:{workSeconds:number;breakSeconds:number;pauseSeconds:number;state:TimerState}};

function duration(seconds:number){const total=Math.max(0,Math.floor(seconds));const h=Math.floor(total/3600),m=Math.floor((total%3600)/60);return `${String(h).padStart(2,'0')}h ${String(m).padStart(2,'0')}min`;}
function status(state:TimerState){if(state==='WORKING')return 'TRABALHANDO';if(state==='BREAK')return 'INTERVALO';if(state==='PAUSE')return 'PAUSA';if(state==='CLOSED')return 'ENCERRADA';return 'NÃO INICIADA';}

export default function ScreenMonitoring(){
 const [data,setData]=useState<Data|null>(null),[search,setSearch]=useState(''),[selectedDate,setSelectedDate]=useState(''),[detail,setDetail]=useState<Detail|null>(null),[busy,setBusy]=useState(''),[message,setMessage]=useState('');
 const load=(targetDate=selectedDate)=>api<Data>(`/admin/time-counters/today${targetDate?`?date=${encodeURIComponent(targetDate)}`:''}`).then(value=>{setData(value);setMessage('');}).catch(e=>setMessage(e instanceof Error?e.message:'Não foi possível carregar os contadores de tempo.'));
 useEffect(()=>{void load(selectedDate);const timer=window.setInterval(()=>void load(selectedDate),30000);return()=>window.clearInterval(timer);},[selectedDate]);
 const rows=useMemo(()=>{const q=search.trim().toLocaleLowerCase('pt-BR');return (data?.employees??[]).filter(row=>!q||`${row.name} ${row.employeeNumber} ${row.department??''} ${row.worksite??''}`.toLocaleLowerCase('pt-BR').includes(q));},[data,search]);
 async function open(row:Row){setBusy(row.id);setMessage('');try{setDetail(await api<Detail>(`/admin/time-counters/employee/${row.id}?date=${encodeURIComponent(data?.workDate??'')}`));}catch(e){setMessage(e instanceof Error?e.message:'Não foi possível abrir o resumo de tempo.');}finally{setBusy('');}}
 return <div className="screen-monitoring-page time-counters-page">
  <section className="card span-wide screen-monitoring-head"><div><div className="eyebrow">CONTADORES DE TEMPO</div><h2>Tempo da jornada por colaborador</h2><p className="muted">Sem gravação de tela. Estes contadores de tempo efetivo, intervalo e pausa são apenas uma visão gerencial de produtividade e não participam do cálculo do banco de horas.</p></div><button className="ghost" onClick={()=>void load()}>Atualizar</button></section>
  {message&&<p className="muted inline-feedback">{message}</p>}
  <section className="card span-wide"><div className="screen-monitor-toolbar"><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Buscar colaborador, matrícula, setor ou unidade"/><label className="monitor-date-filter"><span>Dia</span><input type="date" value={selectedDate||data?.workDate||''} onChange={e=>setSelectedDate(e.target.value)}/></label></div>
   <div className="table-wrap"><table className="compact-table screen-monitor-table time-counter-table"><thead><tr><th>Colaborador</th><th>Efetivo de trabalho</th><th>Intervalo</th><th>Pausa</th><th>Status</th><th></th></tr></thead><tbody>{rows.map(row=><tr key={row.id}><td><b>{row.name}</b><small>Matrícula {row.employeeNumber}{row.department?` · ${row.department}`:''}{row.worksite?` · ${row.worksite}`:''}</small></td><td><b>{duration(row.workSeconds)}</b></td><td>{duration(row.breakSeconds)}</td><td>{duration(row.pauseSeconds)}</td><td><span className={`monitor-status ${row.state==='WORKING'?'active':'inactive'}`}>{status(row.state)}</span></td><td><button className="ghost" disabled={busy===row.id} onClick={()=>void open(row)}>{busy===row.id?'Abrindo…':'Ver tempos'}</button></td></tr>)}{!rows.length&&<tr><td colSpan={6} className="muted">Nenhum colaborador encontrado.</td></tr>}</tbody></table></div>
  </section>
  {detail&&<div className="modal-backdrop" role="dialog" aria-modal="true"><div className="card screen-monitor-modal time-counter-modal"><div className="card-head"><div><div className="eyebrow">RESUMO DE TEMPO</div><h2>{detail.employee.name}</h2><p className="muted">{new Date(`${detail.workDate}T12:00:00`).toLocaleDateString('pt-BR')} · Matrícula {detail.employee.employeeNumber}</p></div><button className="ghost" onClick={()=>setDetail(null)}>Fechar</button></div>
   <div className="work-time-counters monitor-detail-counters time-counter-grid"><div><span>Tempo efetivo de trabalho</span><strong>{duration(detail.timers.workSeconds)}</strong></div><div><span>Tempo de intervalo</span><strong>{duration(detail.timers.breakSeconds)}</strong></div><div><span>Tempo de pausa</span><strong>{duration(detail.timers.pauseSeconds)}</strong></div></div>
   <p className="muted time-counter-note">Status do dia: <b>{status(detail.timers.state)}</b>. Estes tempos são indicadores gerenciais; a apuração oficial do ponto é feita pelas marcações.</p>
  </div></div>}
 </div>;
}
