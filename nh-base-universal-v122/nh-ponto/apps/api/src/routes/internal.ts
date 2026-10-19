import crypto from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { readEncryptedImage } from '../services/biometric-storage.service.js';
import { addDaysKey, zonedDateTimeToUtc } from '../lib/time.js';
import { workDateKeyForInstant } from '../services/time-engine.service.js';

function authorized(request:any){
  const expected=String(process.env.INTERNAL_SERVICE_TOKEN??process.env.JWT_SECRET??'');
  const received=String(request.headers['x-nh-internal-token']??'');
  if(!expected||!received)return false;
  const a=Buffer.from(expected),b=Buffer.from(received);
  return a.length===b.length&&crypto.timingSafeEqual(a,b);
}

function maceioDayRange(){
  const date=new Intl.DateTimeFormat('en-CA',{timeZone:'America/Maceio',year:'numeric',month:'2-digit',day:'2-digit'}).format(new Date());
  return {date,start:new Date(`${date}T00:00:00-03:00`),end:new Date(`${date}T23:59:59.999-03:00`)};
}

async function resolveTenant(){
  const document=String(process.env.NH_PONTO_TENANT_DOCUMENT??'').replace(/\D/g,'');
  return prisma.tenant.findFirst({where:{document},select:{id:true,name:true,legalName:true,document:true,timezone:true}})
    ?? prisma.tenant.findFirst({orderBy:{createdAt:'asc'},select:{id:true,name:true,legalName:true,document:true,timezone:true}});
}


async function internalJourneyReview(tenantId:string,clockOutId:string){
  const clockOut=await prisma.punch.findFirst({where:{id:clockOutId,tenantId,decision:'REVIEW',type:'CLOCK_OUT'},include:{employee:{include:{shift:true,worksite:true}}}});
  if(!clockOut)return null;
  const employee=clockOut.employee;
  const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{timezone:true}});
  const timeZone=employee.shift?.timezone||tenant?.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(clockOut.occurredAt,employee.shift,timeZone);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone),end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const rows=await prisma.punch.findMany({where:{tenantId,employeeId:employee.id,decision:{not:'BLOCKED'},occurredAt:{gte:start,lt:end}},include:{evidence:true},orderBy:[{occurredAt:'asc'},{recordNumber:'asc'}]});
  const punches=rows.filter(p=>workDateKeyForInstant(p.occurredAt,employee.shift,timeZone)===workDate);
  const worksiteIds=[...new Set([employee.worksiteId,...punches.map(p=>p.evidence?.worksiteId)].filter((x):x is string=>Boolean(x)))];
  const worksites=worksiteIds.length?await prisma.worksite.findMany({where:{tenantId,id:{in:worksiteIds}},select:{id:true,name:true,address:true,city:true,stateCode:true,latitude:true,longitude:true}}):[];
  const worksiteMap=new Map(worksites.map(w=>[w.id,w]));
  const defaultWorksite=employee.worksiteId?worksiteMap.get(employee.worksiteId)??null:null;
  const overtimeGrants=await prisma.overtimeGrant.findMany({where:{tenantId,employeeId:employee.id,workDate:new Date(`${workDate}T12:00:00.000Z`)},include:{actor:{select:{displayName:true,email:true}}},orderBy:{createdAt:'asc'}});
  return {id:`${employee.id}:${workDate}`,reviewPunchId:clockOut.id,workDate,status:'PENDING_APPROVAL',timeZone,employee:{id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,jobTitle:employee.jobTitle,worksite:defaultWorksite},startedAt:punches.find(p=>p.type==='CLOCK_IN')?.occurredAt??null,finishedAt:[...punches].reverse().find(p=>p.type==='CLOCK_OUT')?.occurredAt??clockOut.occurredAt,punchCount:punches.length,overtime:{totalMinutes:overtimeGrants.reduce((sum,g)=>sum+g.minutes,0),grants:overtimeGrants.map(g=>({id:g.id,minutes:g.minutes,createdAt:g.createdAt,grantedBy:g.actor.displayName??g.actor.email,reason:g.reason}))},punches:punches.map(p=>({id:p.id,recordNumber:p.recordNumber.toString(),type:p.type,occurredAt:p.occurredAt,decision:p.decision,source:p.source,reviewReason:p.reviewReason,evidence:p.evidence?{latitude:p.evidence.latitude,longitude:p.evidence.longitude,accuracyM:p.evidence.accuracyM,selfieCapturedAt:p.evidence.selfieCapturedAt,hasSelfie:Boolean(p.evidence.selfieStorageKey&&!p.evidence.selfiePurgedAt)}:null,worksite:(p.evidence?.worksiteId?worksiteMap.get(p.evidence.worksiteId):defaultWorksite)??null}))};
}

