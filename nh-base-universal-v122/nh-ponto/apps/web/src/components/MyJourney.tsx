import { useEffect, useMemo, useState } from 'react';
import { api } from '../lib/api';
import { fmtMinutes, monthRange } from '../lib/format';
import TimesheetDocument from './TimesheetDocument';
import { printTimesheetOnly } from '../lib/printTimesheet';
import AttendanceCalendar from './AttendanceCalendar';
import { enrollPlatformBiometric, performTimesheetAcceptance, supportsWebAuthn } from '../lib/webauthn';

type Punch = { id:string; recordNumber:string; type:string; occurredAt:string; source:string; offline:boolean; integrityHash:string; superseded?:boolean; proof?:{proofScore:number;proofLevel:string} };
const label:Record<string,string>={CLOCK_IN:'Entrada',BREAK_START:'Intervalo',BREAK_END:'Retorno do intervalo',PAUSE_START:'Pausa',PAUSE_END:'Retorno da pausa',CLOCK_OUT:'Saída'};
function rangeFromMonth(month:string){const [year,m]=month.split('-').map(Number);const last=new Date(year,m,0).getDate();return {from:`${year}-${String(m).padStart(2,'0')}-01`,to:`${year}-${String(m).padStart(2,'0')}-${String(last).padStart(2,'0')}`};}
function safeFilePart(value:string){return value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/[^a-zA-Z0-9]+/g,'-').replace(/^-+|-+$/g,'').toLowerCase();}

