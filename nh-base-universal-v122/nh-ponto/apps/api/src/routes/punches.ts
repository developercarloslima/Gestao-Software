import crypto from 'node:crypto';
import { FastifyInstance } from 'fastify';
import { LivenessAction, PunchDecision, PunchType, Role } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { buildPunchHashPayload, createImmutablePunch } from '../services/punch.service.js';
import { canonicalize, sha256 } from '../lib/hash.js';
import { addDaysKey, isValidTimeZone, zonedDateTimeToUtc } from '../lib/time.js';
import { persistEmployeeLedger, workDateKeyForInstant } from '../services/time-engine.service.js';
import { getOrCreateSecuritySettings } from '../services/security-settings.service.js';
import { evaluatePresence } from '../services/presence.service.js';
import { evaluateProof } from '../services/proof.service.js';
import { saveEncryptedImageDataUrl, removeEncryptedEvidence } from '../services/biometric-storage.service.js';
import { getPointLockState } from '../services/point-lock.service.js';
import { autoCloseEmployeeIfDue } from '../services/auto-close.service.js';
import { getClockInAccess } from '../services/work-schedule-access.service.js';
import { getLateJustificationMode } from '../services/late-justification.service.js';

const faceEmbedding = z.array(z.number()).min(64).max(4096).optional();
const MANUAL_SELFIE_REQUIRED_TYPES=new Set<PunchType>([PunchType.CLOCK_IN,PunchType.BREAK_START,PunchType.BREAK_END,PunchType.PAUSE_START,PunchType.PAUSE_END,PunchType.CLOCK_OUT]);
const MIN_BREAK_RETURN_MINUTES=105;
const punchSchema = z.object({
  type:z.nativeEnum(PunchType),clientEventId:z.string().uuid(),challengeId:z.string().optional(),occurredAt:z.string().datetime().optional(),
  timezone:z.string().refine(isValidTimeZone,'Fuso horário inválido').default('America/Maceio'),offline:z.boolean().default(false),
  latitude:z.number().min(-90).max(90),longitude:z.number().min(-180).max(180),accuracyM:z.number().nonnegative().max(100000).optional(),altitudeM:z.number().min(-1000).max(20000).optional(),altitudeAccuracyM:z.number().nonnegative().max(100000).optional(),headingDeg:z.number().min(0).max(360).optional(),speedMps:z.number().min(0).max(1000).optional(),locationCapturedAt:z.string().datetime().optional(),
  deviceFingerprint:z.string().max(200).optional(),offlinePayloadHash:z.string().max(128).optional(),
  selfieDataUrl:z.string().min(100).max(4_000_000),faceEmbedding,faceCount:z.number().int().min(0).max(10).optional(),faceQualityScore:z.number().min(0).max(1).optional(),
  livenessScore:z.number().min(0).max(1).optional(),antiSpoofScore:z.number().min(0).max(1).optional(),livenessChallengePassed:z.boolean().optional(),
  biometricProofToken:z.string().max(5000).optional(),dynamicQrToken:z.string().max(3000).optional(),networkGatewayToken:z.string().max(3000).optional(),
  bluetoothName:z.string().max(120).optional(),nfcTagId:z.string().max(200).optional(),clientSecureContext:z.boolean().optional()
});

const ADMIN_ADJUSTMENT_SOURCES=new Set(['APPROVED_CORRECTION','APPROVED_ADDITION','APPROVED_CLOCK_IN_ADDITION']);
function isLivePresencePunch(p:{type:PunchType;source:string;evidence?:{selfieStorageKey?:string|null;latitude?:number|null;longitude?:number|null}|null}){
  if(p.source==='SYSTEM_AUTO_LOCK_NO_SELFIE')return p.type===PunchType.CLOCK_OUT;
  if(p.source==='APPROVED_CLOCK_IN_ADDITION')return p.type===PunchType.CLOCK_IN;
  if(ADMIN_ADJUSTMENT_SOURCES.has(p.source))return false;
  return Boolean(p.evidence?.selfieStorageKey&&p.evidence.latitude!=null&&p.evidence.longitude!=null);
}

