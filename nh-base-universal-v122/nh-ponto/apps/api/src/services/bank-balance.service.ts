import { prisma } from '../lib/prisma.js';
import { dateKeyToUtcNoon } from '../lib/time.js';

type Balance = {
  ledgerNetMinutes:number;
  earnedExtraMinutes:number;
  appliedAdjustmentMinutes:number;
  reservedMinutes:number;
  accumulatedMinutes:number;
};

function int(value:unknown,fallback=0){
  return typeof value==='number'&&Number.isFinite(value)?Math.round(value):fallback;
}

function ledgerValues(row:{bankDeltaMinutes:number;overtime50Minutes:number;overtime100Minutes:number;detailsJson:unknown}){
  const details=(row.detailsJson&&typeof row.detailsJson==='object'&&!Array.isArray(row.detailsJson)?row.detailsJson:{}) as Record<string,unknown>;
  if(details.bankPosted===false)return {net:0,extra:0};

  const hasDetailedBank =
    typeof details.bankExtraMinutes==='number' ||
    typeof details.bankDeficitMinutes==='number' ||
    typeof details.bankCompensatedMinutes==='number';

  if(hasDetailedBank){
    const extra=Math.max(0,int(details.bankExtraMinutes));
    const rawDeficit=Math.max(0,int(details.bankDeficitMinutes));
    const compensated=Math.max(0,int(details.bankCompensatedMinutes));
    const deficit=Math.max(0,rawDeficit-compensated);
    return {net:extra-deficit,extra};
  }

  // Compatibilidade com ledgers antigos: o bankDelta já representa o saldo do dia.
  // Ao contrário da regra V83.88, déficits negativos também fazem parte do acumulado,
  // porque o banco acumulado agora é a soma histórica dos saldos diários consolidados.
  const net=int(row.bankDeltaMinutes);
  const overtimeFallback=Math.max(0,int(row.overtime50Minutes)+int(row.overtime100Minutes));
  return {net,extra:overtimeFallback>0?overtimeFallback:Math.max(0,net)};
}

export async function bankBalancesForEmployees(tenantId:string,employeeIds:string[],throughDateKey?:string){
  const ids=[...new Set(employeeIds)].filter(Boolean);
  const result=new Map<string,Balance>();
  for(const id of ids)result.set(id,{ledgerNetMinutes:0,earnedExtraMinutes:0,appliedAdjustmentMinutes:0,reservedMinutes:0,accumulatedMinutes:0});
  if(!ids.length)return result;

  const workDate=throughDateKey?{lte:dateKeyToUtcNoon(throughDateKey)}:undefined;
  const [ledgerRows,adjustments]=await Promise.all([
    prisma.timeBalanceLedger.findMany({
      where:{tenantId,employeeId:{in:ids},...(workDate?{workDate}:{})},
      select:{employeeId:true,bankDeltaMinutes:true,overtime50Minutes:true,overtime100Minutes:true,detailsJson:true}
    }),
    prisma.bankHourAdjustment.findMany({
      where:{tenantId,employeeId:{in:ids},status:{not:'CANCELLED'}},
      select:{employeeId:true,minutes:true,requestedMinutes:true,kind:true,status:true}
    })
  ]);

  for(const row of ledgerRows){
    const item=result.get(row.employeeId);if(!item)continue;
    const values=ledgerValues(row);
    item.ledgerNetMinutes+=values.net;
    item.earnedExtraMinutes+=values.extra;
  }
  for(const row of adjustments){
    const item=result.get(row.employeeId);if(!item)continue;
    if(row.status==='APPLIED')item.appliedAdjustmentMinutes+=int(row.minutes);
    if(row.kind==='COMPENSATION'&&row.status==='PLANNED')item.reservedMinutes+=Math.max(0,int(row.requestedMinutes));
  }
  for(const item of result.values()){
    // V83.91: Banco acumulado = soma histórica dos saldos diários consolidados
    // + ajustes manuais/compensações já aplicados - reservas ainda planejadas.
    // O saldo diário continua independente; esta soma é somente o acumulador histórico.
    item.accumulatedMinutes=item.ledgerNetMinutes+item.appliedAdjustmentMinutes-item.reservedMinutes;
  }
  return result;
}

export async function bankBalanceForEmployee(tenantId:string,employeeId:string,throughDateKey?:string){
  const map=await bankBalancesForEmployees(tenantId,[employeeId],throughDateKey);
  return map.get(employeeId)??{ledgerNetMinutes:0,earnedExtraMinutes:0,appliedAdjustmentMinutes:0,reservedMinutes:0,accumulatedMinutes:0};
}
