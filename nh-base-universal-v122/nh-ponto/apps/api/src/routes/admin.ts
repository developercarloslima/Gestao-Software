import { FastifyInstance } from 'fastify';
import { FacePose, PunchDecision, PunchType, Role } from '@prisma/client';
import bcrypt from 'bcryptjs';
import { randomUUID } from 'node:crypto';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { ADMIN_ROLES, hasRole, MANAGEMENT_ROLES, JOURNEY_REVIEW_ROLES } from '../lib/access.js';
import { getOrCreateRules, persistEmployeeLedger, workDateKeyForInstant } from '../services/time-engine.service.js';
import { getOrCreateSecuritySettings } from '../services/security-settings.service.js';
import { enrollFaceReference } from '../services/face-enrollment.service.js';
import { addDaysKey, isValidClockTime, isValidTimeZone, localDateKey, weekdayKey, zonedDateTimeToUtc } from '../lib/time.js';
import { deleteFaceEmployee, registerFaceEmployeeDataUrl } from '../services/face-api.service.js';
import { encryptJson, readEncryptedImage, removeEncryptedEvidence } from '../services/biometric-storage.service.js';
import { holidaysForYear } from '../services/holiday.service.js';
import { NH_OFFICES, syncNovoHorizonteWorksites } from '../services/worksite.service.js';

const DAY_KEYS = ['SUN','MON','TUE','WED','THU','FRI','SAT'] as const;
const scheduleDays = z.array(z.enum(DAY_KEYS)).min(1);
const shiftDaySchema = z.object({
  enabled: z.boolean(),
  startTime: z.string().refine(isValidClockTime, 'Horário inicial inválido'),
  endTime: z.string().refine(isValidClockTime, 'Horário final inválido'),
  breakMinutes: z.number().int().min(0).max(240)
});
const daySchedulesSchema = z.object({
  SUN: shiftDaySchema,
  MON: shiftDaySchema,
  TUE: shiftDaySchema,
  WED: shiftDaySchema,
  THU: shiftDaySchema,
  FRI: shiftDaySchema,
  SAT: shiftDaySchema
});

type ShiftDayInput = z.infer<typeof shiftDaySchema>;
type DaySchedulesInput = z.infer<typeof daySchedulesSchema>;

function clockMinutes(value:string){
  const [h,m]=value.split(':').map(Number);
  return h*60+m;
}
function scheduledMinutesForDay(day:ShiftDayInput){
  if(!day.enabled)return 0;
  const start=clockMinutes(day.startTime),end=clockMinutes(day.endTime);
  const span=end>start?end-start:(24*60-start)+end;
  if(day.breakMinutes>=span)throw new Error(`Intervalo deve ser menor que a duração da jornada (${day.startTime}–${day.endTime}).`);
  return span-day.breakMinutes;
}
function normalizeDaySchedules(daySchedules:DaySchedulesInput){
  const enabledDays=DAY_KEYS.filter(day=>daySchedules[day].enabled);
  if(!enabledDays.length)throw new Error('Ative pelo menos um dia da jornada.');
  const minutesByDay=Object.fromEntries(DAY_KEYS.map(day=>[day,scheduledMinutesForDay(daySchedules[day])])) as Record<(typeof DAY_KEYS)[number],number>;
  const weeklyMinutes=DAY_KEYS.reduce((total,day)=>total+minutesByDay[day],0);
  if(weeklyMinutes<=0||weeklyMinutes>10080)throw new Error('Jornada semanal inválida.');
  const first=daySchedules[enabledDays[0]];
  return {
    daySchedules,
    scheduleDays: enabledDays,
    startTime:first.startTime,
    endTime:first.endTime,
    breakMinutes:first.breakMinutes,
    dailyMinutes:minutesByDay[enabledDays[0]],
    weeklyMinutes
  };
}

type OperationalDaySchedule={enabled:boolean;startTime:string;endTime:string;breakMinutes:number};
function operationalScheduleFromJson(raw:unknown,key:string):OperationalDaySchedule|null|undefined{
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||!Object.keys(raw as object).length)return undefined;
  const candidate=(raw as Record<string,unknown>)[key];
  if(!candidate||typeof candidate!=='object'||Array.isArray(candidate))return null;
  const day=candidate as Record<string,unknown>;
  if(typeof day.enabled==='boolean'&&typeof day.startTime==='string'&&typeof day.endTime==='string'&&typeof day.breakMinutes==='number')return {enabled:day.enabled,startTime:day.startTime,endTime:day.endTime,breakMinutes:day.breakMinutes};
  return null;
}
function operationalScheduleForDay(employee:any,dateKey:string):OperationalDaySchedule|null{
  const key=weekdayKey(dateKey);
  const personal=operationalScheduleFromJson(employee.workSchedule,key);
  if(personal!==undefined)return personal?.enabled?personal:null;
  const shift=employee.shift;if(!shift||!shift.active)return null;
  const detailed=operationalScheduleFromJson(shift.daySchedules,key);
  if(detailed!==undefined)return detailed?.enabled?detailed:null;
  const days=Array.isArray(shift.scheduleDays)?shift.scheduleDays as string[]:[];
  if(!days.includes(key))return null;
  return {enabled:true,startTime:shift.startTime,endTime:shift.endTime,breakMinutes:shift.breakMinutes};
}
function isOperationalPunch(p:any){
  if(p.decision==='BLOCKED')return false;
  if(String(p.source)==='APPROVED_CLOCK_IN_ADDITION')return p.type===PunchType.CLOCK_IN;
  if(['APPROVED_CORRECTION','APPROVED_ADDITION'].includes(String(p.source)))return false;
  if(p.type===PunchType.CLOCK_OUT&&p.source==='SYSTEM_AUTO_LOCK_NO_SELFIE')return true;
  return Boolean(p.evidence?.selfieStorageKey&&p.evidence?.latitude!=null&&p.evidence?.longitude!=null);
}



async function registerEmployeeKioskFace(input:{tenantId:string;employeeId:string;actorUserId:string;imageDataUrl:string}){
  const employee=await prisma.employee.findFirst({where:{id:input.employeeId,tenantId:input.tenantId},select:{id:true,name:true}});
  if(!employee)throw new Error('Colaborador não encontrado');
  const registered=await registerFaceEmployeeDataUrl({employeeId:employee.id,name:employee.name,imageDataUrl:input.imageDataUrl});
  const now=new Date();
  await prisma.$transaction(async tx=>{
    await tx.faceReference.updateMany({where:{tenantId:input.tenantId,employeeId:employee.id,revokedAt:null},data:{revokedAt:now}});
    await tx.faceReference.create({data:{tenantId:input.tenantId,employeeId:employee.id,pose:FacePose.FRONT,embeddingEncrypted:encryptJson(registered.encoding),embeddingModel:'dlib-face-recognition-128d',faceQualityScore:registered.quality,livenessScore:null,antiSpoofScore:null,createdByUserId:input.actorUserId}});
    await tx.employee.update({where:{id:employee.id},data:{faceEnrollmentStatus:'ACTIVE',faceEnrolledAt:now,faceTemplateVersion:{increment:1},failedBiometricAttempts:0,biometricLockedUntil:null}});
    await tx.auditEvent.create({data:{tenantId:input.tenantId,actorUserId:input.actorUserId,action:'KIOSK_FACE_PORTRAIT_REGISTERED',entityType:'Employee',entityId:employee.id,metadataJson:{employeeName:employee.name,pose:'FRONT',quality:registered.quality,photoStored:false,usage:'TOTEM_RECOGNITION'}}});
  });
  return {ok:true,employeeId:employee.id,name:employee.name,quality:registered.quality,pose:'FRONT',photoStored:false};
}

async function purgeEmployeeFaceBiometrics(input:{tenantId:string;employeeId:string;actorUserId:string}){
  const employee=await prisma.employee.findFirst({where:{id:input.employeeId,tenantId:input.tenantId},select:{id:true,userId:true,name:true}});
  if(!employee)throw new Error('Colaborador não encontrado');
  const [refs,submissions]=await Promise.all([
    prisma.faceReference.findMany({where:{tenantId:input.tenantId,employeeId:input.employeeId},select:{id:true,storageKey:true}}),
    prisma.faceEnrollmentSubmission.findMany({where:{tenantId:input.tenantId,employeeId:input.employeeId},select:{id:true,imagesJson:true}})
  ]);
  for(const ref of refs){if(ref.storageKey)await removeEncryptedEvidence(ref.storageKey).catch(()=>undefined);}
  for(const submission of submissions){
    const images=Array.isArray(submission.imagesJson)?submission.imagesJson as Array<{storageKey?:string}>:[];
    for(const image of images){if(image?.storageKey)await removeEncryptedEvidence(image.storageKey).catch(()=>undefined);}
  }
  await prisma.$transaction(async tx=>{
    await tx.faceEnrollmentSubmission.deleteMany({where:{tenantId:input.tenantId,employeeId:input.employeeId}});
    await tx.faceReference.deleteMany({where:{tenantId:input.tenantId,employeeId:input.employeeId}});
    await tx.employee.update({where:{id:input.employeeId},data:{faceEnrollmentStatus:'NOT_ENROLLED',faceEnrolledAt:null,faceTemplateVersion:{increment:1},failedBiometricAttempts:0,biometricLockedUntil:null}});
    await tx.auditEvent.create({data:{tenantId:input.tenantId,actorUserId:input.actorUserId,action:'ADMIN_FACE_BIOMETRIC_DELETED',entityType:'Employee',entityId:input.employeeId,metadataJson:{employeeName:employee.name,deletedReferences:refs.length,deletedSubmissions:submissions.length,photoFilesRemoved:true}}});
  });
  const remote=await deleteFaceEmployee(input.employeeId).then(()=>true).catch(()=>false);
  return {ok:true,deletedReferences:refs.length,deletedSubmissions:submissions.length,faceApiDeleted:remote};
}

