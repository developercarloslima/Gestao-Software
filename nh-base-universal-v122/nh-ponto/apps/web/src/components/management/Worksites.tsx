import { FormEvent,useEffect,useState } from 'react';
import { api,getCachedUser } from '../../lib/api';

const blank={name:'',address:'',city:'',stateCode:'AL',latitude:'',longitude:'',bluetoothNamePrefix:'',nfcTagId:'',networkGatewayId:''};
type DeletionImpact={worksite:any;linkedEmployees:any[];destinations:any[]};

export default function Worksites(){
 const [items,setItems]=useState<any[]>([]),[company,setCompany]=useState<any>(null),[msg,setMsg]=useState(''),[form,setForm]=useState({...blank}),[busy,setBusy]=useState(false),[editingId,setEditingId]=useState<string|null>(null);
 const [deleteImpact,setDeleteImpact]=useState<DeletionImpact|null>(null),[choices,setChoices]=useState<Record<string,string>>({}),[deleteMsg,setDeleteMsg]=useState('');
 const currentUser=getCachedUser();const superAdmin=currentUser?.role==='SUPER_ADMIN';const canDelete=['SUPER_ADMIN','ADMIN'].includes(currentUser?.role??'');
 const load=()=>Promise.all([api<any[]>('/admin/worksites'),api<any>('/admin/company')]).then(([w,c])=>{setItems(w);setCompany(c?.tenant??null);});
 useEffect(()=>{load().catch(()=>{});},[]);
 function resetForm(){setEditingId(null);setForm({...blank});}
 function edit(w:any){if(!superAdmin)return;setEditingId(w.id);setForm({name:w.name??'',address:w.address??'',city:w.city??'',stateCode:w.stateCode??'AL',latitude:w.latitude==null?'':String(w.latitude),longitude:w.longitude==null?'':String(w.longitude),bluetoothNamePrefix:w.bluetoothNamePrefix??'',nfcTagId:w.nfcTagId??'',networkGatewayId:w.networkGatewayId??''});window.scrollTo({top:0,behavior:'smooth'});}
 async function submit(e:FormEvent){
  e.preventDefault();setBusy(true);setMsg('');
  try{
   const payload={...form,stateCode:form.stateCode.trim().toUpperCase(),latitude:form.latitude.trim()===''?null:Number(form.latitude),longitude:form.longitude.trim()===''?null:Number(form.longitude)};
   if(editingId){if(!superAdmin)throw new Error('Somente o Super Adm pode editar unidades.');await api(`/admin/worksites/${editingId}`,{method:'PATCH',body:JSON.stringify(payload)});setMsg('Unidade atualizada.');}
   else{await api('/admin/worksites',{method:'POST',body:JSON.stringify(payload)});setMsg('Unidade cadastrada.');}
   resetForm();await load();
  }catch(e){setMsg(e instanceof Error?e.message:'Erro');}finally{setBusy(false);}
 }
 async function toggle(w:any){await api(`/admin/worksites/${w.id}`,{method:'PATCH',body:JSON.stringify({active:!w.active})});await load();}
 async function remove(w:any){
  if(!canDelete)return;setBusy(true);setDeleteMsg('');
  try{
   const impact=await api<DeletionImpact>(`/admin/worksites/${w.id}/deletion-impact`);
   setDeleteImpact(impact);setChoices({});
  }catch(e){setMsg(e instanceof Error?e.message:'Erro ao preparar exclusão da unidade.');}finally{setBusy(false);}
 }
 function deletionActions(){
  if(!deleteImpact)return [];
  return deleteImpact.linkedEmployees.map(emp=>{
    const selected=choices[emp.id]??'';
    return selected==='__ARCHIVE__'?{employeeId:emp.id,action:'ARCHIVE'}:{employeeId:emp.id,action:'REALLOCATE',targetWorksiteId:selected};
  });
 }
 function validateChoices(allowArchive:boolean){
  if(!deleteImpact)return false;
  for(const emp of deleteImpact.linkedEmployees){
    const selected=choices[emp.id];
    if(!selected){setDeleteMsg(`Selecione o que fazer com ${emp.name}.`);return false;}
    if(selected==='__ARCHIVE__'&&!allowArchive){setDeleteMsg('Para “Salvar todas as realocações”, todos os colaboradores precisam ter uma unidade de destino.');return false;}
  }
  return true;
 }
 async function saveReallocations(){
  if(!deleteImpact||!validateChoices(false))return;setBusy(true);setDeleteMsg('');
  try{
    await api(`/admin/worksites/${deleteImpact.worksite.id}/resolve-delete`,{method:'POST',body:JSON.stringify({deleteWorksite:false,actions:deletionActions()})});
    const refreshed=await api<DeletionImpact>(`/admin/worksites/${deleteImpact.worksite.id}/deletion-impact`);setDeleteImpact(refreshed);setChoices({});setDeleteMsg('Todas as realocações foram salvas. Agora a unidade pode ser excluída.');await load();
  }catch(e){setDeleteMsg(e instanceof Error?e.message:'Não foi possível salvar as realocações.');}finally{setBusy(false);}
 }
 async function saveAndDelete(){
  if(!deleteImpact)return;
  if(deleteImpact.linkedEmployees.length&&!validateChoices(true))return;
  setBusy(true);setDeleteMsg('');
  try{
   if(deleteImpact.linkedEmployees.length){await api(`/admin/worksites/${deleteImpact.worksite.id}/resolve-delete`,{method:'POST',body:JSON.stringify({deleteWorksite:true,actions:deletionActions()})});}
   else{await api(`/admin/worksites/${deleteImpact.worksite.id}`,{method:'DELETE'});}
   if(editingId===deleteImpact.worksite.id)resetForm();setDeleteImpact(null);setChoices({});setMsg(`Unidade ${deleteImpact.worksite.name} excluída.`);await load();
  }catch(e){setDeleteMsg(e instanceof Error?e.message:'Erro ao excluir unidade.');}finally{setBusy(false);}
 }

 return <div className="grid-two worksites-layout"><section className="card"><div className="eyebrow">UNIDADES</div><h2>{editingId?'Editar unidade':'Nova unidade'}</h2><p className="muted">A unidade serve para organização dos colaboradores, relatórios e comparação manual das evidências de localização.</p><form className="form-grid" onSubmit={submit}><label>Nome da unidade<input required value={form.name} onChange={e=>setForm({...form,name:e.target.value})}/></label><label>Endereço<input required value={form.address} onChange={e=>setForm({...form,address:e.target.value})}/></label><label>Cidade<input required value={form.city} onChange={e=>setForm({...form,city:e.target.value})}/></label><label>UF<input required maxLength={2} value={form.stateCode} onChange={e=>setForm({...form,stateCode:e.target.value.toUpperCase()})}/></label><label>Latitude de referência <span className="optional">opcional</span><input type="number" step="any" min="-90" max="90" inputMode="decimal" placeholder="Ex.: -9.649823" value={form.latitude} onChange={e=>setForm({...form,latitude:e.target.value})}/></label><label>Longitude de referência <span className="optional">opcional</span><input type="number" step="any" min="-180" max="180" inputMode="decimal" placeholder="Ex.: -35.708412" value={form.longitude} onChange={e=>setForm({...form,longitude:e.target.value})}/></label><label>Prefixo do Beacon Bluetooth <span className="optional">opcional</span><input placeholder="Ex.: AD-MACEIO-" value={form.bluetoothNamePrefix} onChange={e=>setForm({...form,bluetoothNamePrefix:e.target.value})}/></label><label>ID da tag NFC <span className="optional">opcional</span><input value={form.nfcTagId} onChange={e=>setForm({...form,nfcTagId:e.target.value})}/></label><label>ID do gateway de rede local <span className="optional">opcional</span><input value={form.networkGatewayId} onChange={e=>setForm({...form,networkGatewayId:e.target.value})}/></label><div className="worksite-form-actions"><button className="primary" disabled={busy}>{busy?'Aguarde…':editingId?'Salvar alterações':'Cadastrar unidade'}</button>{editingId&&<button type="button" className="ghost" onClick={resetForm}>Cancelar</button>}</div>{msg&&<p className="muted inline-feedback">{msg}</p>}</form></section><section className="card"><div className="eyebrow">LOCAIS CADASTRADOS</div><h2>Unidades</h2>{items.length?items.map(w=><div className="request-item worksite-item" key={w.id}><div className="worksite-copy"><b>{w.name}</b><span>{w.address??'Endereço não informado'}{w.city?` · ${w.city}/${w.stateCode??''}`:''}</span>{w.latitude!=null&&w.longitude!=null&&<small>Referência: {Number(w.latitude).toFixed(6)}, {Number(w.longitude).toFixed(6)}</small>}{Array.isArray(w.supervisors)&&<small>Supervisores: {w.supervisors.length?w.supervisors.map((s:any)=>s.name).join(', '):'nenhum cadastrado'}</small>}</div><div><span className={`status ${w.active?'approved':'rejected'}`}>{w.active?'UNIDADE ATIVA':'UNIDADE INATIVA'}</span></div><div className="worksite-actions">{superAdmin&&<button className="ghost" onClick={()=>edit(w)}>Editar</button>}<button className="ghost" onClick={()=>toggle(w)}>{w.active?'Desativar unidade':'Ativar unidade'}</button>{canDelete&&<button className="ghost danger" onClick={()=>remove(w)}>Excluir</button>}</div></div>):<p className="muted">Nenhuma unidade cadastrada.</p>}</section>
 {deleteImpact&&<div className="modal-backdrop worksite-delete-backdrop" role="dialog" aria-modal="true"><div className="card worksite-delete-modal"><div className="eyebrow">EXCLUIR UNIDADE</div><h2>{deleteImpact.worksite.name}</h2>{deleteImpact.linkedEmployees.length?<><p>Esta unidade possui colaboradores vinculados. Exclua os colaboradores ou realoque eles antes de concluir a exclusão da unidade.</p><div className="worksite-linked-list">{deleteImpact.linkedEmployees.map(emp=><div className="worksite-linked-row" key={emp.id}><div><b>{emp.name}</b><small>Matrícula {emp.employeeNumber}{emp.user?.role?` · ${emp.user.role}`:''}</small></div><label>Destino / ação<select value={choices[emp.id]??''} onChange={e=>setChoices(current=>({...current,[emp.id]:e.target.value}))}><option value="">Selecione</option>{deleteImpact.destinations.map(w=><option value={w.id} key={w.id}>{w.name}</option>)}{emp.canArchive&&<option value="__ARCHIVE__">Excluir colaborador e preservar histórico</option>}</select></label></div>)}</div></>:<p>Nenhum colaborador ativo está vinculado a esta unidade. Ela pode ser excluída agora.</p>}{deleteMsg&&<p className="muted inline-feedback">{deleteMsg}</p>}<div className="inline-actions worksite-delete-actions"><button type="button" className="ghost" onClick={()=>{setDeleteImpact(null);setChoices({});setDeleteMsg('');}}>Cancelar</button>{deleteImpact.linkedEmployees.length>0&&<button type="button" className="ghost" disabled={busy} onClick={saveReallocations}>Salvar todas realocações</button>}<button type="button" className="primary" disabled={busy} onClick={saveAndDelete}>{busy?'Aguarde…':'Salvar e excluir unidade'}</button></div></div></div>}
 </div>;
}
