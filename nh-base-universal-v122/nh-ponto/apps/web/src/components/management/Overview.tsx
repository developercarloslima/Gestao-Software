import { useEffect, useState } from 'react';
import { api, apiBlob } from '../../lib/api';

type OverviewData = { employees:number; working:number; onBreak:number; finished:number; withoutPunch:number; pendingAdjustments:number; lowProofCount:number; alerts:Array<{id:string;severity:string;title:string;description:string}> };

type AdjustmentContext = {
  adjustment:any;
  employee:{id:string;name:string;employeeNumber:string;jobTitle?:string|null};
  requestedWorkDate:string;
  punches:Array<{id:string;recordNumber:string;type:string;occurredAt:string;workDate:string;source:string;decision:string;reviewReason?:string|null}>;
};

const PUNCH_LABELS:Record<string,string>={CLOCK_IN:'Início da jornada',BREAK_START:'Início do intervalo',BREAK_END:'Retorno do intervalo',PAUSE_START:'Início da pausa',PAUSE_END:'Retorno da pausa',CLOCK_OUT:'Fim da jornada'};

export default function Overview() {
  const [data, setData] = useState<OverviewData | null>(null);
  const [requests, setRequests] = useState<any[]>([]);
  const [message, setMessage] = useState('');
  const [viewingId,setViewingId]=useState('');
  const [context,setContext]=useState<AdjustmentContext|null>(null);
  const [contextBusy,setContextBusy]=useState(false);
  const [selfieUrl,setSelfieUrl]=useState('');
  const [documentUrl,setDocumentUrl]=useState('');
  const [documentMime,setDocumentMime]=useState('');
  const load = () => Promise.all([api<OverviewData>('/dashboard/overview'), api<any[]>('/adjustments')]).then(([d,r]) => { setData(d); setRequests(r); });
  useEffect(() => { load().catch(() => {}); }, []);

  async function review(id:string, approve:boolean) {
    setMessage('');
    try {
      await api(`/adjustments/${id}/${approve ? 'approve' : 'reject'}`, { method:'POST', body: JSON.stringify({ note: approve ? 'Aprovado pela central de gestão após conferência das marcações' : 'Rejeitado pela central de gestão após conferência das marcações' }) });
      setMessage(approve ? 'Ajuste aprovado e novo evento imutável criado.' : 'Solicitação rejeitada.');
      if(viewingId===id){if(selfieUrl)URL.revokeObjectURL(selfieUrl);if(documentUrl)URL.revokeObjectURL(documentUrl);setSelfieUrl('');setDocumentUrl('');setDocumentMime('');setViewingId('');setContext(null);}
      await load();
    } catch (e) { setMessage(e instanceof Error ? e.message : 'Falha ao revisar'); }
  }

  async function viewPunches(id:string){
    if(viewingId===id){if(selfieUrl)URL.revokeObjectURL(selfieUrl);if(documentUrl)URL.revokeObjectURL(documentUrl);setSelfieUrl('');setDocumentUrl('');setDocumentMime('');setViewingId('');setContext(null);return;}
    if(selfieUrl)URL.revokeObjectURL(selfieUrl);if(documentUrl)URL.revokeObjectURL(documentUrl);setSelfieUrl('');setDocumentUrl('');setDocumentMime('');setViewingId(id);setContext(null);setContextBusy(true);setMessage('');
    try{
      const value=await api<AdjustmentContext>(`/adjustments/${id}/punches`);setContext(value);
      if(value.adjustment?.selfieAvailable){const blob=await apiBlob(`/adjustments/${id}/selfie`);setSelfieUrl(URL.createObjectURL(blob));}
      if(value.adjustment?.supportingDocumentAvailable){const blob=await apiBlob(`/adjustments/${id}/document`);setDocumentMime(blob.type||value.adjustment.supportingDocumentMimeType||'application/octet-stream');setDocumentUrl(URL.createObjectURL(blob));}
    }
    catch(e){setMessage(e instanceof Error?e.message:'Não foi possível carregar as marcações.');setViewingId('');}
    finally{setContextBusy(false);}
  }

  async function resolveAlert(id:string){ try { await api(`/alerts/${id}/resolve`,{method:'POST',body:'{}'}); await load(); } catch(e){ setMessage(e instanceof Error?e.message:'Falha ao resolver alerta'); } }

  const pending = requests.filter(r => r.status === 'PENDING');
  const sameDay=context?.punches.filter(p=>p.workDate===context.requestedWorkDate)??[];
  const recent=context?.punches.filter(p=>p.workDate!==context.requestedWorkDate).slice(0,20)??[];
  return <>
    <div className="metrics metrics-7">
      <div className="metric"><span>Colaboradores</span><strong>{data?.employees ?? '—'}</strong></div>
      <div className="metric"><span>Trabalhando</span><strong>{data?.working ?? '—'}</strong></div>
      <div className="metric"><span>Em intervalo</span><strong>{data?.onBreak ?? '—'}</strong></div>
      <div className="metric"><span>Finalizados</span><strong>{data?.finished ?? '—'}</strong></div>
      <div className="metric"><span>Sem marcação</span><strong>{data?.withoutPunch ?? '—'}</strong></div>
      <div className="metric"><span>Ajustes</span><strong>{data?.pendingAdjustments ?? '—'}</strong></div>
      <div className="metric"><span>Prova baixa</span><strong>{data?.lowProofCount ?? '—'}</strong></div>
    </div>
    <div className="grid-two">
      <section className="card">
        <div className="eyebrow">PRÉ-FECHAMENTO</div><h2>Pendências da jornada</h2>
        {message && <div className="success-box">{message}</div>}
        {pending.length === 0 ? <p className="muted">Nenhum ajuste pendente.</p> : pending.slice(0,8).map(r => <div className="adjustment-review-block" key={r.id}>
          <div className="approval-row">
            <div><b>{r.employee?.name}</b><span>{new Date(r.requestedTime).toLocaleString('pt-BR')} · {PUNCH_LABELS[r.requestedType]??r.requestedType}</span><small>{r.reason}</small></div>
            <div className="inline-actions adjustment-actions"><button className="ghost" onClick={() => viewPunches(r.id)}>{viewingId===r.id?'Fechar marcações':'Visualizar marcações'}</button><button onClick={() => review(r.id,true)}>Aprovar</button><button className="ghost danger" onClick={() => review(r.id,false)}>Não aprovar</button></div>
          </div>
          {viewingId===r.id&&<div className="adjustment-punch-preview">
            {contextBusy?<p className="muted">Carregando marcações…</p>:context&&<>
              <div className="adjustment-preview-head"><div><b>{context.employee.name}</b><small>Matrícula {context.employee.employeeNumber}{context.employee.jobTitle?` · ${context.employee.jobTitle}`:''}</small></div><div><span>Data solicitada</span><b>{context.requestedWorkDate.split('-').reverse().join('/')}</b></div></div>
              <p className="muted">Confira as marcações antes de decidir. A solicitação pede <b>{PUNCH_LABELS[context.adjustment.requestedType]??context.adjustment.requestedType}</b> em {new Date(context.adjustment.requestedTime).toLocaleString('pt-BR')}.</p>
              {context.adjustment?.selfieAvailable&&<div className="notice-box"><b>Selfie da justificativa de atraso</b><p className="muted">Foto tirada no momento do envio da justificativa{context.adjustment.selfieCapturedAt?` · ${new Date(context.adjustment.selfieCapturedAt).toLocaleString('pt-BR')}`:''}.{context.adjustment.selfieLocation?` GPS: ${Number(context.adjustment.selfieLocation.latitude).toFixed(6)}, ${Number(context.adjustment.selfieLocation.longitude).toFixed(6)}.`:''}</p>{selfieUrl?<img src={selfieUrl} alt="Selfie da justificativa de atraso" style={{display:'block',maxWidth:280,width:'100%',borderRadius:12,marginTop:10}}/>:<small className="muted">Carregando selfie…</small>}</div>}
              {context.adjustment?.lateJustificationTypeLabel&&<div className="notice-box"><b>{context.adjustment.lateJustificationTypeLabel}</b><p className="muted">Motivo informado: {context.adjustment.reason}</p>{context.adjustment.supportingDocumentAvailable&&<div className="late-proof-review"><b>Documento comprobatório</b><span>{context.adjustment.supportingDocumentFileName||'Documento anexado'}</span>{documentUrl?(documentMime.startsWith('image/')?<img src={documentUrl} alt="Documento comprobatório da justificativa"/>:<iframe src={documentUrl} title="Documento comprobatório da justificativa"/>):<small className="muted">Carregando documento…</small>}{documentUrl&&<a className="ghost button-link" href={documentUrl} target="_blank" rel="noreferrer">Abrir documento</a>}</div>}</div>}
              <div className="table-wrap"><table className="compact-table"><thead><tr><th>NSR</th><th>Marcação</th><th>Data/hora</th><th>Origem</th><th>Status</th></tr></thead><tbody>{sameDay.length?sameDay.map(p=><tr key={p.id} className={p.id===context.adjustment.targetPunchId?'highlight-row':''}><td>#{p.recordNumber}</td><td>{PUNCH_LABELS[p.type]??p.type}</td><td>{new Date(p.occurredAt).toLocaleString('pt-BR')}</td><td>{p.source}</td><td>{p.decision}</td></tr>):<tr><td colSpan={5} className="muted">Nenhuma marcação registrada nessa data.</td></tr>}</tbody></table></div>
              {recent.length>0&&<details className="recent-punches"><summary>Ver marcações recentes do colaborador</summary><div className="table-wrap"><table className="compact-table"><thead><tr><th>Data</th><th>NSR</th><th>Marcação</th><th>Hora</th><th>Status</th></tr></thead><tbody>{recent.map(p=><tr key={p.id}><td>{p.workDate.split('-').reverse().join('/')}</td><td>#{p.recordNumber}</td><td>{PUNCH_LABELS[p.type]??p.type}</td><td>{new Date(p.occurredAt).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}</td><td>{p.decision}</td></tr>)}</tbody></table></div></details>}
            </>}
          </div>}
        </div>)}
      </section>
      <section className="card">
        <div className="eyebrow">CENTRAL DE RISCO</div><h2>Alertas ativos</h2>
        {!data?.alerts?.length ? <p className="muted">Nenhum alerta ativo.</p> : data.alerts.map(a => <div className="alert-row" key={a.id}><span className={`severity ${a.severity.toLowerCase()}`}>{a.severity}</span><div><b>{a.title}</b><small>{a.description}</small><button className="ghost alert-action" onClick={()=>resolveAlert(a.id)}>Marcar como resolvido</button></div></div>)}
      </section>
    </div>
  </>;
}
