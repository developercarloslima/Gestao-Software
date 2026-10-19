import { FormEvent, useEffect, useMemo, useState } from 'react';
import { api } from '../../lib/api';
import { fmtMinutes, monthRange } from '../../lib/format';

type Props={editable:boolean};
type BankOverview={month:string;from:string;to:string;updatedAt:string;autoRefreshed:boolean;items:any[]};
type CompensationScope='HOUR'|'DAY'|'WEEK'|'MONTH';

const signed=(value:number)=>value>0?`+${fmtMinutes(value)}`:fmtMinutes(value);
const dateBr=(date:string)=>date?.split('-').reverse().join('/')||'—';
const scopeLabel:Record<CompensationScope,string>={HOUR:'Por hora/minutos',DAY:'Dia inteiro',WEEK:'Semana',MONTH:'Mês'};

function dayStatus(day:any){
  if(day.rejected)return 'Jornada reprovada';
  if(day.pendingReview)return 'Aguardando aprovação';
  if(day.automaticAbsence)return 'Falta';
  if(day.closedJourney)return 'Jornada finalizada';
  if((day.scheduledMinutes??0)>0)return 'Dia apurado';
  return 'Sem jornada no dia';
}

export default function Bank({editable}:Props){
  const initialMonth=monthRange().label;
  const [items,setItems]=useState<any[]>([]);
  const [company,setCompany]=useState<any>(null);
  const [msg,setMsg]=useState('');
  const [loading,setLoading]=useState(false);
  const [month,setMonth]=useState(initialMonth);
  const [updatedAt,setUpdatedAt]=useState('');
  const [employeeId,setEmployeeId]=useState('');
  const [useDate,setUseDate]=useState(new Date().toISOString().slice(0,10));
  const [useMinutes,setUseMinutes]=useState('');
  const [useReason,setUseReason]=useState('');
  const [useScope,setUseScope]=useState<CompensationScope>('HOUR');
  const [usingBank,setUsingBank]=useState(false);

  const load=async(targetMonth=month)=>{
    const [x,c]=await Promise.all([api<BankOverview>(`/bank/overview?month=${encodeURIComponent(targetMonth)}`),api<any>('/admin/company')]);
    setItems(x.items??[]);setCompany(c);setUpdatedAt(x.updatedAt??'');
    setEmployeeId(current=>current&&(x.items??[]).some((i:any)=>i.id===current)?current:((x.items??[])[0]?.id??''));
  };
  useEffect(()=>{load(initialMonth).catch(()=>{});},[]);
  async function refresh(targetMonth=month){setLoading(true);setMsg('');try{await load(targetMonth);setMsg('Banco de horas atualizado por dia, usando somente as marcações da jornada.');}catch(e){setMsg(e instanceof Error?e.message:'Erro ao atualizar o banco de horas.');}finally{setLoading(false);}}
  async function changeMonth(value:string){setMonth(value);await refresh(value);}

  async function compensate(e:FormEvent){
    e.preventDefault();if(usingBank)return;setUsingBank(true);setMsg('');
    try{
      const payload:any={employeeId,workDate:useDate,scope:useScope,reason:useReason};if(useScope==='HOUR')payload.minutes=Number(useMinutes);
      const result=await api<any>('/bank/compensations',{method:'POST',body:JSON.stringify(payload)});
      setUseMinutes('');setUseReason('');setMsg(`${scopeLabel[useScope]} abonado: ${fmtMinutes(result.totalMinutes??0)}. Saldo acumulado restante: ${fmtMinutes(result.balanceAfterMinutes??0)}.`);await load();
    }catch(e){setMsg(e instanceof Error?e.message:'Não foi possível usar o banco de horas.');}finally{setUsingBank(false);}
  }

  const selected=useMemo(()=>items.find(i=>i.id===employeeId)??items[0]??null,[items,employeeId]);
  const hoursMode=company?.rules?.hoursMode??(company?.rules?.bankHoursEnabled&&company?.rules?.overtimeToBank?'BANK_HOURS':'OVERTIME');

  return <div className="grid-two">
    {editable&&<>
      <section className="card">
        <div className="eyebrow">AJUSTES DE MARCAÇÃO</div><h2>Correções agora são feitas pelo horário</h2>
        <p className="muted">Para corrigir entrada, intervalo, retorno ou saída, use <b>Ajustes da equipe</b> no menu lateral. O banco é recalculado automaticamente pelas marcações corrigidas. Não é mais necessário digitar horas/minutos para alterar o saldo.</p>
        {selected&&<div className="adjustment-balance-strip"><div><span>Colaborador</span><b>{selected.name}</b></div><div><span>Banco mensal</span><b className={(selected.monthBalanceMinutes??0)<0?'negative':'positive'}>{signed(selected.monthBalanceMinutes??0)}</b></div><div><span>Banco acumulado</span><b className={(selected.accumulatedBalanceMinutes??0)<0?'negative':'positive'}>{signed(selected.accumulatedBalanceMinutes??0)}</b></div></div>}
      </section>

      <section className="card">
        <div className="eyebrow">COMPENSAÇÃO / ABONO</div><h2>Usar saldo acumulado</h2>
        <p className="muted">O saldo acumulado é separado do saldo de cada dia. Quando o Administrador usa horas para abonar uma falta/atraso, o consumo é associado ao dia correspondente.</p>
        <form className="stack" onSubmit={compensate}>
          <label>Colaborador<select value={employeeId} onChange={e=>setEmployeeId(e.target.value)}>{items.map(i=><option value={i.id} key={i.id}>{i.name} · acumulado {fmtMinutes(i.accumulatedBalanceMinutes??0)}</option>)}</select></label>
          <label>Forma de abono<select value={useScope} onChange={e=>setUseScope(e.target.value as CompensationScope)}><option value="HOUR">Por hora/minutos</option><option value="DAY">Dia inteiro</option><option value="WEEK">Semana</option><option value="MONTH">Mês</option></select></label>
          <label>{useScope==='MONTH'?'Data de referência do mês':useScope==='WEEK'?'Data de referência da semana':'Dia da jornada'}<input type="date" required value={useDate} onChange={e=>setUseDate(e.target.value)}/></label>
          {useScope==='HOUR'&&<label>Minutos a usar<input type="number" min="1" max="100000" required value={useMinutes} onChange={e=>setUseMinutes(e.target.value)} placeholder="Ex.: 60 para 1 hora"/></label>}
          {useScope!=='HOUR'&&<div className="notice-box"><small className="muted">O sistema localizará os déficits consolidados no período escolhido e associará cada compensação ao respectivo dia.</small></div>}
          <label>Justificativa<textarea required minLength={5} value={useReason} onChange={e=>setUseReason(e.target.value)} placeholder="Ex.: compensação autorizada pelo responsável."/></label>
          <button className="primary" disabled={usingBank}>{usingBank?'Aplicando…':'Abonar com banco de horas'}</button>
        </form>
      </section>
    </>}

    <section className="card span-wide">
      <div className="card-head"><div><div className="eyebrow">BANCO DE HORAS</div><h2>Banco mensal por dia</h2><p className="muted">Cada dia é apurado isoladamente pelas marcações: entrada, intervalo, retorno e saída. A jornada do dia vem da escala; as horas trabalhadas vêm somente das marcações. Saldo do dia = horas trabalhadas − jornada do dia. Correções de horário atualizam o cálculo automaticamente.</p>{updatedAt&&<small className="muted">Última atualização: {new Date(updatedAt).toLocaleString('pt-BR')}</small>}</div><div className="stack" style={{minWidth:190}}><label>Competência<input type="month" value={month} max={initialMonth} onChange={e=>changeMonth(e.target.value)}/></label><button onClick={()=>refresh()} disabled={loading}>{loading?'Atualizando…':'Atualizar agora'}</button></div></div>
      {msg&&<div className="success-box">{msg}</div>}
      <div className="table-wrap"><table><thead><tr><th>Colaborador</th><th>Horas trabalhadas (marcações)</th><th>Horas extras</th><th>Déficit</th><th>Banco mensal</th><th>Banco acumulado</th></tr></thead><tbody>{items.map(i=><tr key={i.id} onClick={()=>setEmployeeId(i.id)} style={{cursor:'pointer'}}><td><b>{i.name}</b><small>{i.employeeNumber}</small></td><td><b>{fmtMinutes(i.workedMinutes??0)}</b></td><td className={(i.extraMinutes??0)>0?'positive':''}>{signed(i.extraMinutes??0)}</td><td className={(i.missingMinutes??0)>0?'negative':''}>{(i.missingMinutes??0)>0?`-${fmtMinutes(i.missingMinutes)}`:fmtMinutes(0)}</td><td className={(i.monthBalanceMinutes??0)<0?'negative':(i.monthBalanceMinutes??0)>0?'positive':''}><b>{signed(i.monthBalanceMinutes??0)}</b><small>soma dos saldos diários desta competência</small></td><td className={(i.accumulatedBalanceMinutes??0)<0?'negative':(i.accumulatedBalanceMinutes??0)>0?'positive':''}><b>{signed(i.accumulatedBalanceMinutes??0)}</b><small>soma histórica dos saldos diários + ajustes válidos</small></td></tr>)}</tbody></table></div>

      {selected&&<div style={{marginTop:18}}><div className="card-head"><div><div className="eyebrow">DETALHAMENTO DIÁRIO</div><h3>{selected.name}</h3><p className="muted">Clique em outro colaborador na tabela acima para trocar o detalhamento.</p></div><div><span className="muted">Banco mensal</span><h3 className={(selected.monthBalanceMinutes??0)<0?'negative':'positive'}>{signed(selected.monthBalanceMinutes??0)}</h3></div></div>
        <div className="table-wrap"><table className="compact-table"><thead><tr><th>Data</th><th>Jornada do dia</th><th>Horas trabalhadas (marcações)</th><th>Extra</th><th>Déficit</th><th>Compensação</th><th>Saldo do dia</th><th>Situação</th></tr></thead><tbody>{(selected.days??[]).map((d:any)=><tr key={d.date}><td><b>{dateBr(d.date)}</b></td><td>{fmtMinutes(d.scheduledMinutes??0)}</td><td>{fmtMinutes(d.workedMinutes??0)}</td><td className={(d.extraMinutes??0)>0?'positive':''}>{signed(d.extraMinutes??0)}</td><td className={(d.deficitMinutes??0)>0?'negative':''}>{(d.deficitMinutes??0)>0?`-${fmtMinutes(d.deficitMinutes)}`:fmtMinutes(0)}</td><td>{(d.compensatedMinutes??0)>0?fmtMinutes(d.compensatedMinutes):'0h00'}</td><td className={(d.balanceMinutes??0)<0?'negative':(d.balanceMinutes??0)>0?'positive':''}><b>{signed(d.balanceMinutes??0)}</b></td><td><small>{dayStatus(d)}</small>{(d.reservedMinutes??0)>0&&<small> · reservado {fmtMinutes(d.reservedMinutes)}</small>}</td></tr>)}{!(selected.days??[]).length&&<tr><td colSpan={8} className="muted">Nenhum dia apurado nesta competência.</td></tr>}</tbody></table></div>
      </div>}

      <div className="notice-box"><b>Regra do banco por marcação</b><p className="muted">Jornada do dia 8h00 e horas trabalhadas pelas marcações 7h37 ⇒ saldo daquele dia = <b>-0h23</b>. No dia seguinte o cálculo começa novamente pelas marcações daquele dia. O Banco mensal soma os saldos da competência; o Banco acumulado mantém o histórico.</p></div>
      {hoursMode==='OVERTIME'&&<div className="notice-box"><b>Empresa configurada para pagamento de hora extra</b><p className="muted">O detalhamento diário continua sendo calculado pelas marcações; a destinação financeira das horas extras segue a configuração da empresa.</p></div>}
    </section>
  </div>;
}
