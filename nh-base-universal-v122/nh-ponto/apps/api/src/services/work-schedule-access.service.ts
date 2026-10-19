import { Shift } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, localDateKey, weekdayKey, zonedDateTimeToUtc } from '../lib/time.js';
import { holidayForDate } from './holiday.service.js';

export const EARLY_CLOCK_IN_MINUTES = 15;
export const LATE_CLOCK_IN_TOLERANCE_MINUTES = 15;

type DaySchedule = { enabled:boolean; startTime:string; endTime:string; breakMinutes:number };

function scheduleFromJson(raw:unknown,key:string):DaySchedule|null|undefined{
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||!Object.keys(raw as object).length)return undefined;
  const candidate=(raw as Record<string,unknown>)[key];
  if(!candidate||typeof candidate!=='object'||Array.isArray(candidate))return null;
  const day=candidate as Record<string,unknown>;
  if(typeof day.enabled==='boolean'&&typeof day.startTime==='string'&&typeof day.endTime==='string'&&typeof day.breakMinutes==='number'){
    return {enabled:day.enabled,startTime:day.startTime,endTime:day.endTime,breakMinutes:day.breakMinutes};
  }
  return null;
}

export function resolveEmployeeDaySchedule(shift:Shift|null|undefined,employeeSchedule:unknown,dateKey:string):DaySchedule|null{
  const key=weekdayKey(dateKey);
  const personal=scheduleFromJson(employeeSchedule,key);
  if(personal!==undefined)return personal?.enabled?personal:null;
  if(!shift||!shift.active)return null;
  const detailed=scheduleFromJson(shift.daySchedules,key);
  if(detailed!==undefined)return detailed?.enabled?detailed:null;
  const days=Array.isArray(shift.scheduleDays)?shift.scheduleDays as string[]:[];
  if(!days.includes(key))return null;
  return {enabled:true,startTime:shift.startTime,endTime:shift.endTime,breakMinutes:shift.breakMinutes};
}

export function employeeScheduleWindow(dateKey:string,schedule:DaySchedule,timeZone:string){
  const scheduledStartAt=zonedDateTimeToUtc(dateKey,schedule.startTime,timeZone);
  const overnight=schedule.endTime<=schedule.startTime;
  const endDate=overnight?addDaysKey(dateKey,1):dateKey;
  const scheduledEndAt=zonedDateTimeToUtc(endDate,schedule.endTime,timeZone);
  const clockInOpensAt=new Date(scheduledStartAt.getTime()-EARLY_CLOCK_IN_MINUTES*60_000);
  const clockInClosesAt=new Date(scheduledStartAt.getTime()+LATE_CLOCK_IN_TOLERANCE_MINUTES*60_000);
  return {scheduledStartAt,scheduledEndAt,clockInOpensAt,clockInClosesAt,overnight};
}

function formatTime(value:Date|null|undefined,timeZone:string){
  if(!value)return 'horário permitido';
  return new Intl.DateTimeFormat('pt-BR',{hour:'2-digit',minute:'2-digit',hour12:false,timeZone}).format(value);
}

function messageForBlocked(input:{code:string;schedule?:DaySchedule|null;clockInOpensAt?:Date|null;clockInClosesAt?:Date|null;nextStartAt?:Date|null;holidayName?:string|null;timeZone:string}){
  if(input.code==='NO_SCHEDULE')return 'Nenhuma jornada/escala de trabalho está vinculada ao seu cadastro. Procure um Administrador ou Super Adm.';
  if(input.code==='HOLIDAY')return `Hoje o ponto está fechado${input.holidayName?` por ${input.holidayName}`:' por feriado'}.`;
  if(input.code==='TOO_EARLY'&&input.schedule)return `Sua jornada começa às ${input.schedule.startTime}. A entrada é liberada ${EARLY_CLOCK_IN_MINUTES} minutos antes, a partir de ${formatTime(input.clockInOpensAt,input.timeZone)}.`;
  if(input.code==='LATE_JUSTIFICATION_REQUIRED'&&input.schedule)return `A marcação direta da entrada encerrou às ${formatTime(input.clockInClosesAt,input.timeZone)} (${LATE_CLOCK_IN_TOLERANCE_MINUTES} minutos após o início previsto de ${input.schedule.startTime}). Ao tentar iniciar a jornada, envie a justificativa solicitada para aprovação.`;
  if(input.code==='SHIFT_ENDED'&&input.schedule)return `A jornada prevista de hoje (${input.schedule.startTime}–${input.schedule.endTime}) já terminou. Se você trabalhou e deixou de registrar a entrada, solicite a inclusão pela aba Ajustes de jornada.`;
  return 'Hoje não é um dia de trabalho na escala vinculada. Não é permitido iniciar jornada fora dos dias escalados.';
}