async function purgeEmployeeWebAuthn(input:{tenantId:string;employeeId:string;actorUserId:string;credentialId?:string}){
  const employee=await prisma.employee.findFirst({where:{id:input.employeeId,tenantId:input.tenantId},select:{id:true,userId:true,name:true,user:{select:{role:true}}}});
  if(!employee)throw new Error('Colaborador não encontrado');
  if(!employee.userId)throw new Error('Colaborador sem conta de acesso vinculada');
  const where:any={tenantId:input.tenantId,userId:employee.userId};
  if(input.credentialId)where.id=input.credentialId;
  const credentials=await prisma.webAuthnCredential.findMany({where,select:{id:true,label:true,deviceType:true,backedUp:true}});
  if(input.credentialId&&!credentials.length)throw new Error('Biometria/passkey não encontrada para este colaborador');
  await prisma.$transaction(async tx=>{
    if(input.credentialId)await tx.webAuthnCredential.deleteMany({where:{id:input.credentialId,tenantId:input.tenantId,userId:employee.userId!}});
    else await tx.webAuthnCredential.deleteMany({where:{tenantId:input.tenantId,userId:employee.userId!}});
    await tx.webAuthnChallenge.deleteMany({where:{tenantId:input.tenantId,userId:employee.userId!}});
    const remaining=await tx.webAuthnCredential.count({where:{tenantId:input.tenantId,userId:employee.userId!}});
    if(remaining===0&&employee.user?.role===Role.ADMIN)await tx.user.update({where:{id:employee.userId!},data:{onboardingCompletedAt:null}});
    await tx.auditEvent.create({data:{tenantId:input.tenantId,actorUserId:input.actorUserId,action:input.credentialId?'ADMIN_WEBAUTHN_CREDENTIAL_DELETED':'ADMIN_WEBAUTHN_ALL_DELETED',entityType:'Employee',entityId:input.employeeId,metadataJson:{employeeName:employee.name,deletedCount:credentials.length,credentialIds:credentials.map(x=>x.id)}}});
  });
  const remaining=await prisma.webAuthnCredential.count({where:{tenantId:input.tenantId,userId:employee.userId}});
  return {ok:true,deleted:credentials.length,remaining};
}


async function automaticSupervisorForWorksite(tenantId:string,worksiteId:string,excludeEmployeeId?:string){
  const worksite=await prisma.worksite.findFirst({where:{id:worksiteId,tenantId,active:true,archivedAt:null},select:{id:true,name:true}});
  if(!worksite)return {id:null as string|null,name:null as string|null};
  const supervisor=await prisma.employee.findFirst({
    where:{tenantId,worksiteId,active:true,archivedAt:null,...(excludeEmployeeId?{id:{not:excludeEmployeeId}}:{}),user:{is:{role:Role.SUPERVISOR,active:true}}},
    select:{id:true,name:true},orderBy:{name:'asc'}
  });
  return {id:supervisor?.id??null,name:supervisor?.name??null};
}

async function supervisorForWorksite(tenantId:string,worksiteId:string,supervisorId?:string|null,excludeEmployeeId?:string){
  if(!supervisorId)return automaticSupervisorForWorksite(tenantId,worksiteId,excludeEmployeeId);
  const supervisor=await prisma.employee.findFirst({
    where:{id:supervisorId,tenantId,worksiteId,active:true,archivedAt:null,...(excludeEmployeeId?{id:{not:excludeEmployeeId}}:{}),user:{is:{role:Role.SUPERVISOR,active:true}}},
    select:{id:true,name:true}
  });
  return supervisor?{id:supervisor.id,name:supervisor.name}:{id:null as string|null,name:null as string|null};
}


function friendlyBlockedAttemptReason(attempt:any){
  const missing=Array.isArray(attempt?.metadataJson?.missingRequirements)?attempt.metadataJson.missingRequirements.map((x:any)=>String(x).toUpperCase()):[];
  const raw=String(attempt?.reason??'').toUpperCase();
  const has=(term:string)=>missing.some((x:string)=>x.includes(term))||raw.includes(term);
  if(has('SELFIE'))return 'Não foi possível confirmar a selfie desta marcação. Oriente o colaborador a refazer a foto com o rosto bem visível e boa iluminação.';
  if(has('GPS')||has('LOCATION')||has('GEOFENCE'))return 'A localização não pôde ser confirmada nesta tentativa. O colaborador pode ativar a localização do aparelho e tentar novamente.';
  if(has('DEVICE')||has('TRUST'))return 'O aparelho usado não passou por uma das validações de segurança. Confira o dispositivo e tente a marcação novamente.';
  if(has('NETWORK')||has('QR')||has('BLUETOOTH')||has('NFC'))return 'Uma das confirmações de presença do local não ficou disponível nesta tentativa. Verifique a conexão e os recursos do local antes de tentar novamente.';
  if(has('FACE')||has('LIVENESS')||has('SPOOF'))return 'A confirmação de identidade não ficou nítida o suficiente. O colaborador pode refazer a marcação olhando de frente para a câmera.';
  return 'A marcação não pôde ser concluída porque uma validação de segurança ficou incompleta. Confira os dados da tentativa e, se necessário, peça ao colaborador para tentar novamente.';
}

async function pendingJourneyReview(tenantId:string,clockOutId:string,includeAdminControl=false){
  const clockOut=await prisma.punch.findFirst({
    where:{id:clockOutId,tenantId,decision:includeAdminControl?{in:[PunchDecision.REVIEW,PunchDecision.APPROVED]}:PunchDecision.REVIEW,type:PunchType.CLOCK_OUT},
    include:{employee:{include:{shift:true,tenant:true,worksite:true,user:{select:{role:true}}}}}
  });
  if(!clockOut)return null;
  const employee=clockOut.employee;
  const controlOnly=clockOut.decision===PunchDecision.APPROVED&&employee.user?.role===Role.ADMIN;
  if(clockOut.decision===PunchDecision.APPROVED&&!controlOnly)return null;
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(clockOut.occurredAt,employee.shift,timeZone);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone);
  const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const rows=await prisma.punch.findMany({where:{tenantId,employeeId:employee.id,decision:{not:'BLOCKED'},occurredAt:{gte:start,lt:end}},include:{evidence:true},orderBy:[{occurredAt:'asc'},{recordNumber:'asc'}]});
  const punches=rows.filter(p=>workDateKeyForInstant(p.occurredAt,employee.shift,timeZone)===workDate);
  if(!punches.some(p=>p.type===PunchType.CLOCK_IN)||!punches.some(p=>p.type===PunchType.CLOCK_OUT))return null;
  const worksiteIds=[...new Set([employee.worksiteId,...punches.map(p=>p.evidence?.worksiteId)].filter((x):x is string=>Boolean(x)))];
  const worksites=worksiteIds.length?await prisma.worksite.findMany({where:{tenantId,id:{in:worksiteIds}},select:{id:true,name:true,address:true,city:true,stateCode:true,latitude:true,longitude:true}}):[];
  const worksiteMap=new Map(worksites.map(w=>[w.id,w]));
  const defaultWorksite=employee.worksiteId?worksiteMap.get(employee.worksiteId)??null:null;
  const overtimeGrants=await prisma.overtimeGrant.findMany({where:{tenantId,employeeId:employee.id,workDate:new Date(`${workDate}T12:00:00.000Z`)},include:{actor:{select:{displayName:true,email:true}}},orderBy:{createdAt:'asc'}});
  return {
    id:`${employee.id}:${workDate}`,reviewPunchId:clockOut.id,workDate,status:controlOnly?'APPROVED_CONTROL':'PENDING_APPROVAL',controlOnly,timeZone,
    employee:{id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,jobTitle:employee.jobTitle,worksite:defaultWorksite},
    startedAt:punches.find(p=>p.type===PunchType.CLOCK_IN)?.occurredAt??null,finishedAt:[...punches].reverse().find(p=>p.type===PunchType.CLOCK_OUT)?.occurredAt??clockOut.occurredAt,
    punchCount:punches.length,overtime:{totalMinutes:overtimeGrants.reduce((sum,g)=>sum+g.minutes,0),grants:overtimeGrants.map(g=>({id:g.id,minutes:g.minutes,createdAt:g.createdAt,grantedBy:g.actor.displayName??g.actor.email,reason:g.reason}))},
    punches:punches.map(p=>({...p,recordNumber:p.recordNumber.toString(),worksite:(p.evidence?.worksiteId?worksiteMap.get(p.evidence.worksiteId):defaultWorksite)??null}))
  };
}