export default function MyJourney(){
 const [items,setItems]=useState<Punch[]>([]); const [explain,setExplain]=useState<any>(null); const [summary,setSummary]=useState<any>(null); const [bank,setBank]=useState<any>(null);
 const [month,setMonth]=useState(monthRange().label),[sheet,setSheet]=useState<any>(null),[sheetBusy,setSheetBusy]=useState(false),[sheetMsg,setSheetMsg]=useState('');
 const range=useMemo(()=>rangeFromMonth(month),[month]);
 useEffect(()=>{Promise.all([api<Punch[]>('/punches/me'),api('/assistant/explain-my-day'),api('/time/me'),api('/bank/me')]).then(([p,e,s,b])=>{setItems(p);setExplain(e);setSummary(s);setBank(b)}).catch(()=>{});},[]);
 async function generateMySheet(){setSheetBusy(true);setSheetMsg('');try{const result=await api<any>(`/time/me?from=${encodeURIComponent(range.from)}&to=${encodeURIComponent(range.to)}`);setSheet(result);window.setTimeout(()=>document.getElementById('my-timesheet')?.scrollIntoView({behavior:'smooth',block:'start'}),30);}catch(e){setSheetMsg(e instanceof Error?e.message:'Não foi possível gerar o espelho.');}finally{setSheetBusy(false);}}
 function printSheet(){if(!sheet)return;printTimesheetOnly('my-timesheet',`espelho-ponto-${safeFilePart(sheet.employee?.name??'colaborador')}-${month}`);}
 async function acceptMySheet(){
  if(!sheet?.employee?.id)return;
  setSheetBusy(true);setSheetMsg('');
  try{
    let result:any;
    try{result=await performTimesheetAcceptance(sheet.employee.id,month,'EMPLOYEE');}
    catch(error:any){
      if(/TIMESHEET_PASSKEY_REQUIRED|Cadastre uma passkey|passkey/i.test(String(error?.message||''))&&supportsWebAuthn()){
        await enrollPlatformBiometric('Assinatura do meu espelho de ponto');
        result=await performTimesheetAcceptance(sheet.employee.id,month,'EMPLOYEE');
      }else throw error;
    }
    setSheet((current:any)=>current?{...current,acceptance:result.acceptance}:current);
    setSheetMsg('Seu aceite WebAuthn foi registrado neste espelho de ponto.');
  }catch(e){setSheetMsg(e instanceof Error?e.message:'Não foi possível registrar seu aceite.');}
  finally{setSheetBusy(false);}
 }
 return <><AttendanceCalendar/><section className="card payroll-reference-card"><div className="eyebrow">MINHA JORNADA</div><div className="payroll-reference-strip"><b>44h semanais</b><span>referência mensal conforme escala</span><b>DSR considerado pelo RH</b></div><small className="muted">Acompanhe suas marcações e gere seu próprio espelho de ponto. O PDF não exibe salário nem apuração gerencial do RH.</small><div className="my-timesheet-actions"><label>Competência<input type="month" value={month} onChange={e=>setMonth(e.target.value)}/></label><button className="primary" disabled={sheetBusy} onClick={generateMySheet}>{sheetBusy?'Gerando…':'Gerar meu espelho de ponto'}</button></div>{sheetMsg&&<p className="muted inline-feedback">{sheetMsg}</p>}</section>
 <div className="metrics compensation-metrics"><div className="metric"><span>Trabalhado no mês (marcações)</span><strong>{summary?fmtMinutes(summary.totals.workedMinutes):'—'}</strong></div><div className="metric"><span>Horas faltas</span><strong>{summary?fmtMinutes(summary.totals.missingMinutes):'—'}</strong></div><div className="metric"><span>Horas extras</span><strong>{summary?fmtMinutes(summary.totals.overtime50Minutes+summary.totals.overtime100Minutes):'—'}</strong></div><div className="metric"><span>Saldo acumulado</span><strong>{bank?fmtMinutes(bank.balanceMinutes):'—'}</strong></div></div>
 <div className="grid-two">
  <section className="card"><div className="eyebrow">MARCAÇÕES</div><h2>Marcações recentes</h2><div className="timeline">
   {items.length===0?<p className="muted">Nenhuma marcação registrada.</p>:items.map(p=><div className={`timeline-item ${p.superseded?'superseded':''}`} key={p.id}><div className="timeline-dot"/><div><b>{label[p.type]||p.type}{p.superseded?' · substituído':''}</b><span>{new Date(p.occurredAt).toLocaleString('pt-BR')}</span><small>{p.source}{p.offline?' · sincronizado offline':''} · NSR #{p.recordNumber}</small></div><div className="score-pill">{p.proof?.proofScore??0}</div></div>)}
  </div></section>
  <section className="card assistant-card"><div className="eyebrow">PONTO IA · EXPLICAÇÃO</div><h2>Entenda seu dia</h2><p>{explain?.explanation ?? 'Calculando sua jornada...'}</p>{explain?.warnings?.length>0&&<div className="warning-list">{explain.warnings.map((w:string,i:number)=><div key={i}>⚠ {w}</div>)}</div>}<div className="assistant-note">O motor usa as marcações efetivas e correções aprovadas, preserva os registros substituídos e apresenta horas trabalhadas, extras, faltas e banco de horas.</div></section>
 </div>
 {sheet&&<section className="card timesheet-sheet" id="my-timesheet"><div className="timesheet-preview-toolbar no-print"><div><div className="eyebrow">MEU ESPELHO DE PONTO</div><h2>{sheet.employee.name}</h2><p className="muted">Mesmo documento oficial usado pela gestão, sem informações financeiras do RH.</p>{sheet.acceptance?.adminAcceptedAt&&<small className="timesheet-acceptance-line">Espelho liberado pelo Administrador em {new Date(sheet.acceptance.adminAcceptedAt).toLocaleString('pt-BR')}.</small>}{sheet.acceptance?.employeeAcceptedAt&&<small className="timesheet-acceptance-line">Seu aceite WebAuthn foi registrado em {new Date(sheet.acceptance.employeeAcceptedAt).toLocaleString('pt-BR')}.</small>}</div><div className="inline-actions">{sheet.acceptance?.employeeCanAccept&&<button className="primary" disabled={sheetBusy} onClick={acceptMySheet}>{sheetBusy?'Confirmando…':'Dar meu aceite WebAuthn'}</button>}<button className="primary" onClick={printSheet}>Salvar / imprimir PDF</button><button className="ghost" onClick={()=>setSheet(null)}>Fechar</button></div></div><TimesheetDocument sheet={sheet}/></section>}
 </>;
}
