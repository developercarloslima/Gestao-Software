import { PunchType } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, localDateKey, zonedDateTimeToUtc } from '../lib/time.js';

const LABELS:Record<string,string>={
  CLOCK_IN:'Entrada',BREAK_START:'Início do intervalo',BREAK_END:'Retorno do intervalo',PAUSE_START:'Início da pausa',PAUSE_END:'Retorno da pausa',CLOCK_OUT:'Saída'
};

export type PointAdjustmentSummary={
  id:string;
  workDate:string;
  kind:'CORRECTION'|'ADDITION';
  type:PunchType;
  typeLabel:string;
  requestedTime:Date;
  originalTime:Date|null;
  originalType:PunchType|null;
  originalTypeLabel:string|null;
  reason:string;
  responsibleName:string;
  reviewedAt:Date|null;
};

export async function pointAdjustmentSummariesForRange(tenantId:string,employeeId:string,from:string,to:string):Promise<PointAdjustmentSummary[]> {
  const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{timezone:true}});
  const timezone=tenant?.timezone||'America/Maceio';
  const start=zonedDateTimeToUtc(from,'00:00',timezone);
  const end=zonedDateTimeToUtc(addDaysKey(to,1),'00:00',timezone);
  const rows=await prisma.adjustmentRequest.findMany({
    where:{tenantId,employeeId,status:'APPROVED',lateJustificationMode:null,requestedTime:{gte:start,lt:end}},
    orderBy:[{requestedTime:'asc'},{requestedAt:'asc'}]
  });
  if(!rows.length)return [];
  const reviewerIds=[...new Set(rows.map(r=>r.reviewedById).filter((x):x is string=>Boolean(x)))];
  const targetIds=[...new Set(rows.map(r=>r.targetPunchId).filter((x):x is string=>Boolean(x)))];
  const [reviewers,targets]=await Promise.all([
    reviewerIds.length?prisma.user.findMany({where:{tenantId,id:{in:reviewerIds}},select:{id:true,displayName:true,email:true}}):Promise.resolve([]),
    targetIds.length?prisma.punch.findMany({where:{tenantId,id:{in:targetIds}},select:{id:true,type:true,occurredAt:true}}):Promise.resolve([])
  ]);
  const reviewerMap=new Map(reviewers.map(r=>[r.id,r.displayName?.trim()||r.email||'Administrador']));
  const targetMap=new Map(targets.map(t=>[t.id,t]));
  return rows.map(row=>{
    const original=row.targetPunchId?targetMap.get(row.targetPunchId)??null:null;
    return {
      id:row.id,
      workDate:localDateKey(row.requestedTime,timezone),
      kind:row.targetPunchId?'CORRECTION':'ADDITION',
      type:row.requestedType,
      typeLabel:LABELS[row.requestedType]??row.requestedType,
      requestedTime:row.requestedTime,
      originalTime:original?.occurredAt??null,
      originalType:original?.type??null,
      originalTypeLabel:original?(LABELS[original.type]??original.type):null,
      reason:row.reason,
      responsibleName:row.reviewedById?reviewerMap.get(row.reviewedById)??'Administrador':'Administrador',
      reviewedAt:row.reviewedAt
    };
  });
}
