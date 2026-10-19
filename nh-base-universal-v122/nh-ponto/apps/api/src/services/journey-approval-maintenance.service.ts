import { PunchDecision, PunchType, Role } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, zonedDateTimeToUtc } from '../lib/time.js';
import { persistEmployeeLedger, workDateKeyForInstant } from './time-engine.service.js';

export const JOURNEY_AUTO_APPROVAL_AFTER_MS=4*24*60*60*1000;

async function approveWholeJourney(clockOutId:string,mode:'ADMIN_CONTROL'|'FOUR_DAYS'){
  const clockOut=await prisma.punch.findFirst({
    where:{id:clockOutId,type:PunchType.CLOCK_OUT,decision:PunchDecision.REVIEW},
    include:{employee:{include:{shift:true,tenant:true,user:{select:{role:true}}}}}
  });
  if(!clockOut)return {approved:false,reason:'NOT_PENDING'} as const;
  if(mode==='ADMIN_CONTROL'&&clockOut.employee.user?.role!==Role.ADMIN)return {approved:false,reason:'NOT_ADMIN'} as const;
  if(mode==='FOUR_DAYS'&&clockOut.occurredAt.getTime()>Date.now()-JOURNEY_AUTO_APPROVAL_AFTER_MS)return {approved:false,reason:'NOT_OLD_ENOUGH'} as const;
  const employee=clockOut.employee;
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(clockOut.occurredAt,employee.shift,timeZone);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone);
  const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const candidates=await prisma.punch.findMany({where:{tenantId:employee.tenantId,employeeId:employee.id,decision:PunchDecision.REVIEW,occurredAt:{gte:start,lt:end}},select:{id:true,occurredAt:true}});
  const ids=candidates.filter(p=>workDateKeyForInstant(p.occurredAt,employee.shift,timeZone)===workDate).map(p=>p.id);
  if(!ids.length)return {approved:false,reason:'NO_PENDING_PUNCHES'} as const;
  await prisma.$transaction(async tx=>{
    await tx.punch.updateMany({where:{id:{in:ids},tenantId:employee.tenantId,decision:PunchDecision.REVIEW},data:{decision:PunchDecision.APPROVED}});
    await tx.auditEvent.create({data:{tenantId:employee.tenantId,actorUserId:null,action:mode==='ADMIN_CONTROL'?'ADMIN_JOURNEY_CONSOLIDATED_WITHOUT_APPROVAL':'JOURNEY_AUTO_APPROVED_AFTER_4_DAYS',entityType:'Employee',entityId:employee.id,beforeJson:{workDate,status:'PENDING_APPROVAL',punchIds:ids} as any,afterJson:{workDate,status:'APPROVED',automatic:true,reason:mode==='ADMIN_CONTROL'?'ADMIN_CONTROL':'PENDING_OVER_4_DAYS'} as any}});
    await tx.alert.updateMany({where:{tenantId:employee.tenantId,employeeId:employee.id,code:'PUNCH_REVIEW',resolved:false},data:{resolved:true,resolvedAt:new Date()}});
  });
  await persistEmployeeLedger(employee.id,employee.tenantId,workDate,workDate);
  return {approved:true,employeeId:employee.id,tenantId:employee.tenantId,workDate,punches:ids.length,mode} as const;
}

export async function runJourneyApprovalMaintenance(now=new Date()){
  const cutoff=new Date(now.getTime()-JOURNEY_AUTO_APPROVAL_AFTER_MS);
  const [adminRows,oldRows]=await Promise.all([
    prisma.punch.findMany({where:{type:PunchType.CLOCK_OUT,decision:PunchDecision.REVIEW,employee:{is:{user:{is:{role:Role.ADMIN}}}}},select:{id:true},orderBy:{occurredAt:'asc'},take:300}),
    prisma.punch.findMany({where:{type:PunchType.CLOCK_OUT,decision:PunchDecision.REVIEW,occurredAt:{lte:cutoff}},select:{id:true},orderBy:{occurredAt:'asc'},take:500})
  ]);
  let adminApproved=0,expiredApproved=0,failed=0;
  const handled=new Set<string>();
  for(const row of adminRows){
    handled.add(row.id);
    try{const r=await approveWholeJourney(row.id,'ADMIN_CONTROL');if(r.approved)adminApproved++;}catch{failed++;}
  }
  for(const row of oldRows){
    if(handled.has(row.id))continue;
    try{const r=await approveWholeJourney(row.id,'FOUR_DAYS');if(r.approved)expiredApproved++;}catch{failed++;}
  }
  return {adminApproved,expiredApproved,failed,scanned:adminRows.length+oldRows.length};
}
