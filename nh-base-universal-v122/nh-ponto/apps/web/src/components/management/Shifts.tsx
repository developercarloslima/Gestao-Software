import { FormEvent, useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { fmtMinutes } from '../../lib/format';

type DayKey='MON'|'TUE'|'WED'|'THU'|'FRI'|'SAT'|'SUN';
type DaySchedule={enabled:boolean;startTime:string;endTime:string;breakMinutes:number};
type DaySchedules=Record<DayKey,DaySchedule>;

type EmployeeRow={id:string;name:string;employeeNumber:string;jobTitle?:string|null;shiftId?:string|null;shift?:{id:string;name:string}|null;user?:{role?:string}|null;worksite?:{name:string}|null};

const dayOptions:Array<[DayKey,string]>=[['MON','Segunda'],['TUE','Terça'],['WED','Quarta'],['THU','Quinta'],['FRI','Sexta'],['SAT','Sábado'],['SUN','Domingo']];
const defaultDays:DaySchedules={
 MON:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},
 TUE:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},
 WED:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},
 THU:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},
 FRI:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},
 SAT:{enabled:true,startTime:'08:00',endTime:'12:00',breakMinutes:0},
 SUN:{enabled:false,startTime:'08:00',endTime:'12:00',breakMinutes:0}
};

function cloneDays(days:DaySchedules):DaySchedules{return JSON.parse(JSON.stringify(days)) as DaySchedules;}
function clockMinutes(value:string){const [h,m]=value.split(':').map(Number);return h*60+m;}
function dayMinutes(day:DaySchedule){if(!day.enabled)return 0;const start=clockMinutes(day.startTime),end=clockMinutes(day.endTime);const span=end>start?end-start:(24*60-start)+end;return Math.max(0,span-Math.max(0,day.breakMinutes));}
function legacyDays(shift:any):DaySchedules{
 const result=cloneDays(defaultDays);
 for(const key of Object.keys(result) as DayKey[])result[key]={enabled:false,startTime:shift.startTime??'08:00',endTime:shift.endTime??'18:00',breakMinutes:Number(shift.breakMinutes??0)};
 const enabled=Array.isArray(shift.scheduleDays)?shift.scheduleDays as DayKey[]:[];
 for(const key of enabled){if(result[key])result[key]={enabled:true,startTime:shift.startTime??'08:00',endTime:shift.endTime??'18:00',breakMinutes:Number(shift.breakMinutes??0)};}
 return result;
}
function shiftDays(shift:any):DaySchedules{
 const raw=shift?.daySchedules;
 if(raw&&typeof raw==='object'&&!Array.isArray(raw)&&Object.keys(raw).length){
   const result=cloneDays(defaultDays);
   for(const key of Object.keys(result) as DayKey[]){const d=raw[key];if(d&&typeof d==='object')result[key]={enabled:Boolean(d.enabled),startTime:String(d.startTime??result[key].startTime),endTime:String(d.endTime??result[key].endTime),breakMinutes:Number(d.breakMinutes??0)};}
   return result;
 }
 return legacyDays(shift);
}