export async function adminRoutes(app: FastifyInstance) {
  app.get('/admin/company', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const [tenant, rules] = await Promise.all([
      prisma.tenant.findUniqueOrThrow({ where: { id: request.user.tenantId } }),
      getOrCreateRules(request.user.tenantId)
    ]);
    return {
      tenant,
      rules: {
        ...rules,
        hoursMode: rules.bankHoursEnabled && rules.overtimeToBank ? 'BANK_HOURS' : 'OVERTIME'
      }
    };
  });

  app.patch('/admin/company', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const body = z.object({ name: z.string().min(2).max(120).optional(), legalName: z.string().max(180).nullable().optional(), document: z.string().max(30).nullable().optional(), timezone: z.string().min(3).max(80).refine(isValidTimeZone, 'Fuso horário inválido').optional() }).parse(request.body);
    const before = await prisma.tenant.findUniqueOrThrow({ where: { id: request.user.tenantId } });
    const tenant = await prisma.tenant.update({ where: { id: request.user.tenantId }, data: body });
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'COMPANY_UPDATED', entityType: 'Tenant', entityId: tenant.id, beforeJson: before as any, afterJson: tenant as any } });
    return tenant;
  });

  app.patch('/admin/rules', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const body = z.object({
      hoursMode: z.enum(['BANK_HOURS','OVERTIME']).optional(),
      bankHoursEnabled: z.boolean().optional(), overtimeToBank: z.boolean().optional(), deficitToBank: z.boolean().optional(), overtime50Percent: z.number().int().min(0).max(300).optional(), overtime100Percent: z.number().int().min(0).max(300).optional(),
      sundayOvertime100: z.boolean().optional(), nightStart: z.string().refine(isValidClockTime, 'Horário noturno inicial inválido').optional(), nightEnd: z.string().refine(isValidClockTime, 'Horário noturno final inválido').optional(),
      nightAdditionalPercent: z.number().int().min(0).max(100).optional(), minBreakMinutes: z.number().int().min(0).max(240).optional(), maxDailyMinutes: z.number().int().min(60).max(1440).optional(),
      defaultToleranceMin: z.number().int().min(0).max(60).optional(), monthlyHoursDivisor: z.number().int().min(1).max(400).optional()
    }).parse(request.body);
    const before = await getOrCreateRules(request.user.tenantId);
    const { hoursMode, ...rawRules } = body;
    const modeRules = hoursMode === 'BANK_HOURS'
      ? { bankHoursEnabled: true, overtimeToBank: true, deficitToBank: true }
      : hoursMode === 'OVERTIME'
        ? { bankHoursEnabled: false, overtimeToBank: false, deficitToBank: false }
        : {};
    const rules = await prisma.timeRuleSettings.update({ where: { tenantId: request.user.tenantId }, data: { ...rawRules, ...modeRules, defaultToleranceMin:10 } });
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'TIME_RULES_UPDATED', entityType: 'TimeRuleSettings', entityId: rules.id, beforeJson: before as any, afterJson: rules as any } });
    return rules;
  });

  app.get('/admin/departments', { preHandler: [app.authenticate] }, async (request, reply) => { if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' }); return prisma.department.findMany({ where: { tenantId: request.user.tenantId }, orderBy: { name: 'asc' } }); });
  app.post('/admin/departments', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const body = z.object({ name: z.string().min(2).max(100), code: z.string().max(30).optional() }).parse(request.body);
    const row = await prisma.department.create({ data: { tenantId: request.user.tenantId, ...body } });
    return reply.code(201).send(row);
  });
  app.patch('/admin/departments/:id', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const body = z.object({ name: z.string().min(2).max(100).optional(), code: z.string().max(30).nullable().optional(), active: z.boolean().optional() }).parse(request.body);
    const result = await prisma.department.updateMany({ where: { id, tenantId: request.user.tenantId }, data: body });
    if (!result.count) return reply.code(404).send({ error: 'Setor não encontrado' });
    return prisma.department.findUnique({ where: { id } });
  });

  app.get('/admin/worksites', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    let rows=await prisma.worksite.findMany({ where: { tenantId: request.user.tenantId, archivedAt:null }, orderBy: { name: 'asc' } });
    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{name:true}});
    if(tenant&&/novo\s*horizonte/i.test(tenant.name)){
      const officialKeys=new Set(NH_OFFICES.map(x=>x.sourceKey));
      const archivedOfficial=await prisma.worksite.findMany({where:{tenantId:request.user.tenantId,archivedAt:{not:null},sourceKey:{in:[...officialKeys]}},select:{sourceKey:true}});
      const archivedKeys=new Set(archivedOfficial.map(x=>x.sourceKey).filter(Boolean));
      const existingKeys=new Set(rows.map(x=>x.sourceKey).filter(Boolean));
      // V83.43: a sincronização automática cria apenas unidades oficiais ausentes.
      // Alterações feitas pelo Super Adm não são revertidas ao simplesmente abrir a tela.
      const needsSync=[...officialKeys].some(key=>!existingKeys.has(key)&&!archivedKeys.has(key));
      if(needsSync){
        await syncNovoHorizonteWorksites(request.user.tenantId);
        rows=await prisma.worksite.findMany({where:{tenantId:request.user.tenantId,archivedAt:null},orderBy:{name:'asc'}});
      }
    }
    const supervisors=await prisma.employee.findMany({where:{tenantId:request.user.tenantId,archivedAt:null,active:true,worksiteId:{not:null},user:{is:{role:Role.SUPERVISOR,active:true}}},select:{id:true,name:true,worksiteId:true},orderBy:{name:'asc'}});
    const byWorksite=new Map<string,Array<{id:string,name:string}>>();
    for(const supervisor of supervisors){if(!supervisor.worksiteId)continue;const list=byWorksite.get(supervisor.worksiteId)??[];list.push({id:supervisor.id,name:supervisor.name});byWorksite.set(supervisor.worksiteId,list);}
    return rows.map(row=>({...row,supervisors:byWorksite.get(row.id)??[]}));
  });
  app.post('/admin/worksites', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const body = z.object({
      name:z.string().min(2).max(120),address:z.string().trim().min(5).max(300),city:z.string().trim().min(2).max(100),stateCode:z.string().trim().length(2).transform(v=>v.toUpperCase()),
      latitude:z.number().min(-90).max(90).nullable().optional(),longitude:z.number().min(-180).max(180).nullable().optional(),
      bluetoothNamePrefix:z.string().max(80).optional(),nfcTagId:z.string().max(120).optional(),networkGatewayId:z.string().max(120).optional()
    }).parse(request.body);
    const archived=await prisma.worksite.findFirst({where:{tenantId:request.user.tenantId,name:body.name,archivedAt:{not:null}}});
    if(archived&&request.user.role!==Role.SUPER_ADMIN)return reply.code(409).send({error:'Esta unidade foi excluída pelo Super Adm. Somente o Super Adm pode restaurá-la.'});
    const data={tenantId:request.user.tenantId,name:body.name,address:body.address,city:body.city,stateCode:body.stateCode,latitude:body.latitude??null,longitude:body.longitude??null,bluetoothNamePrefix:body.bluetoothNamePrefix,nfcTagId:body.nfcTagId,networkGatewayId:body.networkGatewayId,active:true,archivedAt:null};
    const row=archived?await prisma.worksite.update({where:{id:archived.id},data}):await prisma.worksite.create({data});
    return reply.code(201).send(row);
  });

  app.patch('/admin/worksites/:id', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const body=z.object({
      name:z.string().min(2).max(120).optional(),address:z.string().trim().min(5).max(300).nullable().optional(),city:z.string().trim().min(2).max(100).nullable().optional(),stateCode:z.string().trim().length(2).transform(v=>v.toUpperCase()).nullable().optional(),
      latitude:z.number().min(-90).max(90).nullable().optional(),longitude:z.number().min(-180).max(180).nullable().optional(),
      bluetoothNamePrefix:z.string().max(80).nullable().optional(),nfcTagId:z.string().max(120).nullable().optional(),networkGatewayId:z.string().max(120).nullable().optional(),active:z.boolean().optional()
    }).parse(request.body);
    const current=await prisma.worksite.findFirst({where:{id,tenantId:request.user.tenantId,archivedAt:null}});if(!current)return reply.code(404).send({error:'Local não encontrado'});

    // V83.55: latitude/longitude da unidade são apenas referência visual para comparação manual com o GPS das selfies.
    // Não existe raio, geocerca ou bloqueio automático por distância.
    if(request.user.role!==Role.SUPER_ADMIN){
      const forbidden=Object.keys(body).filter(key=>key!=='active');
      if(forbidden.length)return reply.code(403).send({error:'Somente o Super Adm pode editar os dados cadastrais da unidade.'});
      if(body.active===undefined)return current;
      return prisma.worksite.update({where:{id},data:{active:body.active}});
    }

    const updated=await prisma.worksite.update({where:{id},data:body});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'WORKSITE_UPDATED_BY_SUPER_ADMIN',entityType:'Worksite',entityId:id,beforeJson:{name:current.name,address:current.address,city:current.city,stateCode:current.stateCode,latitude:current.latitude,longitude:current.longitude,active:current.active} as any,afterJson:{name:updated.name,address:updated.address,city:updated.city,stateCode:updated.stateCode,latitude:updated.latitude,longitude:updated.longitude,active:updated.active} as any}});
    return updated;
  });
  const canDeleteWorksite=(role:Role|string)=>role===Role.SUPER_ADMIN||role===Role.ADMIN;
  const worksiteDeletionImpact=async(tenantId:string,id:string)=>{
    const row=await prisma.worksite.findFirst({where:{id,tenantId,archivedAt:null},select:{id:true,name:true,address:true,city:true,stateCode:true,active:true}});
    if(!row)return null;
    const [linkedEmployees,destinations]=await Promise.all([
      prisma.employee.findMany({where:{tenantId,worksiteId:id,archivedAt:null,active:true},select:{id:true,name:true,employeeNumber:true,userId:true,user:{select:{role:true,email:true}}},orderBy:{name:'asc'}}),
      prisma.worksite.findMany({where:{tenantId,id:{not:id},archivedAt:null,active:true},select:{id:true,name:true,address:true,city:true,stateCode:true},orderBy:{name:'asc'}})
    ]);
    return {worksite:row,linkedEmployees,destinations};
  };

  app.get('/admin/worksites/:id/deletion-impact', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(!canDeleteWorksite(request.user.role))return reply.code(403).send({error:'Somente Administrador ou Super Adm pode excluir unidades.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const impact=await worksiteDeletionImpact(request.user.tenantId,id);
    if(!impact)return reply.code(404).send({error:'Unidade não encontrada.'});
    return {
      ...impact,
      linkedEmployees:impact.linkedEmployees.map(e=>({...e,canArchive:e.id!==request.user.employeeId&&!([Role.SUPER_ADMIN,Role.ADMIN,Role.HR] as Role[]).includes(e.user?.role as Role)}))
    };
  });

  app.post('/admin/worksites/:id/resolve-delete', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(!canDeleteWorksite(request.user.role))return reply.code(403).send({error:'Somente Administrador ou Super Adm pode excluir unidades.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({
      deleteWorksite:z.boolean().default(true),
      actions:z.array(z.object({employeeId:z.string(),action:z.enum(['REALLOCATE','ARCHIVE']),targetWorksiteId:z.string().optional()})).default([])
    }).parse(request.body??{});
    const impact=await worksiteDeletionImpact(request.user.tenantId,id);
    if(!impact)return reply.code(404).send({error:'Unidade não encontrada.'});
    const actionMap=new Map(body.actions.map(a=>[a.employeeId,a]));
    const missing=impact.linkedEmployees.filter(e=>!actionMap.has(e.id));
    if(missing.length)return reply.code(400).send({error:`Defina o destino de todos os colaboradores vinculados: ${missing.map(e=>e.name).join(', ')}.`});
    const destinationIds=new Set(impact.destinations.map(w=>w.id));
    const supervisors=new Map<string,string|null>();
    for(const action of body.actions){
      if(action.action==='REALLOCATE'){
        if(!action.targetWorksiteId||!destinationIds.has(action.targetWorksiteId))return reply.code(400).send({error:'Selecione uma unidade de destino ativa para cada colaborador realocado.'});
        const supervisor=await automaticSupervisorForWorksite(request.user.tenantId,action.targetWorksiteId,action.employeeId);supervisors.set(action.employeeId,supervisor.id);
      } else if(!body.deleteWorksite){
        return reply.code(400).send({error:'O botão “Salvar todas as realocações” aceita somente realocação. Para excluir colaboradores junto com a unidade, use “Salvar e excluir unidade”.'});
      }
    }
    const archivedEmployeeIds:string[]=[];
    const now=new Date();
    await prisma.$transaction(async tx=>{
      for(const employee of impact.linkedEmployees){
        const action=actionMap.get(employee.id)!;
        if(action.action==='REALLOCATE'){
          await tx.employee.update({where:{id:employee.id},data:{worksiteId:action.targetWorksiteId!,supervisorId:supervisors.get(employee.id)??null}});
          await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'EMPLOYEE_REALLOCATED_FOR_WORKSITE_DELETE',entityType:'Employee',entityId:employee.id,beforeJson:{worksiteId:id,worksiteName:impact.worksite.name} as any,afterJson:{worksiteId:action.targetWorksiteId} as any}});
        }else{
          if(employee.id===request.user.employeeId)throw Object.assign(new Error('Você não pode excluir o seu próprio cadastro. Realoque este colaborador para outra unidade.'),{statusCode:400});
          if(([Role.SUPER_ADMIN,Role.ADMIN,Role.HR] as Role[]).includes(employee.user?.role as Role))throw Object.assign(new Error(`${employee.name} possui uma conta administrativa e precisa ser realocado, não excluído.`),{statusCode:400});
          if(employee.userId){
            await tx.webAuthnCredential.deleteMany({where:{tenantId:request.user.tenantId,userId:employee.userId}});
            await tx.webAuthnChallenge.deleteMany({where:{tenantId:request.user.tenantId,userId:employee.userId}});
            await tx.user.update({where:{id:employee.userId},data:{active:false}});
          }
          await tx.employee.updateMany({where:{tenantId:request.user.tenantId,supervisorId:employee.id,archivedAt:null},data:{supervisorId:null}});
          await tx.employee.update({where:{id:employee.id},data:{active:false,archivedAt:now,biometricLockedUntil:null,failedBiometricAttempts:0}});
          await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'EMPLOYEE_ARCHIVED_DURING_WORKSITE_DELETE',entityType:'Employee',entityId:employee.id,beforeJson:{name:employee.name,employeeNumber:employee.employeeNumber,worksiteId:id} as any,afterJson:{active:false,archivedAt:now.toISOString(),historyPreserved:true} as any}});
          archivedEmployeeIds.push(employee.id);
        }
      }
      if(body.deleteWorksite){
        await tx.worksite.update({where:{id},data:{active:false,archivedAt:now}});
        await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'WORKSITE_ARCHIVED_AFTER_REALLOCATION',entityType:'Worksite',entityId:id,beforeJson:{name:impact.worksite.name,address:impact.worksite.address,city:impact.worksite.city,stateCode:impact.worksite.stateCode,active:impact.worksite.active,linkedEmployees:impact.linkedEmployees.length} as any,afterJson:{active:false,archivedAt:now.toISOString()} as any}});
      }
    });
    for(const employeeId of archivedEmployeeIds){
      await deleteFaceEmployee(employeeId).catch(()=>undefined);
      await prisma.faceReference.updateMany({where:{tenantId:request.user.tenantId,employeeId,revokedAt:null},data:{revokedAt:now}}).catch(()=>undefined);
    }
    return {ok:true,id,deleted:body.deleteWorksite,reallocated:body.actions.filter(a=>a.action==='REALLOCATE').length,archivedEmployees:archivedEmployeeIds.length};
  });

  app.delete('/admin/worksites/:id', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(!canDeleteWorksite(request.user.role))return reply.code(403).send({error:'Somente Administrador ou Super Adm pode excluir unidades.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const impact=await worksiteDeletionImpact(request.user.tenantId,id);
    if(!impact)return reply.code(404).send({error:'Unidade não encontrada.'});
    if(impact.linkedEmployees.length)return reply.code(409).send({error:`Esta unidade possui ${impact.linkedEmployees.length} colaborador(es) vinculado(s). Realoque ou exclua esses colaboradores no assistente de exclusão.`,requiresResolution:true,linkedEmployees:impact.linkedEmployees.length});
    const now=new Date();
    await prisma.worksite.update({where:{id},data:{active:false,archivedAt:now}});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'WORKSITE_ARCHIVED_BY_ADMIN_OR_SUPER_ADMIN',entityType:'Worksite',entityId:id,beforeJson:{name:impact.worksite.name,address:impact.worksite.address,city:impact.worksite.city,stateCode:impact.worksite.stateCode,active:impact.worksite.active} as any,afterJson:{active:false,archivedAt:now.toISOString()} as any}});
    return {ok:true,id};
  });

  app.post('/admin/worksites/sync-nh-offices', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(process.env.GS_TENANT_MODE==='true')return reply.code(404).send({error:'Integração exclusiva do ambiente legado indisponível nesta empresa.'});
    if(!hasRole(request.user.role,ADMIN_ROLES))return reply.code(403).send({error:'Sem permissão'});
    const result=await syncNovoHorizonteWorksites(request.user.tenantId);
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'NH_OFFICES_SYNC',entityType:'Tenant',entityId:request.user.tenantId,metadataJson:result as any}});
    return result;
  });




  app.get('/admin/calendar',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!hasRole(request.user.role,MANAGEMENT_ROLES))return reply.code(403).send({error:'Sem permissão'});
    const now=new Date();const query=z.object({year:z.coerce.number().int().min(2020).max(2100).default(now.getFullYear()),month:z.coerce.number().int().min(1).max(12).default(now.getMonth()+1)}).parse(request.query??{});
    const [employees,worksites]=await Promise.all([
      prisma.employee.findMany({where:{tenantId:request.user.tenantId,active:true,archivedAt:null,birthDate:{not:null}},select:{id:true,name:true,birthDate:true,employeeNumber:true,department:{select:{name:true}},worksite:{select:{name:true}}},orderBy:{name:'asc'}}),
      prisma.worksite.findMany({where:{tenantId:request.user.tenantId,active:true,stateCode:{not:null}},select:{stateCode:true}})
    ]);
    const birthdays=employees.filter(e=>e.birthDate&&e.birthDate.getUTCMonth()+1===query.month).map(e=>({id:e.id,name:e.name,employeeNumber:e.employeeNumber,day:e.birthDate!.getUTCDate(),month:e.birthDate!.getUTCMonth()+1,department:e.department?.name??null,worksite:e.worksite?.name??null})).sort((a,b)=>a.day-b.day||a.name.localeCompare(b.name));
    const states:string[]=[...new Set((worksites as Array<{stateCode:string|null}>).map(w=>w.stateCode).filter((x):x is string=>Boolean(x)))];
    const holidays=holidaysForYear(query.year,states).filter(h=>Number(h.date.slice(5,7))===query.month);
    const sundays:number[]=[];const daysInMonth=new Date(Date.UTC(query.year,query.month,0)).getUTCDate();for(let day=1;day<=daysInMonth;day++)if(new Date(Date.UTC(query.year,query.month-1,day)).getUTCDay()===0)sundays.push(day);
    return {year:query.year,month:query.month,birthdays,holidays,sundays,closedPolicy:{sundays:false,sundaysFollowSchedule:true,nationalHolidays:true,stateHolidays:true}};
  });

  app.get('/admin/shifts', { preHandler: [app.authenticate] }, async (request, reply) => { if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' }); return prisma.shift.findMany({ where: { tenantId: request.user.tenantId }, include:{_count:{select:{employees:true}}}, orderBy: { name: 'asc' } }); });
  app.post('/admin/shifts', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN)) return reply.code(403).send({ error: 'Somente Administrador e Super Adm podem criar modelos de jornada.' });
    const body = z.object({
      name: z.string().min(2).max(120),
      timezone: z.string().min(3).max(80).refine(isValidTimeZone, 'Fuso horário inválido').default('America/Maceio'),
      daySchedules: daySchedulesSchema,
      overtimeToleranceMin: z.number().int().min(0).max(60).optional()
    }).parse(request.body);
    let normalized;
    try{ normalized=normalizeDaySchedules(body.daySchedules); }
    catch(error){ return reply.code(400).send({error:error instanceof Error?error.message:'Jornada inválida'}); }
    const row = await prisma.shift.create({ data: { tenantId: request.user.tenantId, name:body.name, timezone:body.timezone, overtimeToleranceMin:10, ...normalized } });
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'SHIFT_CREATED',entityType:'Shift',entityId:row.id,afterJson:{name:row.name,weeklyMinutes:row.weeklyMinutes,daySchedules:row.daySchedules} as any}});
    return reply.code(201).send(row);
  });
  app.patch('/admin/shifts/:id', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN)) return reply.code(403).send({ error: 'Somente Administrador e Super Adm podem editar modelos de jornada.' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const body = z.object({
      name: z.string().min(2).max(120).optional(),
      timezone: z.string().min(3).max(80).refine(isValidTimeZone, 'Fuso horário inválido').optional(),
      daySchedules: daySchedulesSchema.optional(),
      overtimeToleranceMin: z.number().int().min(0).max(60).optional(),
      active: z.boolean().optional()
    }).parse(request.body);
    const before=await prisma.shift.findFirst({where:{id,tenantId:request.user.tenantId}});
    if(!before)return reply.code(404).send({error:'Escala não encontrada'});
    let normalized={};
    if(body.daySchedules){
      try{ normalized=normalizeDaySchedules(body.daySchedules); }
      catch(error){ return reply.code(400).send({error:error instanceof Error?error.message:'Jornada inválida'}); }
    }
    const {daySchedules:_daySchedules,...simple}=body;
    const updated=await prisma.shift.update({where:{id},data:{...simple,...normalized,overtimeToleranceMin:10}});
    if(body.daySchedules)await prisma.employee.updateMany({where:{tenantId:request.user.tenantId,shiftId:id,archivedAt:null},data:{workSchedule:updated.daySchedules as any}});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'SHIFT_UPDATED',entityType:'Shift',entityId:id,beforeJson:{name:before.name,weeklyMinutes:before.weeklyMinutes,daySchedules:before.daySchedules} as any,afterJson:{name:updated.name,weeklyMinutes:updated.weeklyMinutes,daySchedules:updated.daySchedules,linkedEmployeesSynchronized:Boolean(body.daySchedules)} as any}});
    return updated;
  });

  app.delete('/admin/shifts/:id', { preHandler:[app.authenticate] }, async (request:any,reply)=>{
    if (!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN)) return reply.code(403).send({error:'Somente Administrador e Super Adm podem excluir modelos de jornada.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const shift=await prisma.shift.findFirst({where:{id,tenantId:request.user.tenantId},include:{_count:{select:{employees:true}}}});if(!shift)return reply.code(404).send({error:'Modelo de jornada não encontrado.'});
    await prisma.shift.delete({where:{id}});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'SHIFT_DELETED',entityType:'Shift',entityId:id,beforeJson:{name:shift.name,linkedEmployees:shift._count.employees}}});
    return {ok:true,id,name:shift.name,linkedEmployeesMovedToIndividualSchedule:shift._count.employees};
  });


  app.post('/admin/shifts/:id/assign', { preHandler:[app.authenticate] }, async(request:any,reply)=>{
    if (!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN)) return reply.code(403).send({error:'Somente Administrador e Super Adm podem vincular jornadas/escala aos colaboradores.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({employeeIds:z.array(z.string().min(1)).min(1).max(200)}).parse(request.body);
    const shift=await prisma.shift.findFirst({where:{id,tenantId:request.user.tenantId,active:true}});
    if(!shift)return reply.code(404).send({error:'Jornada/escala não encontrada ou inativa.'});
    const employees=await prisma.employee.findMany({where:{tenantId:request.user.tenantId,id:{in:body.employeeIds},active:true,archivedAt:null},select:{id:true,name:true,shiftId:true}});
    if(employees.length!==new Set(body.employeeIds).size)return reply.code(400).send({error:'Um ou mais colaboradores não pertencem à empresa, estão inativos ou não foram encontrados.'});
    await prisma.$transaction(async tx=>{
      await tx.employee.updateMany({where:{tenantId:request.user.tenantId,id:{in:employees.map(e=>e.id)}},data:{shiftId:shift.id,workSchedule:shift.daySchedules as any}});
      for(const employee of employees)await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'SHIFT_ASSIGNED_TO_EMPLOYEE',entityType:'Employee',entityId:employee.id,beforeJson:{shiftId:employee.shiftId} as any,afterJson:{shiftId:shift.id,shiftName:shift.name,daySchedules:shift.daySchedules} as any}});
    });
    return {ok:true,shiftId:shift.id,shiftName:shift.name,assigned:employees.length,employeeIds:employees.map(e=>e.id)};
  });

  app.get('/admin/employees', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const where: any = { tenantId: request.user.tenantId, archivedAt: null };
    if (request.user.role === Role.SUPERVISOR && request.user.employeeId) where.OR = [{ supervisorId: request.user.employeeId }, { id: request.user.employeeId }];
    const rows = await prisma.employee.findMany({ where, include: { user: { select: { email: true, contactEmail:true, role: true, active: true, mustChangePassword:true, onboardingCompletedAt:true, _count:{select:{webAuthnCredentials:true}} } }, department: true, shift: true, worksite: true, supervisor: { select: { id: true, name: true } } }, orderBy: { name: 'asc' } });
    const operationalRows=rows.filter(row=>![Role.HR,Role.SUPER_ADMIN].some(role=>row.user?.role===role));
    if (request.user.role === Role.SUPERVISOR) return operationalRows.map(({ salaryCents: _salary, ...row }) => row);
    return operationalRows;
  });

  app.get('/admin/supervisors', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN))return reply.code(403).send({error:'Somente Administrador e Super Adm podem gerenciar supervisores.'});
    const rows=await prisma.employee.findMany({
      where:{tenantId:request.user.tenantId,archivedAt:null,user:{is:{role:Role.SUPERVISOR}}},
      include:{user:{select:{email:true,contactEmail:true,active:true,mustChangePassword:true,onboardingCompletedAt:true}},worksite:true,department:true,shift:true,_count:{select:{team:true}}},
      orderBy:{name:'asc'}
    });
    return rows;
  });

  app.post('/admin/employees', { preHandler: [app.authenticate] }, async (request:any, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const schema = z.object({
      name: z.string().trim().min(2).max(150),
      employeeNumber: z.union([z.string().trim().max(40), z.literal('')]).optional(),
      username: z.string().trim().min(2).max(120),
      password: z.string().min(6).max(128),
      email: z.preprocess((value)=>value===null||value===undefined||value===''?undefined:value,z.string().trim().email().optional()),
      role: z.enum(['SUPERVISOR','EMPLOYEE','AUDITOR']).transform(v=>v as Role).default(Role.EMPLOYEE),
      cpfMasked: z.string().max(30).nullable().optional(), jobTitle: z.string().max(120).nullable().optional(), admissionDate: z.string().datetime().nullable().optional(), birthDate:z.string().datetime().nullable().optional(),
      salaryCents: z.number().int().min(0).optional().default(0),
      workSchedule: daySchedulesSchema,
      shiftId: z.string().nullable().optional(),
      departmentId: z.string().min(1),
      worksiteId: z.string().min(1),
      supervisorId: z.string().nullable().optional()
    });
    const parsed=schema.safeParse(request.body);
    if(!parsed.success){
      const first=parsed.error.issues[0];
      return reply.code(400).send({error:first?.message||'Revise os campos do colaborador.',code:'EMPLOYEE_VALIDATION_ERROR',field:first?.path?.join('.')||null});
    }
    const body=parsed.data;
    if(body.role===Role.SUPERVISOR&&!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN))return reply.code(403).send({error:'Somente Administrador e Super Adm podem criar supervisores.'});
    try{
      try{normalizeDaySchedules(body.workSchedule);}catch(error){return reply.code(400).send({error:error instanceof Error?error.message:'Jornada inválida',code:'INVALID_WORK_SCHEDULE'});}
      const [department, worksite] = await Promise.all([
        prisma.department.findFirst({where:{id:body.departmentId,tenantId:request.user.tenantId,active:true},select:{id:true}}),
        prisma.worksite.findFirst({where:{id:body.worksiteId,tenantId:request.user.tenantId,active:true},select:{id:true}})
      ]);
      if(!department)return reply.code(400).send({error:'Selecione um setor válido.',code:'INVALID_DEPARTMENT'});
      if(!worksite)return reply.code(400).send({error:'Selecione uma unidade válida.',code:'INVALID_WORKSITE'});
      if(body.shiftId){
        const shift=await prisma.shift.findFirst({where:{id:body.shiftId,tenantId:request.user.tenantId,active:true},select:{id:true}});
        if(!shift)return reply.code(400).send({error:'Selecione uma jornada/escala válida.',code:'INVALID_SHIFT'});
      }
      const automaticSupervisor=body.role===Role.SUPERVISOR
        ? {id:null as string|null,name:null as string|null}
        : await supervisorForWorksite(request.user.tenantId,body.worksiteId,body.supervisorId);
      if(body.supervisorId&&!automaticSupervisor.id)return reply.code(400).send({error:'Selecione um supervisor ativo da mesma unidade.',code:'INVALID_SUPERVISOR'});
      const username=body.username.trim().toLowerCase();
      const employeeNumber=(body.employeeNumber?.trim()||`COL-${randomUUID().slice(0,8).toUpperCase()}`);
      const [existingUsername,existingNumber]=await Promise.all([
        prisma.user.findUnique({where:{tenantId_email:{tenantId:request.user.tenantId,email:username}},select:{id:true}}),
        prisma.employee.findFirst({where:{tenantId:request.user.tenantId,employeeNumber},select:{id:true}})
      ]);
      if(existingUsername)return reply.code(409).send({error:'Este usuário de acesso já está em uso nesta empresa.',code:'USERNAME_ALREADY_EXISTS'});
      if(existingNumber)return reply.code(409).send({error:'Esta matrícula já está em uso nesta empresa.',code:'EMPLOYEE_NUMBER_ALREADY_EXISTS'});
      const passwordHash = await bcrypt.hash(body.password, 12);
      const created = await prisma.$transaction(async tx => {
        const user = await tx.user.create({ data: { tenantId: request.user.tenantId, email: username, displayName:body.name, contactEmail:body.email?.trim().toLowerCase()||null, passwordHash, role: body.role, mustChangePassword:true } });
        const employee=await tx.employee.create({ data: { tenantId: request.user.tenantId, userId: user.id, name: body.name, employeeNumber, cpfMasked: body.cpfMasked||null, jobTitle: body.jobTitle||null, admissionDate: body.admissionDate ? new Date(body.admissionDate) : undefined, birthDate:body.birthDate?new Date(body.birthDate):undefined, salaryCents: body.salaryCents, workSchedule:body.workSchedule, shiftId: body.shiftId||null, departmentId: body.departmentId, worksiteId: body.worksiteId, supervisorId: automaticSupervisor.id }, include: { user: true, department: true, shift: true, worksite: true, supervisor:{select:{id:true,name:true}} } });
        await tx.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'EMPLOYEE_CREATED_FIRST_ACCESS_PENDING', entityType: 'Employee', entityId: employee.id, afterJson: { name: employee.name, employeeNumber: employee.employeeNumber, username: user.email, email: user.contactEmail, role: user.role, salaryCents:employee.salaryCents, workSchedule:employee.workSchedule, onboardingRequired:true, automaticSupervisor:automaticSupervisor.name, supervisorLinked:Boolean(automaticSupervisor.id) } } });
        return employee;
      });
      return reply.code(201).send(created);
    }catch(error:any){
      request.log.error({err:error,requestId:request.id,tenantId:request.user.tenantId},'employee creation failed');
      if(error?.code==='P2002'){
        const target=Array.isArray(error?.meta?.target)?error.meta.target.join(','):String(error?.meta?.target??'');
        if(target.includes('employeeNumber'))return reply.code(409).send({error:'Esta matrícula já está em uso nesta empresa.',code:'EMPLOYEE_NUMBER_ALREADY_EXISTS'});
        if(target.includes('email'))return reply.code(409).send({error:'Este usuário de acesso já está em uso nesta empresa.',code:'USERNAME_ALREADY_EXISTS'});
        return reply.code(409).send({error:'Já existe um cadastro com um dos identificadores informados.',code:'EMPLOYEE_DUPLICATE'});
      }
      if(error?.code==='P2003')return reply.code(400).send({error:'Setor, unidade, jornada ou supervisor inválido. Atualize a página e selecione novamente.',code:'EMPLOYEE_RELATION_INVALID'});
      return reply.code(503).send({error:'Não foi possível concluir o cadastro agora. Nenhum cadastro parcial foi mantido. Tente novamente.',code:'EMPLOYEE_CREATE_UNAVAILABLE',requestId:request.id});
    }
  });

  app.patch('/admin/employees/:id', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const body = z.object({ name: z.string().min(2).max(150).optional(), employeeNumber: z.string().min(1).max(40).optional(), username:z.string().trim().min(2).max(120).optional(), password:z.string().min(6).max(128).optional(), email:z.preprocess((value)=>value===null||value===undefined||value===''?undefined:value,z.string().trim().email().optional()), cpfMasked: z.string().max(30).nullable().optional(), jobTitle: z.string().max(120).nullable().optional(), admissionDate: z.string().datetime().nullable().optional(), birthDate:z.string().datetime().nullable().optional(), salaryCents: z.number().int().min(0).optional(), workSchedule: daySchedulesSchema.optional(), shiftId: z.string().nullable().optional(), departmentId: z.string().nullable().optional(), worksiteId: z.string().nullable().optional(), supervisorId: z.string().nullable().optional(), active: z.boolean().optional(), role: z.enum(['ADMIN','SUPERVISOR','EMPLOYEE','AUDITOR']).transform(v=>v as Role).optional() }).parse(request.body);
    if(body.workSchedule){try{normalizeDaySchedules(body.workSchedule);}catch(error){return reply.code(400).send({error:error instanceof Error?error.message:'Jornada inválida'});}}
    const employee = await prisma.employee.findFirst({ where: { id, tenantId: request.user.tenantId }, include: { user: true } });
    if (!employee) return reply.code(404).send({ error: 'Colaborador não encontrado' });
    if((employee.user?.role===Role.SUPERVISOR||body.role===Role.SUPERVISOR)&&!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN))return reply.code(403).send({error:'Somente Administrador e Super Adm podem editar supervisores.'});
    if(body.role===Role.ADMIN&&employee.user?.role!==Role.ADMIN)return reply.code(400).send({error:'O perfil Administrador é criado e gerenciado somente pelo Super Adm.'});
    if(employee.user?.role===Role.ADMIN&&body.role!==undefined&&body.role!==Role.ADMIN)return reply.code(400).send({error:'O perfil Administrador não pode ser rebaixado pela tela de colaboradores.'});
    if(body.username&&employee.userId){const conflict=await prisma.user.findFirst({where:{tenantId:request.user.tenantId,email:body.username.trim().toLowerCase(),id:{not:employee.userId}},select:{id:true}});if(conflict)return reply.code(409).send({error:'Este usuário de acesso já está em uso nesta empresa.'});}
    const { role, admissionDate, birthDate, username, email, password, ...employeeData } = body;
    const targetWorksiteId=body.worksiteId??employee.worksiteId;
    const targetRole=role??employee.user?.role;
    if(targetWorksiteId){
      if(targetRole===Role.SUPERVISOR){
        employeeData.supervisorId=null;
      }else if(body.supervisorId!==undefined){
        const selectedSupervisor=await supervisorForWorksite(request.user.tenantId,targetWorksiteId,body.supervisorId,id);
        if(body.supervisorId&&!selectedSupervisor.id)return reply.code(400).send({error:'Selecione um supervisor ativo da mesma unidade.',code:'INVALID_SUPERVISOR'});
        employeeData.supervisorId=selectedSupervisor.id;
      }else if(body.worksiteId!==undefined&&body.worksiteId!==employee.worksiteId){
        const automaticSupervisor=await automaticSupervisorForWorksite(request.user.tenantId,targetWorksiteId,id);
        employeeData.supervisorId=automaticSupervisor.id;
      }
    }
    const nextPasswordHash=password?await bcrypt.hash(password,12):undefined;
    const updated = await prisma.$transaction(async tx => {
      if (employee.userId && (role !== undefined || body.active !== undefined || username!==undefined || email!==undefined || body.name!==undefined || password!==undefined)) await tx.user.update({ where: { id: employee.userId }, data: { ...(role ? { role } : {}), ...(body.active !== undefined ? { active: body.active } : {}), ...(username!==undefined?{email:username.trim().toLowerCase()}:{}), ...(email!==undefined?{contactEmail:email?email.trim().toLowerCase():null}:{}), ...(body.name!==undefined?{displayName:body.name}:{}), ...(nextPasswordHash?{passwordHash:nextPasswordHash,mustChangePassword:true,passwordChangedAt:null,onboardingCompletedAt:null}:{}) } });
      const supervisorStopsManaging=employee.user?.role===Role.SUPERVISOR&&((body.worksiteId!==undefined&&body.worksiteId!==employee.worksiteId)||(role!==undefined&&role!==Role.SUPERVISOR)||body.active===false);
      if(supervisorStopsManaging){
        const replacement=employee.worksiteId?await tx.employee.findFirst({where:{tenantId:request.user.tenantId,worksiteId:employee.worksiteId,active:true,archivedAt:null,id:{not:id},user:{is:{role:Role.SUPERVISOR,active:true}}},select:{id:true},orderBy:{name:'asc'}}):null;
        await tx.employee.updateMany({where:{tenantId:request.user.tenantId,supervisorId:id,archivedAt:null},data:{supervisorId:replacement?.id??null}});
      }
      return tx.employee.update({ where: { id }, data: { ...employeeData, ...(admissionDate !== undefined ? { admissionDate: admissionDate ? new Date(admissionDate) : null } : {}), ...(birthDate !== undefined ? { birthDate: birthDate ? new Date(birthDate) : null } : {}) }, include: { user: { select: { email: true, contactEmail:true, role: true, active: true, mustChangePassword:true,onboardingCompletedAt:true, _count:{select:{webAuthnCredentials:true}} } }, department: true, shift: true, worksite: true, supervisor: { select: { id: true, name: true } } } });
    });
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'EMPLOYEE_UPDATED', entityType: 'Employee', entityId: id, beforeJson: { name: employee.name, active: employee.active, salaryCents: employee.salaryCents, workSchedule:employee.workSchedule }, afterJson: { name: updated.name, active: updated.active, salaryCents: updated.salaryCents, workSchedule:updated.workSchedule } } });
    if(body.salaryCents!==undefined&&body.salaryCents!==employee.salaryCents){
      await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'EMPLOYEE_SALARY_CHANGED',entityType:'Employee',entityId:id,beforeJson:{salaryCents:employee.salaryCents},afterJson:{salaryCents:updated.salaryCents}}});
    }
    return updated;
  });

  app.delete('/admin/employees/:id', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const employee=await prisma.employee.findFirst({where:{id,tenantId:request.user.tenantId,archivedAt:null},include:{user:{select:{id:true,role:true,email:true}}}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado ou já excluído.'});
    if(employee.user?.role===Role.SUPERVISOR&&!(request.user.role===Role.SUPER_ADMIN||request.user.role===Role.ADMIN))return reply.code(403).send({error:'Somente Administrador e Super Adm podem excluir supervisores.'});
    if(request.user.employeeId===employee.id)return reply.code(400).send({error:'Você não pode excluir o seu próprio cadastro.'});
    if(employee.user?.role===Role.ADMIN||employee.user?.role===Role.HR||employee.user?.role===Role.SUPER_ADMIN)return reply.code(400).send({error:'Contas administrativas não podem ser excluídas por esta ação.'});
    const now=new Date();
    const userId=employee.userId;
    const replacementSupervisor=employee.user?.role===Role.SUPERVISOR&&employee.worksiteId
      ? await prisma.employee.findFirst({where:{tenantId:request.user.tenantId,worksiteId:employee.worksiteId,active:true,archivedAt:null,id:{not:employee.id},user:{is:{role:Role.SUPERVISOR,active:true}}},select:{id:true,name:true},orderBy:{name:'asc'}})
      : null;
    const credentials=userId?await prisma.webAuthnCredential.findMany({where:{tenantId:request.user.tenantId,userId},select:{id:true}}):[];
    await prisma.$transaction(async tx=>{
      // Mantém marcações, selfies, espelho e auditoria históricos. A exclusão remove o acesso e oculta o colaborador das telas operacionais.
      await tx.employee.updateMany({where:{tenantId:request.user.tenantId,supervisorId:employee.id,archivedAt:null},data:{supervisorId:replacementSupervisor?.id??null}});
      if(userId){
        await tx.webAuthnCredential.deleteMany({where:{tenantId:request.user.tenantId,userId}});
        await tx.webAuthnChallenge.deleteMany({where:{tenantId:request.user.tenantId,userId}});
        await tx.user.update({where:{id:userId},data:{active:false}});
      }
      await tx.employee.update({where:{id:employee.id},data:{active:false,archivedAt:now,biometricLockedUntil:null,failedBiometricAttempts:0}});
      await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'EMPLOYEE_ARCHIVED_BY_ADMIN_OR_HR',entityType:'Employee',entityId:employee.id,beforeJson:{name:employee.name,employeeNumber:employee.employeeNumber,active:employee.active,username:employee.user?.email,role:employee.user?.role} as any,afterJson:{active:false,archivedAt:now.toISOString(),accessRevoked:true,historyPreserved:true,deletedPasskeys:credentials.length,replacementSupervisorId:replacementSupervisor?.id??null,replacementSupervisorName:replacementSupervisor?.name??null} as any}});
    });
    // Remove apenas a referência usada pelo Totem. Selfies de jornadas anteriores permanecem preservadas no histórico.
    await deleteFaceEmployee(employee.id).catch(()=>undefined);
    await prisma.faceReference.updateMany({where:{tenantId:request.user.tenantId,employeeId:employee.id,revokedAt:null},data:{revokedAt:now}}).catch(()=>undefined);
    return {ok:true,id:employee.id,name:employee.name,archivedAt:now,historyPreserved:true};
  });

  app.post('/admin/employees/:id/biometric-unlock', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const employee=await prisma.employee.findFirst({where:{id,tenantId:request.user.tenantId}}); if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    await prisma.employee.update({where:{id},data:{failedBiometricAttempts:0,biometricLockedUntil:null}});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'BIOMETRIC_LOCKOUT_CLEARED',entityType:'Employee',entityId:id}});
    return {ok:true};
  });

  app.get('/admin/security-settings', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    return getOrCreateSecuritySettings(request.user.tenantId);
  });

  app.patch('/admin/security-settings', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const schema = z.object({
      requireSelfie:z.boolean().optional(), requireFaceMatch:z.boolean().optional(), requireLiveness:z.boolean().optional(), requireAntiSpoof:z.boolean().optional(),
      blockWithoutEnrollment:z.boolean().optional(), blockOnFaceFailure:z.boolean().optional(), requirePlatformBiometric:z.boolean().optional(), requireTrustedDevice:z.boolean().optional(),
      requireGeofence:z.boolean().optional(), requireAccurateGps:z.boolean().optional(), maxGpsAccuracyM:z.number().int().min(5).max(5000).optional(),
      requireDynamicQr:z.boolean().optional(), requireNetworkAttestation:z.boolean().optional(), requireBluetoothBeacon:z.boolean().optional(), requireNfcTag:z.boolean().optional(),
      allowOffline:z.boolean().optional(), offlineRequiresReview:z.boolean().optional(), minFaceMatchScore:z.number().min(0).max(1).optional(), minLivenessScore:z.number().min(0).max(1).optional(),
      minAntiSpoofScore:z.number().min(0).max(1).optional(), minFaceQualityScore:z.number().min(0).max(1).optional(), maxFaceCount:z.number().int().min(1).max(3).optional(),
      faceReferenceMin:z.number().int().min(1).max(3).optional(), faceReferenceRecommended:z.number().int().min(1).max(3).optional(), selfieRetentionDays:z.number().int().min(1).max(3650).optional(),
      blockedAttemptRetentionDays:z.number().int().min(1).max(3650).optional(), revokedFaceReferenceRetentionDays:z.number().int().min(1).max(3650).optional(), challengeTtlSeconds:z.number().int().min(30).max(900).optional(), maxFailedBiometricAttempts:z.number().int().min(1).max(20).optional(), biometricLockoutMinutes:z.number().int().min(1).max(1440).optional(), maxOfflineAgeHours:z.number().int().min(1).max(720).optional(), maxFutureClockSkewMinutes:z.number().int().min(0).max(60).optional(), fullIntegrityRequiresAllConfigured:z.boolean().optional()
    });
    const parsed=schema.parse(request.body); const before=await getOrCreateSecuritySettings(request.user.tenantId);
    const body={...parsed,requireSelfie:true,requireFaceMatch:false,requireLiveness:false,requireAntiSpoof:false,blockWithoutEnrollment:false,blockOnFaceFailure:false,requirePlatformBiometric:false,requireGeofence:false,requireAccurateGps:false,maxGpsAccuracyM:5000,maxFaceCount:1,fullIntegrityRequiresAllConfigured:true};
    const updated=await prisma.attendanceSecuritySettings.update({where:{tenantId:request.user.tenantId},data:body});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'SECURITY_POLICY_UPDATED',entityType:'AttendanceSecuritySettings',entityId:updated.id,beforeJson:before as any,afterJson:updated as any}});
    return updated;
  });

  app.post('/admin/employees/:id/kiosk-face', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const {imageDataUrl}=z.object({imageDataUrl:z.string().min(100).max(4_500_000)}).parse(request.body);
    try{
      const result=await registerEmployeeKioskFace({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId,imageDataUrl});
      return reply.code(201).send(result);
    }catch(error){
      const message=error instanceof Error?error.message:'Não foi possível cadastrar a foto frontal';
      return reply.code(message==='Colaborador não encontrado'?404:422).send({error:message});
    }
  });

  app.get('/admin/employees/:id/face-references', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const employee=await prisma.employee.findFirst({where:{id,tenantId:request.user.tenantId},select:{id:true,name:true,faceEnrollmentStatus:true,faceEnrolledAt:true,faceTemplateVersion:true}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    const references=await prisma.faceReference.findMany({where:{tenantId:request.user.tenantId,employeeId:id,revokedAt:null},select:{id:true,pose:true,faceQualityScore:true,livenessScore:true,antiSpoofScore:true,createdAt:true},orderBy:{createdAt:'desc'}});
    return {employee,references};
  });

  app.post('/admin/employees/:id/face-references', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({pose:z.literal(FacePose.FRONT),imageDataUrl:z.string().min(100),embedding:z.array(z.number()).min(64).max(4096),faceQualityScore:z.number().min(0).max(1),livenessScore:z.number().min(0).max(1),antiSpoofScore:z.number().min(0).max(1),livenessChallengePassed:z.literal(true)}).parse(request.body);
    const ref=await enrollFaceReference({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId,...body});
    return reply.code(201).send({id:ref.id,pose:ref.pose,faceQualityScore:ref.faceQualityScore,createdAt:ref.createdAt});
  });

  app.get('/admin/employees/:id/biometrics', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const employee=await prisma.employee.findFirst({where:{id,tenantId:request.user.tenantId},select:{id:true,name:true,employeeNumber:true,faceEnrollmentStatus:true,faceEnrolledAt:true,user:{select:{id:true,email:true,contactEmail:true,role:true,webAuthnCredentials:{select:{id:true,label:true,deviceType:true,backedUp:true,createdAt:true,lastUsedAt:true},orderBy:{createdAt:'desc'}}}}}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    const activeFaceReferences=await prisma.faceReference.count({where:{tenantId:request.user.tenantId,employeeId:id,revokedAt:null,embeddingEncrypted:{not:null}}});
    const punchSelfies=await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:id,evidence:{is:{selfieStorageKey:{not:null},selfiePurgedAt:null}}},select:{id:true,recordNumber:true,type:true,occurredAt:true,decision:true,evidence:{select:{selfieCapturedAt:true}}},orderBy:{occurredAt:'desc'},take:60});
    return {employee:{id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,faceEnrollmentStatus:employee.faceEnrollmentStatus,faceEnrolledAt:employee.faceEnrolledAt,username:employee.user?.email??null,email:employee.user?.contactEmail??null,role:employee.user?.role??null},face:{activeReferences:activeFaceReferences,photoStored:false},credentials:employee.user?.webAuthnCredentials??[],punchSelfies:punchSelfies.map(p=>({...p,recordNumber:p.recordNumber.toString(),selfieCapturedAt:p.evidence?.selfieCapturedAt??null})),note:'O Apontamento Digital não armazena impressão digital bruta. A passkey é uma chave pública WebAuthn; selfies de marcação são evidências criptografadas e sujeitas à retenção configurada.'};
  });

  app.get('/admin/punches/:id/selfie', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES) && !JOURNEY_REVIEW_ROLES.includes(request.user.role as Role)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const punch=await prisma.punch.findFirst({where:{id,tenantId:request.user.tenantId},select:{id:true,employeeId:true,evidence:{select:{selfieStorageKey:true,selfiePurgedAt:true}}}});
    if(!punch||!punch.evidence?.selfieStorageKey||punch.evidence.selfiePurgedAt)return reply.code(404).send({error:'Selfie da marcação não encontrada ou já eliminada pela retenção.'});
    try{
      const bytes=await readEncryptedImage(punch.evidence.selfieStorageKey);
      await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,punchId:punch.id,actorUserId:request.user.userId,action:'PUNCH_SELFIE_VIEWED',entityType:'Punch',entityId:punch.id,metadataJson:{employeeId:punch.employeeId,viewerRole:request.user.role}}});
      reply.header('Cache-Control','no-store, private').type('image/jpeg');return reply.send(bytes);
    }catch{return reply.code(404).send({error:'Selfie da marcação indisponível.'});}
  });

  app.post('/admin/employees/:id/face-reset', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    try{return await purgeEmployeeFaceBiometrics({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId});}
    catch(error){return reply.code(404).send({error:error instanceof Error?error.message:'Colaborador não encontrado'});}
  });

  app.delete('/admin/employees/:id/webauthn/:credentialId', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id,credentialId}=z.object({id:z.string(),credentialId:z.string()}).parse(request.params);
    try{return await purgeEmployeeWebAuthn({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId,credentialId});}
    catch(error){return reply.code(404).send({error:error instanceof Error?error.message:'Credencial não encontrada'});}
  });

  app.post('/admin/employees/:id/webauthn-reset', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    try{return await purgeEmployeeWebAuthn({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId});}
    catch(error){return reply.code(404).send({error:error instanceof Error?error.message:'Colaborador não encontrado'});}
  });

  app.post('/admin/employees/:id/biometrics-reset', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    try{
      const face=await purgeEmployeeFaceBiometrics({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId});
      const webauthn=await purgeEmployeeWebAuthn({tenantId:request.user.tenantId,employeeId:id,actorUserId:request.user.userId});
      await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'ADMIN_ALL_BIOMETRICS_DELETED',entityType:'Employee',entityId:id,metadataJson:{faceDeleted:true,webauthnDeleted:webauthn.deleted}}});
      return {ok:true,face,webauthn};
    }catch(error){return reply.code(404).send({error:error instanceof Error?error.message:'Colaborador não encontrado'});}
  });

  app.get('/admin/timesheet-employees', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if(!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role)&&request.user.role!==Role.HR)return reply.code(403).send({error:'Sem permissão'});
    const rows=await prisma.employee.findMany({
      where:{tenantId:request.user.tenantId,active:true,archivedAt:null},
      select:{id:true,name:true,employeeNumber:true,jobTitle:true,user:{select:{role:true}}},
      orderBy:{name:'asc'}
    });
    return rows.filter(row=>row.user?.role!==Role.SUPER_ADMIN).map(row=>({id:row.id,name:row.name,employeeNumber:row.employeeNumber,jobTitle:row.jobTitle,role:row.user?.role??'EMPLOYEE'}));
  });

  app.get('/admin/attendance-today', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if(!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role))return reply.code(403).send({error:'A presença diária é exclusiva de responsáveis autorizados.'});
    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{id:true,timezone:true}});
    if(!tenant)return reply.code(404).send({error:'Empresa não encontrada'});
    const tenantTz=tenant.timezone||'America/Maceio';const now=new Date();const today=localDateKey(now,tenantTz);
    const q=z.object({date:z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional()}).parse(request.query??{});
    const workDate=q.date??today;const minDate=addDaysKey(today,-365);
    if(workDate>today||workDate<minDate)return reply.code(400).send({error:'Selecione uma data entre hoje e os últimos 365 dias.'});
    const employees=await prisma.employee.findMany({
      where:{tenantId:request.user.tenantId,active:true,archivedAt:null},
      include:{shift:true,worksite:{select:{id:true,name:true}},department:{select:{id:true,name:true}},user:{select:{role:true}}},
      orderBy:{name:'asc'}
    });
    const scheduled=employees.map(employee=>({employee,schedule:operationalScheduleForDay(employee,workDate)})).filter(row=>row.schedule&&row.employee.user?.role!==Role.SUPER_ADMIN);
    const ids=scheduled.map(row=>row.employee.id);
    const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',tenantTz);const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',tenantTz);
    const rawPunches=ids.length?await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:{in:ids},occurredAt:{gte:start,lt:end}},include:{evidence:{select:{selfieStorageKey:true,selfiePurgedAt:true,latitude:true,longitude:true}}},orderBy:{occurredAt:'asc'}}):[];
    const grouped=new Map<string,any[]>();
    for(const punch of rawPunches){
      const row=scheduled.find(item=>item.employee.id===punch.employeeId);if(!row)continue;
      const tz=row.employee.shift?.timezone||tenantTz;
      if(workDateKeyForInstant(punch.occurredAt,row.employee.shift,tz)!==workDate)continue;
      if(!isOperationalPunch(punch))continue;
      const list=grouped.get(punch.employeeId)??[];list.push(punch);grouped.set(punch.employeeId,list);
    }
    let started=0,finished=0,notStarted=0;
    const rows=scheduled.map(({employee,schedule})=>{
      const punches=grouped.get(employee.id)??[];
      const clockIn=punches.find(p=>p.type===PunchType.CLOCK_IN);
      const last=punches[punches.length-1]??null;
      const status=!clockIn?'NOT_STARTED':last?.type===PunchType.CLOCK_OUT?'FINISHED':'STARTED';
      if(status==='NOT_STARTED')notStarted++;else{started++;if(status==='FINISHED')finished++;}
      return {id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,role:employee.user?.role??Role.EMPLOYEE,jobTitle:employee.jobTitle,department:employee.department?.name??null,worksite:employee.worksite?.name??null,status,scheduledStart:schedule!.startTime,scheduledEnd:schedule!.endTime,startedAt:clockIn?.occurredAt??null,lastPunchAt:last?.occurredAt??null,lastPunchType:last?.type??null,punchCount:punches.length};
    });
    return {workDate,timeZone:tenantTz,generatedAt:now,summary:{scheduled:rows.length,started,notStarted,finished,inProgress:Math.max(0,started-finished)},employees:rows};
  });

  app.get('/admin/attendance-today/:employeeId', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if(!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role))return reply.code(403).send({error:'A presença diária é exclusiva de responsáveis autorizados.'});
    const {employeeId}=z.object({employeeId:z.string()}).parse(request.params);
    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{timezone:true}});if(!tenant)return reply.code(404).send({error:'Empresa não encontrada'});
    const tenantTz=tenant.timezone||'America/Maceio';const today=localDateKey(new Date(),tenantTz);
    const q=z.object({date:z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional()}).parse(request.query??{});
    const workDate=q.date??today;const minDate=addDaysKey(today,-365);
    if(workDate>today||workDate<minDate)return reply.code(400).send({error:'Selecione uma data entre hoje e os últimos 365 dias.'});
    const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId:request.user.tenantId,active:true,archivedAt:null},include:{shift:true,worksite:{select:{id:true,name:true,address:true,city:true,stateCode:true}},department:{select:{name:true}},user:{select:{role:true}}}});
    if(!employee||employee.user?.role===Role.SUPER_ADMIN)return reply.code(404).send({error:'Colaborador não encontrado'});
    const schedule=operationalScheduleForDay(employee,workDate);if(!schedule)return reply.code(400).send({error:'Este colaborador não possui jornada prevista na data selecionada.'});
    const tz=employee.shift?.timezone||tenantTz;const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',tz);const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',tz);
    const raw=await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId,occurredAt:{gte:start,lt:end}},include:{evidence:true},orderBy:{occurredAt:'asc'}});
    const punches=raw.filter(p=>workDateKeyForInstant(p.occurredAt,employee.shift,tz)===workDate&&isOperationalPunch(p));
    const worksiteIds=[...new Set(punches.map(p=>p.evidence?.worksiteId).filter(Boolean) as string[])];
    const worksites=worksiteIds.length?await prisma.worksite.findMany({where:{tenantId:request.user.tenantId,id:{in:worksiteIds}},select:{id:true,name:true,address:true,city:true,stateCode:true}}):[];const worksiteMap=new Map(worksites.map(w=>[w.id,w]));
    const clockIn=punches.find(p=>p.type===PunchType.CLOCK_IN);const last=punches[punches.length-1]??null;const status=!clockIn?'NOT_STARTED':last?.type===PunchType.CLOCK_OUT?'FINISHED':'STARTED';
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'DAILY_ATTENDANCE_INFO_VIEWED',entityType:'Employee',entityId:employee.id,metadataJson:{employeeName:employee.name,workDate,punchCount:punches.length}}});
    return {workDate,timeZone:tz,status,schedule,employee:{id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,role:employee.user?.role??Role.EMPLOYEE,jobTitle:employee.jobTitle,department:employee.department?.name??null,worksite:employee.worksite??null},punches:punches.map(p=>({id:p.id,recordNumber:p.recordNumber.toString(),type:p.type,occurredAt:p.occurredAt,source:p.source,decision:p.decision,reviewReason:p.reviewReason,selfieAvailable:Boolean(p.evidence?.selfieStorageKey&&!p.evidence?.selfiePurgedAt),evidence:p.evidence?{latitude:p.evidence.latitude,longitude:p.evidence.longitude,accuracyM:p.evidence.accuracyM,selfieCapturedAt:p.evidence.selfieCapturedAt,clientCapturedAt:p.evidence.clientCapturedAt,worksite:p.evidence.worksiteId?worksiteMap.get(p.evidence.worksiteId)??null:null}:null}))};
  });

  app.get('/admin/security-reviews', { preHandler:[app.authenticate] }, async (request,reply)=>{
    if(!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role))return reply.code(403).send({error:'A área de conferência é exclusiva de responsáveis autorizados.'});
    // V83.96: jornada normal não entra mais em fila de aprovação. Somente faltas e atrasos possuem decisão administrativa.
    const attempts=await prisma.punchAttempt.findMany({where:{tenantId:request.user.tenantId,decision:'BLOCKED'},include:{employee:{select:{id:true,name:true,employeeNumber:true}}},orderBy:{attemptedAt:'desc'},take:100});
    return {journeys:[],attempts:attempts.map((attempt:any)=>({...attempt,technicalReason:attempt.reason,reason:friendlyBlockedAttemptReason(attempt)}))};
  });

  app.get('/admin/journey-reviews/:id', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role))return reply.code(403).send({error:'A aprovação diária da jornada é exclusiva de responsáveis autorizados.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const journey=await pendingJourneyReview(request.user.tenantId,id,true);
    if(!journey)return reply.code(404).send({error:'Jornada finalizada não encontrada.'});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'JOURNEY_REVIEW_OPENED',entityType:'Punch',entityId:id,metadataJson:{employeeId:journey.employee.id,employeeName:journey.employee.name,workDate:journey.workDate,punchCount:journey.punchCount}}});
    return journey;
  });

  app.post('/admin/journey-reviews/:id/review', { preHandler:[app.authenticate] }, async(request,reply)=>{
    if(!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role))return reply.code(403).send({error:'A aprovação diária da jornada é exclusiva de responsáveis autorizados.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({decision:z.enum(['APPROVED','BLOCKED']),note:z.string().max(500).optional().default(''),biometricProofToken:z.string().min(20)}).superRefine((value,ctx)=>{if(value.decision==='BLOCKED'&&value.note.trim().length<3)ctx.addIssue({code:z.ZodIssueCode.custom,path:['note'],message:'A justificativa da reprovação é obrigatória.'});}).parse(request.body);
    const journey=await pendingJourneyReview(request.user.tenantId,id);
    if(!journey)return reply.code(404).send({error:'Jornada finalizada pendente de aprovação não encontrada.'});
    const opened=await prisma.auditEvent.findFirst({where:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'JOURNEY_REVIEW_OPENED',entityType:'Punch',entityId:id},orderBy:{createdAt:'desc'},select:{id:true}});
    if(!opened)return reply.code(409).send({error:'Clique em “Ver informações” e confira horários, GPS e selfies antes de aprovar ou reprovar a jornada.'});
    const missingSelfie=journey.punches.find((p:any)=>(!p.evidence?.selfieStorageKey||p.evidence?.selfiePurgedAt)&&p.source!=='SYSTEM_AUTO_LOCK_NO_SELFIE');
    if(missingSelfie)return reply.code(409).send({error:`A marcação #${missingSelfie.recordNumber} não possui selfie disponível. Revise a ocorrência antes de decidir a jornada.`});
    // A saída automática ao completar 15 minutos após o fim previsto nasce propositalmente sem selfie/GPS; é a única exceção permitida.
    // Ela permanece destacada para análise e pode ser aprovada ou reprovada pelo responsável.
    let proof:any;try{proof=(app.jwt as any).verify(body.biometricProofToken);}catch{return reply.code(403).send({error:'Confirmação biométrica/passkey do aprovador inválida ou expirada'});}
    const bound=`REVIEW:${request.user.tenantId}:${id}`;
    if(proof?.kind!=='PUNCH_BIOMETRIC'||proof?.userId!==request.user.userId||proof?.tenantId!==request.user.tenantId||proof?.punchChallengeId!==bound||!proof?.credentialId)return reply.code(403).send({error:'Esta decisão exige uma confirmação nova da passkey do próprio Administrador/Super Adm.'});
    const hasSystemAutoClose=journey.punches.some((p:any)=>p.source==='SYSTEM_AUTO_LOCK_NO_SELFIE');
    const note=body.decision==='BLOCKED'?body.note.trim():(body.note.trim()||(hasSystemAutoClose?'Jornada aprovada após conferência da saída automática sem selfie e das demais evidências disponíveis do dia.':'Jornada aprovada após conferência dos horários, selfies e localizações do dia.'));
    const punchIds=journey.punches.map((p:any)=>p.id);
    await prisma.$transaction(async tx=>{
      await tx.punch.updateMany({where:{tenantId:request.user.tenantId,id:{in:punchIds},decision:'REVIEW'},data:{decision:body.decision,reviewReason:note}});
      await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'DAILY_JOURNEY_REVIEWED_WITH_APPROVER_PASSKEY',entityType:'Employee',entityId:journey.employee.id,beforeJson:{workDate:journey.workDate,status:'PENDING_APPROVAL',punchIds} as any,afterJson:{workDate:journey.workDate,decision:body.decision,note,credentialId:proof.credentialId,approverRole:request.user.role} as any}});
      await tx.alert.updateMany({where:{tenantId:request.user.tenantId,employeeId:journey.employee.id,code:'PUNCH_REVIEW',resolved:false},data:{resolved:true,resolvedAt:new Date()}});
    });
    await persistEmployeeLedger(journey.employee.id,request.user.tenantId,journey.workDate,journey.workDate);
    return {ok:true,employeeId:journey.employee.id,workDate:journey.workDate,decision:body.decision,punches:punchIds.length,justification:note,approval:{verified:true,credentialId:proof.credentialId,actorUserId:request.user.userId}};
  });

  app.post('/admin/punches/:id/review', { preHandler:[app.authenticate] }, async(_request,reply)=>{
    return reply.code(410).send({error:'A aprovação individual de marcações foi substituída pela aprovação da jornada completa após a saída do dia.'});
  });

  app.get('/admin/devices', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    return prisma.device.findMany({
      where: { employee: { tenantId: request.user.tenantId } },
      include: { employee: { select: { id: true, name: true, employeeNumber: true } } },
      orderBy: { lastSeenAt: 'desc' }, take: 300
    });
  });

  app.post('/admin/devices/:id/trust', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const { trusted } = z.object({ trusted: z.boolean() }).parse(request.body);
    const device = await prisma.device.findFirst({ where: { id, employee: { tenantId: request.user.tenantId } } });
    if (!device) return reply.code(404).send({ error: 'Dispositivo não encontrado' });
    return prisma.device.update({ where: { id }, data: { trusted } });
  });
}
