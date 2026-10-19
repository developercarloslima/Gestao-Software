import { Punch, Shift, TimeRuleSettings } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, dateKeyToUtcNoon, listDateKeys, localDateKey, localTimeKey, weekdayKey, zonedDateTimeToUtc } from '../lib/time.js';
import { holidayForDate } from './holiday.service.js';

type Interval = { start: Date; end: Date };

type ShiftDaySchedule = { enabled:boolean; startTime:string; endTime:string; breakMinutes:number };

function clockMinutes(value:string){
  const [h,m]=value.split(':').map(Number);
  return h*60+m;
}

function scheduledMinutesFromClock(startTime:string,endTime:string,breakMinutes:number){
  const start=clockMinutes(startTime),end=clockMinutes(endTime);
  const span=end>start?end-start:(24*60-start)+end;
  return Math.max(0,span-Math.max(0,breakMinutes));
}

function scheduleFromJson(raw:unknown,dayKey:string):ShiftDaySchedule|null|undefined{
  if(!raw||typeof raw!=='object'||Array.isArray(raw)||!Object.keys(raw as object).length)return undefined;
  const candidate=(raw as Record<string,unknown>)[dayKey];
  if(!candidate||typeof candidate!=='object'||Array.isArray(candidate))return null;
  const day=candidate as Record<string,unknown>;
  if(typeof day.enabled==='boolean'&&typeof day.startTime==='string'&&typeof day.endTime==='string'&&typeof day.breakMinutes==='number'){
    return {enabled:day.enabled,startTime:day.startTime,endTime:day.endTime,breakMinutes:day.breakMinutes};
  }
  return null;
}

function detailedScheduleForDay(shift:Shift|null|undefined,dayKey:string,employeeSchedule?:unknown):ShiftDaySchedule|null{
  const personal=scheduleFromJson(employeeSchedule,dayKey);
  if(personal!==undefined)return personal?.enabled?personal:null;
  if(!shift||!shift.active)return null;
  const detailed=scheduleFromJson(shift.daySchedules,dayKey);
  if(detailed!==undefined)return detailed?.enabled?detailed:null;
  const scheduleDays=Array.isArray(shift.scheduleDays)?shift.scheduleDays as string[]:[];
  if(!scheduleDays.includes(dayKey))return null;
  return {enabled:true,startTime:shift.startTime,endTime:shift.endTime,breakMinutes:shift.breakMinutes};
}

export type DayCalculation = {
  date: string;
  scheduledMinutes: number;
  workedMinutes: number;
  regularMinutes: number;
  overtime50Minutes: number;
  overtime100Minutes: number;
  nightMinutes: number;
  missingMinutes: number;
  bankDeltaMinutes: number;
  breakMinutes: number;
  scheduledSeconds: number;
  workedSeconds: number;
  regularSeconds: number;
  overtime50Seconds: number;
  overtime100Seconds: number;
  nightSeconds: number;
  missingSeconds: number;
  bankDeltaSeconds: number;
  breakSeconds: number;
  openPunch: boolean;
  warnings: string[];
  punchCount: number;
  closedDay?: boolean;
  closedReason?: string | null;
  pendingReview?: boolean;
  automaticAbsence?: boolean;
  absenceJustification?: {id:string;status:string;documentType:string;submittedAt:Date;reviewReason?:string|null;useBankHours?:boolean;bankMinutesUsed?:number}|null;
  overtimeExtensionMinutes?: number;
  bankCreditMinutes?: number;
  bankCompensatedMinutes?: number;
  bankWorkedMinutes?: number;
  bankRegularMinutes?: number;
  bankExtraMinutes?: number;
  bankDeficitMinutes?: number;
  bankWorkedSeconds?: number;
  bankRegularSeconds?: number;
  bankExtraSeconds?: number;
  bankDeficitSeconds?: number;
  bankPolicyV2?: boolean;
  effectiveWorkSeconds?: number;
  closedJourney?: boolean;
  rejectedJourney?: boolean;
};

export async function getOrCreateRules(tenantId: string) {
  return prisma.timeRuleSettings.upsert({
    where: { tenantId }, update: {}, create: { tenantId }
  });
}

function secondsBetween(a: Date, b: Date) {
  return Math.max(0, Math.round((b.getTime() - a.getTime()) / 1000));
}

function minutesFromSeconds(seconds: number) {
  return Math.max(0, Math.round(seconds / 60));
}

function isOvernightShift(shift?: Shift | null) {
  return Boolean(shift && shift.endTime <= shift.startTime);
}

