import type { CSSProperties } from 'react';
import { fmtMinutes, fmtSecondsDuration } from '../lib/format';

function fmtDateBr(value?:string|null){
 if(!value)return 'Não informado';
 const raw=String(value).slice(0,10),[y,m,d]=raw.split('-');
 return y&&m&&d?`${d}/${m}/${y}`:String(value);
}
function fmtDateTimeBr(value?:string|null){
 if(!value)return '—';
 const dt=new Date(value);if(Number.isNaN(dt.getTime()))return String(value);
 return new Intl.DateTimeFormat('pt-BR',{dateStyle:'short',timeStyle:'short'}).format(dt);
}
function fmtTimeBr(value?:string|null){
 if(!value)return '—';
 const dt=new Date(value);if(Number.isNaN(dt.getTime()))return String(value);
 return new Intl.DateTimeFormat('pt-BR',{hour:'2-digit',minute:'2-digit'}).format(dt);
}
function fmtDocument(value?:string|null){
 const digits=String(value??'').replace(/\D/g,'');
 if(digits.length===14)return digits.replace(/^(\d{2})(\d{3})(\d{3})(\d{4})(\d{2})$/,'$1.$2.$3/$4-$5');
 return value||'Não informado';
}
function initials(name?:string|null){return String(name??'').split(/\s+/).filter(Boolean).slice(0,2).map(x=>x[0]).join('').toUpperCase()||'AD';}
function shortCredential(value?:string|null){const v=String(value??'');return v?v.length>18?`${v.slice(0,8)}…${v.slice(-6)}`:v:'—';}
function defaultCompanyLogo(company?:any, brand?:any){
 const explicit=String(brand?.logoUrl??'').trim();
 if(explicit)return explicit;
 const haystack=`${String(company?.name??'')} ${String(company?.legalName??'')}`.normalize('NFD').replace(/[\u0300-\u036f]/g,'').toLowerCase();
 
 return '';
}
export function timesheetDocumentCode(sheet:any){
 return sheet?`EP-${String(sheet.from).slice(0,7).replace('-','')}-${String(sheet.employee?.employeeNumber??sheet.employee?.id??'COLAB').toUpperCase()}`:'';
}

