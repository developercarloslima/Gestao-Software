import { FastifyInstance } from 'fastify';
import { PunchDecision, PunchType, Role } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, localDateKey, zonedDateTimeToUtc } from '../lib/time.js';
import { workDateKeyForInstant } from '../services/time-engine.service.js';

const dateQuerySchema=z.object({date:z.string().regex(/^\d{4}-\d{2}-\d{2}$/).optional()});
function canViewCounters(role:string){return role===Role.SUPER_ADMIN||role===Role.ADMIN;}

const ADMIN_ADJUSTMENT_SOURCES=new Set(['APPROVED_CORRECTION','APPROVED_ADDITION','APPROVED_CLOCK_IN_ADDITION']);
function isLivePresencePunch(p:{type:PunchType;source:string;evidence?:{selfieStorageKey?:string|null;latitude?:number|null;longitude?:number|null}|null}){
  if(p.source==='SYSTEM_AUTO_LOCK_NO_SELFIE')return p.type===PunchType.CLOCK_OUT;
  if(p.source==='APPROVED_CLOCK_IN_ADDITION')return p.type===PunchType.CLOCK_IN;
  if(ADMIN_ADJUSTMENT_SOURCES.has(p.source))return false;
  return Boolean(p.evidence?.selfieStorageKey&&p.evidence.latitude!=null&&p.evidence.longitude!=null);
}

type TimerPunch={type:PunchType;occurredAt:Date;source:string;evidence?:{selfieStorageKey?:string|null;latitude?:number|null;longitude?:number|null}|null};
type WorkTimerState='NOT_STARTED'|'WORKING'|'BREAK'|'PAUSE'|'CLOSED';

function computeWorkdayCounters(rawPunches:TimerPunch[],now=new Date()){
  const punches=[...rawPunches].sort((a,b)=>a.occurredAt.getTime()-b.occurredAt.getTime());
  let state:WorkTimerState='NOT_STARTED',stateStartedAt:Date|null=null,sessionStartedAt:Date|null=null;
  let workSeconds=0,breakSeconds=0,pauseSeconds=0;
  let firstClockInAt:Date|null=null,lastPunchAt:Date|null=null;
  const addState=(until:Date)=>{
    if(!stateStartedAt)return;
    const seconds=Math.max(0,Math.floor((until.getTime()-stateStartedAt.getTime())/1000));
    if(state==='WORKING')workSeconds+=seconds;
    else if(state==='BREAK')breakSeconds+=seconds;
    else if(state==='PAUSE')pauseSeconds+=seconds;
  };
  for(const punch of punches){
    lastPunchAt=punch.occurredAt;
    if(punch.type===PunchType.CLOCK_IN){
      if(sessionStartedAt)addState(punch.occurredAt);
      sessionStartedAt=punch.occurredAt;firstClockInAt??=punch.occurredAt;state='WORKING';stateStartedAt=punch.occurredAt;
    }else if(punch.type===PunchType.BREAK_START&&sessionStartedAt){
      addState(punch.occurredAt);state='BREAK';stateStartedAt=punch.occurredAt;
    }else if(punch.type===PunchType.BREAK_END&&sessionStartedAt){
      addState(punch.occurredAt);state='WORKING';stateStartedAt=punch.occurredAt;
    }else if(punch.type===PunchType.PAUSE_START&&sessionStartedAt){
      addState(punch.occurredAt);state='PAUSE';stateStartedAt=punch.occurredAt;
    }else if(punch.type===PunchType.PAUSE_END&&sessionStartedAt){
      addState(punch.occurredAt);state='WORKING';stateStartedAt=punch.occurredAt;
    }else if(punch.type===PunchType.CLOCK_OUT&&sessionStartedAt){
      addState(punch.occurredAt);
      sessionStartedAt=null;stateStartedAt=null;state='CLOSED';
    }
  }
  if(sessionStartedAt){
    const effectiveNow=now.getTime()>sessionStartedAt.getTime()?now:sessionStartedAt;
    addState(effectiveNow);
  }
  // V83.89: o total entre entrada e saída não é exposto. Estes contadores são
  // exclusivamente gerenciais e não participam da apuração oficial do ponto.
  return {workSeconds,breakSeconds,pauseSeconds,state,firstClockInAt,lastPunchAt};
}

async function loadEmployeeLivePunches(tenantId:string,employeeId:string,shift:any,timeZone:string,workDate:string){
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone),end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const punches=await prisma.punch.findMany({where:{tenantId,employeeId,decision:{not:PunchDecision.BLOCKED},occurredAt:{gte:start,lt:end}},select:{type:true,occurredAt:true,source:true,evidence:{select:{selfieStorageKey:true,latitude:true,longitude:true}}},orderBy:{occurredAt:'asc'}});
  return punches.filter(row=>workDateKeyForInstant(row.occurredAt,shift,timeZone)===workDate&&isLivePresencePunch(row));
}

