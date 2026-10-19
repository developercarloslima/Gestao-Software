import { LateJustificationDocumentType, LateJustificationMode, PunchType } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, localDateKey, zonedDateTimeToUtc } from '../lib/time.js';

export async function getLateJustificationMode(tenantId:string){
  const rules=await prisma.timeRuleSettings.upsert({where:{tenantId},update:{},create:{tenantId}});
  return rules.lateJustificationMode;
}

export function lateJustificationTypeLabel(mode:LateJustificationMode|null|undefined,documentType:LateJustificationDocumentType|null|undefined){
  if(mode===LateJustificationMode.PROFESSIONAL){
    if(documentType===LateJustificationDocumentType.HOURS_DECLARATION)return 'Justificativa com declaração de horas';
    if(documentType===LateJustificationDocumentType.ATTENDANCE_PROOF)return 'Justificativa com comprovante de presença';
    return 'Justificativa profissional';
  }
  return 'Justificativa manual';
}

export function adjustmentStatusLabel(status:string){
  if(status==='APPROVED')return 'Aprovado';
  if(status==='REJECTED')return 'Reprovado';
  return 'Aguardando aprovação';
}

export async function lateJustificationSummariesForRange(tenantId:string,employeeId:string,from:string,to:string){
  const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{timezone:true}});
  if(!tenant)return [];
  const timeZone=tenant.timezone||'America/Maceio';
  const start=zonedDateTimeToUtc(from,'00:00',timeZone);
  const end=zonedDateTimeToUtc(addDaysKey(to,1),'00:00',timeZone);
  const rows=await prisma.adjustmentRequest.findMany({
    where:{tenantId,employeeId,requestedType:PunchType.CLOCK_IN,targetPunchId:null,lateJustificationMode:{not:null},requestedTime:{gte:start,lt:end}},
    orderBy:[{requestedTime:'asc'},{requestedAt:'asc'}]
  });
  const reviewerIds=[...new Set(rows.map(r=>r.reviewedById).filter((id):id is string=>Boolean(id)))];
  const reviewers=reviewerIds.length?await prisma.user.findMany({where:{id:{in:reviewerIds}},select:{id:true,displayName:true,email:true}}):[];
  const reviewerById=new Map(reviewers.map(u=>[u.id,u.displayName?.trim()||u.email]));
  return rows.map(row=>({
    id:row.id,
    workDate:localDateKey(row.requestedTime,timeZone),
    requestedTime:row.requestedTime,
    submittedAt:row.requestedAt,
    type:row.lateJustificationMode,
    documentType:row.supportingDocumentType,
    typeLabel:lateJustificationTypeLabel(row.lateJustificationMode,row.supportingDocumentType),
    status:row.status,
    statusLabel:adjustmentStatusLabel(row.status),
    reviewerName:row.automaticRejection?'Sistema':row.reviewedById?reviewerById.get(row.reviewedById)??'Responsável não identificado':null,
    reviewedAt:row.reviewedAt,
    automaticRejection:row.automaticRejection,
    hasDocument:Boolean(row.supportingDocumentStorageKey)
  }));
}