export async function internalRoutes(app:FastifyInstance){
  // Integração servidor-a-servidor: não cria usuário nem sessão humana.
  // O backend principal autentica com X-NH-Internal-Token e chama somente
  // endpoints internos explicitamente permitidos.

  app.get('/internal/admin/overview',async(request:any,reply)=>{
    if(!authorized(request))return reply.code(401).send({error:'Credencial interna inválida'});
    const tenant=await resolveTenant();
    if(!tenant)return reply.code(404).send({error:'Empresa do Apontamento Digital não encontrada'});
    const day=maceioDayRange();
    const [employees,todayPunches,pendingPunches]=await Promise.all([
      prisma.employee.findMany({
        where:{tenantId:tenant.id,archivedAt:null},
        include:{
          user:{select:{email:true,contactEmail:true,role:true,active:true,mustChangePassword:true,onboardingCompletedAt:true,_count:{select:{webAuthnCredentials:true}}}},
          department:{select:{name:true,code:true}},shift:{select:{name:true,startTime:true,endTime:true,breakMinutes:true,dailyMinutes:true,weeklyMinutes:true,scheduleDays:true,daySchedules:true}},worksite:{select:{name:true}},supervisor:{select:{id:true,name:true,employeeNumber:true}},
          punches:{where:{decision:{not:'BLOCKED'}},orderBy:[{occurredAt:'desc'},{recordNumber:'desc'}],take:1,select:{id:true,type:true,occurredAt:true,decision:true,recordNumber:true,evidence:{select:{selfieStorageKey:true,selfiePurgedAt:true,platformBiometricVerified:true,webAuthnCredentialId:true}}}}
        },orderBy:{name:'asc'}
      }),
      prisma.punch.findMany({where:{tenantId:tenant.id,occurredAt:{gte:day.start,lte:day.end},decision:{not:'BLOCKED'}},select:{employeeId:true,type:true,occurredAt:true,decision:true},orderBy:{occurredAt:'asc'}}),
      prisma.punch.findMany({where:{tenantId:tenant.id,decision:'REVIEW',type:'CLOCK_OUT'},include:{employee:{select:{id:true,name:true,employeeNumber:true,jobTitle:true}},evidence:{select:{selfieStorageKey:true,selfiePurgedAt:true,selfieCapturedAt:true,latitude:true,longitude:true,accuracyM:true,worksiteId:true,platformBiometricVerified:true,webAuthnCredentialId:true}}},orderBy:{occurredAt:'desc'},take:150})
    ]);
    const todayMap=new Map<string,typeof todayPunches>();
    for(const punch of todayPunches){const list=todayMap.get(punch.employeeId)??[];list.push(punch);todayMap.set(punch.employeeId,list);}
    const rows=employees.map(employee=>{
      const today=todayMap.get(employee.id)??[];
      const first=today[0]??null,last=today[today.length-1]??null;
      return {
        id:employee.id,employeeNumber:employee.employeeNumber,name:employee.name,cpfMasked:employee.cpfMasked,jobTitle:employee.jobTitle,
        admissionDate:employee.admissionDate,salaryCents:employee.salaryCents,active:employee.active,workSchedule:employee.workSchedule,
        faceEnrollmentStatus:employee.faceEnrollmentStatus,faceEnrolledAt:employee.faceEnrolledAt,biometricLockedUntil:employee.biometricLockedUntil,
        username:employee.user?.email??null,email:employee.user?.contactEmail??null,role:employee.user?.role??null,userActive:employee.user?.active??false,mustChangePassword:employee.user?.mustChangePassword??false,
        onboardingCompletedAt:employee.user?.onboardingCompletedAt??null,webAuthnCredentials:employee.user?._count.webAuthnCredentials??0,
        department:employee.department?.name??null,departmentCode:employee.department?.code??null,worksite:employee.worksite?.name??null,
        supervisor:employee.supervisor??null,
        shift:employee.shift?{name:employee.shift.name,startTime:employee.shift.startTime,endTime:employee.shift.endTime,breakMinutes:employee.shift.breakMinutes,dailyMinutes:employee.shift.dailyMinutes,weeklyMinutes:employee.shift.weeklyMinutes,scheduleDays:employee.shift.scheduleDays,daySchedules:employee.shift.daySchedules}:null,
        lastPunch:employee.punches[0]?{id:employee.punches[0].id,type:employee.punches[0].type,occurredAt:employee.punches[0].occurredAt,decision:employee.punches[0].decision,recordNumber:employee.punches[0].recordNumber.toString(),hasSelfie:Boolean(employee.punches[0].evidence?.selfieStorageKey&&!employee.punches[0].evidence?.selfiePurgedAt)}:null,
        today:{count:today.length,firstAt:first?.occurredAt??null,lastAt:last?.occurredAt??null,lastType:last?.type??null}
      };
    });
    return {
      generatedAt:new Date().toISOString(),workDate:day.date,tenant,
      summary:{total:rows.length,active:rows.filter(x=>x.active).length,faceActive:rows.filter(x=>x.faceEnrollmentStatus==='ACTIVE').length,facePending:rows.filter(x=>x.faceEnrollmentStatus==='PENDING').length,passkeyEnrolled:rows.filter(x=>['ADMIN','HR'].includes(String(x.role))&&x.webAuthnCredentials>0).length,punchedToday:rows.filter(x=>x.today.count>0).length},
      employees:rows,
      pendingPunches:pendingPunches.map(p=>({id:p.id,recordNumber:p.recordNumber.toString(),type:p.type,occurredAt:p.occurredAt,decision:p.decision,source:p.source,reviewReason:p.reviewReason,employee:p.employee,hasSelfie:Boolean(p.evidence?.selfieStorageKey&&!p.evidence?.selfiePurgedAt),selfieCapturedAt:p.evidence?.selfieCapturedAt??null,latitude:p.evidence?.latitude??null,longitude:p.evidence?.longitude??null,accuracyM:p.evidence?.accuracyM??null,worksiteId:p.evidence?.worksiteId??null}))
    };
  });

  app.get('/internal/admin/journeys/:id',async(request:any,reply)=>{
    if(!authorized(request))return reply.code(401).send({error:'Credencial interna inválida'});
    const tenant=await resolveTenant(); if(!tenant)return reply.code(404).send({error:'Empresa do Apontamento Digital não encontrada'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const journey=await internalJourneyReview(tenant.id,id);
    if(!journey)return reply.code(404).send({error:'Jornada pendente não encontrada'});
    return journey;
  });

  app.get('/internal/admin/employees/:id/punch-selfies',async(request:any,reply)=>{
    if(!authorized(request))return reply.code(401).send({error:'Credencial interna inválida'});
    const tenant=await resolveTenant(); if(!tenant)return reply.code(404).send({error:'Empresa do Apontamento Digital não encontrada'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const employee=await prisma.employee.findFirst({where:{id,tenantId:tenant.id},select:{id:true,name:true,employeeNumber:true}});
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado'});
    const punches=await prisma.punch.findMany({
      where:{tenantId:tenant.id,employeeId:id,evidence:{is:{selfieStorageKey:{not:null},selfiePurgedAt:null}}},
      select:{id:true,recordNumber:true,type:true,occurredAt:true,decision:true,reviewReason:true,evidence:{select:{selfieCapturedAt:true,platformBiometricVerified:true,webAuthnCredentialId:true}}},
      orderBy:{occurredAt:'desc'},take:100
    });
    return {employee,punches:punches.map(p=>({...p,recordNumber:p.recordNumber.toString(),selfieCapturedAt:p.evidence?.selfieCapturedAt??null}))};
  });

  app.get('/internal/admin/punches/:id/selfie',async(request:any,reply)=>{
    if(!authorized(request))return reply.code(401).send({error:'Credencial interna inválida'});
    const tenant=await resolveTenant(); if(!tenant)return reply.code(404).send({error:'Empresa do Apontamento Digital não encontrada'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const query=z.object({actorEmail:z.string().email().optional()}).parse(request.query??{});
    const actorEmail=query.actorEmail?.trim().toLowerCase()??null;
    const punch=await prisma.punch.findFirst({where:{id,tenantId:tenant.id},select:{id:true,employeeId:true,evidence:{select:{selfieStorageKey:true,selfiePurgedAt:true}}}});
    if(!punch||!punch.evidence?.selfieStorageKey||punch.evidence.selfiePurgedAt)return reply.code(404).send({error:'Selfie da marcação não encontrada ou já eliminada.'});
    try{
      const bytes=await readEncryptedImage(punch.evidence.selfieStorageKey);
      const actor=actorEmail?await prisma.user.findUnique({where:{tenantId_email:{tenantId:tenant.id,email:actorEmail}},select:{id:true}}).catch(()=>null):null;
      await prisma.auditEvent.create({data:{tenantId:tenant.id,punchId:id,actorUserId:actor?.id??null,action:'PUNCH_SELFIE_VIEWED_BY_MAIN_ADMIN',entityType:'Punch',entityId:id,metadataJson:{employeeId:punch.employeeId,source:'MAIN_ADMIN_PANEL',actorEmail}}});
      reply.header('Cache-Control','no-store, private').type('image/jpeg'); return reply.send(bytes);
    }catch{return reply.code(404).send({error:'Selfie da marcação indisponível.'});}
  });

  app.post('/internal/admin/punches/:id/review',async(request:any,reply)=>{
    if(!authorized(request))return reply.code(401).send({error:'Credencial interna inválida'});
    return reply.code(410).send({error:'A aprovação individual de marcações foi desativada. Abra o NH Ponto e use “Aprovação de jornadas” para conferir todas as selfies, horários e localizações do dia antes de decidir.'});
  });

}