async function quarantineInvalidPresencePunchesForDay(tenantId:string,employeeId:string,instant:Date){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId},include:{shift:true,tenant:true}});
  if(!employee)return 0;
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(instant,employee.shift,timeZone);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone);
  const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const rows=await prisma.punch.findMany({where:{tenantId,employeeId,decision:{not:PunchDecision.BLOCKED},source:{notIn:['APPROVED_CORRECTION','APPROVED_ADDITION','APPROVED_CLOCK_IN_ADDITION','SYSTEM_AUTO_LOCK_NO_SELFIE']},occurredAt:{gte:start,lt:end}},include:{evidence:{select:{selfieStorageKey:true,latitude:true,longitude:true}}},orderBy:{recordNumber:'asc'}});
  const invalid=rows.filter(p=>workDateKeyForInstant(p.occurredAt,employee.shift,timeZone)===workDate&&!isLivePresencePunch(p));
  for(const punch of invalid){
    const reason='Marcação presencial invalidada automaticamente: selfie e GPS são obrigatórios para registrar jornada.';
    await prisma.$transaction(async tx=>{
      const updated=await tx.punch.updateMany({where:{id:punch.id,decision:{not:PunchDecision.BLOCKED}},data:{decision:PunchDecision.BLOCKED,reviewReason:reason}});
      if(!updated.count)return;
      await tx.auditEvent.create({data:{tenantId,punchId:punch.id,actorUserId:null,action:'PUNCH_INVALID_EVIDENCE_QUARANTINED',entityType:'Punch',entityId:punch.id,metadataJson:{workDate,source:punch.source,reason}}});
      const existingAlert=await tx.alert.findFirst({where:{tenantId,employeeId,code:'PUNCH_INVALID_EVIDENCE',resolved:false,description:{contains:punch.id}},select:{id:true}});
      if(!existingAlert)await tx.alert.create({data:{tenantId,employeeId,severity:'HIGH',code:'PUNCH_INVALID_EVIDENCE',title:`Marcação de ${employee.name} invalidada por falta de selfie/GPS`,description:`A marcação ${punch.id} foi bloqueada automaticamente porque uma jornada presencial não pode ser iniciada nem alterada sem selfie e GPS capturados na própria ação.`}});
    });
  }
  return invalid.length;
}

const validPrevious:Record<PunchType,PunchType[]>={
  CLOCK_IN:[PunchType.CLOCK_OUT],BREAK_START:[PunchType.CLOCK_IN,PunchType.BREAK_END,PunchType.PAUSE_END],BREAK_END:[PunchType.BREAK_START],
  PAUSE_START:[PunchType.CLOCK_IN,PunchType.BREAK_END,PunchType.PAUSE_END],PAUSE_END:[PunchType.PAUSE_START],CLOCK_OUT:[PunchType.CLOCK_IN,PunchType.BREAK_END,PunchType.PAUSE_END]
};

async function canSeeEmployee(user:any,employeeId:string){
  if(user.employeeId===employeeId)return true;const privilegedRoles: Role[]=[Role.SUPER_ADMIN,Role.ADMIN,Role.HR,Role.AUDITOR];if(privilegedRoles.includes(user.role as Role))return true;
  if(user.role===Role.SUPERVISOR&&user.employeeId)return Boolean(await prisma.employee.findFirst({where:{id:employeeId,tenantId:user.tenantId,supervisorId:user.employeeId},select:{id:true}}));
  return false;
}

function webIntegrityStatus(request:any, clientSecureContext?:boolean) {
  const origin=String(request.headers.origin??''); const proto=String(request.headers['x-forwarded-proto']??'');
  const serverSecure=origin.startsWith('https://')||origin.startsWith('http://localhost')||proto==='https';
  return serverSecure && clientSecureContext ? 'WEB_SECURE_CONTEXT' : 'WEB_UNATTESTED';
}

async function punchUsageForTurn(tenantId:string,employeeId:string,instant:Date){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId},include:{shift:true,tenant:true}});
  if(!employee)throw Object.assign(new Error('Colaborador não encontrado'),{statusCode:404});
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(instant,employee.shift,timeZone);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone);
  const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const rows=await prisma.punch.findMany({where:{tenantId,employeeId,decision:{not:PunchDecision.BLOCKED},occurredAt:{gte:start,lt:end}},select:{type:true,occurredAt:true,source:true,evidence:{select:{selfieStorageKey:true,latitude:true,longitude:true}}},orderBy:{occurredAt:'asc'}});
  const today=rows.filter(row=>workDateKeyForInstant(row.occurredAt,employee.shift,timeZone)===workDate&&isLivePresencePunch(row));
  const usedTypes=[...new Set(today.map(row=>row.type))];
  const lastPunch=today.at(-1)??null;
  const lastClockOut=[...today].reverse().find(row=>row.type===PunchType.CLOCK_OUT)??null;
  const dateNoon=new Date(`${workDate}T12:00:00.000Z`);
  const grants=await prisma.overtimeGrant.findMany({where:{tenantId,employeeId,workDate:dateNoon},select:{createdAt:true,minutes:true},orderBy:{createdAt:'asc'}});
  const canReopenOvertime=Boolean(lastClockOut&&grants.some(g=>g.createdAt.getTime()>lastClockOut.occurredAt.getTime())&&!today.some(p=>p.type===PunchType.CLOCK_IN&&p.occurredAt.getTime()>lastClockOut.occurredAt.getTime()));
  const reopenedOvertimeActive=Boolean(lastClockOut&&today.some(p=>p.type===PunchType.CLOCK_IN&&p.occurredAt.getTime()>lastClockOut.occurredAt.getTime())&&lastPunch?.type!==PunchType.CLOCK_OUT);
  const activeBreakStart=lastPunch?.type===PunchType.BREAK_START?lastPunch:null;
  const breakReturnAllowedAt=activeBreakStart?new Date(activeBreakStart.occurredAt.getTime()+MIN_BREAK_RETURN_MINUTES*60_000):null;
  const breakReturnRemainingSeconds=breakReturnAllowedAt?Math.max(0,Math.ceil((breakReturnAllowedAt.getTime()-instant.getTime())/1000)):0;
  const breakReturnLocked=Boolean(activeBreakStart&&breakReturnRemainingSeconds>0);
  return {workDate,timeZone,usedTypes,lastPunchType:lastPunch?.type??null,hasClockOut:Boolean(lastClockOut),canReopenOvertime,reopenedOvertimeActive,overtimeExtensionMinutes:grants.reduce((a,g)=>a+g.minutes,0),overtimeGrantCount:grants.length,breakStartedAt:activeBreakStart?.occurredAt??null,breakReturnAllowedAt,breakReturnRemainingSeconds,breakReturnLocked,minimumBreakMinutes:MIN_BREAK_RETURN_MINUTES};
}

