import { PunchDecision, PunchType, Shift } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, localDateKey, weekdayKey, zonedDateTimeToUtc } from '../lib/time.js';
import { holidayForDate } from './holiday.service.js';
import { workDateKeyForInstant } from './time-engine.service.js';

type DaySchedule={enabled:boolean;startTime:string;endTime:string;breakMinutes:number};

// V83.74 — toda saída feita pelo colaborador exige selfie.
// Após 10 min do fim previsto o ponto trava e mantém apenas a saída manual com selfie.
// Ao completar 15 min sem saída, somente o servidor pode finalizar automaticamente sem selfie.
export const MANUAL_CLOCK_OUT_TOLERANCE_MINUTES=10;
export const SYSTEM_AUTO_CLOSE_AFTER_MINUTES=15;
const ADMIN_ADJUSTMENT_SOURCES=new Set(['APPROVED_CORRECTION','APPROVED_ADDITION','APPROVED_CLOCK_IN_ADDITION']);
function isLivePresencePunch(p:{type:PunchType;source:string;evidence?:{selfieStorageKey?:string|null;latitude?:number|null;longitude?:number|null}|null}){
  if(p.source==='SYSTEM_AUTO_LOCK_NO_SELFIE')return p.type===PunchType.CLOCK_OUT;
  if(p.source==='APPROVED_CLOCK_IN_ADDITION')return p.type===PunchType.CLOCK_IN;
  if(ADMIN_ADJUSTMENT_SOURCES.has(p.source))return false;
  return Boolean(p.evidence?.selfieStorageKey&&p.evidence.latitude!=null&&p.evidence.longitude!=null);
}

function scheduleFromJson(raw:unknown,key:string):DaySchedule|null|undefined{
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||!Object.keys(raw as object).length)return undefined;
  const candidate=(raw as Record<string,unknown>)[key];
  if(!candidate||typeof candidate!=='object'||Array.isArray(candidate))return null;
  const d=candidate as Record<string,unknown>;
  if(typeof d.enabled==='boolean'&&typeof d.startTime==='string'&&typeof d.endTime==='string'&&typeof d.breakMinutes==='number')return {enabled:d.enabled,startTime:d.startTime,endTime:d.endTime,breakMinutes:d.breakMinutes};
  return null;
}
function resolveDaySchedule(shift:Shift|null|undefined,key:string,employeeSchedule?:unknown):DaySchedule|null{
  const personal=scheduleFromJson(employeeSchedule,key);if(personal!==undefined)return personal?.enabled?personal:null;
  if(!shift||!shift.active)return null;
  const detailed=scheduleFromJson(shift.daySchedules,key);if(detailed!==undefined)return detailed?.enabled?detailed:null;
  const days=Array.isArray(shift.scheduleDays)?shift.scheduleDays as string[]:[];if(!days.includes(key))return null;
  return {enabled:true,startTime:shift.startTime,endTime:shift.endTime,breakMinutes:shift.breakMinutes};
}
function endInstant(workDate:string,schedule:DaySchedule,timeZone:string){
  const overnight=schedule.endTime<=schedule.startTime;const endDate=overnight?addDaysKey(workDate,1):workDate;
  return zonedDateTimeToUtc(endDate,schedule.endTime,timeZone);
}
async function closedReason(dateKey:string,stateCode?:string|null){
  const holiday=holidayForDate(dateKey,stateCode);if(holiday)return {closed:true,reason:holiday.name,holiday};
  return {closed:false,reason:null,holiday:null};
}
async function previousScheduledDate(workDate:string,shift:Shift|null,employeeSchedule:unknown,stateCode?:string|null){
  for(let i=1;i<=10;i++){
    const key=addDaysKey(workDate,-i);const closed=await closedReason(key,stateCode);if(closed.closed)continue;
    if(resolveDaySchedule(shift,weekdayKey(key),employeeSchedule))return key;
  }
  return null;
}