export default function TimesheetDocument({sheet}:{sheet:any}){
 const brand=sheet?.company?.brand??{};
 // O corpo documental usa o azul institucional NH para manter a leitura padronizada.
 // A faixa superior, o destaque e a logo continuam seguindo a identidade cadastrada da empresa.
 const reportStyle={
   '--report-primary':brand.primary??'#070C40',
   '--report-accent':brand.secondary??'#F0F000',
   '--report-surface':brand.surface??'#FFFFFF',
   '--report-on-primary':brand.text??'#FFFFFF',
   '--report-text':'#070C40'
 } as CSSProperties;
 const documentCode=timesheetDocumentCode(sheet);
 const brandLogoUrl=defaultCompanyLogo(sheet?.company,brand);
 const acceptance=sheet?.acceptance??null;
 const acceptanceStatus=acceptance?.fullyAccepted?'ACEITES WEBAUTHN CONCLUÍDOS':acceptance?.adminAcceptedAt?'AGUARDANDO COLABORADOR':'CONFERÊNCIA RH';
 const manualByDate=new Map<string,number>();
 for(const item of sheet?.manualAdjustments??[])manualByDate.set(item.referenceDate,(manualByDate.get(item.referenceDate)??0)+Number(item.minutes??0));
 return <article className="timesheet-document" style={reportStyle}>
   <header className="timesheet-brand-header">
     <div className="timesheet-brand-logo">{brandLogoUrl?<img src={brandLogoUrl} alt={`Logo ${sheet.company?.name??'empresa'}`}/>:<span>{initials(sheet.company?.name)}</span>}</div>
     <div className="timesheet-brand-title"><strong>{sheet.company?.name??'Apontamento Digital'}</strong><span>{sheet.company?.legalName||'Gestão profissional de jornada'}</span><small>APONTAMENTO DIGITAL · ESPELHO DE PONTO</small></div>
     <div className="timesheet-document-meta"><span>Nº DO DOCUMENTO</span><b>{documentCode}</b><span>PERÍODO</span><b>{fmtDateBr(sheet.from)} a {fmtDateBr(sheet.to)}</b></div>
   </header>

   <div className="timesheet-report-intro">
     <div><h1>Espelho mensal de jornada</h1><p>Relatório consolidado das marcações de jornada, horas trabalhadas por marcação, banco de horas diário, ajustes e ocorrências do período.</p></div>
     <div className="timesheet-status-box">{acceptanceStatus}</div>
   </div>

   <section className="timesheet-report-section">
     <h3>IDENTIFICAÇÃO DO COLABORADOR</h3>
     <div className="timesheet-info-grid">
       <div><span>Colaborador</span><b>{sheet.employee.name}</b></div>
       <div><span>Matrícula</span><b>{sheet.employee.employeeNumber||'Não informado'}</b></div>
       <div><span>CPF</span><b>{sheet.employee.cpfMasked||'Não informado'}</b></div>
       <div><span>Cargo</span><b>{sheet.employee.jobTitle||'Não informado'}</b></div>
       <div><span>Setor</span><b>{sheet.employee.department?.name||'Não informado'}</b></div>
       <div><span>Unidade</span><b>{sheet.employee.worksite?.name||'Não informado'}</b></div>
       <div><span>Escala / jornada</span><b>{sheet.employee.shift?.name||'Não informado'}</b></div>
       <div><span>Admissão</span><b>{fmtDateBr(sheet.employee.admissionDate)}</b></div>
       <div className="wide"><span>Empresa</span><b>{sheet.company?.legalName||sheet.company?.name}</b><small>{sheet.company?.document?`CNPJ / documento: ${fmtDocument(sheet.company.document)}`:'Documento empresarial não informado'}</small></div>
       <div className="wide"><span>Local de trabalho</span><b>{sheet.employee.worksite?.address||sheet.employee.worksite?.name||'Não informado'}</b><small>{sheet.employee.worksite?.city?`${sheet.employee.worksite.city}${sheet.employee.worksite.stateCode?`/${sheet.employee.worksite.stateCode}`:''}`:''}</small></div>
     </div>
   </section>

   <section className="timesheet-report-section">
     <h3>RESUMO DA JORNADA</h3>
     <div className="timesheet-summary-strip">
       <div><span>Jornada do período</span><b>{fmtSecondsDuration(sheet.totals.scheduledSeconds??sheet.totals.scheduledMinutes*60)}</b></div>
       <div><span>Horas trabalhadas (marcações)</span><b>{fmtSecondsDuration(sheet.totals.workedSeconds??sheet.totals.workedMinutes*60)}</b></div>
       <div><span>Faltas</span><b>{fmtSecondsDuration(sheet.totals.missingSeconds??sheet.totals.missingMinutes*60)}</b></div>
       <div><span>Banco mensal</span><b>{fmtMinutes(sheet.bank?.monthlyBalanceMinutes??sheet.totals.bankDeltaMinutes??0)}</b></div>
       <div><span>Banco acumulado</span><b>{fmtMinutes(sheet.bank?.accumulatedBalanceMinutes??0)}</b></div>
       <div><span>Extra 50%</span><b>{fmtSecondsDuration(sheet.totals.overtime50Seconds??sheet.totals.overtime50Minutes*60)}</b></div>
       <div><span>Extra 100%</span><b>{fmtSecondsDuration(sheet.totals.overtime100Seconds??sheet.totals.overtime100Minutes*60)}</b></div>
       <div><span>Ocorrências</span><b>{sheet.totals.warningCount??0}</b></div>
     </div>
   </section>

   <section className="timesheet-report-section timesheet-daily-section">
     <h3>DETALHAMENTO DIÁRIO</h3>
     <table className="timesheet-report-table"><thead><tr><th>Data</th><th>Jornada do dia</th><th>Horas trabalhadas</th><th>Intervalo</th><th>Déficit</th><th>Extra 50%</th><th>Extra 100%</th><th>Ajuste</th><th>Saldo do dia</th><th>Ocorrências</th></tr></thead><tbody>{sheet.days.map((d:any)=><tr key={d.date}><td><b>{fmtDateBr(d.date)}</b>{d.closedReason&&<small>{d.closedReason}</small>}</td><td>{fmtSecondsDuration(d.scheduledSeconds??d.scheduledMinutes*60)}</td><td>{fmtSecondsDuration(d.workedSeconds??d.workedMinutes*60)}</td><td>{fmtSecondsDuration(d.breakSeconds??d.breakMinutes*60)}</td><td>{fmtSecondsDuration(d.missingSeconds??d.missingMinutes*60)}</td><td>{fmtSecondsDuration(d.overtime50Seconds??d.overtime50Minutes*60)}</td><td>{fmtSecondsDuration(d.overtime100Seconds??d.overtime100Minutes*60)}</td><td>{fmtMinutes(manualByDate.get(d.date)??0)}</td><td><b>{fmtMinutes((d.bankDeltaMinutes??Math.round((d.bankDeltaSeconds??0)/60))+(manualByDate.get(d.date)??0))}</b></td><td>{d.warnings?.length?<span className="timesheet-warning-text">{d.warnings.join(' · ')}</span>:'—'}</td></tr>)}</tbody><tfoot><tr><th>TOTAL</th><th>{fmtSecondsDuration(sheet.totals.scheduledSeconds??sheet.totals.scheduledMinutes*60)}</th><th>{fmtSecondsDuration(sheet.totals.workedSeconds??sheet.totals.workedMinutes*60)}</th><th>—</th><th>{fmtSecondsDuration(sheet.totals.missingSeconds??sheet.totals.missingMinutes*60)}</th><th>{fmtSecondsDuration(sheet.totals.overtime50Seconds??sheet.totals.overtime50Minutes*60)}</th><th>{fmtSecondsDuration(sheet.totals.overtime100Seconds??sheet.totals.overtime100Minutes*60)}</th><th>{fmtMinutes((sheet.manualAdjustments??[]).reduce((sum:number,a:any)=>sum+Number(a.minutes??0),0))}</th><th>{fmtMinutes(sheet.bank?.monthlyBalanceMinutes??sheet.totals.bankDeltaMinutes??0)}</th><th>{sheet.totals.warningCount??0}</th></tr></tfoot></table>
   </section>

   <section className="timesheet-report-section">
     <h3>OBSERVAÇÕES — JUSTIFICATIVAS DE ATRASO</h3>
     {sheet.lateJustifications?.length?<table className="timesheet-report-table"><thead><tr><th>Data</th><th>Observação</th></tr></thead><tbody>{sheet.lateJustifications.map((j:any)=><tr key={j.id}><td><b>{fmtDateBr(j.workDate)}</b></td><td>{j.typeLabel} · {j.statusLabel}{j.reviewerName?` por ${j.reviewerName}`:''}</td></tr>)}</tbody></table>:<p className="muted">Não houve justificativa de atraso registrada nesta competência.</p>}
   </section>

   <section className="timesheet-report-section">
     <h3>AJUSTES DE MARCAÇÃO</h3>
     {sheet.pointAdjustments?.length?<table className="timesheet-report-table"><thead><tr><th>Data</th><th>Marcação</th><th>Horário anterior</th><th>Horário ajustado</th><th>Motivo</th><th>Responsável</th></tr></thead><tbody>{sheet.pointAdjustments.map((a:any)=><tr key={a.id}><td><b>{fmtDateBr(a.workDate)}</b></td><td>{a.kind==='CORRECTION'?'Correção':'Inclusão'} · {a.typeLabel}</td><td>{a.originalTime?`${a.originalTypeLabel||a.typeLabel} · ${fmtTimeBr(a.originalTime)}`:'—'}</td><td><b>{fmtTimeBr(a.requestedTime)}</b></td><td>{a.reason}</td><td>{a.responsibleName||'Não identificado'}</td></tr>)}</tbody></table>:<p className="muted">Não houve ajuste de marcação nesta competência.</p>}
   </section>

   <section className="timesheet-report-section">
     <h3>AJUSTES MANUAIS DO BANCO DE HORAS</h3>
     {sheet.manualAdjustments?.length?<table className="timesheet-report-table"><thead><tr><th>Dia de referência</th><th>Operação</th><th>Valor</th><th>Motivo</th><th>Responsável</th><th>Lançado em</th></tr></thead><tbody>{sheet.manualAdjustments.map((a:any)=><tr key={a.id}><td><b>{fmtDateBr(a.referenceDate)}</b></td><td>{a.minutes>=0?'Crédito':'Débito'}</td><td><b>{fmtMinutes(a.minutes)}</b></td><td>{a.reason}</td><td>{a.actor?.name||'Não identificado'}</td><td>{fmtDateTimeBr(a.effectiveAt)}</td></tr>)}</tbody></table>:<p className="muted">Não houve ajuste manual de banco de horas nesta competência.</p>}
   </section>

   <section className="timesheet-report-section timesheet-declaration">
     <h3>CONFERÊNCIA DO ESPELHO</h3>
     <p>Este documento consolida as marcações de jornada do período, o banco de horas diário, os ajustes de marcação, os ajustes históricos do banco e o resultado das justificativas de atraso. O conteúdo detalhado das justificativas e seus documentos comprobatórios permanecem restritos aos perfis autorizados e registrados na auditoria do Apontamento Digital.</p>
     <div className="timesheet-signatures"><div><span>CIÊNCIA DO COLABORADOR</span><i/><b>{acceptance?.employeeAcceptedAt?acceptance.employeeSigner?.name||sheet.employee.name:sheet.employee.name}</b><small>{acceptance?.employeeAcceptedAt?`WebAuthn em ${fmtDateTimeBr(acceptance.employeeAcceptedAt)} · credencial ${shortCredential(acceptance.employeeSigner?.credentialId)}`:`Matrícula ${sheet.employee.employeeNumber} · aguardando aceite WebAuthn`}</small></div><div><span>CONFERÊNCIA RH / ADMINISTRADOR</span><i/><b>{acceptance?.adminAcceptedAt?acceptance.adminSigner?.name||'Administrador responsável':'Responsável pela conferência'}</b><small>{acceptance?.adminAcceptedAt?`WebAuthn em ${fmtDateTimeBr(acceptance.adminAcceptedAt)} · credencial ${shortCredential(acceptance.adminSigner?.credentialId)}`:'Aguardando aceite WebAuthn da competência'}</small></div></div>
   </section>

   <footer className="timesheet-report-footer"><div><b>{sheet.company?.name}</b><span>{sheet.company?.document?` · ${fmtDocument(sheet.company.document)}`:''} · Apontamento Digital</span></div><div><span>Gerado em {fmtDateTimeBr(sheet.generatedAt)}</span><b>{documentCode}</b></div></footer>
 </article>;
}
