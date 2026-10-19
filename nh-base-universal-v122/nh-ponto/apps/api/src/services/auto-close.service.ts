import { PunchDecision, PunchType } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { createImmutablePunch } from './punch.service.js';
import { getPointLockState } from './point-lock.service.js';
import { persistEmployeeLedger } from './time-engine.service.js';

export const AUTO_CLOSE_OBSERVATION='Jornada finalizada automaticamente pelo sistema após completar 15 minutos sem saída manual; esta é a única finalização permitida sem selfie';

export async function autoCloseEmployeeIfDue(tenantId:string,employeeId:string,now=new Date()){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId,active:true,archivedAt:null,userId:{not:null}},select:{id:true,tenantId:true,name:true,worksiteId:true,user:{select:{role:true}}}});
  if(!employee)return {closed:false,reason:'EMPLOYEE_NOT_ACTIVE'} as const;
  const lock=await getPointLockState(tenantId,employeeId,now);
  if(!lock.autoFinalizeDue||!lock.autoCloseAt)return {closed:false,reason:'NOT_DUE',lock} as const;

  // Idempotência por colaborador + dia. Mesmo com scheduler e requisições concorrentes,
  // a saída automática não será duplicada.
  const clientEventId=`system-auto-lock-no-selfie:${employee.id}:${lock.workDate}:${lock.overtimeGrantCount??0}`;
  const punch=await createImmutablePunch({
    tenantId:employee.tenantId,
    clientEventId,
    employeeId:employee.id,
    actorUserId:null,
    type:PunchType.CLOCK_OUT,
    occurredAt:lock.autoCloseAt,
    timezone:lock.timeZone,
    source:'SYSTEM_AUTO_LOCK_NO_SELFIE',
    offline:false,
    decision:PunchDecision.APPROVED,
    reviewReason:AUTO_CLOSE_OBSERVATION,
    evidence:{
      worksiteId:employee.worksiteId??undefined,
      deviceTrusted:false,
      faceDetected:false,
      faceCount:0,
      faceVerified:false,
      livenessChallengePassed:false,
      platformBiometricVerified:false,
      mockLocationRisk:false,
      appIntegrityStatus:'SYSTEM_AUTO_CLOSE',
      locationTelemetry:{automatic:true,lockAt:lock.cutoffAt?.toISOString()??null,selfieDeadlineAt:lock.autoCloseAt.toISOString(),manualSelfieRequiredUntil:lock.autoCloseAt.toISOString(),automaticNoSelfieException:true},
      riskSignals:['AUTO_CLOCK_OUT_WITHOUT_SELFIE','AUTO_CLOCK_OUT_WITHOUT_GPS'],
      proofScore:0,
      proofLevel:'REVIEW',
      reasons:[AUTO_CLOSE_OBSERVATION],
      missingRequirements:['SELFIE','GPS']
    }
  });

  const alreadyAlerted=await prisma.alert.findFirst({where:{tenantId:employee.tenantId,employeeId:employee.id,code:'POINT_AUTO_CLOSE_INFO',resolved:false,description:{contains:AUTO_CLOSE_OBSERVATION}},select:{id:true}});
  if(!alreadyAlerted){
    await prisma.alert.create({data:{tenantId:employee.tenantId,employeeId:employee.id,severity:'MEDIUM',code:'POINT_AUTO_CLOSE_INFO',title:`Jornada de ${employee.name} finalizada automaticamente`,description:`${AUTO_CLOSE_OBSERVATION}. Saída automática registrada em ${lock.autoCloseAt.toISOString()}. A jornada foi consolidada sem fila de aprovação.`}});
  }
  const audited=await prisma.auditEvent.findFirst({where:{tenantId:employee.tenantId,punchId:punch.id,action:'JOURNEY_AUTO_CLOSED_NO_SELFIE'},select:{id:true}});
  if(!audited){
    await prisma.auditEvent.create({data:{tenantId:employee.tenantId,punchId:punch.id,actorUserId:null,action:'JOURNEY_AUTO_CLOSED_NO_SELFIE',entityType:'Employee',entityId:employee.id,metadataJson:{workDate:lock.workDate,clockOutId:punch.id,lockAt:lock.cutoffAt,autoCloseAt:lock.autoCloseAt,observation:AUTO_CLOSE_OBSERVATION}}});
  }
  // A finalização automática também encerra e consolida a jornada imediatamente.
  await persistEmployeeLedger(employee.id,employee.tenantId,lock.workDate,lock.workDate);
  return {closed:true,punchId:punch.id,workDate:lock.workDate,autoCloseAt:lock.autoCloseAt,observation:AUTO_CLOSE_OBSERVATION} as const;
}

/**
 * Varredura de segurança para fechar jornadas mesmo quando o colaborador não está com o app aberto.
 */
export async function runAutoCloseExpiredJourneys(now=new Date()){
  const employees=await prisma.employee.findMany({where:{active:true,archivedAt:null,userId:{not:null},shiftId:{not:null}},select:{id:true,tenantId:true}});
  let scanned=0,closed=0,failed=0;
  for(const employee of employees){
    scanned++;
    try{const result=await autoCloseEmployeeIfDue(employee.tenantId,employee.id,now);if(result.closed)closed++;}
    catch{failed++;}
  }
  return {scanned,closed,failed};
}