export default function Shifts(){
 const [items,setItems]=useState<any[]>([]),[employees,setEmployees]=useState<EmployeeRow[]>([]),[company,setCompany]=useState<any>(null),[msg,setMsg]=useState(''),[editingId,setEditingId]=useState('');
 const [assignmentShiftId,setAssignmentShiftId]=useState(''),[assignmentIds,setAssignmentIds]=useState<string[]>([]),[assignBusy,setAssignBusy]=useState(false);
 const [form,setForm]=useState({name:'Administrativo 44h semanais - diurno',daySchedules:cloneDays(defaultDays)});
 const load=()=>Promise.all([api<any[]>('/admin/shifts'),api<any>('/admin/company'),api<EmployeeRow[]>('/admin/employees')]).then(([rows,c,people])=>{
   setItems(rows);setCompany(c);setEmployees(people);
   setAssignmentShiftId(current=>current&&rows.some(r=>r.id===current)?current:(rows.find(r=>r.active)?.id??''));
 });
 useEffect(()=>{load().catch(()=>{});},[]);
 const weeklyMinutes=useMemo(()=>dayOptions.reduce((sum,[key])=>sum+dayMinutes(form.daySchedules[key]),0),[form.daySchedules]);
 const monthlyDivisor=Number(company?.rules?.monthlyHoursDivisor??220)||220;
 const assignmentShift=items.find(x=>x.id===assignmentShiftId)??null;
 function setDay(key:DayKey,patch:Partial<DaySchedule>){setForm(current=>({...current,daySchedules:{...current.daySchedules,[key]:{...current.daySchedules[key],...patch}}}));}
 function resetForm(){setEditingId('');setForm({name:'Administrativo 44h semanais - diurno',daySchedules:cloneDays(defaultDays)});}
 function edit(item:any){setEditingId(item.id);setForm({name:item.name??'',daySchedules:shiftDays(item)});setMsg('');window.scrollTo({top:0,behavior:'smooth'});}
 function toggleEmployee(id:string,checked:boolean){setAssignmentIds(current=>checked?[...new Set([...current,id])]:current.filter(x=>x!==id));}
 async function submit(e:FormEvent){e.preventDefault();setMsg('');try{
   if(weeklyMinutes<=0)throw new Error('Ative pelo menos um dia da jornada.');
   for(const [key,label] of dayOptions){const d=form.daySchedules[key];if(d.enabled&&dayMinutes(d)<=0)throw new Error(`Revise a jornada de ${label}: o intervalo não pode consumir todo o período.`);}
   const payload={name:form.name,timezone:'America/Maceio',overtimeToleranceMin:10,daySchedules:form.daySchedules};
   if(editingId){await api(`/admin/shifts/${editingId}`,{method:'PATCH',body:JSON.stringify(payload)});setMsg('Jornada/escala atualizada. Os colaboradores vinculados receberam os novos dias e horários.');}
   else{await api('/admin/shifts',{method:'POST',body:JSON.stringify(payload)});setMsg('Jornada/escala criada e pronta para ser vinculada aos colaboradores.');}
   resetForm();await load();
 }catch(e){setMsg(e instanceof Error?e.message:'Erro');}}
 async function toggle(item:any){try{await api(`/admin/shifts/${item.id}`,{method:'PATCH',body:JSON.stringify({active:!item.active})});await load();}catch(e){setMsg(e instanceof Error?e.message:'Erro');}}
 async function remove(item:any){if(!window.confirm(`Excluir a jornada/escala ${item.name}? Os colaboradores vinculados manterão uma cópia individual dos dias e horários atuais, mas deixarão de estar vinculados a este modelo.`))return;try{await api(`/admin/shifts/${item.id}`,{method:'DELETE'});if(editingId===item.id)resetForm();setAssignmentIds([]);await load();setMsg(`Jornada/escala ${item.name} excluída.`);}catch(e){setMsg(e instanceof Error?e.message:'Erro');}}
 async function assign(){if(!assignmentShiftId) return setMsg('Selecione uma jornada/escala.');if(!assignmentIds.length)return setMsg('Selecione pelo menos um colaborador.');setAssignBusy(true);setMsg('');try{
   const result=await api<{assigned:number;shiftName:string}>(`/admin/shifts/${assignmentShiftId}/assign`,{method:'POST',body:JSON.stringify({employeeIds:assignmentIds})});
   setMsg(`${result.shiftName} vinculada a ${result.assigned} colaborador(es). Os dias e horários definidos passam a valer imediatamente para novas entradas.`);setAssignmentIds([]);await load();
 }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível vincular a jornada.');}finally{setAssignBusy(false);}}
 return <div className="grid-two"><section className="card"><div className="eyebrow">JORNADAS E ESCALAS · ADM / SUPER ADM</div><h2>{editingId?'Editar jornada / escala':'Criar jornada / escala'}</h2>
  <div className="payroll-reference-strip"><b>Referência: 44h/semana · {monthlyDivisor}h/mês</b><span>Cada dia pode ter horário próprio. Dias desmarcados são folga e não permitem iniciar jornada.</span></div>
  <form className="stack" onSubmit={submit}><label>Nome da jornada / escala<input required value={form.name} onChange={e=>setForm({...form,name:e.target.value})} placeholder="Ex.: Comercial segunda a sábado"/></label>
   <div className="schedule-week-grid">{dayOptions.map(([key,label])=>{const d=form.daySchedules[key],minutes=dayMinutes(d);return <div className={`schedule-day-row ${d.enabled?'enabled':'disabled'}`} key={key}>
    <label className="schedule-day-toggle"><input type="checkbox" checked={d.enabled} onChange={e=>setDay(key,{enabled:e.target.checked})}/><b>{label}</b></label>
    <label>Entrada<input type="time" disabled={!d.enabled} value={d.startTime} onChange={e=>setDay(key,{startTime:e.target.value})}/></label>
    <label>Saída<input type="time" disabled={!d.enabled} value={d.endTime} onChange={e=>setDay(key,{endTime:e.target.value})}/></label>
    <label>Intervalo (min)<input type="number" min="0" max="240" disabled={!d.enabled} value={d.breakMinutes} onChange={e=>setDay(key,{breakMinutes:Number(e.target.value)})}/></label>
    <div className="schedule-day-total"><span>Trabalhadas</span><b>{d.enabled?fmtMinutes(minutes):'Folga'}</b></div>
   </div>})}</div>
   <div className={`weekly-total ${weeklyMinutes===2640?'target':''}`}><div><span>Total semanal calculado</span><strong>{fmtMinutes(weeklyMinutes)}</strong></div><div><span>Referência contratual</span><strong>44h</strong></div><small>{weeklyMinutes===2640?'Jornada fechando em 44 horas semanais.':'Confira se a carga cadastrada corresponde ao contrato/escala do colaborador.'}</small></div>
   <div className="notice-box"><b>Entrada protegida pela escala · tolerância 15/15</b><p className="muted">A marcação direta da entrada fica disponível de <b>15 minutos antes até 15 minutos depois</b> do início previsto. Ex.: entrada às 08:00 → janela direta de 07:45 a 08:15. Depois de 08:15, o colaborador precisa informar a hora real em que chegou e justificar o atraso; a entrada só é criada naquele horário após aprovação. Fora do dia escalado a entrada continua bloqueada. Uma liberação explícita de hora extra segue como exceção autorizada.</p></div>
   <div className="notice-box"><b>Tolerância fixa de 10 minutos na saída</b><p className="muted">Não pode ser alterada. Até +10 min a saída manual exige selfie normalmente. De +10 a +15 min o ponto fica travado para outras ações e a saída manual continua exigindo selfie. Ao completar +15 min sem saída, o sistema encerra automaticamente sem selfie e envia para revisão.</p></div>
   <div className="inline-actions"><button className="primary">{editingId?'Salvar jornada / escala':'Criar jornada / escala'}</button>{editingId&&<button type="button" className="ghost" onClick={resetForm}>Cancelar</button>}</div>{msg&&<div className="success-box">{msg}</div>}
  </form></section>
  <section className="card"><div className="eyebrow">JORNADAS / ESCALAS CADASTRADAS</div><h2>Modelos disponíveis</h2><p className="muted">Editar um modelo sincroniza os novos dias e horários com todos os colaboradores que continuam vinculados a ele.</p>{items.map(s=>{const days=shiftDays(s);return <div className="request-item shift-list-item" key={s.id}><div><b>{s.name}</b><span>{fmtMinutes(Number(s.weeklyMinutes??0))}/semana · {Number(s._count?.employees??0)} vínculo(s)</span><small>{dayOptions.filter(([key])=>days[key].enabled).map(([key,label])=>`${label.slice(0,3)} ${days[key].startTime}–${days[key].endTime}${days[key].breakMinutes?` / int. ${days[key].breakMinutes}m`:''}`).join(' · ')}</small></div><div className="inline-actions"><span className={`status ${s.active?'approved':'rejected'}`}>{s.active?'ATIVA':'INATIVA'}</span><button type="button" className="ghost" onClick={()=>edit(s)}>Editar</button><button type="button" className="ghost" onClick={()=>toggle(s)}>{s.active?'Desativar':'Ativar'}</button><button type="button" className="ghost danger" onClick={()=>remove(s)}>Excluir</button></div></div>})}</section>
  <section className="card span-wide"><div className="card-head"><div><div className="eyebrow">VINCULAR JORNADA / ESCALA</div><h2>Aplicar modelo aos colaboradores</h2><p className="muted">Selecione a escala e marque uma ou várias pessoas. O vínculo substitui a jornada individual atual pelos dias e horários do modelo escolhido.</p></div></div>
   <div className="grid-two"><label>Jornada / escala<select value={assignmentShiftId} onChange={e=>{setAssignmentShiftId(e.target.value);setAssignmentIds([]);}}><option value="">Selecione</option>{items.filter(x=>x.active).map(x=><option value={x.id} key={x.id}>{x.name}</option>)}</select></label><div className="fact-row"><span>Selecionados</span><b>{assignmentIds.length}</b></div></div>
   {assignmentShift&&<div className="notice-box"><b>{assignmentShift.name}</b><p className="muted">{dayOptions.filter(([key])=>shiftDays(assignmentShift)[key].enabled).map(([key,label])=>`${label}: ${shiftDays(assignmentShift)[key].startTime}–${shiftDays(assignmentShift)[key].endTime}`).join(' · ')}</p></div>}
   <div className="inline-actions"><button type="button" className="ghost" onClick={()=>setAssignmentIds(employees.map(e=>e.id))}>Selecionar todos</button><button type="button" className="ghost" onClick={()=>setAssignmentIds(employees.filter(e=>e.shiftId!==assignmentShiftId).map(e=>e.id))}>Somente diferente desta escala</button><button type="button" className="ghost" onClick={()=>setAssignmentIds([])}>Limpar</button><button type="button" className="primary" disabled={assignBusy||!assignmentShiftId||!assignmentIds.length} onClick={assign}>{assignBusy?'Vinculando…':`Vincular ${assignmentIds.length||''} colaborador(es)`}</button></div>
   <div className="table-wrap"><table className="team-table"><thead><tr><th></th><th>Nome</th><th>Perfil</th><th>Unidade</th><th>Jornada atual</th></tr></thead><tbody>{employees.map(e=><tr key={e.id}><td><input type="checkbox" checked={assignmentIds.includes(e.id)} onChange={ev=>toggleEmployee(e.id,ev.target.checked)} aria-label={`Selecionar ${e.name}`}/></td><td><b>{e.name}</b><small>{e.employeeNumber}{e.jobTitle?` · ${e.jobTitle}`:''}</small></td><td>{e.user?.role??'EMPLOYEE'}</td><td>{e.worksite?.name??'—'}</td><td>{e.shift?.name??'Jornada individual'}</td></tr>)}</tbody></table></div>
  </section>
 </div>;
}
