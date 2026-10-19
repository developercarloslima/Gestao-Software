import { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { ADMIN_ROLES, MANAGEMENT_ROLES, hasRole, JOURNEY_REVIEW_ROLES } from '../lib/access.js';
import { calculateEmployeeRange, getOrCreateRules, persistEmployeeLedger, scheduledMinutesForEmployeeDate } from '../services/time-engine.service.js';
import { addDaysKey, dateKeyToUtcNoon, localDateKey, weekdayKey } from '../lib/time.js';
import { calculateCompensation } from '../services/compensation.service.js';
import { tenantBrand } from '../services/brand-guide.service.js';
import { monthRange, timesheetAcceptanceStatus } from '../services/timesheet-acceptance.service.js';
import { refreshTenantBankMonth } from '../services/bank-hours-maintenance.service.js';
import { bankBalanceForEmployee, bankBalancesForEmployees } from '../services/bank-balance.service.js';
import { manualBankAdjustmentsForRange } from '../services/manual-bank-adjustments.service.js';
import { lateJustificationSummariesForRange } from '../services/late-justification.service.js';
import { pointAdjustmentSummariesForRange } from '../services/point-adjustment.service.js';

const dateRange = z.object({ from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) });

async function currentMonthRange(tenantId: string) {
  const tenant = await prisma.tenant.findUniqueOrThrow({ where: { id: tenantId } });
  const today = localDateKey(new Date(), tenant.timezone || 'America/Maceio');
  const [y,m] = today.split('-').map(Number);
  const from = `${y}-${String(m).padStart(2,'0')}-01`;
  const last = new Date(Date.UTC(y, m, 0)).getUTCDate();
  return { from, to: `${y}-${String(m).padStart(2,'0')}-${String(last).padStart(2,'0')}`, today };
}


function exactMonthForRange(from:string,to:string){
  const month=from.slice(0,7);
  if(!/^\d{4}-\d{2}$/.test(month)||from!==`${month}-01`)return null;
  try{return monthRange(month).to===to?month:null;}catch{return null;}
}

type CompensationScope='HOUR'|'DAY'|'WEEK'|'MONTH';
function compensationBounds(scope:CompensationScope,referenceDate:string){
  if(scope==='MONTH'){
    const bounds=monthRange(referenceDate.slice(0,7));
    return {from:bounds.from,to:bounds.to};
  }
  if(scope==='WEEK'){
    const offset:Record<string,number>={MON:0,TUE:-1,WED:-2,THU:-3,FRI:-4,SAT:-5,SUN:-6};
    const from=addDaysKey(referenceDate,offset[weekdayKey(referenceDate)]??0);
    return {from,to:addDaysKey(from,6)};
  }
  return {from:referenceDate,to:referenceDate};
}

