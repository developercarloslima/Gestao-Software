import { FastifyInstance } from 'fastify';
import type { Prisma } from '@prisma/client';
import { validateVacationPeriod, vacationEligibleFrom } from '../services/vacation-policy.js';
import { PunchDecision, Role } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { addDaysKey, dateKeyToUtcNoon, localDateKey, zonedDateTimeToUtc } from '../lib/time.js';
import { holidaysForYear, holidayForDate } from '../services/holiday.service.js';
import { resolveEmployeeDaySchedule } from '../services/work-schedule-access.service.js';
import { persistEmployeeLedger, workDateKeyForInstant } from '../services/time-engine.service.js';

const DATE=/^\d{4}-\d{2}-\d{2}$/;
const ALLOWED: Role[] = [Role.SUPER_ADMIN, Role.ADMIN];
function guard(role:Role){return ALLOWED.includes(role);}
function inRange(key:string,start:Date,end:Date){return key>=localDateKey(start,'UTC')&&key<=localDateKey(end,'UTC');}
function sameCity(a?:string|null,b?:string|null){return String(a??'').trim().toLocaleLowerCase('pt-BR')===String(b??'').trim().toLocaleLowerCase('pt-BR');}
function normalizePhone(value?:string|null){return String(value??'').replace(/\D/g,'');}
async function tenantToday(tenantId:string){const row=await prisma.tenant.findUnique({where:{id:tenantId},select:{timezone:true}});return localDateKey(new Date(),row?.timezone||'America/Maceio');}

// ADM only manages their own worksite and direct team; Super Adm sees selected tenant.
// No employee link => no permission to manage other people's vacations by default.
async function vacationEmployeeScope(tenantId:string,role:Role,employeeId?:string|null):Promise<Prisma.EmployeeWhereInput>{
  const base:Prisma.EmployeeWhereInput={tenantId,archivedAt:null};
  if(role===Role.SUPER_ADMIN)return base;
  if(!employeeId)return {...base,id:'__NO_TEAM_LINK__'}; // Sem vínculo ao colaborador, não existe equipe autorizada
  const actor=await prisma.employee.findFirst({where:{id:employeeId,tenantId,archivedAt:null},select:{id:true,worksiteId:true}});
  if(!actor)return {...base,id:'__NO_TEAM_LINK__'}; // Impede acesso cruzado entre empresas
  return {...base,OR:[{id:actor.id},{supervisorId:actor.id},...(actor.worksiteId?[{worksiteId:actor.worksiteId}]:[])]};
}

