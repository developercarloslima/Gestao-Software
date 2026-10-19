import { FastifyInstance } from 'fastify';
import { AdjustmentStatus, AbsenceDocumentType, Role } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, dateKeyToUtcNoon, localDateKey, weekdayKey, zonedDateTimeToUtc } from '../lib/time.js';
import { calculateEmployeeRange, getOrCreateRules, persistEmployeeLedger } from '../services/time-engine.service.js';
import { readEncryptedImage, removeEncryptedEvidence, saveEncryptedEvidenceDataUrl } from '../services/biometric-storage.service.js';
import { bankBalanceForEmployee, bankBalancesForEmployees } from '../services/bank-balance.service.js';

const ADMIN_ONLY:Role[]=[Role.SUPER_ADMIN,Role.ADMIN];
const isAdmin=(role:string)=>ADMIN_ONLY.includes(role as Role);
type DaySchedule={enabled:boolean;startTime:string;endTime:string;breakMinutes:number};

function scheduleFromJson(raw:unknown,key:string):DaySchedule|null|undefined{
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||!Object.keys(raw as object).length)return undefined;
  const candidate=(raw as Record<string,unknown>)[key];
  if(!candidate||typeof candidate!=='object'||Array.isArray(candidate))return null;
  const d=candidate as Record<string,unknown>;
  if(typeof d.enabled==='boolean'&&typeof d.startTime==='string'&&typeof d.endTime==='string'&&typeof d.breakMinutes==='number')return {enabled:d.enabled,startTime:d.startTime,endTime:d.endTime,breakMinutes:d.breakMinutes};
  return null;
}
function scheduleForDay(employee:any,dateKey:string):DaySchedule|null{
  const key=weekdayKey(dateKey);
  const personal=scheduleFromJson(employee.workSchedule,key);if(personal!==undefined)return personal?.enabled?personal:null;
  const shift=employee.shift;if(!shift||!shift.active)return null;
  const detailed=scheduleFromJson(shift.daySchedules,key);if(detailed!==undefined)return detailed?.enabled?detailed:null;
  const days=Array.isArray(shift.scheduleDays)?shift.scheduleDays as string[]:[];if(!days.includes(key))return null;
  return {enabled:true,startTime:shift.startTime,endTime:shift.endTime,breakMinutes:shift.breakMinutes};
}
function absenceDeadline(employee:any,dateKey:string){
  const schedule=scheduleForDay(employee,dateKey);if(!schedule)return null;
  const tz=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const overnight=schedule.endTime<=schedule.startTime;const endDate=overnight?addDaysKey(dateKey,1):dateKey;
  return new Date(zonedDateTimeToUtc(endDate,schedule.endTime,tz).getTime()+48*60*60*1000);
}

