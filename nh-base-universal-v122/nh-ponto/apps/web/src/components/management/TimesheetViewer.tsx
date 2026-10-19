import { useEffect, useMemo, useState } from 'react';
import { api, apiBlob, getCachedUser } from '../../lib/api';
import { enrollPlatformBiometric, performTimesheetAcceptance, supportsWebAuthn } from '../../lib/webauthn';
import TimesheetDocument from '../TimesheetDocument';
import { printTimesheetOnly } from '../../lib/printTimesheet';

function rangeFromMonth(month:string){
  const [year,m]=month.split('-').map(Number);
  const last=new Date(year,m,0).getDate();
  return {from:`${year}-${String(m).padStart(2,'0')}-01`,to:`${year}-${String(m).padStart(2,'0')}-${String(last).padStart(2,'0')}`};
}
function currentMonth(){
  const now=new Date();
  return `${now.getFullYear()}-${String(now.getMonth()+1).padStart(2,'0')}`;
}
function safeFilePart(value:string){return value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/[^a-zA-Z0-9]+/g,'-').replace(/^-+|-+$/g,'').toLowerCase();}
function roleLabel(role:string){return ({SUPER_ADMIN:'Super Adm',ADMIN:'Administrador',HR:'RH',SUPERVISOR:'Supervisor',EMPLOYEE:'Colaborador',AUDITOR:'Auditor',TIME_REVIEWER:'Responsável de ponto'} as Record<string,string>)[role]??role;}
function fmtDate(value?:string|null){if(!value)return '—';const [y,m,d]=String(value).slice(0,10).split('-');return y&&m&&d?`${d}/${m}/${y}`:String(value);}
function fmtDateTime(value?:string|null){if(!value)return '—';const d=new Date(value);return Number.isNaN(d.getTime())?String(value):d.toLocaleString('pt-BR');}

type EmployeeOption={id:string;name:string;employeeNumber:string;jobTitle?:string|null;role:string};
type JustificationDetail={
  id:string;employee:{id:string;name:string;employeeNumber:string};requestedTime:string;submittedAt:string;reason:string;
  type:string;typeLabel:string;documentType?:string|null;documentFileName?:string|null;documentMimeType?:string|null;documentUploadedAt?:string|null;documentAvailable:boolean;
  selfieCapturedAt?:string|null;selfieAvailable:boolean;selfieLocation?:{latitude:number;longitude:number;accuracyM?:number|null}|null;
  status:string;statusLabel:string;reviewNote?:string|null;reviewedAt?:string|null;automaticRejection:boolean;reviewer?:{id?:string|null;name:string;role:string}|null;
};