function breakReturnBlocked(usage:any,type:PunchType){
  if(type!==PunchType.BREAK_END||!usage.breakReturnLocked)return null;
  const allowedAt=usage.breakReturnAllowedAt instanceof Date?usage.breakReturnAllowedAt:new Date(usage.breakReturnAllowedAt);
  const remainingSeconds=Math.max(0,Number(usage.breakReturnRemainingSeconds??0));
  const remainingMinutes=Math.ceil(remainingSeconds/60);
  return {
    status:423,
    error:`O retorno do intervalo só é liberado após ${MIN_BREAK_RETURN_MINUTES} minutos. Aguarde mais ${remainingMinutes} minuto${remainingMinutes===1?'':'s'}.`,
    code:'BREAK_MINIMUM_105_MINUTES',
    breakReturnAllowedAt:allowedAt.toISOString(),
    breakReturnRemainingSeconds:remainingSeconds,
    minimumBreakMinutes:MIN_BREAK_RETURN_MINUTES
  };
}

function punchAlreadyUsedOrClosed(usage:any,type:PunchType){
  if(usage.hasClockOut){
    if(type===PunchType.CLOCK_IN&&usage.canReopenOvertime)return null;
    if(type===PunchType.CLOCK_OUT&&usage.reopenedOvertimeActive)return null;
    return usage.canReopenOvertime
      ? {status:409,error:'A hora extra foi liberada. Inicie a jornada extra antes de registrar outra ação.',code:'OVERTIME_REOPEN_CLOCK_IN_REQUIRED'}
      : {status:423,error:'A jornada deste dia já foi finalizada. Uma nova jornada só pode começar no dia seguinte ou após liberação de +1h extra por um Administrador.',code:'JOURNEY_ALREADY_CLOSED'};
  }
  if(usage.usedTypes.includes(type))return {status:409,error:'Esta marcação já foi registrada neste turno.',code:'PUNCH_ALREADY_RECORDED'};
  return null;
}