export function workDateKeyForInstant(instant: Date, shift: Shift | null | undefined, timeZone: string) {
  let key = localDateKey(instant, timeZone);
  if (isOvernightShift(shift) && localTimeKey(instant, timeZone) <= (shift?.endTime ?? '00:00')) {
    key = addDaysKey(key, -1);
  }
  return key;
}

function workDateForPunch(punch: Punch, shift: Shift | null | undefined, timeZone: string) {
  return workDateKeyForInstant(punch.occurredAt, shift, timeZone);
}

function buildIntervals(punches: Punch[]) {
  const intervals: Interval[] = [];
  const warnings: string[] = [];
  let open: Date | null = null;
  let lastBreakStart: Date | null = null;
  let lastPauseStart: Date | null = null;
  let breakSeconds = 0;

  for (const p of punches) {
    if (p.type === 'CLOCK_IN') {
      if (open) warnings.push('Existe uma nova entrada antes do fechamento da marcação anterior.');
      open = p.occurredAt;
    } else if (p.type === 'BREAK_START') {
      if (open) {
        intervals.push({ start: open, end: p.occurredAt });
        open = null;
      } else warnings.push('Início de intervalo sem período de trabalho aberto.');
      lastBreakStart = p.occurredAt;
    } else if (p.type === 'BREAK_END') {
      if (lastBreakStart) breakSeconds += secondsBetween(lastBreakStart, p.occurredAt);
      else warnings.push('Retorno de intervalo sem início correspondente.');
      lastBreakStart = null;
      open = p.occurredAt;
    } else if (p.type === 'PAUSE_START') {
      if (open) { intervals.push({ start: open, end: p.occurredAt }); open = null; }
      else warnings.push('Início de pausa sem período de trabalho aberto.');
      lastPauseStart = p.occurredAt;
    } else if (p.type === 'PAUSE_END') {
      if (!lastPauseStart) warnings.push('Retorno de pausa sem início correspondente.');
      lastPauseStart = null;
      open = p.occurredAt;
    } else if (p.type === 'CLOCK_OUT') {
      if (open) {
        intervals.push({ start: open, end: p.occurredAt });
        open = null;
      } else warnings.push('Saída sem período de trabalho aberto.');
    }
  }

  if (lastBreakStart) warnings.push('Intervalo sem retorno registrado.');
  if (lastPauseStart) warnings.push('Pausa sem retorno registrado.');
  if (open) warnings.push('Jornada possui período aberto sem saída.');
  return { intervals, warnings, breakSeconds, openPunch: Boolean(open) };
}

// V83.89: a apuração oficial é feita exclusivamente pelas marcações.
// Intervalo e pausa fecham o período trabalhado; o tempo entre entrada e saída
// sem descontar essas marcações não participa do banco de horas.

function overlapSeconds(aStart: Date, aEnd: Date, bStart: Date, bEnd: Date) {
  const start = Math.max(aStart.getTime(), bStart.getTime());
  const end = Math.min(aEnd.getTime(), bEnd.getTime());
  return end > start ? Math.round((end - start) / 1000) : 0;
}

function nightSeconds(intervals: Interval[], workDate: string, timeZone: string, rules: TimeRuleSettings) {
  let total = 0;
  for (const candidateDate of [addDaysKey(workDate, -1), workDate, addDaysKey(workDate, 1)]) {
    const start = zonedDateTimeToUtc(candidateDate, rules.nightStart, timeZone);
    const endDate = rules.nightEnd <= rules.nightStart ? addDaysKey(candidateDate, 1) : candidateDate;
    const end = zonedDateTimeToUtc(endDate, rules.nightEnd, timeZone);
    for (const interval of intervals) total += overlapSeconds(interval.start, interval.end, start, end);
  }
  return total;
}