export async function getClockInAccess(tenantId:string,employeeId:string,instant=new Date()){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId,active:true,archivedAt:null},include:{shift:true,tenant:true,worksite:true}});
  if(!employee)throw Object.assign(new Error('Colaborador não encontrado ou inativo'),{statusCode:404});
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const localToday=localDateKey(instant,timeZone);
  const candidates=[addDaysKey(localToday,-1),localToday];

  let todaySchedule:DaySchedule|null=null;
  let todayWindow:ReturnType<typeof employeeScheduleWindow>|null=null;
  let holidayName:string|null=null;

  for(const dateKey of candidates){
    const schedule=resolveEmployeeDaySchedule(employee.shift,employee.workSchedule,dateKey);
    if(!schedule)continue;
    const holiday=holidayForDate(dateKey,employee.worksite?.stateCode);
    if(holiday){if(dateKey===localToday)holidayName=holiday.name;continue;}
    const window=employeeScheduleWindow(dateKey,schedule,timeZone);
    if(dateKey===localToday){todaySchedule=schedule;todayWindow=window;}
    if(instant.getTime()>=window.clockInOpensAt.getTime()&&instant.getTime()<=window.clockInClosesAt.getTime()){
      return {allowed:true,code:'ALLOWED',message:null,workDate:dateKey,timeZone,earlyMinutes:EARLY_CLOCK_IN_MINUTES,lateMinutes:LATE_CLOCK_IN_TOLERANCE_MINUTES,schedule,clockInOpensAt:window.clockInOpensAt,clockInClosesAt:window.clockInClosesAt,scheduledStartAt:window.scheduledStartAt,scheduledEndAt:window.scheduledEndAt,nextStartAt:window.scheduledStartAt,lateJustificationRequired:false};
    }
  }

  let nextStartAt:Date|null=null;let nextWorkDate:string|null=null;let nextSchedule:DaySchedule|null=null;
  for(let offset=0;offset<=14;offset++){
    const key=addDaysKey(localToday,offset);const schedule=resolveEmployeeDaySchedule(employee.shift,employee.workSchedule,key);if(!schedule)continue;
    if(holidayForDate(key,employee.worksite?.stateCode))continue;
    const window=employeeScheduleWindow(key,schedule,timeZone);
    if(window.clockInOpensAt.getTime()>instant.getTime()){nextStartAt=window.clockInOpensAt;nextWorkDate=key;nextSchedule=schedule;break;}
  }

  let code='NOT_SCHEDULED';
  const hasPersonalSchedule=Boolean(employee.workSchedule&&typeof employee.workSchedule==='object'&&!Array.isArray(employee.workSchedule)&&Object.keys(employee.workSchedule as object).length);
  if(!employee.shift&&!hasPersonalSchedule)code='NO_SCHEDULE';
  else if(holidayName)code='HOLIDAY';
  else if(todaySchedule&&todayWindow&&instant.getTime()<todayWindow.clockInOpensAt.getTime())code='TOO_EARLY';
  else if(todaySchedule&&todayWindow&&instant.getTime()>todayWindow.clockInClosesAt.getTime()&&instant.getTime()<=todayWindow.scheduledEndAt.getTime())code='LATE_JUSTIFICATION_REQUIRED';
  else if(todaySchedule&&todayWindow&&instant.getTime()>todayWindow.scheduledEndAt.getTime())code='SHIFT_ENDED';
  const schedule=todaySchedule??nextSchedule;
  return {allowed:false,code,message:messageForBlocked({code,schedule:todaySchedule,clockInOpensAt:todayWindow?.clockInOpensAt??null,clockInClosesAt:todayWindow?.clockInClosesAt??null,nextStartAt,holidayName,timeZone}),workDate:localToday,timeZone,earlyMinutes:EARLY_CLOCK_IN_MINUTES,lateMinutes:LATE_CLOCK_IN_TOLERANCE_MINUTES,schedule:todaySchedule,clockInOpensAt:todayWindow?.clockInOpensAt??null,clockInClosesAt:todayWindow?.clockInClosesAt??null,scheduledStartAt:todayWindow?.scheduledStartAt??null,scheduledEndAt:todayWindow?.scheduledEndAt??null,nextStartAt,nextWorkDate,nextSchedule,lateJustificationRequired:code==='LATE_JUSTIFICATION_REQUIRED'};
}