export async function punchRoutes(app:FastifyInstance){
  app.get('/punches/actions',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const now=new Date();
    await autoCloseEmployeeIfDue(request.user.tenantId,request.user.employeeId,now);
    await quarantineInvalidPresencePunchesForDay(request.user.tenantId,request.user.employeeId,now);
    const [usage,lock,clockInAccess,lateJustificationMode]=await Promise.all([punchUsageForTurn(request.user.tenantId,request.user.employeeId,now),getPointLockState(request.user.tenantId,request.user.employeeId,now),getClockInAccess(request.user.tenantId,request.user.employeeId,now),getLateJustificationMode(request.user.tenantId)]);
    const lateArrivalPending=clockInAccess.code==='LATE_JUSTIFICATION_REQUIRED'&&clockInAccess.clockInOpensAt&&clockInAccess.scheduledEndAt
      ? await prisma.adjustmentRequest.findFirst({where:{tenantId:request.user.tenantId,employeeId:request.user.employeeId,requestedType:PunchType.CLOCK_IN,targetPunchId:null,status:'PENDING',requestedTime:{gte:clockInAccess.clockInOpensAt,lte:clockInAccess.scheduledEndAt}},select:{id:true,requestedTime:true,reason:true,requestedAt:true,lateJustificationMode:true,supportingDocumentType:true}})
      : null;
    return {...usage,...lock,clockInAccess,lateArrivalPending,lateJustificationMode};
  });

  app.post('/punches/precheck',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const body=z.object({type:z.nativeEnum(PunchType)}).parse(request.body);
    const now=new Date();
    await autoCloseEmployeeIfDue(request.user.tenantId,request.user.employeeId,now);
    await quarantineInvalidPresencePunchesForDay(request.user.tenantId,request.user.employeeId,now);
    const [usage,lock,employee,clockInAccess]=await Promise.all([
      punchUsageForTurn(request.user.tenantId,request.user.employeeId,now),
      getPointLockState(request.user.tenantId,request.user.employeeId,now),
      prisma.employee.findFirst({where:{id:request.user.employeeId,tenantId:request.user.tenantId,active:true},select:{id:true,worksiteId:true}}),
      getClockInAccess(request.user.tenantId,request.user.employeeId,now)
    ]);
    if(!employee)return reply.code(403).send({error:'Vínculo de colaborador inativo'});
    if(lock.closedDay)return reply.code(423).send({error:`Ponto fechado: ${lock.closedReason}.`,code:'CLOSED_DAY',closedReason:lock.closedReason});
    if(lock.autoLockRequired&&body.type!==PunchType.CLOCK_OUT)return reply.code(423).send({error:lock.warning??'O ponto está travado. Finalize a jornada com uma selfie.',code:'POINT_LOCKED_CLOCK_OUT_REQUIRED'});
    if(body.type===PunchType.CLOCK_IN&&!usage.canReopenOvertime&&!clockInAccess.allowed)return reply.code(423).send({error:clockInAccess.message,code:`CLOCK_IN_${clockInAccess.code}`,clockInAccess});
    const breakBlocked=breakReturnBlocked(usage,body.type);if(breakBlocked)return reply.code(breakBlocked.status).send(breakBlocked);
    const blocked=punchAlreadyUsedOrClosed(usage,body.type);if(blocked)return reply.code(blocked.status).send({error:blocked.error,code:blocked.code});
    return {ok:true,locationValidation:'MANUAL_REVIEW_ONLY',worksiteId:employee.worksiteId??null,selfieRequired:MANUAL_SELFIE_REQUIRED_TYPES.has(body.type),automaticNoSelfieException:body.type===PunchType.CLOCK_OUT?'Somente a finalização automática do servidor após 15 minutos pode ocorrer sem selfie.':null};
  });

  app.post('/punches/challenge',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const {type}=z.object({type:z.nativeEnum(PunchType)}).parse(request.body);
    const now=new Date();
    await autoCloseEmployeeIfDue(request.user.tenantId,request.user.employeeId,now);
    await quarantineInvalidPresencePunchesForDay(request.user.tenantId,request.user.employeeId,now);
    const [usage,lock,clockInAccess]=await Promise.all([punchUsageForTurn(request.user.tenantId,request.user.employeeId,now),getPointLockState(request.user.tenantId,request.user.employeeId,now),getClockInAccess(request.user.tenantId,request.user.employeeId,now)]);
    if(lock.closedDay)return reply.code(423).send({error:`Ponto fechado: ${lock.closedReason}.`,code:'CLOSED_DAY',closedReason:lock.closedReason});
    if(lock.autoLockRequired&&type!==PunchType.CLOCK_OUT)return reply.code(423).send({error:lock.warning??'O ponto está travado. Finalize a jornada com uma selfie.',code:'POINT_LOCKED_CLOCK_OUT_REQUIRED',autoLockRequired:true,warning:lock.warning});
    if(type===PunchType.CLOCK_IN&&!usage.canReopenOvertime&&!clockInAccess.allowed)return reply.code(423).send({error:clockInAccess.message,code:`CLOCK_IN_${clockInAccess.code}`,clockInAccess});
    const breakBlocked=breakReturnBlocked(usage,type);if(breakBlocked)return reply.code(breakBlocked.status).send({...breakBlocked,type,workDate:usage.workDate});
    const blocked=punchAlreadyUsedOrClosed(usage,type);if(blocked)return reply.code(blocked.status).send({error:blocked.error,code:blocked.code,type,workDate:usage.workDate});
    const [employee,policy]=await Promise.all([
      prisma.employee.findFirst({where:{id:request.user.employeeId,tenantId:request.user.tenantId,active:true},select:{id:true}}),
      getOrCreateSecuritySettings(request.user.tenantId)
    ]);
    if(!employee)return reply.code(403).send({error:'Vínculo de colaborador inativo'});
    const challenge=await prisma.punchChallenge.create({data:{tenantId:request.user.tenantId,employeeId:employee.id,type,nonce:crypto.randomBytes(24).toString('base64url'),livenessAction:LivenessAction.BLINK,expiresAt:new Date(Date.now()+policy.challengeTtlSeconds*1000)}});
    const forcedAutoLock=Boolean(lock.autoLockRequired&&type===PunchType.CLOCK_OUT);
    return {id:challenge.id,nonce:challenge.nonce,type,livenessAction:null,expiresAt:challenge.expiresAt,verificationMode:'SELFIE_WITH_GPS_EVIDENCE',forcedAutoLock,selfieRequired:true,manualPunch:true,policy:{requireSelfie:true,requireLocationEvidence:true,requireFaceMatch:false,requireLiveness:false,requireAntiSpoof:false,requirePlatformBiometric:false,requireGeofence:false,requireAccurateGps:false,requireDynamicQr:forcedAutoLock?false:policy.requireDynamicQr,requireBluetoothBeacon:forcedAutoLock?false:policy.requireBluetoothBeacon,requireNfcTag:forcedAutoLock?false:policy.requireNfcTag,requireNetworkAttestation:forcedAutoLock?false:policy.requireNetworkAttestation,requireTrustedDevice:forcedAutoLock?false:policy.requireTrustedDevice,allowOffline:false,offlineRequiresReview:true},platformBiometricEnrolled:false};
  });

  app.post('/punches',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const employee=await prisma.employee.findFirst({where:{id:request.user.employeeId,tenantId:request.user.tenantId,active:true},select:{id:true,name:true}});if(!employee)return reply.code(403).send({error:'Vínculo de colaborador inativo'});
    const parsedPunch=punchSchema.safeParse(request.body);
    if(!parsedPunch.success){
      const missingSelfie=parsedPunch.error.issues.some(issue=>issue.path[0]==='selfieDataUrl');
      const missingGps=parsedPunch.error.issues.some(issue=>issue.path[0]==='latitude'||issue.path[0]==='longitude');
      return reply.code(400).send({error:missingSelfie||missingGps?'Toda marcação manual de jornada exige selfie e GPS capturados na própria ação: entrada, início/retorno de intervalo, início/retorno de pausa e saída. A única exceção sem selfie é a saída automática feita pelo servidor após 15 minutos.':'Revise os dados da marcação e tente novamente.',code:missingSelfie?'SELFIE_REQUIRED':missingGps?'GPS_REQUIRED':'PUNCH_VALIDATION_ERROR',field:parsedPunch.error.issues[0]?.path?.join('.')||null});
    }
    const body=parsedPunch.data;
    if(!MANUAL_SELFIE_REQUIRED_TYPES.has(body.type))return reply.code(400).send({error:'Tipo de marcação manual inválido.',code:'INVALID_MANUAL_PUNCH_TYPE'});
    const clientCapturedAt=body.occurredAt?new Date(body.occurredAt):new Date();
    if(Number.isNaN(clientCapturedAt.getTime()))return reply.code(400).send({error:'Horário capturado inválido'});
    const duplicate=await prisma.punch.findUnique({where:{tenantId_clientEventId:{tenantId:request.user.tenantId,clientEventId:body.clientEventId}},include:{evidence:true}});
    if(duplicate)return reply.send({id:duplicate.id,recordNumber:duplicate.recordNumber.toString(),type:duplicate.type,occurredAt:duplicate.occurredAt,receivedAt:duplicate.receivedAt,integrityHash:duplicate.integrityHash,previousHash:duplicate.previousHash,decision:duplicate.decision,proof:duplicate.evidence,duplicate:true});

    const policy=await getOrCreateSecuritySettings(request.user.tenantId);
    const serverNow=new Date();
    await autoCloseEmployeeIfDue(request.user.tenantId,employee.id,serverNow);
    await quarantineInvalidPresencePunchesForDay(request.user.tenantId,employee.id,serverNow);
    if(body.offline){
      const ageMs=serverNow.getTime()-clientCapturedAt.getTime();
      if(ageMs>policy.maxOfflineAgeHours*3_600_000)return reply.code(400).send({error:`Marcação offline excede ${policy.maxOfflineAgeHours}h e não pode ser sincronizada automaticamente`});
      if(ageMs<-(policy.maxFutureClockSkewMinutes*60_000))return reply.code(400).send({error:'Relógio do dispositivo está adiantado além da tolerância permitida'});
    }
    const occurredAt=body.offline?clientCapturedAt:serverNow;
    const [usage,lock,clockInAccess]=await Promise.all([punchUsageForTurn(request.user.tenantId,employee.id,occurredAt),getPointLockState(request.user.tenantId,employee.id,occurredAt),getClockInAccess(request.user.tenantId,employee.id,occurredAt)]);
    if(lock.closedDay)return reply.code(423).send({error:`Ponto fechado: ${lock.closedReason}.`,code:'CLOSED_DAY',closedReason:lock.closedReason});
    if(lock.autoLockRequired&&body.type!==PunchType.CLOCK_OUT)return reply.code(423).send({error:lock.warning??'O ponto está travado. Finalize a jornada com uma selfie.',code:'POINT_LOCKED_CLOCK_OUT_REQUIRED',autoLockRequired:true,warning:lock.warning});
    if(body.type===PunchType.CLOCK_IN&&!usage.canReopenOvertime&&!clockInAccess.allowed)return reply.code(423).send({error:clockInAccess.message,code:`CLOCK_IN_${clockInAccess.code}`,clockInAccess});
    const breakBlockedNow=breakReturnBlocked(usage,body.type);if(breakBlockedNow)return reply.code(breakBlockedNow.status).send({...breakBlockedNow,type:body.type,workDate:usage.workDate});
    const blockedNow=punchAlreadyUsedOrClosed(usage,body.type);if(blockedNow)return reply.code(blockedNow.status).send({error:blockedNow.error,code:blockedNow.code,type:body.type,workDate:usage.workDate});
    let challengeVerified=false; let livenessAction:string|undefined; let challengeId:string|undefined;
    if(body.offline)return reply.code(403).send({error:'Marcação offline desativada: a selfie precisa ser capturada e enviada em tempo real.',code:'SELFIE_ONLINE_REQUIRED'});
    if(!body.challengeId)return reply.code(400).send({error:'Desafio de marcação obrigatório'});
    const challenge=await prisma.punchChallenge.findFirst({where:{id:body.challengeId,tenantId:request.user.tenantId,employeeId:employee.id,type:body.type,usedAt:null,expiresAt:{gt:new Date()}}});
    if(!challenge)return reply.code(400).send({error:'Desafio expirado, inválido ou já utilizado'});
    challengeVerified=true;challengeId=challenge.id;livenessAction=challenge.livenessAction;

    let selfieStored:{storageKey:string;mime:string;byteLength:number};
    try{
      selfieStored=await saveEncryptedImageDataUrl(body.selfieDataUrl,`punch-${request.user.tenantId}-${employee.id}`);
    }catch(error){
      return reply.code(400).send({error:error instanceof Error?error.message:'Selfie obrigatória inválida',code:'SELFIE_REQUIRED'});
    }
    const face={
      storageKey:selfieStored.storageKey,faceDetected:false,faceCount:0,faceQualityScore:0,faceMatchScore:0,faceVerified:false,livenessScore:0,livenessOk:false,antiSpoofScore:0,antiSpoofOk:false,
      missing:[] as string[],reasons:['Selfie simples capturada no momento da ação e armazenada de forma criptografada como evidência.','A selfie não é usada para reconhecimento facial; a marcação permanece pendente para conferência do Administrador/Super Adm.']
    };
    const consumed=await prisma.punchChallenge.updateMany({where:{id:challenge.id,usedAt:null},data:{usedAt:new Date()}});
    if(!consumed.count){await removeEncryptedEvidence(selfieStored.storageKey);return reply.code(409).send({error:'Desafio já utilizado'});}
    const presence=await evaluatePresence({tenantId:request.user.tenantId,employeeId:employee.id,latitude:body.latitude,longitude:body.longitude,accuracyM:body.accuracyM,altitudeM:body.altitudeM,altitudeAccuracyM:body.altitudeAccuracyM,headingDeg:body.headingDeg,speedMps:body.speedMps,locationCapturedAt:body.locationCapturedAt,deviceFingerprint:body.deviceFingerprint,dynamicQrToken:body.dynamicQrToken,networkGatewayToken:body.networkGatewayToken,bluetoothName:body.bluetoothName,nfcTagId:body.nfcTagId});
    const appIntegrityStatus=webIntegrityStatus(request,body.clientSecureContext);
    const autoLockClockOut=Boolean(lock.autoLockRequired&&body.type===PunchType.CLOCK_OUT);
    const effectivePolicy={...presence.policy,requireSelfie:true,requireFaceMatch:false,requireLiveness:false,requireAntiSpoof:false,blockWithoutEnrollment:false,blockOnFaceFailure:false,requirePlatformBiometric:false,requireGeofence:false,requireAccurateGps:false,allowOffline:false,...(autoLockClockOut?{requireDynamicQr:false,requireBluetoothBeacon:false,requireNfcTag:false,requireNetworkAttestation:false,requireTrustedDevice:false}:{})};
    const proof=evaluateProof({policy:effectivePolicy,offline:body.offline,challengeVerified,selfiePresent:true,face,presence,deviceFingerprint:body.deviceFingerprint,platformBiometricVerified:false,webAuthnCredentialId:undefined,appIntegrityStatus});

    if(proof.decision==='BLOCKED'){
      const reason=`Marcação bloqueada: ${proof.missingRequirements.join(', ')||'política de segurança'}`;
      await removeEncryptedEvidence(selfieStored.storageKey);
      await prisma.punchAttempt.upsert({where:{tenantId_clientEventId:{tenantId:request.user.tenantId,clientEventId:body.clientEventId}},update:{},create:{tenantId:request.user.tenantId,employeeId:employee.id,clientEventId:body.clientEventId,type:body.type,decision:PunchDecision.BLOCKED,reason,selfieStorageKey:undefined,faceMatchScore:face.faceMatchScore,livenessScore:face.livenessScore,antiSpoofScore:face.antiSpoofScore,deviceFingerprint:body.deviceFingerprint,latitude:body.latitude,longitude:body.longitude,metadataJson:{proofScore:proof.score,proofLevel:proof.proofLevel,missingRequirements:proof.missingRequirements,reasons:proof.reasons,livenessAction,challengeId}}});
      await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'PUNCH_BLOCKED',entityType:'PunchAttempt',entityId:body.clientEventId,metadataJson:{type:body.type,proofScore:proof.score,missingRequirements:proof.missingRequirements}}});
      return reply.code(403).send({error:'Marcação não concluída. A validação de identidade/presença falhou.',decision:'BLOCKED',proof:{proofScore:proof.score,proofLevel:proof.proofLevel,reasonsJson:proof.reasons,missingRequirements:proof.missingRequirements,checks:proof.checks}});
    }

    const previousCandidates=await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:employee.id,decision:{not:'BLOCKED'}},include:{evidence:{select:{selfieStorageKey:true,latitude:true,longitude:true}}},orderBy:{recordNumber:'desc'},take:20});
    const previous=previousCandidates.find(p=>isLivePresencePunch(p))??null;
    const sequenceOdd=previous?!validPrevious[body.type].includes(previous.type):body.type!==PunchType.CLOCK_IN;
    // V83.96 — marcações regulares entram aprovadas imediatamente.
    // Apenas falta e entrada atrasada seguem fluxo de justificativa/aprovação.
    const decision=PunchDecision.APPROVED;
    const reviewReason=sequenceOdd?'Sequência de marcação fora do fluxo esperado':autoLockClockOut?'Saída manual com selfie após atingir a tolerância de finalização':null;
    let punch:any;
    try{punch=await createImmutablePunch({tenantId:request.user.tenantId,clientEventId:body.clientEventId,employeeId:employee.id,actorUserId:request.user.userId,type:body.type,occurredAt,timezone:body.timezone,source:autoLockClockOut?'WEB_PWA_AUTO_LOCK':body.offline?'WEB_PWA_OFFLINE_SYNC':'WEB_PWA',offline:body.offline,decision,reviewReason,evidence:{
      challengeId,latitude:body.latitude,longitude:body.longitude,accuracyM:body.accuracyM,worksiteId:presence.worksiteId,geofenceOk:presence.geofenceOk,deviceFingerprint:body.deviceFingerprint,deviceTrusted:presence.deviceTrusted,
      selfieStorageKey:selfieStored.storageKey,selfieCapturedAt:serverNow,clientCapturedAt,faceDetected:false,faceCount:0,faceQualityScore:undefined,faceMatchScore:undefined,faceVerified:false,
      livenessScore:undefined,livenessOk:false,antiSpoofScore:undefined,antiSpoofOk:false,livenessAction:undefined,livenessChallengePassed:false,platformBiometricVerified:false,webAuthnCredentialId:undefined,
      mockLocationRisk:presence.mockLocationRisk,gpsAccuracyOk:presence.accuracyOk,appIntegrityStatus,locationTelemetry:presence.locationTelemetry,riskSignals:presence.riskSignals,networkAttested:presence.networkAttested,bluetoothAttested:presence.bluetoothAttested,nfcAttested:presence.nfcAttested,dynamicQrAttested:presence.dynamicQrAttested,offlinePayloadHash:body.offlinePayloadHash,
      proofScore:proof.score,proofLevel:proof.proofLevel,reasons:proof.reasons,missingRequirements:proof.missingRequirements
    }});}catch(error){await removeEncryptedEvidence(selfieStored.storageKey);throw error;}

    if(sequenceOdd)await prisma.alert.create({data:{tenantId:request.user.tenantId,employeeId:employee.id,severity:'MEDIUM',code:'PUNCH_SEQUENCE',title:`Sequência de marcação de ${employee.name} aguardando revisão`,description:`Nova marcação ${body.type} após ${previous?.type??'nenhuma marcação anterior'}.`}});
    if(autoLockClockOut){
      await prisma.alert.create({data:{tenantId:request.user.tenantId,employeeId:employee.id,severity:lock.consecutiveAutoLocks>=2?'HIGH':'MEDIUM',code:lock.consecutiveAutoLocks>=2?'POINT_AUTO_LOCK_REPEATED':'POINT_AUTO_LOCK',title:lock.consecutiveAutoLocks>=2?`Jornada de ${employee.name} com trava automática repetida aguardando revisão`:`Jornada de ${employee.name} finalizada após trava automática aguardando revisão`,description:lock.warning??'A tolerância fixa de 10 minutos foi atingida. A selfie de saída foi solicitada e o período de tolerância não será enviado ao banco de horas nem calculado como hora extra.'}});
    }
    if(proof.score<75)await prisma.alert.create({data:{tenantId:request.user.tenantId,employeeId:employee.id,severity:proof.score<50?'HIGH':'MEDIUM',code:'PUNCH_INTEGRITY_INFO',title:`Marcação de ${employee.name} com evidência de baixa confiança`,description:`A marcação #${punch.recordNumber.toString()} recebeu ${proof.score}/100. A jornada regular permanece válida e pode ser conferida em Presença hoje.`}});
    // V83.86: ao finalizar a jornada, o período trabalhado entra imediatamente no banco/demonstrativo,
    // mesmo que a jornada ainda esteja em REVIEW. A aprovação preserva o valor e uma reprovação
    // recalcula o dia removendo as horas trabalhadas e lançando a jornada prevista como déficit.
    const workDate=usage.workDate;
    if(body.type===PunchType.CLOCK_OUT)await persistEmployeeLedger(employee.id,request.user.tenantId,workDate,workDate);
    return reply.code(201).send({id:punch.id,recordNumber:punch.recordNumber.toString(),type:punch.type,occurredAt:punch.occurredAt,receivedAt:punch.receivedAt,integrityHash:punch.integrityHash,previousHash:punch.previousHash,decision:punch.decision,approvalRequired:false,journeyWorkDate:workDate,proof:punch.evidence,duplicate:false,autoLock:autoLockClockOut,warning:autoLockClockOut?lock.warning:null});
  });

  app.get('/punches/me',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const [punches,adjustments]=await Promise.all([prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:request.user.employeeId},include:{evidence:true},orderBy:[{occurredAt:'desc'},{recordNumber:'desc'}],take:150}),prisma.adjustmentRequest.findMany({where:{tenantId:request.user.tenantId,employeeId:request.user.employeeId,status:'APPROVED',targetPunchId:{not:null}},select:{targetPunchId:true,resultingPunchId:true}})]);
    const superseded=new Set(adjustments.map(a=>a.targetPunchId).filter(Boolean));return punches.map(p=>({...p,recordNumber:p.recordNumber.toString(),proof:p.evidence,superseded:superseded.has(p.id)}));
  });

  app.get('/punches/:id/proof',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    const {id}=z.object({id:z.string()}).parse(request.params);const punch=await prisma.punch.findFirst({where:{id,tenantId:request.user.tenantId},include:{evidence:true,employee:{select:{id:true,name:true,employeeNumber:true}}}});if(!punch)return reply.code(404).send({error:'Marcação não encontrada'});if(!(await canSeeEmployee(request.user,punch.employeeId)))return reply.code(403).send({error:'Sem permissão'});return {...punch,recordNumber:punch.recordNumber.toString()};
  });

  app.get('/integrity/verify/:employeeId',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    const {employeeId}=z.object({employeeId:z.string()}).parse(request.params);if(!(await canSeeEmployee(request.user,employeeId)))return reply.code(403).send({error:'Sem permissão'});
    const punches=await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId},include:{evidence:true},orderBy:{recordNumber:'asc'}});let previousHash:string|null=null;const failures:Array<{id:string;reason:string}>=[];
    for(const p of punches){
      if((p.previousHash??null)!==previousHash)failures.push({id:p.id,reason:'previousHash divergente'});const e=p.evidence;if(!e){failures.push({id:p.id,reason:'evidência ausente'});previousHash=p.integrityHash;continue;}
      const hashPayload=buildPunchHashPayload({tenantId:p.tenantId,clientEventId:p.clientEventId,employeeId:p.employeeId,type:p.type,occurredAt:p.occurredAt,timezone:p.timezone,source:p.source,offline:p.offline,previousHash:p.previousHash,evidence:{challengeId:e.challengeId??undefined,latitude:e.latitude??undefined,longitude:e.longitude??undefined,accuracyM:e.accuracyM??undefined,worksiteId:e.worksiteId??undefined,geofenceOk:e.geofenceOk??undefined,deviceFingerprint:e.deviceFingerprint??undefined,deviceTrusted:e.deviceTrusted,selfieStorageKey:e.selfieStorageKey??undefined,selfieCapturedAt:e.selfieCapturedAt??undefined,clientCapturedAt:e.clientCapturedAt??undefined,faceDetected:e.faceDetected,faceCount:e.faceCount,faceQualityScore:e.faceQualityScore??undefined,faceMatchScore:e.faceMatchScore??undefined,faceVerified:e.faceVerified,livenessScore:e.livenessScore??undefined,livenessOk:e.livenessOk??undefined,antiSpoofScore:e.antiSpoofScore??undefined,antiSpoofOk:e.antiSpoofOk??undefined,livenessAction:e.livenessAction??undefined,livenessChallengePassed:e.livenessChallengePassed,platformBiometricVerified:e.platformBiometricVerified,webAuthnCredentialId:e.webAuthnCredentialId??undefined,mockLocationRisk:e.mockLocationRisk,gpsAccuracyOk:e.gpsAccuracyOk??undefined,appIntegrityStatus:e.appIntegrityStatus,locationTelemetry:(e.locationTelemetryJson&&typeof e.locationTelemetryJson==='object'?e.locationTelemetryJson as Record<string,unknown>:undefined),riskSignals:Array.isArray(e.riskSignalsJson)?e.riskSignalsJson as string[]:[],networkAttested:e.networkAttested??undefined,bluetoothAttested:e.bluetoothAttested??undefined,nfcAttested:e.nfcAttested??undefined,dynamicQrAttested:e.dynamicQrAttested??undefined,offlinePayloadHash:e.offlinePayloadHash??undefined,proofScore:e.proofScore,proofLevel:e.proofLevel,reasons:Array.isArray(e.reasonsJson)?e.reasonsJson as string[]:[],missingRequirements:Array.isArray(e.missingRequirementsJson)?e.missingRequirementsJson as string[]:[]}});
      if(sha256(canonicalize(hashPayload))!==p.integrityHash)failures.push({id:p.id,reason:'integrityHash inválido'});previousHash=p.integrityHash;
    }
    return {valid:failures.length===0,records:punches.length,failures,lastHash:previousHash};
  });
}