export function calculateDay(date: string, punches: Punch[], shift: Shift | null | undefined, rules: TimeRuleSettings, timeZone: string, employeeSchedule?:unknown, closedReason?:string|null, overtimeExtensionMinutes=0, bankCompensationMinutes=0): DayCalculation {
  const dayKey=weekdayKey(date);
  const daySchedule=closedReason?null:detailedScheduleForDay(shift,dayKey,employeeSchedule);
  const isScheduled=Boolean(daySchedule?.enabled);
  const scheduledMinutes=isScheduled&&daySchedule
    ? scheduledMinutesFromClock(daySchedule.startTime,daySchedule.endTime,daySchedule.breakMinutes)
    : 0;
  const scheduledSeconds = scheduledMinutes * 60;
  // Regra fixa do Apontamento Digital: 10 minutos diários de tolerância, não editáveis.
  const toleranceSeconds = 10 * 60;

  let effectivePunches=[...punches];
  let scheduledEndForDay:Date|null=null;
  if(daySchedule){
    const overnight=daySchedule.endTime<=daySchedule.startTime;
    const endDate=overnight?addDaysKey(date,1):date;
    const scheduledEnd=zonedDateTimeToUtc(endDate,daySchedule.endTime,timeZone);
    scheduledEndForDay=scheduledEnd;
    const lockCutoff=new Date(scheduledEnd.getTime()+(toleranceSeconds+Math.max(0,overtimeExtensionMinutes*60))*1000);
    effectivePunches=effectivePunches.map(p=>{
      if(p.type==='CLOCK_OUT'&&['WEB_PWA_AUTO_LOCK','SYSTEM_AUTO_LOCK_NO_SELFIE'].includes(p.source)&&p.occurredAt.getTime()>lockCutoff.getTime())return {...p,occurredAt:lockCutoff} as Punch;
      return p;
    });
  }

  const ordered = effectivePunches.sort((a,b) => a.occurredAt.getTime() - b.occurredAt.getTime());
  const { intervals, warnings, breakSeconds, openPunch } = buildIntervals(ordered);
  const effectiveWorkSeconds = intervals.reduce((sum, i) => sum + secondsBetween(i.start, i.end), 0);
  // V83.89: "trabalhado" é a soma dos blocos entre as marcações: entrada→intervalo,
  // retorno→pausa/saída etc. Intervalo e pausa não contam como trabalho.
  const workedSeconds = effectiveWorkSeconds;

  // A saída automática não cria crédito artificial. Para o banco, somente o trecho
  // produzido pela marcação automática após o fim previsto é cortado.
  const bankPunches=ordered.map(p=>{
    if(p.type==='CLOCK_OUT'&&['WEB_PWA_AUTO_LOCK','SYSTEM_AUTO_LOCK_NO_SELFIE'].includes(p.source)&&scheduledEndForDay&&p.occurredAt.getTime()>scheduledEndForDay.getTime()){
      return {...p,occurredAt:scheduledEndForDay} as Punch;
    }
    return p;
  });
  const bankIntervals=buildIntervals(bankPunches).intervals;
  const bankWorkedSeconds=bankIntervals.reduce((sum,i)=>sum+secondsBetween(i.start,i.end),0);
  const bankPolicyV2Date=localDateKey(rules.bankPolicyV2At,timeZone);
  const bankPolicyV2=date>=bankPolicyV2Date;

  // V83.91: o banco de horas é apurado exclusivamente pelas marcações do próprio dia.
  // A escala fornece apenas a carga diária de referência. Não existe "tempo logado" na
  // apuração e o saldo de um dia jamais é usado para recalcular o dia seguinte.
  //
  // Exemplo: carga do dia 8h00; marcações somam 7h37 => saldo do dia -0h23.
  // Se as marcações somarem 8h25 => saldo do dia +0h25.
  // Intervalo e pausa ficam fora do tempo trabalhado porque possuem marcações próprias.
  let missingSeconds=0;
  if(!closedReason&&scheduledSeconds>0&&bankWorkedSeconds<scheduledSeconds){
    missingSeconds=scheduledSeconds-bankWorkedSeconds;
  }

  const regularSeconds = scheduledSeconds ? Math.min(workedSeconds, scheduledSeconds) : 0;
  const sunday = weekdayKey(date) === 'SUN';
  // Qualquer excedente efetivamente registrado pelas marcações compõe o saldo positivo
  // do dia. A autorização de extensão continua sendo uma regra operacional da jornada,
  // mas não substitui a apuração matemática feita pelas marcações efetivamente gravadas.
  const rawBankCreditSeconds=closedReason?0:(scheduledSeconds>0
    ? Math.max(0,bankWorkedSeconds-scheduledSeconds)
    : bankWorkedSeconds);
  const overtimeSeconds=rawBankCreditSeconds;
  const overtime100Seconds = sunday && rules.sundayOvertime100 ? overtimeSeconds : 0;
  const overtime50Seconds = overtimeSeconds - overtime100Seconds;
  const calculatedNightSeconds = nightSeconds(intervals, date, timeZone, rules);
  // Déficit também nasce exclusivamente da diferença entre a carga do dia e as
  // marcações daquele mesmo dia. Dias futuros não entram no ledger.
  const rawBankDeficitSeconds=closedReason||scheduledSeconds<=0?0:Math.max(0,scheduledSeconds-bankWorkedSeconds);
  // V83.83 — grandezas completas do banco de horas. O relatório mensal usa o
  // tempo reconhecido pelo banco, sem o trecho criado apenas pela saída automática.
  const bankRegularSeconds=closedReason?0:(scheduledSeconds>0?Math.min(bankWorkedSeconds,scheduledSeconds):0);
  const requestedCompensationSeconds=Math.max(0,bankCompensationMinutes)*60;
  const bankCompensatedSeconds=bankPolicyV2?Math.min(rawBankDeficitSeconds,requestedCompensationSeconds):0;
  const effectiveMissingSeconds=Math.max(0,rawBankDeficitSeconds-bankCompensatedSeconds);
  // V83.91: saldo diário = excedente pelas marcações - déficit pelas marcações.
  // A configuração de pagamento/banco continua sendo usada pela camada financeira,
  // porém o demonstrativo do banco não troca a matemática do dia por "tempo logado".
  const v2BankCreditSeconds=rawBankCreditSeconds;
  const bankDeltaSeconds = v2BankCreditSeconds-effectiveMissingSeconds;

  const workedMinutes = minutesFromSeconds(workedSeconds);
  const regularMinutes = minutesFromSeconds(regularSeconds);
  const overtime50Minutes = minutesFromSeconds(overtime50Seconds);
  const overtime100Minutes = minutesFromSeconds(overtime100Seconds);
  const calculatedNightMinutes = minutesFromSeconds(calculatedNightSeconds);
  const missingMinutes = minutesFromSeconds(effectiveMissingSeconds);
  const bankDeltaMinutes = Math.round(bankDeltaSeconds / 60);
  // Para o demonstrativo, o crédito representa sempre o excedente real reconhecido
  // pelo banco. O bankDelta continua preservando a política histórica do saldo acumulado.
  const bankCreditMinutes = minutesFromSeconds(v2BankCreditSeconds);
  const bankCompensatedMinutes = minutesFromSeconds(bankCompensatedSeconds);
  const bankWorkedMinutes = minutesFromSeconds(bankWorkedSeconds);
  const bankRegularMinutes = minutesFromSeconds(bankRegularSeconds);
  const bankExtraMinutes = minutesFromSeconds(rawBankCreditSeconds);
  const bankDeficitMinutes = minutesFromSeconds(rawBankDeficitSeconds);
  const breakMinutes = minutesFromSeconds(breakSeconds);

  const expectedBreakMinutes=isScheduled&&daySchedule?Math.max(0,daySchedule.breakMinutes):0;
  if(closedReason)warnings.push(`Dia fechado: ${closedReason}.`);
  if (scheduledSeconds > 0 && workedSeconds > 0 && expectedBreakMinutes > 0 && breakSeconds < expectedBreakMinutes * 60) warnings.push(`Intervalo abaixo da jornada prevista de ${expectedBreakMinutes} min.`);
  if (workedSeconds > rules.maxDailyMinutes * 60) warnings.push(`Jornada acima do parâmetro de ${rules.maxDailyMinutes} min/dia.`);
  if (ordered.length % 2 !== 0) warnings.push('Quantidade ímpar de marcações; revise a jornada.');
  if (punches.some(p=>p.source==='SYSTEM_AUTO_LOCK_NO_SELFIE')) warnings.push('Jornada finalizada automaticamente pelo sistema após completar 15 minutos sem saída manual; única exceção de saída sem selfie.');

  return {
    date, scheduledMinutes, workedMinutes, regularMinutes, overtime50Minutes, overtime100Minutes,
    nightMinutes: calculatedNightMinutes, missingMinutes, bankDeltaMinutes, breakMinutes,
    scheduledSeconds, workedSeconds, regularSeconds, overtime50Seconds, overtime100Seconds,
    nightSeconds: calculatedNightSeconds, missingSeconds:effectiveMissingSeconds, bankDeltaSeconds, breakSeconds,
    openPunch, warnings: [...new Set(warnings)], punchCount: ordered.length,closedJourney:ordered.some(p=>p.type==='CLOCK_OUT'),
    closedDay:Boolean(closedReason),closedReason:closedReason??null,overtimeExtensionMinutes,
    bankCreditMinutes,bankCompensatedMinutes,bankWorkedMinutes,bankRegularMinutes,bankExtraMinutes,bankDeficitMinutes,
    bankWorkedSeconds,bankRegularSeconds,bankExtraSeconds:rawBankCreditSeconds,bankDeficitSeconds:rawBankDeficitSeconds,bankPolicyV2,effectiveWorkSeconds
  };
}