export async function timeRoutes(app: FastifyInstance) {
  app.get('/time/me', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!request.user.employeeId) return reply.code(403).send({ error: 'Usuário sem colaborador' });
    const q = dateRange.partial().parse(request.query);
    const range = { ...(await currentMonthRange(request.user.tenantId)), ...q };
    const result = await calculateEmployeeRange(request.user.employeeId, request.user.tenantId, range.from, range.to);
    const acceptanceMonth=exactMonthForRange(range.from,range.to);
    const acceptance=acceptanceMonth?await timesheetAcceptanceStatus(request.user.tenantId,request.user.employeeId,acceptanceMonth):null;
    const [manualAdjustments,bankBalance,lateJustifications,pointAdjustments]=await Promise.all([
      manualBankAdjustmentsForRange(request.user.tenantId,request.user.employeeId,range.from,range.to),
      bankBalanceForEmployee(request.user.tenantId,request.user.employeeId,range.to),
      lateJustificationSummariesForRange(request.user.tenantId,request.user.employeeId,range.from,range.to),
      pointAdjustmentSummariesForRange(request.user.tenantId,request.user.employeeId,range.from,range.to)
    ]);
    const monthlyBalanceMinutes=result.totals.bankDeltaMinutes+manualAdjustments.reduce((sum,item)=>sum+item.minutes,0);
    // O colaborador pode gerar o próprio espelho com o mesmo layout documental da gestão.
    // Dados financeiros do RH nunca são expostos por esta rota.
    return {
      company: {
        id: result.employee.tenant.id,
        name: result.employee.tenant.name,
        legalName: result.employee.tenant.legalName ?? null,
        document: result.employee.tenant.document ?? null,
        timezone: result.employee.tenant.timezone,
        brand: tenantBrand(result.employee.tenant)
      },
      employee: {
        id: result.employee.id,
        name: result.employee.name,
        employeeNumber: result.employee.employeeNumber,
        cpfMasked: result.employee.cpfMasked ?? null,
        jobTitle: result.employee.jobTitle ?? null,
        admissionDate: result.employee.admissionDate ?? null,
        department: result.employee.department ? { id: result.employee.department.id, name: result.employee.department.name } : null,
        worksite: result.employee.worksite ? { id: result.employee.worksite.id, name: result.employee.worksite.name, address: result.employee.worksite.address, city: result.employee.worksite.city, stateCode: result.employee.worksite.stateCode } : null,
        shift: result.employee.shift ? { id: result.employee.shift.id, name: result.employee.shift.name, startTime: result.employee.shift.startTime, endTime: result.employee.shift.endTime, breakMinutes: result.employee.shift.breakMinutes } : null
      },
      days: result.days,
      totals: result.totals,
      from: range.from,
      to: range.to,
      generatedAt: new Date().toISOString(),
      acceptance,
      manualAdjustments,
      lateJustifications,
      pointAdjustments,
      bank:{monthlyBalanceMinutes,accumulatedBalanceMinutes:bankBalance.accumulatedMinutes}
    };
  });

  app.get('/time/employees/:id', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES) && !JOURNEY_REVIEW_ROLES.includes(request.user.role as any)) return reply.code(403).send({ error: 'Sem permissão' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const q = dateRange.partial().parse(request.query);
    const range = { ...(await currentMonthRange(request.user.tenantId)), ...q };
    if (request.user.role === 'SUPERVISOR' && request.user.employeeId) {
      const target = await prisma.employee.findFirst({ where: { id, tenantId: request.user.tenantId } });
      if (!target || (target.id !== request.user.employeeId && target.supervisorId !== request.user.employeeId)) return reply.code(403).send({ error: 'Colaborador fora da sua equipe' });
    }
    const result = await calculateEmployeeRange(id, request.user.tenantId, range.from, range.to);
    const acceptanceMonth=exactMonthForRange(range.from,range.to);
    const acceptance=acceptanceMonth?await timesheetAcceptanceStatus(request.user.tenantId,id,acceptanceMonth):null;
    const [manualAdjustments,bankBalance,lateJustifications,pointAdjustments]=await Promise.all([
      manualBankAdjustmentsForRange(request.user.tenantId,id,range.from,range.to),
      bankBalanceForEmployee(request.user.tenantId,id,range.to),
      lateJustificationSummariesForRange(request.user.tenantId,id,range.from,range.to),
      pointAdjustmentSummariesForRange(request.user.tenantId,id,range.from,range.to)
    ]);
    const monthlyBalanceMinutes=result.totals.bankDeltaMinutes+manualAdjustments.reduce((sum,item)=>sum+item.minutes,0);
    const compensation = hasRole(request.user.role, ADMIN_ROLES)
      ? calculateCompensation({ salaryCents: result.employee.salaryCents, monthlyHoursDivisor: result.rules.monthlyHoursDivisor, overtime50Percent: result.rules.overtime50Percent, overtime100Percent: result.rules.overtime100Percent, nightAdditionalPercent: result.rules.nightAdditionalPercent, bankHoursEnabled: result.rules.bankHoursEnabled, overtimeToBank: result.rules.overtimeToBank, totals: result.totals })
      : undefined;
    return {
      company: {
        id: result.employee.tenant.id,
        name: result.employee.tenant.name,
        legalName: result.employee.tenant.legalName ?? null,
        document: result.employee.tenant.document ?? null,
        timezone: result.employee.tenant.timezone,
        brand: tenantBrand(result.employee.tenant)
      },
      employee: {
        id: result.employee.id,
        name: result.employee.name,
        employeeNumber: result.employee.employeeNumber,
        cpfMasked: result.employee.cpfMasked ?? null,
        jobTitle: result.employee.jobTitle ?? null,
        admissionDate: result.employee.admissionDate ?? null,
        department: result.employee.department ? { id: result.employee.department.id, name: result.employee.department.name } : null,
        worksite: result.employee.worksite ? { id: result.employee.worksite.id, name: result.employee.worksite.name, address: result.employee.worksite.address, city: result.employee.worksite.city, stateCode: result.employee.worksite.stateCode } : null,
        shift: result.employee.shift ? { id: result.employee.shift.id, name: result.employee.shift.name, startTime: result.employee.shift.startTime, endTime: result.employee.shift.endTime, breakMinutes: result.employee.shift.breakMinutes } : null
      },
      days: result.days,
      totals: result.totals,
      ...(compensation ? { compensation } : {}),
      from: range.from,
      to: range.to,
      generatedAt: new Date().toISOString(),
      acceptance,
      manualAdjustments,
      lateJustifications,
      pointAdjustments,
      bank:{monthlyBalanceMinutes,accumulatedBalanceMinutes:bankBalance.accumulatedMinutes}
    };
  });

  app.post('/time/recalculate', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const body = z.object({ employeeId: z.string().optional(), from: z.string().regex(/^\d{4}-\d{2}-\d{2}$/), to: z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }).parse(request.body);
    let employeeIds: string[];
    if (body.employeeId) {
      if (request.user.role === 'SUPERVISOR' && request.user.employeeId) {
        const target = await prisma.employee.findFirst({ where: { id: body.employeeId, tenantId: request.user.tenantId } });
        if (!target || (target.id !== request.user.employeeId && target.supervisorId !== request.user.employeeId)) return reply.code(403).send({ error: 'Colaborador fora da sua equipe' });
      }
      employeeIds = [body.employeeId];
    } else {
      const where: any = { tenantId: request.user.tenantId, active: true, archivedAt: null };
      if (request.user.role === 'SUPERVISOR' && request.user.employeeId) where.OR = [{ id: request.user.employeeId }, { supervisorId: request.user.employeeId }];
      employeeIds = (await prisma.employee.findMany({ where, select: { id: true } })).map(x => x.id);
    }
    const results = [];
    for (const employeeId of employeeIds) {
      const r = await persistEmployeeLedger(employeeId, request.user.tenantId, body.from, body.to);
      results.push({ employeeId, totals: r.totals });
    }
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'TIME_RECALCULATED', entityType: 'TimeBalanceLedger', entityId: body.employeeId ?? 'ALL', metadataJson: { from: body.from, to: body.to, employees: employeeIds.length } } });
    return { ok: true, results };
  });

  app.get('/bank/me', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!request.user.employeeId) return reply.code(403).send({ error: 'Usuário sem colaborador' });
    const tenant=await prisma.tenant.findUniqueOrThrow({where:{id:request.user.tenantId},select:{timezone:true}});
    const today=localDateKey(new Date(),tenant.timezone||'America/Maceio');
    const [rows, manual, balance] = await Promise.all([
      prisma.timeBalanceLedger.findMany({ where: { tenantId: request.user.tenantId, employeeId: request.user.employeeId,workDate:{lte:dateKeyToUtcNoon(today)} }, orderBy: { workDate: 'desc' }, take: 120 }),
      prisma.bankHourAdjustment.findMany({ where: { tenantId: request.user.tenantId, employeeId: request.user.employeeId,status:{not:'CANCELLED'} }, orderBy: { effectiveAt: 'desc' }, take: 100 }),
      bankBalanceForEmployee(request.user.tenantId,request.user.employeeId,today)
    ]);
    // V83.91: o banco acumulado é a soma histórica dos saldos diários consolidados
    // (positivos e negativos), acrescida dos ajustes manuais e das compensações.
    // Cada dia continua independente; o acumulado é apenas a visão histórica.
    return { balanceMinutes:balance.accumulatedMinutes, accumulatedBalanceMinutes:balance.accumulatedMinutes, earnedExtraMinutes:balance.earnedExtraMinutes, reservedMinutes:balance.reservedMinutes, rows, manual };
  });

  app.get('/bank/overview', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const query=z.object({month:z.string().regex(/^\d{4}-\d{2}$/).optional()}).parse(request.query??{});
    // V83.89: cada dia possui seu próprio saldo. A competência mensal é apenas a soma
    // dos dias daquele mês; nenhum saldo diário é carregado artificialmente para o dia seguinte.
    const range=await refreshTenantBankMonth(request.user.tenantId,query.month,new Date(),{ensureWeekPolicy:true});
    const where:any={tenantId:request.user.tenantId,active:true,archivedAt:null};
    if(request.user.role==='SUPERVISOR'&&request.user.employeeId)where.OR=[{id:request.user.employeeId},{supervisorId:request.user.employeeId}];
    const employees=await prisma.employee.findMany({where,select:{id:true,name:true,employeeNumber:true},orderBy:{name:'asc'}});
    const employeeIds=employees.map(e=>e.id);
    const monthStart=dateKeyToUtcNoon(range.from),monthEnd=dateKeyToUtcNoon(range.to),fullMonthEnd=dateKeyToUtcNoon(range.monthTo);
    const effectiveFrom=new Date(`${range.from}T00:00:00.000Z`),effectiveTo=new Date(`${addDaysKey(range.monthTo,1)}T00:00:00.000Z`);
    const [bankBalances,monthLedgers,monthAdjustments]=await Promise.all([
      bankBalancesForEmployees(request.user.tenantId,employeeIds,range.today),
      prisma.timeBalanceLedger.findMany({where:{tenantId:request.user.tenantId,employeeId:{in:employeeIds},workDate:{gte:monthStart,lte:monthEnd}},orderBy:[{employeeId:'asc'},{workDate:'asc'}]}),
      prisma.bankHourAdjustment.findMany({where:{tenantId:request.user.tenantId,employeeId:{in:employeeIds},status:{not:'CANCELLED'},OR:[
        {kind:'MANUAL',status:'APPLIED',workDate:{gte:monthStart,lte:fullMonthEnd}},
        {kind:'MANUAL',status:'APPLIED',workDate:null,effectiveAt:{gte:effectiveFrom,lt:effectiveTo}},
        {kind:'COMPENSATION',workDate:{gte:monthStart,lte:fullMonthEnd}}
      ]},orderBy:[{workDate:'asc'},{effectiveAt:'asc'}]})
    ]);
    const numberOr=(value:unknown,fallback=0)=>typeof value==='number'&&Number.isFinite(value)?Math.round(value):fallback;
    type DayRow={date:string;scheduledMinutes:number;workedMinutes:number;regularMinutes:number;extraMinutes:number;rawDeficitMinutes:number;deficitMinutes:number;manualMinutes:number;balanceMinutes:number;compensatedMinutes:number;reservedMinutes:number;pendingReview:boolean;rejected:boolean;automaticAbsence:boolean;closedJourney:boolean;warnings:string[]};
    const dayMaps=new Map<string,Map<string,DayRow>>();
    const getMap=(employeeId:string)=>{let map=dayMaps.get(employeeId);if(!map){map=new Map();dayMaps.set(employeeId,map);}return map;};
    const blank=(date:string):DayRow=>({date,scheduledMinutes:0,workedMinutes:0,regularMinutes:0,extraMinutes:0,rawDeficitMinutes:0,deficitMinutes:0,manualMinutes:0,balanceMinutes:0,compensatedMinutes:0,reservedMinutes:0,pendingReview:false,rejected:false,automaticAbsence:false,closedJourney:false,warnings:[]});
    for(const row of monthLedgers){
      const date=localDateKey(row.workDate,'UTC');
      const details=(row.detailsJson&&typeof row.detailsJson==='object'&&!Array.isArray(row.detailsJson)?row.detailsJson:{}) as Record<string,unknown>;
      const bankPosted=details.bankPosted!==false;
      const currentDayOpen=date===range.today&&details.closedJourney!==true&&details.automaticAbsence!==true&&details.rejectedJourney!==true;
      const worked=bankPosted?numberOr(details.bankWorkedMinutes,row.workedMinutes):0;
      const regular=bankPosted?numberOr(details.bankRegularMinutes,Math.min(worked,row.scheduledMinutes)):0;
      const extra=bankPosted?numberOr(details.bankExtraMinutes,Math.max(0,row.bankDeltaMinutes)):0;
      const rawDeficit=!bankPosted||currentDayOpen?0:numberOr(details.bankDeficitMinutes,Math.max(0,row.scheduledMinutes-worked));
      const compensated=bankPosted?numberOr(details.bankCompensatedMinutes,Math.max(0,rawDeficit-row.missingMinutes)):0;
      const deficit=Math.max(0,rawDeficit-compensated);
      const item:DayRow={date,scheduledMinutes:row.scheduledMinutes,workedMinutes:worked,regularMinutes:regular,extraMinutes:extra,rawDeficitMinutes:rawDeficit,deficitMinutes:deficit,manualMinutes:0,balanceMinutes:bankPosted&&!currentDayOpen?numberOr(row.bankDeltaMinutes,extra-deficit):0,compensatedMinutes:compensated,reservedMinutes:0,pendingReview:Boolean(details.pendingReview),rejected:Boolean(details.rejectedJourney),automaticAbsence:Boolean(details.automaticAbsence),closedJourney:Boolean(details.closedJourney),warnings:Array.isArray(details.warnings)?details.warnings.filter((x):x is string=>typeof x==='string'):[]};
      getMap(row.employeeId).set(date,item);
    }
    for(const adj of monthAdjustments){
      const date=adj.workDate?localDateKey(adj.workDate,'UTC'):adj.effectiveAt.toISOString().slice(0,10);
      if(date<range.from||date>range.to)continue;
      const map=getMap(adj.employeeId),day=map.get(date)??blank(date);
      if(adj.kind==='MANUAL'&&adj.status==='APPLIED'){day.manualMinutes+=adj.minutes;day.balanceMinutes+=adj.minutes;}
      if(adj.kind==='COMPENSATION'&&adj.status==='APPLIED'&&day.compensatedMinutes===0)day.compensatedMinutes=Math.abs(adj.minutes);
      if(adj.kind==='COMPENSATION'&&adj.status==='PLANNED')day.reservedMinutes+=Math.max(0,adj.requestedMinutes??0);
      map.set(date,day);
    }
    const items=employees.map(employee=>{
      const days=[...(dayMaps.get(employee.id)?.values()??[])].sort((a,b)=>a.date.localeCompare(b.date));
      const scheduledMinutes=days.reduce((s,d)=>s+d.scheduledMinutes,0);
      const workedMinutes=days.reduce((s,d)=>s+d.workedMinutes,0);
      const normalMinutes=days.reduce((s,d)=>s+d.regularMinutes,0);
      const extraMinutes=days.reduce((s,d)=>s+d.extraMinutes,0);
      const rawDeficitMinutes=days.reduce((s,d)=>s+d.rawDeficitMinutes,0);
      const missingMinutes=days.reduce((s,d)=>s+d.deficitMinutes,0);
      const manualMinutes=days.reduce((s,d)=>s+d.manualMinutes,0);
      const monthBalanceMinutes=days.reduce((s,d)=>s+d.balanceMinutes,0);
      const compensatedMinutes=days.reduce((s,d)=>s+d.compensatedMinutes,0);
      const monthReservedMinutes=days.reduce((s,d)=>s+d.reservedMinutes,0);
      const bank=bankBalances.get(employee.id)??{earnedExtraMinutes:0,appliedAdjustmentMinutes:0,reservedMinutes:0,accumulatedMinutes:0};
      return {...employee,month:range.month,scheduledMinutes,workedMinutes,normalMinutes,extraMinutes,rawDeficitMinutes,missingMinutes,manualMinutes,monthBalanceMinutes,monthNetMinutes:monthBalanceMinutes,compensatedMinutes,monthReservedMinutes,reservedMinutes:bank.reservedMinutes,earnedExtraMinutes:bank.earnedExtraMinutes,accumulatedBalanceMinutes:bank.accumulatedMinutes,balanceMinutes:bank.accumulatedMinutes,days};
    });
    return {month:range.month,from:range.from,to:range.to,updatedAt:new Date().toISOString(),autoRefreshed:true,items};
  });

  app.post('/bank/adjustments', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Sem permissão' });
    const rules=await getOrCreateRules(request.user.tenantId);
    if(!rules.bankHoursEnabled||!rules.overtimeToBank)return reply.code(400).send({error:'Esta empresa está configurada para pagamento de hora extra, não para banco de horas.'});
    const parsed=z.object({
      employeeId:z.string().trim().min(1),
      workDate:z.string().regex(/^\d{4}-\d{2}-\d{2}$/,'Informe o dia de referência.'),
      minutes:z.number().int().min(-100000).max(100000).refine(v=>v!==0,'Informe uma quantidade diferente de zero.'),
      reason:z.string().trim().min(5,'Informe o motivo com pelo menos 5 caracteres.').max(500)
    }).safeParse(request.body);
    if(!parsed.success)return reply.code(400).send({error:parsed.error.issues[0]?.message||'Dados do ajuste inválidos.'});
    const body=parsed.data;
    const employee=await prisma.employee.findFirst({where:{id:body.employeeId,tenantId:request.user.tenantId,active:true,archivedAt:null},select:{id:true,name:true,tenant:{select:{timezone:true}}}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado ou inativo.'});
    const today=localDateKey(new Date(),employee.tenant.timezone||'America/Maceio');
    if(body.workDate>today)return reply.code(400).send({error:'O dia de referência do ajuste não pode estar no futuro.'});
    const actor=await prisma.user.findFirst({where:{id:request.user.userId,tenantId:request.user.tenantId},select:{displayName:true,email:true}});
    const actorName=actor?.displayName?.trim()||actor?.email||'Administrador';
    const effectiveAt=new Date();
    const item=await prisma.$transaction(async tx=>{
      const created=await tx.bankHourAdjustment.create({data:{tenantId:request.user.tenantId,employeeId:employee.id,minutes:body.minutes,reason:body.reason,effectiveAt,workDate:dateKeyToUtcNoon(body.workDate),kind:'MANUAL',status:'APPLIED',actorUserId:request.user.userId}});
      await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'BANK_HOURS_MANUAL_ADJUSTMENT',entityType:'BankHourAdjustment',entityId:created.id,afterJson:{employeeId:employee.id,employeeName:employee.name,workDate:body.workDate,direction:body.minutes>0?'CREDIT':'DEBIT',minutes:body.minutes,reason:body.reason,responsibleUserId:request.user.userId,responsibleName:actorName,effectiveAt:created.effectiveAt.toISOString()}}});
      return created;
    });
    return reply.code(201).send({...item,responsibleName:actorName,referenceDate:body.workDate});
  });

  app.post('/bank/compensations', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, ADMIN_ROLES)) return reply.code(403).send({ error: 'Somente Administrador e Super Adm podem usar saldo do banco de horas para abonar déficit.' });
    const body=z.object({
      employeeId:z.string().trim().min(1),
      workDate:z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      scope:z.enum(['HOUR','DAY','WEEK','MONTH']).optional().default('HOUR'),
      minutes:z.number().int().min(1).max(100000).optional(),
      reason:z.string().trim().min(5).max(500)
    }).superRefine((value,ctx)=>{
      if(value.scope==='HOUR'&&!value.minutes)ctx.addIssue({code:z.ZodIssueCode.custom,path:['minutes'],message:'Informe os minutos que serão abonados.'});
    }).parse(request.body);
    const rules=await getOrCreateRules(request.user.tenantId);
    if(!rules.bankHoursEnabled||!rules.overtimeToBank)return reply.code(400).send({error:'Esta empresa não está usando banco de horas.'});
    const employee=await prisma.employee.findFirst({where:{id:body.employeeId,tenantId:request.user.tenantId,active:true,archivedAt:null},select:{id:true,name:true,tenant:{select:{timezone:true}}}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado ou inativo.'});
    const timeZone=employee.tenant.timezone||'America/Maceio';
    const policyDate=localDateKey(rules.bankPolicyV2At,timeZone);
    const today=localDateKey(new Date(),timeZone);
    const bounds=compensationBounds(body.scope as CompensationScope,body.workDate);

    type Target={workDate:string;minutes:number;status:'APPLIED'|'PLANNED'};
    let targets:Target[]=[];

    if(body.scope==='HOUR'){
      if(body.workDate<policyDate)return reply.code(400).send({error:`A compensação por banco de horas nesta modalidade vale a partir de ${policyDate}. O histórico anterior foi preservado sem recálculo.`});
      const scheduled=await scheduledMinutesForEmployeeDate(employee.id,request.user.tenantId,body.workDate);
      if(scheduled<=0)return reply.code(400).send({error:'Esse dia não possui jornada prevista para receber compensação de banco de horas.'});
      const requested=body.minutes??0;
      if(requested>scheduled)return reply.code(400).send({error:`A compensação não pode ultrapassar a jornada prevista de ${scheduled} minuto(s).`});
      const result=body.workDate<=today?await calculateEmployeeRange(employee.id,request.user.tenantId,body.workDate,body.workDate):null;
      const day=result?.days?.[0]??null;
      const finalized=Boolean(day&&(body.workDate<today||day.closedJourney||day.automaticAbsence||day.rejectedJourney));
      if(finalized&&requested>(day?.missingMinutes??0))return reply.code(400).send({error:`O máximo disponível para compensar nesse dia é ${day?.missingMinutes??0} minuto(s).`});
      targets=[{workDate:body.workDate,minutes:requested,status:finalized?'APPLIED':'PLANNED'}];
    }else{
      // Dia/semana/mês abonam automaticamente apenas déficits já consolidados. Para programar
      // uma compensação futura parcial, use o modo "Por hora/minutos".
      const rangeFrom=bounds.from<policyDate?policyDate:bounds.from;
      const rangeTo=bounds.to>today?today:bounds.to;
      if(rangeFrom>rangeTo)return reply.code(400).send({error:'Não há déficit consolidado nesse período para abonar.'});
      const result=await calculateEmployeeRange(employee.id,request.user.tenantId,rangeFrom,rangeTo);
      targets=result.days
        .filter(day=>Boolean(day.date<today||day.closedJourney||day.automaticAbsence||day.rejectedJourney))
        .filter(day=>(day.missingMinutes??0)>0)
        .map(day=>({workDate:day.date,minutes:Math.max(0,day.missingMinutes??0),status:'APPLIED' as const}));
      if(!targets.length)return reply.code(400).send({error:'Não existe falta/atraso consolidado nesse período para abonar.'});
    }

    const totalRequested=targets.reduce((sum,item)=>sum+item.minutes,0);
    const balance=await bankBalanceForEmployee(request.user.tenantId,employee.id,today);
    const available=balance.accumulatedMinutes;
    if(available<totalRequested)return reply.code(400).send({error:`Saldo insuficiente. Necessário: ${totalRequested} minuto(s). Disponível para uso: ${Math.max(0,available)} minuto(s).`});

    const created=await prisma.$transaction(async tx=>{
      const rows=[];
      for(const target of targets){
        const row=await tx.bankHourAdjustment.create({data:{
          tenantId:request.user.tenantId,employeeId:employee.id,
          minutes:target.status==='APPLIED'?-target.minutes:0,
          requestedMinutes:target.minutes,reason:body.reason,effectiveAt:new Date(),kind:'COMPENSATION',status:target.status,
          workDate:dateKeyToUtcNoon(target.workDate),actorUserId:request.user.userId
        }});
        rows.push(row);
      }
      await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'BANK_HOURS_COMPENSATION_SCOPE',entityType:'Employee',entityId:employee.id,afterJson:{employeeId:employee.id,employeeName:employee.name,scope:body.scope,referenceDate:body.workDate,totalMinutes:totalRequested,reason:body.reason,targets:targets.map(t=>({workDate:t.workDate,minutes:t.minutes,status:t.status})),balanceAvailableBeforeMinutes:available}}});
      return rows;
    });

    for(const target of targets){
      if(target.status!=='APPLIED')continue;
      try{await persistEmployeeLedger(employee.id,request.user.tenantId,target.workDate,target.workDate);}
      catch(error){request.log.error({error,employeeId:employee.id,workDate:target.workDate},'bank compensation saved but ledger refresh failed');}
    }
    return reply.code(201).send({ok:true,scope:body.scope,employeeId:employee.id,referenceDate:body.workDate,totalMinutes:totalRequested,targets,balanceBeforeMinutes:available,balanceAfterMinutes:available-totalRequested,ids:created.map(x=>x.id)});
  });

  app.delete('/bank/compensations/:id',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!hasRole(request.user.role,ADMIN_ROLES))return reply.code(403).send({error:'Sem permissão'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const item=await prisma.bankHourAdjustment.findFirst({where:{id,tenantId:request.user.tenantId,kind:'COMPENSATION',status:{not:'CANCELLED'}}});
    if(!item)return reply.code(404).send({error:'Compensação não encontrada ou já cancelada.'});
    await prisma.$transaction(async tx=>{
      await tx.bankHourAdjustment.update({where:{id},data:{status:'CANCELLED',minutes:0}});
      await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'BANK_HOURS_COMPENSATION_CANCELLED',entityType:'BankHourAdjustment',entityId:id,beforeJson:{status:item.status,minutes:item.minutes,requestedMinutes:item.requestedMinutes},afterJson:{status:'CANCELLED'}}});
    });
    if(item.workDate)await persistEmployeeLedger(item.employeeId,item.tenantId,localDateKey(item.workDate,'UTC'),localDateKey(item.workDate,'UTC'));
    return {ok:true};
  });

}