export async function monitoringRoutes(app:FastifyInstance){
  // V83.87: o antigo monitoramento de tela foi desativado. Esta rota mantém somente
  // contadores derivados das marcações oficiais da jornada; nenhum vídeo é capturado.
  app.get('/monitoring/me/today',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!request.user.employeeId)return reply.code(403).send({error:'Esta conta não possui jornada vinculada.'});
    const employee=await prisma.employee.findFirst({where:{id:request.user.employeeId,tenantId:request.user.tenantId},include:{shift:true,tenant:true}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado.'});
    const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
    const workDate=workDateKeyForInstant(new Date(),employee.shift,timeZone);
    const generatedAt=new Date();
    const punches=await loadEmployeeLivePunches(request.user.tenantId,request.user.employeeId,employee.shift,timeZone,workDate);
    const timers=computeWorkdayCounters(punches,generatedAt);
    return {workDate,timeZone,generatedAt,timers,screenRecordingEnabled:false};
  });

  app.get('/admin/time-counters/today',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!canViewCounters(request.user.role))return reply.code(403).send({error:'Os contadores de jornada são exclusivos do Administrador e Super Adm.'});
    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{timezone:true}});if(!tenant)return reply.code(404).send({error:'Empresa não encontrada.'});
    const query=dateQuerySchema.parse(request.query??{}),workDate=query.date??localDateKey(new Date(),tenant.timezone||'America/Maceio');
    const employeeRows=await prisma.employee.findMany({where:{tenantId:request.user.tenantId,active:true,archivedAt:null},include:{shift:true,department:{select:{name:true}},worksite:{select:{name:true}},user:{select:{role:true}}},orderBy:{name:'asc'}});
    const employees=employeeRows.filter(employee=>employee.user?.role!==Role.SUPER_ADMIN);
    const timeZone=tenant.timezone||'America/Maceio';
    const queryStart=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone),queryEnd=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
    const allPunches=await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:{in:employees.map(e=>e.id)},decision:{not:PunchDecision.BLOCKED},occurredAt:{gte:queryStart,lt:queryEnd}},select:{employeeId:true,type:true,occurredAt:true,source:true,evidence:{select:{selfieStorageKey:true,latitude:true,longitude:true}}},orderBy:{occurredAt:'asc'}});
    const generatedAt=new Date(),todayKey=localDateKey(generatedAt,timeZone);
    const rows=employees.map(employee=>{
      const employeeTimeZone=employee.shift?.timezone||timeZone;
      const employeePunches=allPunches.filter(row=>row.employeeId===employee.id&&workDateKeyForInstant(row.occurredAt,employee.shift,employeeTimeZone)===workDate&&isLivePresencePunch(row));
      const counterNow=workDate===todayKey?generatedAt:(employeePunches.at(-1)?.occurredAt??generatedAt);
      const timers=computeWorkdayCounters(employeePunches,counterNow);
      return {id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,department:employee.department?.name??null,worksite:employee.worksite?.name??null,...timers};
    });
    return {workDate,timeZone,generatedAt,screenRecordingEnabled:false,employees:rows};
  });

  app.get('/admin/time-counters/employee/:employeeId',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!canViewCounters(request.user.role))return reply.code(403).send({error:'Os contadores de jornada são exclusivos do Administrador e Super Adm.'});
    const {employeeId}=z.object({employeeId:z.string()}).parse(request.params);const query=dateQuerySchema.parse(request.query??{});
    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{timezone:true}});if(!tenant)return reply.code(404).send({error:'Empresa não encontrada.'});
    const workDate=query.date??localDateKey(new Date(),tenant.timezone||'America/Maceio');
    const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId:request.user.tenantId},select:{id:true,name:true,employeeNumber:true,shift:true,department:{select:{name:true}},worksite:{select:{name:true}}}});if(!employee)return reply.code(404).send({error:'Colaborador não encontrado.'});
    const employeeTimeZone=employee.shift?.timezone||tenant.timezone||'America/Maceio';
    const punches=await loadEmployeeLivePunches(request.user.tenantId,employeeId,employee.shift,employeeTimeZone,workDate);
    const generatedAt=new Date(),todayKey=localDateKey(generatedAt,employeeTimeZone);
    const timers=computeWorkdayCounters(punches,workDate===todayKey?generatedAt:(punches.at(-1)?.occurredAt??generatedAt));
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'TIME_COUNTER_DAY_OPENED',entityType:'Employee',entityId:employee.id,metadataJson:{employeeName:employee.name,workDate}}});
    return {workDate,timeZone:employeeTimeZone,generatedAt,screenRecordingEnabled:false,timers,employee:{id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,department:employee.department,worksite:employee.worksite}};
  });
}
