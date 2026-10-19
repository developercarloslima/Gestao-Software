import { prisma } from '../lib/prisma.js';
import { addDaysKey, dateKeyToUtcNoon, localDateKey } from '../lib/time.js';

export type ManualBankAdjustmentView={
  id:string;
  referenceDate:string;
  minutes:number;
  direction:'CREDIT'|'DEBIT';
  reason:string;
  effectiveAt:Date;
  actor:{id:string|null;name:string};
};

export async function manualBankAdjustmentsForRange(tenantId:string,employeeId:string,from:string,to:string):Promise<ManualBankAdjustmentView[]> {
  const fromDate=dateKeyToUtcNoon(from),toDate=dateKeyToUtcNoon(to);
  const fallbackFrom=new Date(`${from}T00:00:00.000Z`);
  const fallbackTo=new Date(`${addDaysKey(to,1)}T00:00:00.000Z`);
  const rows=await prisma.bankHourAdjustment.findMany({
    where:{
      tenantId,employeeId,kind:'MANUAL',status:'APPLIED',
      OR:[
        {workDate:{gte:fromDate,lte:toDate}},
        {workDate:null,effectiveAt:{gte:fallbackFrom,lt:fallbackTo}}
      ]
    },
    select:{id:true,minutes:true,reason:true,effectiveAt:true,workDate:true,actorUserId:true},
    orderBy:[{workDate:'asc'},{effectiveAt:'asc'}]
  });
  const actorIds=[...new Set(rows.map(r=>r.actorUserId).filter((x):x is string=>Boolean(x)))];
  const actors=actorIds.length?await prisma.user.findMany({where:{tenantId,id:{in:actorIds}},select:{id:true,displayName:true,email:true}}):[];
  const actorMap=new Map(actors.map(a=>[a.id,a.displayName?.trim()||a.email]));
  return rows.map(row=>({
    id:row.id,
    referenceDate:row.workDate?localDateKey(row.workDate,'UTC'):row.effectiveAt.toISOString().slice(0,10),
    minutes:row.minutes,
    direction:row.minutes>=0?'CREDIT':'DEBIT',
    reason:row.reason,
    effectiveAt:row.effectiveAt,
    actor:{id:row.actorUserId??null,name:(row.actorUserId&&actorMap.get(row.actorUserId))||'Sistema / responsável não identificado'}
  }));
}