export async function getPointLockState(tenantId:string,employeeId:string,instant=new Date()){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId,active:true},include:{shift:true,tenant:true,worksite:true}});
  if(!employee)throw Object.assign(new Error('Colaborador não encontrado ou inativo'),{statusCode:404});
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(instant,employee.shift,timeZone);
  const closed=await closedReason(workDate,employee.worksite?.stateCode);
  const schedule=resolveDaySchedule(employee.shift,weekdayKey(workDate),employee.workSchedule);
  if(closed.closed)return {workDate,timeZone,closedDay:true,closedReason:closed.reason,holiday:closed.holiday,scheduled:false,autoLockRequired:false,cutoffAt:null,autoCloseAt:null,autoFinalizeDue:false,warning:null,consecutiveAutoLocks:0};
  if(!schedule)return {workDate,timeZone,closedDay:true,closedReason:'Folga prevista na jornada',holiday:null,scheduled:false,autoLockRequired:false,cutoffAt:null,autoCloseAt:null,autoFinalizeDue:false,warning:null,consecutiveAutoLocks:0};
  const dayEnd=endInstant(workDate,schedule,timeZone);
  const overtimeGrants=await prisma.overtimeGrant.findMany({where:{tenantId,employeeId,workDate:{equals:new Date(`${workDate}T12:00:00.000Z`)}},select:{id:true,minutes:true,createdAt:true},orderBy:{createdAt:'asc'}});
  const overtimeExtensionMinutes=overtimeGrants.reduce((sum,g)=>sum+g.minutes,0);
  const cutoffAt=new Date(dayEnd.getTime()+(MANUAL_CLOCK_OUT_TOLERANCE_MINUTES+overtimeExtensionMinutes)*60_000);
  const autoCloseAt=new Date(dayEnd.getTime()+(SYSTEM_AUTO_CLOSE_AFTER_MINUTES+overtimeExtensionMinutes)*60_000);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone);const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const punches=await prisma.punch.findMany({where:{tenantId,employeeId,decision:{not:PunchDecision.BLOCKED},occurredAt:{gte:start,lt:end}},select:{type:true,occurredAt:true,source:true,evidence:{select:{selfieStorageKey:true,latitude:true,longitude:true}}},orderBy:{occurredAt:'asc'}});
  const today=punches.filter(p=>workDateKeyForInstant(p.occurredAt,employee.shift,timeZone)===workDate&&isLivePresencePunch(p));
  const hasClockIn=today.some(p=>p.type===PunchType.CLOCK_IN);const hasClockOut=today.some(p=>p.type===PunchType.CLOCK_OUT);
  const lastPunch=today.at(-1)??null;
  const journeyOpen=hasClockIn&&lastPunch?.type!==PunchType.CLOCK_OUT;
  const autoLockRequired=journeyOpen&&instant.getTime()>=cutoffAt.getTime();
  const autoFinalizeDue=journeyOpen&&instant.getTime()>=autoCloseAt.getTime();
  let consecutiveAutoLocks=0;let warning:string|null=null;
  if(autoLockRequired){
    consecutiveAutoLocks=1;
    const previousDate=await previousScheduledDate(workDate,employee.shift,employee.workSchedule,employee.worksite?.stateCode);
    if(previousDate){
      const prevStart=zonedDateTimeToUtc(addDaysKey(previousDate,-1),'00:00',timeZone),prevEnd=zonedDateTimeToUtc(addDaysKey(previousDate,2),'00:00',timeZone);
      const previousAuto=await prisma.punch.findFirst({where:{tenantId,employeeId,type:PunchType.CLOCK_OUT,decision:{not:PunchDecision.BLOCKED},source:{in:['WEB_PWA_AUTO_LOCK','SYSTEM_AUTO_LOCK_NO_SELFIE']},occurredAt:{gte:prevStart,lt:prevEnd}},select:{occurredAt:true}});
      if(previousAuto&&workDateKeyForInstant(previousAuto.occurredAt,employee.shift,timeZone)===previousDate)consecutiveAutoLocks=2;
    }
    const lockReason=overtimeExtensionMinutes>0
      ? `A jornada extra liberada de ${overtimeExtensionMinutes} minuto(s) chegou ao limite autorizado.`
      : `A tolerância manual de ${MANUAL_CLOCK_OUT_TOLERANCE_MINUTES} minutos foi atingida.`;
    warning=consecutiveAutoLocks>=2
      ? `Atenção: esta é a segunda jornada consecutiva que atingiu a trava automática. ${lockReason} Até completar ${SYSTEM_AUTO_CLOSE_AFTER_MINUTES} minutos após o fim previsto, a saída manual continua exigindo selfie. Depois disso o sistema finalizará automaticamente sem selfie e enviará a jornada para revisão.`
      : `${lockReason} O ponto está travado apenas para finalização. Até completar ${SYSTEM_AUTO_CLOSE_AFTER_MINUTES} minutos após o fim previsto, o colaborador só pode finalizar com selfie. Depois disso o sistema finalizará automaticamente sem selfie e enviará a jornada para revisão.`;
  }
  return {workDate,timeZone,closedDay:false,closedReason:null,holiday:null,scheduled:true,schedule,scheduledEndAt:dayEnd,cutoffAt,autoCloseAt,autoLockRequired,autoFinalizeDue,warning,consecutiveAutoLocks,overtimeExtensionMinutes,overtimeGrantCount:overtimeGrants.length,journeyOpen,hasClockOut};
}
