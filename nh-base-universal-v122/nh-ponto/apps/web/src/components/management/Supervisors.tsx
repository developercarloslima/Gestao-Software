import { FormEvent,useEffect,useMemo,useState } from 'react';
import { api,getCachedUser } from '../../lib/api';

type DayKey='MON'|'TUE'|'WED'|'THU'|'FRI'|'SAT'|'SUN';
type DaySchedule={enabled:boolean;startTime:string;endTime:string;breakMinutes:number};
type DaySchedules=Record<DayKey,DaySchedule>;

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
function scheduleFromShift(shift:any):DaySchedules{
 if(shift?.daySchedules&&typeof shift.daySchedules==='object'&&!Array.isArray(shift.daySchedules))return {...cloneDays(defaultDays),...shift.daySchedules};
 return cloneDays(defaultDays);
}
function blank(){return {name:'',employeeNumber:'',username:'',password:'',email:'',jobTitle:'Supervisor',departmentId:'',worksiteId:'',shiftId:''};}

export default function Supervisors(){
 const session=getCachedUser(); const allowed=['SUPER_ADMIN','ADMIN'].includes(session?.role??'');
 const [items,setItems]=useState<any[]>([]),[worksites,setWorksites]=useState<any[]>([]),[departments,setDepartments]=useState<any[]>([]),[shifts,setShifts]=useState<any[]>([]);
 const [form,setForm]=useState(blank()),[editingId,setEditingId]=useState(''),[msg,setMsg]=useState(''),[busy,setBusy]=useState(false);
 const load=()=>Promise.all([api<any[]>('/admin/supervisors'),api<any[]>('/admin/worksites'),api<any[]>('/admin/departments'),api<any[]>('/admin/shifts')]).then(([s,w,d,h])=>{setItems(s);setWorksites(w);setDepartments(d);setShifts(h);});
 useEffect(()=>{if(allowed)load().catch(e=>setMsg(e instanceof Error?e.message:'Erro ao carregar supervisores.'));},[allowed]);
 const grouped=useMemo(()=>worksites.filter(w=>w.archivedAt==null).map(w=>({...w,supervisors:items.filter(i=>i.worksiteId===w.id)})),[worksites,items]);
 function cancel(){setEditingId('');setForm(blank());setMsg('');}
 function edit(item:any){setEditingId(item.id);setForm({name:item.name??'',employeeNumber:item.employeeNumber??'',username:item.user?.email??'',password:'',email:item.user?.contactEmail??'',jobTitle:item.jobTitle??'Supervisor',departmentId:item.departmentId??'',worksiteId:item.worksiteId??'',shiftId:item.shiftId??''});setMsg('');window.scrollTo({top:0,behavior:'smooth'});}
 async function submit(e:FormEvent){
  e.preventDefault(); if(!allowed)return; setBusy(true);setMsg('');
  try{
   if(!form.name.trim())throw new Error('Informe o nome do supervisor.');
   if(!form.username.trim())throw new Error('Informe o usuário de acesso.');
   if(!editingId&&form.password.length<6)throw new Error('Informe uma senha inicial com pelo menos 6 caracteres.');
   if(!form.departmentId)throw new Error('Selecione o setor.');
   if(!form.worksiteId)throw new Error('Selecione o local/unidade do supervisor.');
   const selectedShift=shifts.find(x=>x.id===form.shiftId);
   const payload:any={name:form.name.trim(),employeeNumber:form.employeeNumber.trim()||undefined,username:form.username.trim(),...(form.password?{password:form.password}:{}),...(form.email.trim()?{email:form.email.trim()}:{}),role:'SUPERVISOR',jobTitle:form.jobTitle.trim()||'Supervisor',departmentId:form.departmentId,worksiteId:form.worksiteId,shiftId:form.shiftId||null,supervisorId:null};
   if(!editingId){payload.workSchedule=scheduleFromShift(selectedShift);payload.salaryCents=0;}
   else if(selectedShift)payload.workSchedule=scheduleFromShift(selectedShift);
   if(editingId){await api(`/admin/employees/${editingId}`,{method:'PATCH',body:JSON.stringify(payload)});setMsg('Supervisor atualizado.');}
   else{await api('/admin/employees',{method:'POST',body:JSON.stringify(payload)});setMsg('Supervisor criado.');}
   setEditingId('');setForm(blank());await load();
  }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível salvar o supervisor.');}finally{setBusy(false);}
 }
 async function remove(item:any){
  if(!allowed)return;
  const teamCount=Number(item?._count?.team??0);
  const detail=teamCount?` Este supervisor possui ${teamCount} colaborador(es) vinculados. Eles serão realocados para outro supervisor ativo do mesmo local, se existir; caso contrário ficarão sem supervisor.`:'';
  if(!window.confirm(`Excluir o supervisor ${item.name}?${detail}`))return;
  setBusy(true);setMsg('');
  try{await api(`/admin/employees/${item.id}`,{method:'DELETE'});setMsg('Supervisor excluído e histórico preservado.');if(editingId===item.id)cancel();await load();}
  catch(e){setMsg(e instanceof Error?e.message:'Não foi possível excluir o supervisor.');}finally{setBusy(false);}
 }
 if(!allowed)return <section className="card"><h2>Supervisores</h2><p className="muted">Sem permissão.</p></section>;
 return <div className="grid-two">
  <section className="card"><div className="eyebrow">SUPERVISORES</div><h2>{editingId?'Editar supervisor':'Novo supervisor'}</h2><p className="muted">Cadastre o supervisor e escolha o local/unidade pelo qual ele será responsável. A equipe só pode ser vinculada a supervisores ativos do mesmo local.</p>
   <form className="form-grid" onSubmit={submit}>
    <label>Nome completo<input required value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label>
    <label>Matrícula <span className="optional">opcional</span><input value={form.employeeNumber} onChange={e=>setForm({...form,employeeNumber:e.target.value})}/></label>
    <label>Usuário de acesso<input required autoCapitalize="none" value={form.username} onChange={e=>setForm({...form,username:e.target.value})}/></label>
    <label>{editingId?'Nova senha':'Senha inicial'} {editingId&&<span className="optional">opcional</span>}<input type="password" required={!editingId} minLength={editingId?0:6} value={form.password} onChange={e=>setForm({...form,password:e.target.value})}/></label>
    <label>E-mail <span className="optional">opcional</span><input type="email" value={form.email} onChange={e=>setForm({...form,email:e.target.value})}/></label>
    <label>Cargo<input value={form.jobTitle} onChange={e=>setForm({...form,jobTitle:e.target.value})}/></label>
    <label>Setor<select required value={form.departmentId} onChange={e=>setForm({...form,departmentId:e.target.value})}><option value="">Selecione</option>{departments.filter(x=>x.active!==false).map(x=><option key={x.id} value={x.id}>{x.name}</option>)}</select></label>
    <label>Local / unidade<select required value={form.worksiteId} onChange={e=>setForm({...form,worksiteId:e.target.value})}><option value="">Selecione</option>{worksites.filter(x=>x.active!==false&&x.archivedAt==null).map(x=><option key={x.id} value={x.id}>{x.name}</option>)}</select></label>
    <label>Modelo de jornada <span className="optional">opcional</span><select value={form.shiftId} onChange={e=>setForm({...form,shiftId:e.target.value})}><option value="">Jornada padrão administrativa</option>{shifts.filter(x=>x.active!==false).map(x=><option key={x.id} value={x.id}>{x.name}</option>)}</select></label>
    <div className="inline-actions"><button className="primary" disabled={busy}>{busy?'Aguarde…':editingId?'Salvar alterações':'Criar supervisor'}</button>{editingId&&<button type="button" className="ghost" onClick={cancel}>Cancelar</button>}</div>
    {msg&&<p className="muted inline-feedback">{msg}</p>}
   </form>
  </section>
  <section className="card"><div className="eyebrow">RESPONSÁVEIS POR LOCAL</div><h2>Supervisores por unidade</h2><p className="muted">Editar um supervisor permite trocar sua unidade. Ao mover um supervisor, os colaboradores da unidade anterior são realocados para outro supervisor ativo daquele local, quando houver.</p>
   {grouped.map(w=><div className="request-item" key={w.id}><div><b>{w.name}</b><span>{w.address??'Endereço não informado'}{w.city?` · ${w.city}/${w.stateCode??''}`:''}</span><small>{w.supervisors.length?`${w.supervisors.length} supervisor(es)`:'Nenhum supervisor cadastrado'}</small></div><div className="supervisor-local-list">{w.supervisors.length?w.supervisors.map((s:any)=><div className="inline-actions" key={s.id}><span><b>{s.name}</b> · equipe {s._count?.team??0}</span><button className="ghost" onClick={()=>edit(s)}>Editar</button><button className="ghost danger" disabled={busy} onClick={()=>remove(s)}>Excluir</button></div>):<span className="muted">Sem supervisor ativo.</span>}</div></div>)}
  </section>
 </div>;
}