export async function timeOffRoutes(app:FastifyInstance){
  app.get('/time-off/dashboard',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!guard(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem gerenciar férias e feriados.'});
    const query=z.object({year:z.coerce.number().int().min(2020).max(2100).optional()}).parse(request.query??{});
    const tenantId=request.user.tenantId;
    const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{id:true,name:true,timezone:true}});
    if(!tenant)return reply.code(404).send({error:'Empresa não encontrada.'});
    const timeZone=tenant.timezone||'America/Maceio';
    const today=localDateKey(new Date(),timeZone);const year=query.year??Number(today.slice(0,4));
    const fromKey=addDaysKey(today,-120),fromDate=dateKeyToUtcNoon(fromKey),todayDate=dateKeyToUtcNoon(today);
    const managed=await vacationEmployeeScope(tenantId,request.user.role as Role,request.user.employeeId);
    const employees=await prisma.employee.findMany({where:managed,include:{user:{select:{email:true,contactEmail:true,role:true,active:true}},shift:true,worksite:true,department:true},orderBy:{name:'asc'}});
    const ids=employees.map(e=>e.id);
    const [punches,vacations,approvedAbsences,municipalHolidays,worksites]=await Promise.all([
      ids.length?prisma.punch.findMany({where:{tenantId,employeeId:{in:ids},decision:{not:PunchDecision.BLOCKED},occurredAt:{gte:new Date(fromDate.getTime()-24*60*60*1000),lte:new Date()}},select:{employeeId:true,occurredAt:true}}):Promise.resolve([]),
      ids.length?prisma.vacationPeriod.findMany({where:{tenantId,employeeId:{in:ids},startDate:{lte:dateKeyToUtcNoon(`${year+1}-12-31`)},endDate:{gte:fromDate}},select:{id:true,employeeId:true,startDate:true,endDate:true,note:true},orderBy:{startDate:'desc'}}):Promise.resolve([]),
      ids.length?prisma.absenceJustification.findMany({where:{tenantId,employeeId:{in:ids},status:'APPROVED',workDate:{gte:fromDate,lte:todayDate}},select:{employeeId:true,workDate:true}}):Promise.resolve([]),
      prisma.municipalHoliday.findMany({where:{tenantId,date:{gte:new Date(Math.min(fromDate.getTime(),Date.UTC(year,0,1,12))),lte:new Date(Math.max(todayDate.getTime(),Date.UTC(year,11,31,12)))}},orderBy:[{date:'asc'},{city:'asc'},{name:'asc'}]}),
      prisma.worksite.findMany({where:{tenantId,active:true,archivedAt:null},select:{id:true,name:true,city:true,stateCode:true},orderBy:{name:'asc'}})
    ]);
    const vacationByEmployee=new Map<string,typeof vacations>();for(const v of vacations)vacationByEmployee.set(v.employeeId,[...(vacationByEmployee.get(v.employeeId)??[]),v]);
    const approvedByEmployee=new Map<string,Set<string>>();for(const a of approvedAbsences){const set=approvedByEmployee.get(a.employeeId)??new Set<string>();set.add(localDateKey(a.workDate,'UTC'));approvedByEmployee.set(a.employeeId,set);}
    const punchDates=new Map<string,Set<string>>();
    for(const p of punches){const employee=employees.find(e=>e.id===p.employeeId);if(!employee)continue;const tz=employee.shift?.timezone||timeZone;const key=workDateKeyForInstant(p.occurredAt,employee.shift,tz);const set=punchDates.get(p.employeeId)??new Set<string>();set.add(key);punchDates.set(p.employeeId,set);}
    const rows=employees.map(employee=>{
      const tz=employee.shift?.timezone||timeZone;const employeeVacations=vacationByEmployee.get(employee.id)??[];
      const currentVacation=employeeVacations.find(v=>inRange(today,v.startDate,v.endDate))??null;
      const worked=punchDates.get(employee.id)??new Set<string>();const justified=approvedByEmployee.get(employee.id)??new Set<string>();const admissionKey=employee.admissionDate?localDateKey(employee.admissionDate,'UTC'):null;let streak=0;
      if(employee.active){
        for(let offset=1;offset<=120;offset++){
          const key=addDaysKey(today,-offset);if(admissionKey&&key<admissionKey)break;const schedule=resolveEmployeeDaySchedule(employee.shift,employee.workSchedule,key);if(!schedule)continue;
          if(employeeVacations.some(v=>inRange(key,v.startDate,v.endDate)))break;
          if(holidayForDate(key,employee.worksite?.stateCode))continue;
          if(municipalHolidays.some(h=>localDateKey(h.date,'UTC')===key&&sameCity(h.city,employee.worksite?.city)&&(h.stateCode==null||!h.stateCode||String(h.stateCode).toUpperCase()===String(employee.worksite?.stateCode??'').toUpperCase())))continue;
          if(justified.has(key))break;
          if(worked.has(key))break;
          streak++;
        }
      }
      const status=!employee.active?'OFF':currentVacation?'VACATION':streak>=30?'ABSENT_30':'ACTIVE';
      return {id:employee.id,name:employee.name,admissionDate:employee.admissionDate?localDateKey(employee.admissionDate,'UTC'):null,eligibleFrom:vacationEligibleFrom(employee.admissionDate),employeeNumber:employee.employeeNumber,jobTitle:employee.jobTitle,active:employee.active,status,absenceStreak:streak,contactPhone:employee.contactPhone??null,contactEmail:employee.user?.contactEmail??null,username:employee.user?.email??null,department:employee.department?{id:employee.department.id,name:employee.department.name}:null,worksite:employee.worksite?{id:employee.worksite.id,name:employee.worksite.name,city:employee.worksite.city,stateCode:employee.worksite.stateCode}:null,currentVacation:currentVacation?{id:currentVacation.id,startDate:localDateKey(currentVacation.startDate,'UTC'),endDate:localDateKey(currentVacation.endDate,'UTC'),note:currentVacation.note}:null,recentVacations:employeeVacations.slice(0,3).map(v=>({id:v.id,startDate:localDateKey(v.startDate,'UTC'),endDate:localDateKey(v.endDate,'UTC'),note:v.note}))};
    });
    const states=[...new Set(worksites.map(w=>String(w.stateCode??'').toUpperCase()).filter(Boolean))];
    return {tenant,year,today,employees:rows,worksites,officialHolidays:holidaysForYear(year,states),municipalHolidays:municipalHolidays.filter(h=>localDateKey(h.date,'UTC').startsWith(`${year}-`)).map(h=>({...h,date:localDateKey(h.date,'UTC')}))};
  });

  app.post('/time-off/vacations',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!guard(request.user.role as Role))return reply.code(403).send({error:'Somente ADM e Super ADM podem programar férias.'});
    const body=z.object({employeeId:z.string().min(1),startDate:z.string().regex(DATE),endDate:z.string().regex(DATE),note:z.string().max(500).optional().nullable()}).parse(request.body);
    const where=await vacationEmployeeScope(request.user.tenantId,request.user.role as Role,request.user.employeeId);
    const employee=await prisma.employee.findFirst({where:{AND:[where,{id:body.employeeId,active:true}]},include:{shift:true,tenant:true}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado ou fora da sua equipe.'});
    let validated:ReturnType<typeof validateVacationPeriod>;
    try{validated=validateVacationPeriod(employee.admissionDate,body.startDate,body.endDate);}
    catch(error){return reply.code(400).send({error:error instanceof Error?error.message:'Período de férias inválido.'});}
    const start=dateKeyToUtcNoon(body.startDate),end=dateKeyToUtcNoon(body.endDate);
    // Reject conflict with existing punches/approved adjustments; don't silently rewrite attendance evidence.
    const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
    const from=zonedDateTimeToUtc(addDaysKey(body.startDate,-1),'00:00',timeZone);
    const to=zonedDateTimeToUtc(addDaysKey(body.endDate,2),'00:00',timeZone);
    const relevantPunches=await prisma.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:employee.id,occurredAt:{gte:from,lt:to},decision:{not:'BLOCKED'}},select:{occurredAt:true}});
    if(relevantPunches.some(p=>{const key=workDateKeyForInstant(p.occurredAt,employee.shift,timeZone);return key>=body.startDate&&key<=body.endDate;}))return reply.code(409).send({error:'Existem marcações neste período. Corrija a apuração antes de programar férias; registros anteriores não serão apagados.'});
    try{
      const vacation=await prisma.$transaction(async tx=>{
        await tx.$queryRaw`SELECT pg_advisory_xact_lock(hashtext(${employee.id}))::text AS acquired`;
        const lockedPunches=await tx.punch.findMany({where:{tenantId:request.user.tenantId,employeeId:employee.id,occurredAt:{gte:from,lt:to},decision:{not:PunchDecision.BLOCKED}},select:{occurredAt:true}});
        if(lockedPunches.some(p=>{const key=workDateKeyForInstant(p.occurredAt,employee.shift,timeZone);return key>=body.startDate&&key<=body.endDate;}))throw Object.assign(new Error('Há marcações no período. Verifique antes de programar férias.'),{statusCode:409});
        const overlap=await tx.vacationPeriod.findFirst({where:{tenantId:request.user.tenantId,employeeId:employee.id,startDate:{lte:end},endDate:{gte:start}}});
        if(overlap)throw Object.assign(new Error('Já existem férias programadas que coincidem com esse período.'),{statusCode:409});
        const item=await tx.vacationPeriod.create({data:{tenantId:request.user.tenantId,employeeId:employee.id,startDate:start,endDate:end,note:body.note?.trim()||null,createdByUserId:request.user.userId}});
        await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'VACATION_PERIOD_DEFINED',entityType:'Employee',entityId:employee.id,afterJson:{employeeName:employee.name,admissionDate:employee.admissionDate?.toISOString(),eligibleFrom:validated.eligibleFrom,startDate:body.startDate,endDate:body.endDate,days:validated.days,note:body.note??null}}});
        return item;
      });
      const today=await tenantToday(request.user.tenantId),recalcTo=body.endDate<today?body.endDate:today;
      if(body.startDate<=recalcTo)await persistEmployeeLedger(employee.id,request.user.tenantId,body.startDate,recalcTo);
      return reply.code(201).send({...vacation,startDate:body.startDate,endDate:body.endDate,days:validated.days,eligibleFrom:validated.eligibleFrom});
    }catch(error:any){if(error?.statusCode===409)return reply.code(409).send({error:error.message});throw error;}
  });

  app.delete('/time-off/vacations/:id',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!guard(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem remover períodos de férias.'});
    const {id}=z.object({id:z.string().min(1)}).parse(request.params);
    const managed=await vacationEmployeeScope(request.user.tenantId,request.user.role as Role,request.user.employeeId);
    const item=await prisma.vacationPeriod.findFirst({where:{id,tenantId:request.user.tenantId,employee:{is:managed}},include:{employee:{select:{id:true,name:true}}}});if(!item)return reply.code(404).send({error:'Período de férias não encontrado.'});
    await prisma.$transaction([prisma.vacationPeriod.delete({where:{id:item.id}}),prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'VACATION_PERIOD_REMOVED',entityType:'Employee',entityId:item.employee.id,beforeJson:{employeeName:item.employee.name,startDate:localDateKey(item.startDate,'UTC'),endDate:localDateKey(item.endDate,'UTC')}}})]);
    const from=localDateKey(item.startDate,'UTC'),endKey=localDateKey(item.endDate,'UTC'),today=await tenantToday(request.user.tenantId),to=endKey<today?endKey:today;if(from<=to)await persistEmployeeLedger(item.employee.id,request.user.tenantId,from,to);
    return {ok:true};
  });

  app.post('/time-off/holidays',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!guard(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem cadastrar feriados municipais.'});
    const body=z.object({date:z.string().regex(DATE),name:z.string().trim().min(2).max(150),city:z.string().trim().min(2).max(120),stateCode:z.string().trim().min(2).max(2).optional().nullable()}).parse(request.body);
    const worksite=await prisma.worksite.findFirst({where:{tenantId:request.user.tenantId,active:true,archivedAt:null,city:{equals:body.city,mode:'insensitive'},...(body.stateCode?{stateCode:{equals:body.stateCode,mode:'insensitive'}}:{})},select:{id:true}});if(!worksite)return reply.code(400).send({error:'A cidade informada não possui unidade ativa nesta empresa.'});
    const holiday=await prisma.municipalHoliday.create({data:{tenantId:request.user.tenantId,date:dateKeyToUtcNoon(body.date),name:body.name,city:body.city,stateCode:body.stateCode?.toUpperCase()||null,createdByUserId:request.user.userId}}).catch((e:any)=>{if(e?.code==='P2002')throw Object.assign(new Error('Este feriado municipal já está cadastrado.'),{statusCode:409});throw e;});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'MUNICIPAL_HOLIDAY_CREATED',entityType:'MunicipalHoliday',entityId:holiday.id,afterJson:{date:body.date,name:body.name,city:body.city,stateCode:body.stateCode??null}}});
    const affected=await prisma.employee.findMany({where:{tenantId:request.user.tenantId,archivedAt:null,worksite:{is:{city:{equals:body.city,mode:'insensitive'},...(body.stateCode?{stateCode:{equals:body.stateCode,mode:'insensitive'}}:{})}}},select:{id:true}});for(const e of affected)await persistEmployeeLedger(e.id,request.user.tenantId,body.date,body.date);
    return reply.code(201).send({...holiday,date:body.date});
  });

  app.delete('/time-off/holidays/:id',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!guard(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador e Super Adm podem excluir feriados municipais.'});
    const {id}=z.object({id:z.string().min(1)}).parse(request.params);const item=await prisma.municipalHoliday.findFirst({where:{id,tenantId:request.user.tenantId}});if(!item)return reply.code(404).send({error:'Feriado municipal não encontrado.'});
    await prisma.$transaction([prisma.municipalHoliday.delete({where:{id:item.id}}),prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'MUNICIPAL_HOLIDAY_REMOVED',entityType:'MunicipalHoliday',entityId:item.id,beforeJson:{date:localDateKey(item.date,'UTC'),name:item.name,city:item.city,stateCode:item.stateCode}}})]);
    const key=localDateKey(item.date,'UTC');const affected=await prisma.employee.findMany({where:{tenantId:request.user.tenantId,archivedAt:null,worksite:{is:{city:{equals:item.city,mode:'insensitive'}}}},select:{id:true}});for(const e of affected)await persistEmployeeLedger(e.id,request.user.tenantId,key,key);
    return {ok:true};
  });

  app.post('/time-off/employees/:id/contacted',{preHandler:[app.authenticate]},async(request,reply)=>{
    if(!guard(request.user.role as Role))return reply.code(403).send({error:'Sem permissão.'});const {id}=z.object({id:z.string().min(1)}).parse(request.params);const managed=await vacationEmployeeScope(request.user.tenantId,request.user.role as Role,request.user.employeeId);const employee=await prisma.employee.findFirst({where:{AND:[managed,{id}]},include:{user:{select:{contactEmail:true,email:true}}}});if(!employee)return reply.code(404).send({error:'Colaborador não encontrado.'});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'ABSENCE_CONTACT_REQUESTED',entityType:'Employee',entityId:employee.id,metadataJson:{employeeName:employee.name,contactPhone:employee.contactPhone??null,contactEmail:employee.user?.contactEmail??null}}});
    return {ok:true,phone:normalizePhone(employee.contactPhone),email:employee.user?.contactEmail??null,username:employee.user?.email??null};
  });
}