export async function calculateEmployeeRange(employeeId: string, tenantId: string, from: string, to: string) {
  const employee = await prisma.employee.findFirst({
    where: { id: employeeId, tenantId }, include: { shift: true, tenant: true, worksite:true, department:true }
  });
  if (!employee) throw Object.assign(new Error('Colaborador não encontrado'), { statusCode: 404 });
  const rules = await getOrCreateRules(tenantId);
  const timeZone = employee.shift?.timezone || employee.tenant.timezone || 'America/Maceio';
  const startUtc = zonedDateTimeToUtc(addDaysKey(from, -1), '00:00', timeZone);
  const endUtc = zonedDateTimeToUtc(addDaysKey(to, 2), '00:00', timeZone);
  const [rawPunches, pendingPunches, blockedPunches, approvedAdjustments, absenceJustifications, overtimeGrants, bankCompensations] = await Promise.all([
    prisma.punch.findMany({
      where: { tenantId, employeeId, decision: 'APPROVED', occurredAt: { gte: startUtc, lt: endUtc } }, orderBy: { occurredAt: 'asc' }
    }),
    prisma.punch.findMany({
      where: { tenantId, employeeId, decision: 'REVIEW', occurredAt: { gte: startUtc, lt: endUtc } }, orderBy: { occurredAt: 'asc' }
    }),
    prisma.punch.findMany({
      where: { tenantId, employeeId, decision: 'BLOCKED', occurredAt: { gte: startUtc, lt: endUtc } }, orderBy: { occurredAt: 'asc' }
    }),
    prisma.adjustmentRequest.findMany({
      where: { tenantId, employeeId, status: 'APPROVED', targetPunchId: { not: null } },
      select: { targetPunchId: true }
    }),
    prisma.absenceJustification.findMany({
      where:{tenantId,employeeId,workDate:{gte:dateKeyToUtcNoon(from),lte:dateKeyToUtcNoon(to)}},
      select:{id:true,workDate:true,status:true,documentType:true,submittedAt:true,reviewReason:true,useBankHours:true,bankMinutesUsed:true}
    }),
    prisma.overtimeGrant.findMany({
      where:{tenantId,employeeId,workDate:{gte:dateKeyToUtcNoon(from),lte:dateKeyToUtcNoon(to)}},
      select:{workDate:true,minutes:true}
    }),
    prisma.bankHourAdjustment.findMany({
      where:{tenantId,employeeId,kind:'COMPENSATION',status:{not:'CANCELLED'},workDate:{gte:dateKeyToUtcNoon(from),lte:dateKeyToUtcNoon(to)}},
      select:{workDate:true,minutes:true,requestedMinutes:true,status:true}
    })
  ]);
  const superseded = new Set(approvedAdjustments.map(a => a.targetPunchId).filter((x): x is string => Boolean(x)));
  const punches = rawPunches.filter(p => !superseded.has(p.id));
  const buckets = new Map<string, Punch[]>();
  for (const p of punches) {
    const key = workDateForPunch(p, employee.shift, timeZone);
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key)!.push(p);
  }
  const pendingBuckets = new Map<string, Punch[]>();
  for (const p of pendingPunches) {
    const key = workDateForPunch(p, employee.shift, timeZone);
    if (!pendingBuckets.has(key)) pendingBuckets.set(key, []);
    pendingBuckets.get(key)!.push(p);
  }
  const blockedBuckets = new Map<string, Punch[]>();
  for (const p of blockedPunches) {
    const key = workDateForPunch(p, employee.shift, timeZone);
    if (!blockedBuckets.has(key)) blockedBuckets.set(key, []);
    blockedBuckets.get(key)!.push(p);
  }
  const absenceMap=new Map(absenceJustifications.map(j=>[localDateKey(j.workDate,'UTC'),j]));
  const overtimeMap=new Map<string,number>();
  for(const grant of overtimeGrants){const key=localDateKey(grant.workDate,'UTC');overtimeMap.set(key,(overtimeMap.get(key)??0)+grant.minutes);}
  const bankCompensationMap=new Map<string,number>();
  for(const item of bankCompensations){if(!item.workDate)continue;const key=localDateKey(item.workDate,'UTC');const value=item.status==='PLANNED'?Math.max(0,item.requestedMinutes??0):Math.abs(item.minutes);bankCompensationMap.set(key,(bankCompensationMap.get(key)??0)+value);}
  const now=new Date();
  const todayKey = localDateKey(now, timeZone);
  const days = listDateKeys(from, to).map(key => {
    // A escala define os dias trabalhados, inclusive domingos. Feriados permanecem fechados por política global.
    const holiday=holidayForDate(key,employee.worksite?.stateCode);
    const closedReason=holiday?.name??null;
    // Não transforma dias futuros em faltas. Em prévias do mês corrente, o
    // desconto só considera jornadas que já chegaram no calendário local.
    if (key > todayKey) return calculateDay(key, buckets.get(key) ?? [], null, rules, timeZone, undefined, closedReason,overtimeMap.get(key)??0,bankCompensationMap.get(key)??0);
    const approved=buckets.get(key)??[];
    const pending=pendingBuckets.get(key)??[];
    const blocked=blockedBuckets.get(key)??[];
    const rejectedJourney=approved.length===0&&pending.length===0&&blocked.some(p=>p.type==='CLOCK_OUT');
    // V83.86: uma jornada finalizada em REVIEW já entra provisoriamente no demonstrativo/banco.
    // Aprovar não muda o valor; reprovar substitui o trabalho do dia por déficit integral da jornada prevista.
    const calculationPunches=rejectedJourney?[]:(pending.length?[...approved,...pending]:approved);
    let calculated=calculateDay(key, calculationPunches, employee.shift, rules, timeZone, employee.workSchedule, closedReason,overtimeMap.get(key)??0,bankCompensationMap.get(key)??0);
    if(rejectedJourney){
      const compensated=Math.max(0,calculated.bankCompensatedMinutes??0);
      const rejectedDeficit=Math.max(0,calculated.scheduledMinutes-compensated);
      calculated={...calculated,workedMinutes:0,regularMinutes:0,overtime50Minutes:0,overtime100Minutes:0,workedSeconds:0,regularSeconds:0,overtime50Seconds:0,overtime100Seconds:0,missingMinutes:rejectedDeficit,missingSeconds:rejectedDeficit*60,bankDeltaMinutes:-rejectedDeficit,bankDeltaSeconds:-rejectedDeficit*60,bankCreditMinutes:0,bankWorkedMinutes:0,bankRegularMinutes:0,bankExtraMinutes:0,bankDeficitMinutes:calculated.scheduledMinutes,bankWorkedSeconds:0,bankRegularSeconds:0,bankExtraSeconds:0,bankDeficitSeconds:calculated.scheduledSeconds,closedJourney:true,rejectedJourney:true,automaticAbsence:false,warnings:[...new Set([...calculated.warnings,'Jornada reprovada: horas trabalhadas removidas e jornada prevista lançada como déficit.'])]};
    }
    const absence=absenceMap.get(key)??null;
    if(absence?.status==='APPROVED'&&!absence.useBankHours)calculated={...calculated,missingMinutes:0,missingSeconds:0,bankDeficitMinutes:0,bankDeficitSeconds:0,bankDeltaMinutes:calculated.bankCreditMinutes??0,bankDeltaSeconds:calculated.bankExtraSeconds??0,automaticAbsence:false,warnings:[...new Set([...calculated.warnings,'Falta/déficit abonado por justificativa aprovada.'])],absenceJustification:absence};
    else if(absence?.status==='APPROVED'&&absence.useBankHours)calculated={...calculated,automaticAbsence:false,warnings:[...new Set([...calculated.warnings,`Compensação aprovada com ${absence.bankMinutesUsed} min do banco de horas.`])],absenceJustification:absence};
    else if(absence)calculated={...calculated,absenceJustification:absence};
    // V83.96 — PunchDecision.REVIEW legado é tratado como jornada regular válida.
    // O fluxo de aprovação fica restrito a faltas e entradas atrasadas (AdjustmentRequest).
    // Isso também limpa a semântica de versões anteriores sem apagar marcações históricas.
    if(key===todayKey&&!closedReason&&!calculated.rejectedJourney&&calculated.scheduledSeconds>0&&calculated.punchCount===0){
      const schedule=detailedScheduleForDay(employee.shift,weekdayKey(key),employee.workSchedule);
      if(schedule){
        const overnight=schedule.endTime<=schedule.startTime;
        const endDate=overnight?addDaysKey(key,1):key;
        // Sem nenhuma entrada no dia, a falta é consolidada assim que termina o horário previsto.
        // A tolerância de saída não adia uma falta integral.
        const cutoff=zonedDateTimeToUtc(endDate,schedule.endTime,timeZone);
        if(now.getTime()<cutoff.getTime())return {...calculated,missingMinutes:0,missingSeconds:0,bankDeltaMinutes:0,bankDeltaSeconds:0,automaticAbsence:false};
      }
    }
    if(!closedReason&&!calculated.rejectedJourney&&calculated.scheduledSeconds>0&&calculated.punchCount===0&&calculated.missingSeconds>0){
      return {...calculated,warnings:[...new Set([...calculated.warnings,'Falta automática: nenhuma jornada aprovada registrada neste dia.'])],automaticAbsence:true};
    }
    return calculated;
  });
  const totals = days.reduce((acc, day) => {
    acc.scheduledMinutes += day.scheduledMinutes;
    acc.workedMinutes += day.workedMinutes;
    acc.regularMinutes += day.regularMinutes;
    acc.overtime50Minutes += day.overtime50Minutes;
    acc.overtime100Minutes += day.overtime100Minutes;
    acc.nightMinutes += day.nightMinutes;
    acc.missingMinutes += day.missingMinutes;
    acc.bankDeltaMinutes += day.bankDeltaMinutes;
    acc.scheduledSeconds += day.scheduledSeconds;
    acc.workedSeconds += day.workedSeconds;
    acc.regularSeconds += day.regularSeconds;
    acc.overtime50Seconds += day.overtime50Seconds;
    acc.overtime100Seconds += day.overtime100Seconds;
    acc.nightSeconds += day.nightSeconds;
    acc.missingSeconds += day.missingSeconds;
    acc.bankDeltaSeconds += day.bankDeltaSeconds;
    acc.bankCreditMinutes += day.bankCreditMinutes??0;
    acc.bankCompensatedMinutes += day.bankCompensatedMinutes??0;
    acc.warningCount += day.warnings.length;
    return acc;
  }, {
    scheduledMinutes:0, workedMinutes:0, regularMinutes:0, overtime50Minutes:0, overtime100Minutes:0,
    nightMinutes:0, missingMinutes:0, bankDeltaMinutes:0, scheduledSeconds:0, workedSeconds:0,
    regularSeconds:0, overtime50Seconds:0, overtime100Seconds:0, nightSeconds:0, missingSeconds:0,
    bankDeltaSeconds:0, bankCreditMinutes:0, bankCompensatedMinutes:0, warningCount:0
  });
  return { employee, rules, timeZone, days, totals };
}

