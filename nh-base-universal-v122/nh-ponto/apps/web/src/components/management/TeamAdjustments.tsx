import { FormEvent, useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { fmtMinutes } from '../../lib/format';

type Punch={id:string;recordNumber:string;type:string;occurredAt:string;source:string;decision:string;reviewReason?:string|null;localDate:string;localTime:string};
type EmployeeRow={id:string;name:string;employeeNumber:string;jobTitle?:string|null;department?:string|null;worksite?:string|null;schedule?:{startTime:string;endTime:string;breakMinutes:number}|null;punches:Punch[]};
type TeamDay={date:string;timezone:string;employees:EmployeeRow[]};
type BankOverview={month:string;items:any[]};
type Editing={employee:EmployeeRow;punch?:Punch|null;type:string;workDate:string;clockTime:string};

const LABELS:Record<string,string>={CLOCK_IN:'Entrada',BREAK_START:'Início intervalo',BREAK_END:'Retorno intervalo',PAUSE_START:'Início pausa',PAUSE_END:'Retorno pausa',CLOCK_OUT:'Saída'};
const ORDER=['CLOCK_IN','BREAK_START','BREAK_END','PAUSE_START','PAUSE_END','CLOCK_OUT'];
const pad=(n:number)=>String(n).padStart(2,'0');
function today(){const d=new Date();return `${d.getFullYear()}-${pad(d.getMonth()+1)}-${pad(d.getDate())}`;}
function brDate(value:string){const [y,m,d]=value.split('-');return `${d}/${m}/${y}`;}
function signed(value:number){return value>0?`+${fmtMinutes(value)}`:fmtMinutes(value);}
function punchOf(row:EmployeeRow,type:string){return row.punches.find(p=>p.type===type)??null;}

export default function TeamAdjustments(){
  const [date,setDate]=useState(today());
  const [data,setData]=useState<TeamDay|null>(null);
  const [bank,setBank]=useState<BankOverview|null>(null);
  const [search,setSearch]=useState('');
  const [editing,setEditing]=useState<Editing|null>(null);
  const [reason,setReason]=useState('');
  const [busy,setBusy]=useState(false);
  const [msg,setMsg]=useState('');

  async function load(targetDate=date){
    setBusy(true);setMsg('');
    try{
      const month=targetDate.slice(0,7);
      const [day,overview]=await Promise.all([
        api<TeamDay>(`/adjustments/team-day?date=${encodeURIComponent(targetDate)}`),
        api<BankOverview>(`/bank/overview?month=${encodeURIComponent(month)}`)
      ]);
      setData(day);setBank(overview);
    }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível carregar os ajustes da equipe.');}
    finally{setBusy(false);}
  }
  useEffect(()=>{void load(date);},[]);

  async function changeDate(value:string){setDate(value);setEditing(null);await load(value);}
  const bankMap=useMemo(()=>new Map((bank?.items??[]).map((i:any)=>[i.id,i])),[bank]);
  const filtered=useMemo(()=>{
    const q=search.trim().toLocaleLowerCase('pt-BR');
    if(!q)return data?.employees??[];
    return (data?.employees??[]).filter(e=>`${e.name} ${e.employeeNumber} ${e.jobTitle??''} ${e.department??''} ${e.worksite??''}`.toLocaleLowerCase('pt-BR').includes(q));
  },[data,search]);

  function openExisting(employee:EmployeeRow,punch:Punch){
    setReason('');setMsg('');setEditing({employee,punch,type:punch.type,workDate:punch.localDate||date,clockTime:punch.localTime||'08:00'});
  }
  function openMissing(employee:EmployeeRow,type='CLOCK_IN'){
    const defaultTime=type==='CLOCK_IN'?employee.schedule?.startTime:type==='CLOCK_OUT'?employee.schedule?.endTime:'';
    setReason('');setMsg('');setEditing({employee,punch:null,type,workDate:date,clockTime:defaultTime||'08:00'});
  }
  async function save(e:FormEvent){
    e.preventDefault();if(!editing||busy)return;setBusy(true);setMsg('');
    try{
      await api('/adjustments/direct',{method:'POST',body:JSON.stringify({employeeId:editing.employee.id,targetPunchId:editing.punch?.id||undefined,workDate:editing.workDate,clockTime:editing.clockTime,requestedType:editing.type,reason})});
      setEditing(null);setReason('');await load(editing.workDate);
      setDate(editing.workDate);setMsg(editing.punch?'Horário corrigido e registrado na auditoria.':'Marcação adicionada e registrada na auditoria.');
    }catch(error){setMsg(error instanceof Error?error.message:'Não foi possível salvar o ajuste.');}
    finally{setBusy(false);}
  }

  return <div className="team-adjustments-page">
    <section className="card team-adjustments-hero">
      <div className="card-head"><div><div className="eyebrow">AJUSTES DA EQUIPE</div><h2>Corrigir horários pelas marcações</h2><p className="muted">Selecione o dia, encontre o colaborador e ajuste diretamente a marcação. O sistema recalcula o banco de horas pelo horário corrigido, sem lançar horas manualmente.</p></div><div className="team-adjustments-actions"><label>Dia<input type="date" value={date} max={today()} onChange={e=>void changeDate(e.target.value)}/></label><button className="ghost" disabled={busy} onClick={()=>void load()}>{busy?'Atualizando…':'Atualizar'}</button></div></div>
      <div className="team-adjustments-toolbar"><input value={search} onChange={e=>setSearch(e.target.value)} placeholder="Buscar colaborador, matrícula, setor ou unidade…"/><span>{filtered.length} colaborador(es)</span></div>
      {msg&&<div className="success-box">{msg}</div>}
    </section>

    <section className="card">
      <div className="table-wrap"><table className="team-adjustments-table"><thead><tr><th>Colaborador</th><th>Jornada</th><th>Entrada</th><th>Início intervalo</th><th>Retorno intervalo</th><th>Saída</th><th>Trabalhado</th><th>Saldo do dia</th><th>Banco mensal</th><th>Banco acumulado</th><th></th></tr></thead><tbody>{filtered.map(employee=>{
        const b:any=bankMap.get(employee.id)??{};
        const day=(b.days??[]).find((d:any)=>d.date===date)??{};
        const cell=(type:string)=>{const p=punchOf(employee,type);return p?<button className="time-adjust-button" onClick={()=>openExisting(employee,p)} title={`Editar ${LABELS[type]??type}`}>{p.localTime}<small>#{p.recordNumber}</small></button>:<button className="time-adjust-button missing" onClick={()=>openMissing(employee,type)}>+ adicionar</button>;};
        return <tr key={employee.id}><td><b>{employee.name}</b><small>{employee.employeeNumber}{employee.department?` · ${employee.department}`:''}</small></td><td><b>{employee.schedule?`${employee.schedule.startTime}–${employee.schedule.endTime}`:'Sem jornada'}</b><small>{employee.schedule?`${employee.schedule.breakMinutes} min intervalo`:employee.worksite??''}</small></td><td>{cell('CLOCK_IN')}</td><td>{cell('BREAK_START')}</td><td>{cell('BREAK_END')}</td><td>{cell('CLOCK_OUT')}</td><td><b>{fmtMinutes(day.workedMinutes??0)}</b></td><td className={(day.balanceMinutes??0)<0?'negative':(day.balanceMinutes??0)>0?'positive':''}><b>{signed(day.balanceMinutes??0)}</b></td><td className={(b.monthBalanceMinutes??0)<0?'negative':(b.monthBalanceMinutes??0)>0?'positive':''}><b>{signed(b.monthBalanceMinutes??0)}</b></td><td className={(b.accumulatedBalanceMinutes??0)<0?'negative':(b.accumulatedBalanceMinutes??0)>0?'positive':''}><b>{signed(b.accumulatedBalanceMinutes??0)}</b></td><td><button className="ghost" onClick={()=>openMissing(employee)}>Nova marcação</button></td></tr>;
      })}{!filtered.length&&<tr><td colSpan={11} className="muted">Nenhum colaborador encontrado.</td></tr>}</tbody></table></div>
      <div className="notice-box"><b>Como funciona</b><p className="muted">Uma correção não apaga o registro original. O registro antigo fica preservado como substituído, a nova marcação é criada com trilha de auditoria e o banco daquele dia é recalculado. O espelho de ponto informa o ajuste, o motivo e o responsável.</p></div>
    </section>

    {editing&&<div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Ajustar marcação"><div className="modal-card team-adjustment-modal">
      <div className="card-head"><div><div className="eyebrow">CORREÇÃO AUDITÁVEL</div><h2>{editing.punch?'Ajustar horário':'Adicionar marcação'}</h2><p className="muted">{editing.employee.name} · {editing.employee.employeeNumber}</p></div><button type="button" className="ghost" onClick={()=>setEditing(null)}>Fechar</button></div>
      <div className="adjustment-balance-strip">{(()=>{const b:any=bankMap.get(editing.employee.id)??{};const day=(b.days??[]).find((d:any)=>d.date===editing.workDate)??{};return <><div><span>Saldo do dia</span><b className={(day.balanceMinutes??0)<0?'negative':'positive'}>{signed(day.balanceMinutes??0)}</b></div><div><span>Banco mensal</span><b className={(b.monthBalanceMinutes??0)<0?'negative':'positive'}>{signed(b.monthBalanceMinutes??0)}</b></div><div><span>Banco acumulado</span><b className={(b.accumulatedBalanceMinutes??0)<0?'negative':'positive'}>{signed(b.accumulatedBalanceMinutes??0)}</b></div></>;})()}</div>
      {editing.punch&&<div className="notice-box"><b>Marcação atual</b><p className="muted">{LABELS[editing.punch.type]??editing.punch.type} · {brDate(editing.punch.localDate)} às {editing.punch.localTime} · NSR #{editing.punch.recordNumber}</p></div>}
      <form className="form-grid" onSubmit={save}>
        <label>Tipo da marcação<select value={editing.type} onChange={e=>setEditing({...editing,type:e.target.value})}>{ORDER.map(type=><option value={type} key={type}>{LABELS[type]}</option>)}</select></label>
        <label>Dia<input type="date" required value={editing.workDate} max={today()} onChange={e=>setEditing({...editing,workDate:e.target.value})}/></label>
        <label>Horário<input type="time" required value={editing.clockTime} onChange={e=>setEditing({...editing,clockTime:e.target.value})}/></label>
        <label className="form-full">Motivo<textarea required minLength={5} value={reason} onChange={e=>setReason(e.target.value)} placeholder="Ex.: colaborador esqueceu de registrar a saída; horário conferido pela gestão."/></label>
        <div className="form-full inline-actions"><button className="primary" disabled={busy}>{busy?'Salvando…':editing.punch?'Salvar novo horário':'Adicionar marcação'}</button><button type="button" className="ghost" disabled={busy} onClick={()=>setEditing(null)}>Cancelar</button></div>
      </form>
    </div></div>}
  </div>;
}
