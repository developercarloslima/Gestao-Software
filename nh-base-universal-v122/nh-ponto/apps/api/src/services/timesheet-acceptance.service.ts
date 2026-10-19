import { prisma } from '../lib/prisma.js';
import { canonicalize, sha256 } from '../lib/hash.js';
import { localDateKey } from '../lib/time.js';
import { calculateEmployeeRange } from './time-engine.service.js';
import { manualBankAdjustmentsForRange } from './manual-bank-adjustments.service.js';
import { lateJustificationSummariesForRange } from './late-justification.service.js';
import { pointAdjustmentSummariesForRange } from './point-adjustment.service.js';

export const MONTH_RE = /^\d{4}-(0[1-9]|1[0-2])$/;

export function monthRange(month:string){
  if(!MONTH_RE.test(month))throw Object.assign(new Error('Competência inválida. Use AAAA-MM.'),{statusCode:400});
  const [year,m]=month.split('-').map(Number);
  const last=new Date(Date.UTC(year,m,0)).getUTCDate();
  return {from:`${month}-01`,to:`${month}-${String(last).padStart(2,'0')}`,lastDay:last};
}

function snapshotPayload(result:any,month:string,from:string,to:string,manualAdjustments:any[],lateJustifications:any[],pointAdjustments:any[]){
  return {
    version:'NH_TIMESHEET_V1',month,from,to,
    company:{id:result.employee.tenant.id,name:result.employee.tenant.name,legalName:result.employee.tenant.legalName??null,document:result.employee.tenant.document??null,timezone:result.employee.tenant.timezone},
    employee:{id:result.employee.id,name:result.employee.name,employeeNumber:result.employee.employeeNumber,cpfMasked:result.employee.cpfMasked??null,jobTitle:result.employee.jobTitle??null,admissionDate:result.employee.admissionDate?.toISOString?.()??result.employee.admissionDate??null,department:result.employee.department?.name??null,worksite:result.employee.worksite?.name??null,shift:result.employee.shift?.name??null},
    days:result.days.map((d:any)=>({date:d.date,scheduledSeconds:d.scheduledSeconds,workedSeconds:d.workedSeconds,breakSeconds:d.breakSeconds,missingSeconds:d.missingSeconds,overtime50Seconds:d.overtime50Seconds,overtime100Seconds:d.overtime100Seconds,nightSeconds:d.nightSeconds,bankDeltaSeconds:d.bankDeltaSeconds,punchCount:d.punchCount,pendingReview:Boolean(d.pendingReview),warnings:d.warnings??[]})),
    totals:result.totals,
    manualAdjustments:manualAdjustments.map(a=>({id:a.id,referenceDate:a.referenceDate,minutes:a.minutes,reason:a.reason,effectiveAt:a.effectiveAt,actor:a.actor})),
    lateJustifications:lateJustifications.map(j=>({id:j.id,workDate:j.workDate,typeLabel:j.typeLabel,status:j.status,statusLabel:j.statusLabel,reviewerName:j.reviewerName,reviewedAt:j.reviewedAt,automaticRejection:j.automaticRejection,hasDocument:j.hasDocument})),
    pointAdjustments:pointAdjustments.map(a=>({id:a.id,workDate:a.workDate,kind:a.kind,type:a.type,typeLabel:a.typeLabel,requestedTime:a.requestedTime,originalTime:a.originalTime,originalType:a.originalType,originalTypeLabel:a.originalTypeLabel,reason:a.reason,responsibleName:a.responsibleName,reviewedAt:a.reviewedAt}))
  };
}

export async function buildTimesheetAcceptanceSnapshot(tenantId:string,employeeId:string,month:string){
  const {from,to}=monthRange(month);
  const [result,manualAdjustments,lateJustifications,pointAdjustments]=await Promise.all([calculateEmployeeRange(employeeId,tenantId,from,to),manualBankAdjustmentsForRange(tenantId,employeeId,from,to),lateJustificationSummariesForRange(tenantId,employeeId,from,to),pointAdjustmentSummariesForRange(tenantId,employeeId,from,to)]);
  const payload=snapshotPayload(result,month,from,to,manualAdjustments,lateJustifications,pointAdjustments);
  const snapshotHash=sha256(canonicalize(payload));
  const hasPendingReviews=result.days.some((d:any)=>Boolean(d.pendingReview));
  return {snapshotHash,payload,result,from,to,hasPendingReviews};
}

export async function timesheetAcceptanceStatus(tenantId:string,employeeId:string,month:string,now=new Date()){
  const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{timezone:true}});
  if(!tenant)throw Object.assign(new Error('Empresa não encontrada'),{statusCode:404});
  const snapshot=await buildTimesheetAcceptanceSnapshot(tenantId,employeeId,month);
  const row=await prisma.timesheetAcceptance.findUnique({
    where:{tenantId_employeeId_month:{tenantId,employeeId,month}},
    include:{adminUser:{select:{displayName:true,email:true}},employeeUser:{select:{displayName:true,email:true}}}
  });
  const today=localDateKey(now,tenant.timezone||'America/Maceio');
  const opensAt=snapshot.to;
  const currentHashMatches=Boolean(row&&row.snapshotHash===snapshot.snapshotHash);
  const adminAccepted=Boolean(row?.adminAcceptedAt&&currentHashMatches);
  const employeeAccepted=Boolean(row?.employeeAcceptedAt&&adminAccepted);
  return {
    month,from:snapshot.from,to:snapshot.to,snapshotHash:snapshot.snapshotHash,currentHashMatches,
    hasPendingReviews:snapshot.hasPendingReviews,acceptanceOpensAt:opensAt,adminCanAccept:today>=opensAt&&!snapshot.hasPendingReviews,
    adminAcceptedAt:adminAccepted?row!.adminAcceptedAt:null,
    adminSigner:adminAccepted?{id:row!.adminUserId,name:row!.adminUser?.displayName??row!.adminUser?.email??'Administrador',credentialId:row!.adminCredentialId}:null,
    employeeCanAccept:adminAccepted&&!employeeAccepted,
    employeeAcceptedAt:employeeAccepted?row!.employeeAcceptedAt:null,
    employeeSigner:employeeAccepted?{id:row!.employeeUserId,name:row!.employeeUser?.displayName??row!.employeeUser?.email??'Colaborador',credentialId:row!.employeeCredentialId}:null,
    fullyAccepted:adminAccepted&&employeeAccepted,
    outdated:Boolean(row?.adminAcceptedAt&&!currentHashMatches)
  };
}