export async function scheduledMinutesForEmployeeDate(employeeId:string,tenantId:string,date:string){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId},include:{shift:true,tenant:true,worksite:true}});
  if(!employee)throw Object.assign(new Error('Colaborador não encontrado'),{statusCode:404});
  const rules=await getOrCreateRules(tenantId);
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const closedReason=holidayForDate(date,employee.worksite?.stateCode)?.name??null;
  return calculateDay(date,[],employee.shift,rules,timeZone,employee.workSchedule,closedReason,0,0).scheduledMinutes;
}

export async function persistEmployeeLedger(employeeId: string, tenantId: string, from: string, to: string) {
  const result = await calculateEmployeeRange(employeeId, tenantId, from, to);
  const todayKey=localDateKey(new Date(),result.timeZone);
  for (const day of result.days) {
    if(day.date>todayKey)continue;
    const bankPosted=Boolean(day.closedJourney||day.automaticAbsence||day.rejectedJourney);
    const postedBankWorkedMinutes=bankPosted?(day.bankWorkedMinutes??day.workedMinutes):0;
    const postedBankRegularMinutes=bankPosted?(day.bankRegularMinutes??day.regularMinutes):0;
    const postedBankExtraMinutes=bankPosted?(day.bankExtraMinutes??day.bankCreditMinutes??0):0;
    const postedBankDeficitMinutes=bankPosted?(day.bankDeficitMinutes??day.missingMinutes):0;
    const postedBankWorkedSeconds=bankPosted?(day.bankWorkedSeconds??day.workedSeconds):0;
    const postedBankRegularSeconds=bankPosted?(day.bankRegularSeconds??day.regularSeconds):0;
    const postedBankExtraSeconds=bankPosted?(day.bankExtraSeconds??((day.bankExtraMinutes??0)*60)):0;
    const postedBankDeficitSeconds=bankPosted?(day.bankDeficitSeconds??((day.bankDeficitMinutes??0)*60)):0;
    const exactSeconds = {
      scheduledSeconds: day.scheduledSeconds, workedSeconds: day.workedSeconds, regularSeconds: day.regularSeconds,
      overtime50Seconds: day.overtime50Seconds, overtime100Seconds: day.overtime100Seconds,
      nightSeconds: day.nightSeconds, missingSeconds: day.missingSeconds, bankDeltaSeconds: day.bankDeltaSeconds,
      breakSeconds: day.breakSeconds, effectiveWorkSeconds: day.effectiveWorkSeconds??0
    };
    await prisma.timeBalanceLedger.upsert({
      where: { tenantId_employeeId_workDate: { tenantId, employeeId, workDate: dateKeyToUtcNoon(day.date) } },
      update: {
        scheduledMinutes: day.scheduledMinutes, workedMinutes: day.workedMinutes, regularMinutes: day.regularMinutes,
        overtime50Minutes: day.overtime50Minutes, overtime100Minutes: day.overtime100Minutes,
        nightMinutes: day.nightMinutes, missingMinutes: day.missingMinutes, bankDeltaMinutes: day.bankDeltaMinutes,
        detailsJson: { warnings: day.warnings, breakMinutes: day.breakMinutes, punchCount: day.punchCount, openPunch: day.openPunch, closedJourney:day.closedJourney??false, pendingReview:day.pendingReview??false, rejectedJourney:day.rejectedJourney??false, automaticAbsence:day.automaticAbsence??false, bankPosted, bankCreditMinutes:bankPosted?(day.bankCreditMinutes??0):0, bankCompensatedMinutes:bankPosted?(day.bankCompensatedMinutes??0):0, bankWorkedMinutes:postedBankWorkedMinutes, bankRegularMinutes:postedBankRegularMinutes, bankExtraMinutes:postedBankExtraMinutes, bankDeficitMinutes:postedBankDeficitMinutes, bankWorkedSeconds:postedBankWorkedSeconds, bankRegularSeconds:postedBankRegularSeconds, bankExtraSeconds:postedBankExtraSeconds, bankDeficitSeconds:postedBankDeficitSeconds, bankPolicyV2:day.bankPolicyV2??false, ...exactSeconds },
        calculatedAt: new Date()
      },
      create: {
        tenantId, employeeId, workDate: dateKeyToUtcNoon(day.date), scheduledMinutes: day.scheduledMinutes,
        workedMinutes: day.workedMinutes, regularMinutes: day.regularMinutes, overtime50Minutes: day.overtime50Minutes,
        overtime100Minutes: day.overtime100Minutes, nightMinutes: day.nightMinutes, missingMinutes: day.missingMinutes,
        bankDeltaMinutes: day.bankDeltaMinutes,
        detailsJson: { warnings: day.warnings, breakMinutes: day.breakMinutes, punchCount: day.punchCount, openPunch: day.openPunch, closedJourney:day.closedJourney??false, pendingReview:day.pendingReview??false, rejectedJourney:day.rejectedJourney??false, automaticAbsence:day.automaticAbsence??false, bankPosted, bankCreditMinutes:bankPosted?(day.bankCreditMinutes??0):0, bankCompensatedMinutes:bankPosted?(day.bankCompensatedMinutes??0):0, bankWorkedMinutes:postedBankWorkedMinutes, bankRegularMinutes:postedBankRegularMinutes, bankExtraMinutes:postedBankExtraMinutes, bankDeficitMinutes:postedBankDeficitMinutes, bankWorkedSeconds:postedBankWorkedSeconds, bankRegularSeconds:postedBankRegularSeconds, bankExtraSeconds:postedBankExtraSeconds, bankDeficitSeconds:postedBankDeficitSeconds, bankPolicyV2:day.bankPolicyV2??false, ...exactSeconds }
      }
    });
  }

  // Fecha reservas de banco somente quando a jornada realmente terminou (ou virou falta consolidada).
  // O valor aplicado nunca supera o déficit efetivo; sobra de uma reserva é liberada automaticamente.
  for(const day of result.days){
    const canSettle=day.date<todayKey||(day.date===todayKey&&(day.closedJourney||day.automaticAbsence));
    if(!canSettle)continue;
    const planned=await prisma.bankHourAdjustment.findMany({where:{tenantId,employeeId,kind:'COMPENSATION',status:'PLANNED',workDate:dateKeyToUtcNoon(day.date)},orderBy:{createdAt:'asc'}});
    if(!planned.length)continue;
    let remaining=Math.max(0,day.bankCompensatedMinutes??0);
    for(const item of planned){
      const requested=Math.max(0,item.requestedMinutes??0);
      const applied=Math.min(requested,remaining);
      remaining-=applied;
      await prisma.bankHourAdjustment.update({where:{id:item.id},data:{status:applied>0?'APPLIED':'CANCELLED',minutes:applied>0?-applied:0}});
      await prisma.auditEvent.create({data:{tenantId,action:applied>0?'BANK_HOURS_PLANNED_APPLIED':'BANK_HOURS_PLANNED_RELEASED',entityType:'BankHourAdjustment',entityId:item.id,metadataJson:{employeeId,workDate:day.date,requestedMinutes:requested,appliedMinutes:applied}}});
    }
  }
  return result;
}