export async function attendanceRoutes(app:FastifyInstance){
  app.get('/attendance/calendar',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const now=new Date();const q=z.object({year:z.coerce.number().int().min(2020).max(2100).default(now.getFullYear()),month:z.coerce.number().int().min(1).max(12).default(now.getMonth()+1)}).parse(request.query??{});
    const employee=await prisma.employee.findFirst({where:{id:request.user.employeeId,tenantId:request.user.tenantId,active:true,archivedAt:null},include:{shift:true,tenant:true}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    const last=new Date(Date.UTC(q.year,q.month,0)).getUTCDate();const from=`${q.year}-${String(q.month).padStart(2,'0')}-01`;const to=`${q.year}-${String(q.month).padStart(2,'0')}-${String(last).padStart(2,'0')}`;
    const result=await calculateEmployeeRange(employee.id,employee.tenantId,from,to);
    const rows=result.days.map((day:any)=>{
      const deadline=absenceDeadline(employee,day.date);const just=day.absenceJustification??null;
      const isAbsence=Boolean(day.automaticAbsence||just);
      const canJustify=Boolean(isAbsence&&!just&&deadline&&now.getTime()<=deadline.getTime());
      const status=day.pendingReview?'PENDING_REVIEW':just?.status==='APPROVED'&&just?.useBankHours?'BANK_COMPENSATED':just?.status==='APPROVED'?'JUSTIFIED':just?.status==='PENDING'?'JUSTIFICATION_PENDING':just?.status==='REJECTED'?'JUSTIFICATION_REJECTED':day.automaticAbsence?'ABSENCE':day.punchCount>0?'WORKED':day.closedDay?'CLOSED':'NO_RECORD';
      return {...day,status,canJustify,justificationDeadline:deadline?.toISOString()??null,justification:just?{id:just.id,status:just.status,documentType:just.documentType,submittedAt:just.submittedAt,reviewReason:just.reviewReason,useBankHours:just.useBankHours,bankMinutesUsed:just.bankMinutesUsed}:null};
    });
    return {year:q.year,month:q.month,timeZone:result.timeZone,days:rows};
  });

  app.post('/attendance/absence-justifications',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const body=z.object({workDate:z.string().regex(/^\d{4}-\d{2}-\d{2}$/),documentType:z.nativeEnum(AbsenceDocumentType),originalFileName:z.string().trim().min(1).max(180),fileDataUrl:z.string().min(100).max(12_000_000),employeeNote:z.string().trim().max(500).optional()}).parse(request.body);
    const employee=await prisma.employee.findFirst({where:{id:request.user.employeeId,tenantId:request.user.tenantId,active:true,archivedAt:null},include:{shift:true,tenant:true}});if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    const deadline=absenceDeadline(employee,body.workDate);if(!deadline)return reply.code(400).send({error:'Este dia não possui jornada prevista para justificar.'});
    if(new Date().getTime()>deadline.getTime())return reply.code(410).send({error:'O prazo de 48 horas para justificar esta falta já terminou.'});
    const day=await calculateEmployeeRange(employee.id,employee.tenantId,body.workDate,body.workDate).then(r=>r.days[0]);
    if(!day?.automaticAbsence)return reply.code(400).send({error:'Este dia não está registrado como falta apta para justificativa.'});
    const existing=await prisma.absenceJustification.findUnique({where:{tenantId_employeeId_workDate:{tenantId:employee.tenantId,employeeId:employee.id,workDate:dateKeyToUtcNoon(body.workDate)}}});
    if(existing)return reply.code(409).send({error:'Já existe uma justificativa enviada para esta falta.'});
    const stored=await saveEncryptedEvidenceDataUrl(body.fileDataUrl,`absence-${employee.tenantId}-${employee.id}`);
    let item:any;
    try{item=await prisma.absenceJustification.create({data:{tenantId:employee.tenantId,employeeId:employee.id,workDate:dateKeyToUtcNoon(body.workDate),documentType:body.documentType,originalFileName:body.originalFileName,mimeType:stored.mime,storageKey:stored.storageKey,employeeNote:body.employeeNote||null,status:AdjustmentStatus.PENDING}});}catch(e){await removeEncryptedEvidence(stored.storageKey);throw e;}
    await prisma.auditEvent.create({data:{tenantId:employee.tenantId,actorUserId:request.user.userId,action:'ABSENCE_JUSTIFICATION_SUBMITTED',entityType:'AbsenceJustification',entityId:item.id,metadataJson:{employeeId:employee.id,workDate:body.workDate,documentType:body.documentType,fileName:body.originalFileName,deadline:deadline.toISOString()}}});
    try{await prisma.alert.create({data:{tenantId:employee.tenantId,employeeId:employee.id,severity:'MEDIUM',code:'ABSENCE_JUSTIFICATION_REVIEW',title:`Justificativa de falta de ${employee.name} aguardando análise`,description:`Justificativa ${item.id} · documento enviado para a falta de ${body.workDate}. Tipo: ${body.documentType}.`}});}catch(alertError){request.log.error({err:alertError,employeeId:employee.id,justificationId:item.id},'failed to create absence justification risk alert');}
    return reply.code(201).send({id:item.id,status:item.status,submittedAt:item.submittedAt});
  });

  app.get('/admin/absence-justifications',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isAdmin(request.user.role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem analisar justificativas.'});
    const q=z.object({status:z.nativeEnum(AdjustmentStatus).optional()}).parse(request.query??{});
    const items=await prisma.absenceJustification.findMany({where:{tenantId:request.user.tenantId,...(q.status?{status:q.status}:{})},include:{employee:{select:{id:true,name:true,employeeNumber:true,worksite:{select:{name:true}},department:{select:{name:true}}}},reviewedBy:{select:{displayName:true,email:true}}},orderBy:{submittedAt:'desc'},take:300});
    const employeeIds=[...new Set(items.map(x=>x.employeeId))];
    const balances=await bankBalancesForEmployees(request.user.tenantId,employeeIds);
    return items.map(x=>({...x,bankBalanceMinutes:balances.get(x.employeeId)?.accumulatedMinutes??0}));
  });

  app.get('/admin/absence-justifications/:id/file',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isAdmin(request.user.role))return reply.code(403).send({error:'Sem permissão'});const {id}=z.object({id:z.string()}).parse(request.params);
    const item=await prisma.absenceJustification.findFirst({where:{id,tenantId:request.user.tenantId}});if(!item)return reply.code(404).send({error:'Justificativa não encontrada'});
    const raw=await readEncryptedImage(item.storageKey);reply.header('Content-Type',item.mimeType);reply.header('Content-Disposition',`inline; filename="${item.originalFileName.replace(/["\r\n]/g,'_')}"`);return reply.send(raw);
  });

  app.patch('/admin/absence-justifications/:id',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isAdmin(request.user.role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem decidir justificativas.'});const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({status:z.enum(['APPROVED','REJECTED']),reviewReason:z.string().trim().max(800).optional(),bankMinutes:z.number().int().min(0).max(1440).optional()}).parse(request.body);
    if(body.status==='REJECTED'&&(!body.reviewReason||body.reviewReason.length<3))return reply.code(400).send({error:'Informe a justificativa da reprovação.'});
    const before=await prisma.absenceJustification.findFirst({where:{id,tenantId:request.user.tenantId},include:{employee:{select:{id:true,name:true,tenant:{select:{timezone:true}}}}}});if(!before)return reply.code(404).send({error:'Justificativa não encontrada'});
    if(before.status!=='PENDING')return reply.code(409).send({error:'Esta justificativa já foi decidida.'});
    const date=localDateKey(before.workDate,'UTC');
    const bankMinutes=body.status==='APPROVED'?Math.max(0,body.bankMinutes??0):0;
    if(bankMinutes>0){
      const rules=await getOrCreateRules(before.tenantId);const policyDate=localDateKey(rules.bankPolicyV2At,before.employee.tenant.timezone||'America/Maceio');
      if(date<policyDate)return reply.code(400).send({error:`O uso de banco na justificativa vale a partir de ${policyDate}; o histórico anterior permanece preservado.`});
      const day=await calculateEmployeeRange(before.employeeId,before.tenantId,date,date).then(r=>r.days[0]);
      if(!day||day.missingMinutes<=0)return reply.code(400).send({error:'Não há déficit pendente nesse dia para utilizar banco de horas.'});
      if(bankMinutes>day.missingMinutes)return reply.code(400).send({error:`O máximo que pode ser compensado nesse dia é ${day.missingMinutes} minuto(s).`});
      const balance=(await bankBalanceForEmployee(before.tenantId,before.employeeId)).accumulatedMinutes;
      if(balance<bankMinutes)return reply.code(400).send({error:`Saldo insuficiente no banco de horas. Disponível: ${Math.max(0,balance)} minuto(s).`});
      await prisma.bankHourAdjustment.create({data:{tenantId:before.tenantId,employeeId:before.employeeId,minutes:-bankMinutes,requestedMinutes:bankMinutes,reason:body.reviewReason||`Compensação da falta de ${date}`,effectiveAt:new Date(),kind:'COMPENSATION',status:'APPLIED',workDate:dateKeyToUtcNoon(date),actorUserId:request.user.userId}});
    }
    const item=await prisma.absenceJustification.update({where:{id},data:{status:body.status as AdjustmentStatus,reviewReason:body.reviewReason||null,useBankHours:bankMinutes>0,bankMinutesUsed:bankMinutes,reviewedAt:new Date(),reviewedByUserId:request.user.userId}});
    await persistEmployeeLedger(item.employeeId,item.tenantId,date,date);
    await prisma.auditEvent.create({data:{tenantId:item.tenantId,actorUserId:request.user.userId,action:body.status==='APPROVED'?'ABSENCE_JUSTIFICATION_APPROVED':'ABSENCE_JUSTIFICATION_REJECTED',entityType:'AbsenceJustification',entityId:item.id,beforeJson:{status:before.status},afterJson:{status:item.status,reviewReason:item.reviewReason,bankMinutesUsed:bankMinutes}}});
    await prisma.alert.updateMany({where:{tenantId:item.tenantId,employeeId:item.employeeId,code:'ABSENCE_JUSTIFICATION_REVIEW',resolved:false,description:{contains:item.id}},data:{resolved:true,resolvedAt:new Date()}});
    return item;
  });

  app.post('/admin/employees/:id/overtime-grants',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isAdmin(request.user.role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem liberar hora extra.'});
    const {id}=z.object({id:z.string()}).parse(request.params);const body=z.object({reason:z.string().trim().max(300).optional()}).parse(request.body??{});
    const employee=await prisma.employee.findFirst({where:{id,tenantId:request.user.tenantId,active:true,archivedAt:null},include:{shift:true,tenant:true}});if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    const tz=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';const workDate=localDateKey(new Date(),tz);const date=dateKeyToUtcNoon(workDate);
    const count=await prisma.overtimeGrant.count({where:{tenantId:employee.tenantId,employeeId:employee.id,workDate:date}});if(count>=2)return reply.code(409).send({error:'O limite de +2h extras para este colaborador neste dia já foi atingido.'});
    const grant=await prisma.overtimeGrant.create({data:{tenantId:employee.tenantId,employeeId:employee.id,workDate:date,minutes:60,actorUserId:request.user.userId,reason:body.reason||null}});
    const total=(count+1)*60;
    await prisma.auditEvent.create({data:{tenantId:employee.tenantId,actorUserId:request.user.userId,action:'OVERTIME_HOUR_GRANTED',entityType:'OvertimeGrant',entityId:grant.id,metadataJson:{employeeId:employee.id,employeeName:employee.name,workDate,addedMinutes:60,totalGrantedMinutes:total}}});
    return reply.code(201).send({ok:true,grantId:grant.id,workDate,addedMinutes:60,totalGrantedMinutes:total,remainingGrants:Math.max(0,2-(count+1))});
  });
}
