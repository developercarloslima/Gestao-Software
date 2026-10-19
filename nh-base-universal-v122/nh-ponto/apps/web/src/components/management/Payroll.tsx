import { useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { fmtMinutes, fmtMoney, fmtRateCents, fmtSecondsDuration, monthRange } from '../../lib/format';
import TimesheetDocument from '../TimesheetDocument';
import { printTimesheetOnly } from '../../lib/printTimesheet';

function rangeFromMonth(month:string){
 const [year,m]=month.split('-').map(Number); const last=new Date(year,m,0).getDate();
 return {from:`${year}-${String(m).padStart(2,'0')}-01`,to:`${year}-${String(m).padStart(2,'0')}-${String(last).padStart(2,'0')}`,label:month};
}
function safeFilePart(value:string){return value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/[^a-zA-Z0-9]+/g,'-').replace(/^-+|-+$/g,'').toLowerCase();}

export default function Payroll(){
 const defaultRange=monthRange(); const [month,setMonth]=useState(defaultRange.label); const range=useMemo(()=>rangeFromMonth(month),[month]);
 const [periods,setPeriods]=useState<any[]>([]);const [current,setCurrent]=useState<any>(null);const [msg,setMsg]=useState('');const [busy,setBusy]=useState(false);
 const [sheet,setSheet]=useState<any>(null);const [sheetBusy,setSheetBusy]=useState(false);
 const load=()=>api<any[]>('/payroll/periods').then(setPeriods);useEffect(()=>{load().catch(()=>{});},[]);
 async function generate(){setBusy(true);setMsg('');setSheet(null);try{const r=await api<any>('/payroll/generate',{method:'POST',body:JSON.stringify({label:range.label,from:range.from,to:range.to})});setMsg(`Espelho gerado para ${r.count} colaborador(es).`);await load();await open(r.period.id);}catch(e){setMsg(e instanceof Error?e.message:'Erro');}finally{setBusy(false)}}
 async function open(id:string){setSheet(null);setCurrent(await api(`/payroll/periods/${id}`));}
 async function openSheet(employeeId:string){
   if(!current)return;setSheetBusy(true);setMsg('');
   try{
     const from=String(current.startDate).slice(0,10),to=String(current.endDate).slice(0,10);
     setSheet(await api(`/time/employees/${employeeId}?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`));
     window.setTimeout(()=>document.getElementById('employee-timesheet')?.scrollIntoView({behavior:'smooth',block:'start'}),30);
   }catch(e){setMsg(e instanceof Error?e.message:'Erro ao abrir espelho');}finally{setSheetBusy(false)}
 }
 function printSheet(){
   if(!sheet)return;
   const period=String(sheet.from??'').slice(0,7);
   printTimesheetOnly('employee-timesheet',`espelho-ponto-${safeFilePart(sheet.employee?.name??'colaborador')}-${period}`);
 }
 const ref=current?.payrollReference;
 return <><section className="card"><div className="card-head"><div><div className="eyebrow">RH · ESPELHO DE PONTO</div><h2>Espelho de ponto e valores</h2><p className="muted">O RH cadastra o salário de cada colaborador. O colaborador apenas registra o ponto; os valores de faltas e extras são calculados automaticamente aqui.</p></div><div className="payroll-actions"><input type="month" value={month} onChange={e=>setMonth(e.target.value)}/><button className="primary" onClick={generate} disabled={busy}>{busy?'Gerando...':'Gerar espelho'}</button></div></div><div className="payroll-reference-strip"><b>{ref?.monthlyHours??220}h mensais</b><span>DSR incluído</span><b>{ref?.weeklyHours??44}h semanais</b><span>cálculo até o segundo</span></div><small className="muted">Fórmula: salário ÷ divisor mensal = valor/hora; hora ÷ 60 = valor/minuto; minuto ÷ 60 = valor/segundo. Estimativa operacional — a folha oficial pode incluir regras legais, convenções, INSS, IR e outros eventos.</small>{msg&&<div className="success-box">{msg}</div>}<div className="period-list">{periods.map(p=><button key={p.id} onClick={()=>open(p.id)} className={current?.id===p.id?'active':''}><b>{p.label}</b><span>{p.status} · {p._count?.previews??0} pessoas</span></button>)}</div></section>
 {current&&<section className="card"><div className="eyebrow">RESUMO DO RH</div><h2>{current.label}</h2><div className="table-wrap"><table className="payroll-table payroll-values-table"><thead><tr><th>Colaborador</th><th>Salário</th><th>Hora</th><th>Minuto</th><th>Segundo</th><th>Trabalhado</th><th>Faltas / desconto</th><th>Extra 50%</th><th>Extra 100%</th><th>Valor extras</th><th>Estimativa após horas</th><th>Espelho</th></tr></thead><tbody>{current.previews.map((p:any)=>{const c=p.compensation??{};return <tr key={p.id}><td><b>{p.employee.name}</b><small>Matrícula {p.employee.employeeNumber}</small></td><td><b>{fmtMoney(p.employee.salaryCents)}</b></td><td>{fmtRateCents(c.hourlyRateCentsExact??c.hourlyRateCents??0,2)}</td><td>{fmtRateCents(c.minuteRateCentsExact??0,5)}</td><td>{fmtRateCents(c.secondRateCentsExact??0,5)}</td><td>{p.workedSeconds?fmtSecondsDuration(p.workedSeconds):fmtMinutes(p.workedMinutes)}</td><td>{p.missingSeconds?fmtSecondsDuration(p.missingSeconds):fmtMinutes(p.missingMinutes)}<small className="negative">- {fmtMoney(c.missingDiscountCents??0)}</small></td><td>{p.overtime50Seconds?fmtSecondsDuration(p.overtime50Seconds):fmtMinutes(p.overtime50Minutes)}<small>{fmtMoney(c.overtime50ReferenceCents??p.overtime50Cents)}</small></td><td>{p.overtime100Seconds?fmtSecondsDuration(p.overtime100Seconds):fmtMinutes(p.overtime100Minutes)}<small>{fmtMoney(c.overtime100ReferenceCents??p.overtime100Cents)}</small></td><td><b>{fmtMoney(c.overtimeReferenceCents??(p.overtime50Cents+p.overtime100Cents))}</b><small>{c.overtimeToBank?'referência · vai para banco':'estimado a receber'}</small></td><td><b>{fmtMoney(c.estimatedPayableCents??p.estimatedGrossCents)}</b><small>antes de encargos e outros eventos</small></td><td><button className="ghost" disabled={sheetBusy} onClick={()=>openSheet(p.employee.id)}>{sheetBusy?'Abrindo…':'Ver espelho'}</button></td></tr>})}</tbody></table></div></section>}
 {sheet&&<section className="card timesheet-sheet" id="employee-timesheet"><div className="timesheet-preview-toolbar no-print"><div><div className="eyebrow">PRÉVIA DO PDF</div><h2>{sheet.employee.name}</h2><p className="muted">Documento padronizado com a identidade visual da empresa.</p></div><div className="inline-actions"><button className="primary" onClick={printSheet}>Salvar / imprimir PDF</button><button className="ghost" onClick={()=>setSheet(null)}>Fechar</button></div></div>
 <TimesheetDocument sheet={sheet}/>
 </section>}
 </>;
}