export default function TimesheetViewer(){
  const [month,setMonth]=useState(currentMonth());
  const range=useMemo(()=>rangeFromMonth(month),[month]);
  const [employees,setEmployees]=useState<EmployeeOption[]>([]);
  const [employeeId,setEmployeeId]=useState('');
  const [sheet,setSheet]=useState<any>(null);
  const [busy,setBusy]=useState(false);
  const [msg,setMsg]=useState('');
  const [justificationDetail,setJustificationDetail]=useState<JustificationDetail|null>(null);
  const [detailBusy,setDetailBusy]=useState(false);
  const [detailSelfieUrl,setDetailSelfieUrl]=useState('');
  const [detailDocumentUrl,setDetailDocumentUrl]=useState('');
  const [detailDocumentMime,setDetailDocumentMime]=useState('');
  const currentUser=getCachedUser();
  const canSignAsAdmin=['ADMIN','SUPER_ADMIN'].includes(String(currentUser?.role??''));
  const canViewJustificationDetails=['SUPER_ADMIN','TIME_REVIEWER'].includes(String(currentUser?.role??''));

  useEffect(()=>{
    api<EmployeeOption[]>('/admin/timesheet-employees').then(rows=>{setEmployees(rows);setEmployeeId(current=>current||rows[0]?.id||'');}).catch(e=>setMsg(e instanceof Error?e.message:'Não foi possível carregar os colaboradores.'));
  },[]);

  function clearDetail(){
    if(detailSelfieUrl)URL.revokeObjectURL(detailSelfieUrl);
    if(detailDocumentUrl)URL.revokeObjectURL(detailDocumentUrl);
    setDetailSelfieUrl('');setDetailDocumentUrl('');setDetailDocumentMime('');setJustificationDetail(null);
  }

  async function openJustification(id:string){
    clearDetail();setDetailBusy(true);setMsg('');
    try{
      const detail=await api<JustificationDetail>(`/adjustments/${id}/late-justification-detail`);
      setJustificationDetail(detail);
      if(detail.selfieAvailable){const blob=await apiBlob(`/adjustments/${id}/selfie`);setDetailSelfieUrl(URL.createObjectURL(blob));}
      if(detail.documentAvailable){const blob=await apiBlob(`/adjustments/${id}/document`);setDetailDocumentMime(blob.type||detail.documentMimeType||'application/octet-stream');setDetailDocumentUrl(URL.createObjectURL(blob));}
    }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível abrir a justificativa.');}
    finally{setDetailBusy(false);}
  }

  async function openSheet(){
    if(!employeeId)return;
    clearDetail();setBusy(true);setMsg('');setSheet(null);
    try{
      const result=await api<any>(`/time/employees/${employeeId}?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`);
      setSheet(result);
      window.setTimeout(()=>document.getElementById('reviewer-timesheet')?.scrollIntoView({behavior:'smooth',block:'start'}),30);
    }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível gerar o espelho.');}
    finally{setBusy(false);}
  }

  function printSheet(){
    if(!sheet)return;
    printTimesheetOnly('reviewer-timesheet',`espelho-ponto-${safeFilePart(sheet.employee?.name??'colaborador')}-${month}`);
  }

  async function acceptAsAdmin(){
    if(!sheet?.employee?.id)return;
    setBusy(true);setMsg('');
    try{
      let result:any;
      try{result=await performTimesheetAcceptance(sheet.employee.id,month,'ADMIN');}
      catch(error:any){
        if(/TIMESHEET_PASSKEY_REQUIRED|Cadastre a biometria\/passkey|passkey/i.test(String(error?.message||''))&&supportsWebAuthn()){
          await enrollPlatformBiometric('Assinatura mensal do espelho de ponto');
          result=await performTimesheetAcceptance(sheet.employee.id,month,'ADMIN');
        }else throw error;
      }
      setSheet((current:any)=>current?{...current,acceptance:result.acceptance}:current);
      setMsg(`Aceite WebAuthn registrado para o espelho de ${sheet.employee.name}.`);
    }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível registrar o aceite do espelho.');}
    finally{setBusy(false);}
  }

  const selected=employees.find(e=>e.id===employeeId);
  return <>
    <section className="card">
      <div className="card-head"><div><div className="eyebrow">CONFERÊNCIA · ESPELHO DE PONTOS</div><h2>Consultar espelho de ponto</h2><p className="muted">Visualização operacional das jornadas. Esta área não mostra salário, valores de folha ou configurações financeiras.</p></div></div>
      <div className="form-grid">
        <label>Colaborador / administrador<select value={employeeId} onChange={e=>{setEmployeeId(e.target.value);setSheet(null);clearDetail();}}><option value="">Selecione…</option>{employees.map(e=><option key={e.id} value={e.id}>{e.name} · {roleLabel(e.role)} · {e.employeeNumber}</option>)}</select>{selected&&<small>{roleLabel(selected.role)}{selected.jobTitle?` · ${selected.jobTitle}`:''}</small>}</label>
        <label>Competência<input type="month" value={month} onChange={e=>{setMonth(e.target.value);setSheet(null);clearDetail();}}/></label>
        <div className="form-full inline-actions"><button className="primary" disabled={busy||!employeeId} onClick={openSheet}>{busy?'Gerando…':'Gerar espelho de ponto'}</button></div>
      </div>
      {msg&&<p className="muted inline-feedback">{msg}</p>}
    </section>
    {sheet&&<section className="card timesheet-sheet" id="reviewer-timesheet">
      <div className="timesheet-preview-toolbar no-print"><div><div className="eyebrow">PRÉVIA DO ESPELHO</div><h2>{sheet.employee.name}</h2><p className="muted">Documento de jornada sem dados financeiros.</p>{sheet.acceptance?.adminAcceptedAt&&<small className="timesheet-acceptance-line">Aceite ADM registrado em {new Date(sheet.acceptance.adminAcceptedAt).toLocaleString('pt-BR')} · {sheet.acceptance.employeeAcceptedAt?'colaborador também assinou':'aguardando aceite do colaborador'}</small>}{sheet.acceptance?.outdated&&<small className="timesheet-acceptance-line">O conteúdo do espelho mudou após o último aceite. Assine novamente a versão atual.</small>}</div><div className="inline-actions">{canSignAsAdmin&&sheet.acceptance?.adminCanAccept&&!sheet.acceptance?.adminAcceptedAt&&<button className="primary" disabled={busy} onClick={acceptAsAdmin}>{busy?'Confirmando…':'Dar aceite WebAuthn'}</button>}<button className="primary" onClick={printSheet}>Salvar / imprimir PDF</button><button className="ghost" onClick={()=>{setSheet(null);clearDetail();}}>Fechar</button></div></div>
      {canViewJustificationDetails&&sheet.lateJustifications?.length>0&&<div className="timesheet-justification-inspector no-print"><div className="card-head"><div><div className="eyebrow">ACESSO RESTRITO</div><h3>Justificativas de atraso desta competência</h3><p className="muted">Os detalhes, a selfie e o documento comprobatório ficam restritos ao Super Adm e ao responsável de ponto.</p></div></div><div className="justification-inspector-list">{sheet.lateJustifications.map((j:any)=><div className="justification-inspector-row" key={j.id}><div><b>{fmtDate(j.workDate)} · {j.typeLabel}</b><span>{j.statusLabel}{j.reviewerName?` · ${j.reviewerName}`:''}{j.hasDocument?' · documento anexado':' · sem documento'}</span></div><button type="button" className="ghost" disabled={detailBusy} onClick={()=>void openJustification(j.id)}>{detailBusy?'Carregando…':'Ver justificativa'}</button></div>)}</div></div>}
      <TimesheetDocument sheet={sheet}/>
    </section>}

    {justificationDetail&&<div className="modal-backdrop no-print" role="dialog" aria-modal="true" aria-label="Detalhes da justificativa"><div className="modal-card justification-detail-modal">
      <div className="card-head"><div><div className="eyebrow">JUSTIFICATIVA DE ATRASO</div><h2>{justificationDetail.employee.name}</h2><p className="muted">{fmtDateTime(justificationDetail.requestedTime)} · {justificationDetail.typeLabel}</p></div><button type="button" className="ghost" onClick={clearDetail}>Fechar</button></div>
      <div className="justification-detail-grid"><div><span>Motivo informado</span><b>{justificationDetail.reason}</b></div><div><span>Status</span><b>{justificationDetail.statusLabel}</b></div><div><span>Responsável pela decisão</span><b>{justificationDetail.reviewer?.name||'Aguardando análise'}</b></div><div><span>Analisado em</span><b>{fmtDateTime(justificationDetail.reviewedAt)}</b></div>{justificationDetail.reviewNote&&<div className="wide"><span>Observação da análise</span><b>{justificationDetail.reviewNote}</b></div>}{justificationDetail.selfieLocation&&<div className="wide"><span>Localização da selfie</span><b>{justificationDetail.selfieLocation.latitude.toFixed(6)}, {justificationDetail.selfieLocation.longitude.toFixed(6)}{justificationDetail.selfieLocation.accuracyM!=null?` · precisão ${Math.round(justificationDetail.selfieLocation.accuracyM)} m`:''}</b></div>}</div>
      <div className="justification-evidence-grid">
        <div className="notice-box"><b>Selfie tirada na justificativa</b><small>{fmtDateTime(justificationDetail.selfieCapturedAt)}</small>{detailSelfieUrl?<img src={detailSelfieUrl} alt="Selfie da justificativa"/>:<p className="muted">Selfie não disponível.</p>}</div>
        {justificationDetail.documentAvailable&&<div className="notice-box"><b>Documento comprobatório</b><small>{justificationDetail.documentFileName||'Documento anexado'}</small>{detailDocumentUrl?(detailDocumentMime.startsWith('image/')?<img src={detailDocumentUrl} alt="Documento comprobatório"/>:<iframe src={detailDocumentUrl} title="Documento comprobatório"/>):<p className="muted">Carregando documento…</p>}{detailDocumentUrl&&<a className="ghost button-link" href={detailDocumentUrl} target="_blank" rel="noreferrer">Abrir documento</a>}</div>}
      </div>
    </div></div>}
  </>;
}
