import { FastifyInstance } from 'fastify';
import { FaceEnrollmentStatus, LateJustificationMode, Role } from '@prisma/client';
import bcrypt from 'bcryptjs';
import { randomBytes } from 'node:crypto';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { isValidTimeZone } from '../lib/time.js';
import { importBrandGuide, readTenantBrandFile, saveBrandLogo, tenantBrand } from '../services/brand-guide.service.js';
import { ensureAdminAttendanceEmployee } from '../services/admin-attendance.service.js';
import { ensureDefaultShiftModelsForTenant } from '../services/shift-models.service.js';
import { deleteFaceEmployee } from '../services/face-api.service.js';
import { removeEncryptedEvidence } from '../services/biometric-storage.service.js';

const temporaryPassword=()=>randomBytes(24).toString('base64url');
const DAY_SCHEDULES={
  SUN:{enabled:false,startTime:'08:00',endTime:'12:00',breakMinutes:0},MON:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},TUE:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},WED:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},THU:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},FRI:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},SAT:{enabled:true,startTime:'08:00',endTime:'12:00',breakMinutes:0}
};
function isSuper(request:any){return request.user?.role===Role.SUPER_ADMIN;}
function cleanDoc(value?:string|null){const v=(value??'').trim();return v||null;}
function usernameFor(name:string){return `adm ${name}`.trim().toLowerCase().replace(/\s+/g,' ');}
function displayAdminFor(name:string){return `Adm ${name}`.trim().replace(/\s+/g,' ');}
function hex(){return z.string().regex(/^#[0-9A-Fa-f]{6}$/,'Use uma cor HEX no formato #RRGGBB.').transform(v=>v.toUpperCase());}

async function ensureTenantDefaults(tenantId:string,timezone:string){
  const department=await prisma.department.create({data:{tenantId,name:'Administração',code:'ADM'}});
  const shift=await prisma.shift.create({data:{tenantId,name:'Administrativo 44h',timezone,startTime:'08:00',endTime:'18:00',breakMinutes:120,dailyMinutes:480,weeklyMinutes:2640,overtimeToleranceMin:10,scheduleDays:['MON','TUE','WED','THU','FRI','SAT'],daySchedules:DAY_SCHEDULES}});
  await prisma.timeRuleSettings.create({data:{tenantId,bankHoursEnabled:true,monthlyHoursDivisor:220}});
  await prisma.attendanceSecuritySettings.create({data:{tenantId,requireSelfie:true,requireFaceMatch:false,requireLiveness:false,requireAntiSpoof:false,blockWithoutEnrollment:false,blockOnFaceFailure:false,requirePlatformBiometric:false,requireGeofence:false,requireAccurateGps:false,maxGpsAccuracyM:5000,allowOffline:false,offlineRequiresReview:true}});
  return {department,shift};
}

export async function superAdminRoutes(app:FastifyInstance){
  app.get('/public/branding/:tenantId',async(request,reply)=>{
    const {tenantId}=z.object({tenantId:z.string()}).parse(request.params);
    const tenant=await prisma.tenant.findUnique({where:{id:tenantId}});if(!tenant)return reply.code(404).send({error:'Empresa não encontrada'});
    return {tenantId:tenant.id,name:tenant.name,brand:tenantBrand(tenant)};
  });
  app.get('/public/branding/:tenantId/logo',async(request,reply)=>{
    const {tenantId}=z.object({tenantId:z.string()}).parse(request.params);
    const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{brandLogoStorageKey:true}});if(!tenant?.brandLogoStorageKey)return reply.code(404).send({error:'Logo não cadastrada'});
    try{const bytes=await readTenantBrandFile(tenantId,tenant.brandLogoStorageKey);const ext=tenant.brandLogoStorageKey.split('.').pop()?.toLowerCase();const mime=ext==='png'?'image/png':ext==='webp'?'image/webp':'image/jpeg';reply.header('Cache-Control','public, max-age=300').type(mime);return reply.send(bytes);}catch{return reply.code(404).send({error:'Logo indisponível'});}
  });

  app.get('/super-admin/tenants',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const rows=await prisma.tenant.findMany({where:{document:{not:'99999999999999'}},include:{rules:{select:{lateJustificationMode:true}},_count:{select:{users:{where:{active:true}},employees:{where:{archivedAt:null}}}},users:{where:{role:Role.ADMIN,active:true},select:{id:true,email:true,displayName:true,contactEmail:true,active:true,mustChangePassword:true,employee:{select:{name:true,employeeNumber:true}}},orderBy:{createdAt:'asc'},take:1}},orderBy:{name:'asc'}});
    return rows.map(t=>({id:t.id,name:t.name,legalName:t.legalName,document:t.document,timezone:t.timezone,createdAt:t.createdAt,updatedAt:t.updatedAt,brand:tenantBrand(t),counts:t._count,admin:t.users[0]??null,lateJustificationMode:t.rules?.lateJustificationMode??LateJustificationMode.VARIED}));
  });

  app.post('/super-admin/tenants',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const body=z.object({name:z.string().trim().min(2).max(120),legalName:z.string().trim().max(180).optional(),document:z.string().trim().max(30).optional(),timezone:z.string().trim().min(3).max(80).refine(isValidTimeZone,'Fuso horário inválido').default('America/Maceio'),adminEmail:z.union([z.string().trim().email(),z.literal('')]).optional(),hoursMode:z.enum(['BANK_HOURS','OVERTIME']).default('BANK_HOURS')}).parse(request.body);
    const document=cleanDoc(body.document);if(document){const exists=await prisma.tenant.findUnique({where:{document}});if(exists)return reply.code(409).send({error:'Já existe uma empresa com este CNPJ/documento.'});}

    const username=usernameFor(body.name);const displayName=displayAdminFor(body.name);const initPassword=temporaryPassword();const passwordHash=await bcrypt.hash(initPassword,12);
    const result=await prisma.$transaction(async tx=>{
      const tenant=await tx.tenant.create({data:{name:body.name,legalName:body.legalName||null,document,timezone:body.timezone}});
      const department=await tx.department.create({data:{tenantId:tenant.id,name:'Administração',code:'ADM'}});
      const shift=await tx.shift.create({data:{tenantId:tenant.id,name:'Administrativo 44h',timezone:body.timezone,startTime:'08:00',endTime:'18:00',breakMinutes:120,dailyMinutes:480,weeklyMinutes:2640,overtimeToleranceMin:10,scheduleDays:['MON','TUE','WED','THU','FRI','SAT'],daySchedules:DAY_SCHEDULES}});
      await tx.timeRuleSettings.create({data:{tenantId:tenant.id,bankHoursEnabled:body.hoursMode==='BANK_HOURS',overtimeToBank:body.hoursMode==='BANK_HOURS',deficitToBank:body.hoursMode==='BANK_HOURS',monthlyHoursDivisor:220}});
      await tx.attendanceSecuritySettings.create({data:{tenantId:tenant.id,requireSelfie:true,requireFaceMatch:false,requireLiveness:false,requireAntiSpoof:false,blockWithoutEnrollment:false,blockOnFaceFailure:false,requirePlatformBiometric:false,requireGeofence:false,requireAccurateGps:false,maxGpsAccuracyM:5000,allowOffline:false,offlineRequiresReview:true}});
      const user=await tx.user.create({data:{tenantId:tenant.id,email:username,displayName,passwordHash,contactEmail:body.adminEmail||null,role:Role.ADMIN,active:true,mustChangePassword:true}});
      await ensureAdminAttendanceEmployee(tx,{userId:user.id,tenantId:tenant.id,name:displayName,departmentId:department.id,shiftId:shift.id});
      await tx.adminTenantAccess.create({data:{userId:user.id,tenantId:tenant.id,createdByUserId:request.user.userId}});
      await tx.auditEvent.create({data:{tenantId:tenant.id,actorUserId:request.user.userId,action:'TENANT_CREATED_BY_SUPER_ADMIN',entityType:'Tenant',entityId:tenant.id,afterJson:{name:tenant.name,document:tenant.document,adminUsername:username,adminDisplayName:displayName,hoursMode:body.hoursMode} as any}});
      return {tenant,user};
    });
    await ensureDefaultShiftModelsForTenant(result.tenant.id);
    return reply.code(201).send({tenant:{...result.tenant,brand:tenantBrand(result.tenant)},admin:{id:result.user.id,username:result.user.email,name:result.user.displayName,email:result.user.contactEmail,temporaryPassword:initPassword,mustChangePassword:true}});
  });



  app.post('/super-admin/tenants/:id/copy-worksites',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id:targetTenantId}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({sourceTenantId:z.string().min(1)}).parse(request.body??{});
    if(body.sourceTenantId===targetTenantId)return reply.code(400).send({error:'Selecione uma empresa de origem diferente da empresa de destino.'});
    const [sourceTenant,targetTenant]=await Promise.all([
      prisma.tenant.findUnique({where:{id:body.sourceTenantId},select:{id:true,name:true,document:true}}),
      prisma.tenant.findUnique({where:{id:targetTenantId},select:{id:true,name:true,document:true}})
    ]);
    if(!sourceTenant||sourceTenant.document==='99999999999999')return reply.code(404).send({error:'Empresa de origem não encontrada.'});
    if(!targetTenant||targetTenant.document==='99999999999999')return reply.code(404).send({error:'Empresa de destino não encontrada.'});
    const sourceRows=await prisma.worksite.findMany({where:{tenantId:body.sourceTenantId,archivedAt:null},orderBy:{name:'asc'}});
    if(!sourceRows.length)return reply.code(409).send({error:`${sourceTenant.name} não possui locais cadastrados para copiar.`});
    const result=await prisma.$transaction(async tx=>{
      let created=0,updated=0,restored=0;
      const copied:Array<{sourceId:string;targetId:string;name:string;action:'CREATED'|'UPDATED'|'RESTORED'}>=[];
      for(const source of sourceRows){
        const existing=await tx.worksite.findFirst({where:{tenantId:targetTenantId,name:source.name}});
        const data={
          name:source.name,
          address:source.address,
          city:source.city,
          stateCode:source.stateCode,
          latitude:source.latitude,
          longitude:source.longitude,
          active:source.active,
          archivedAt:null,
          bluetoothNamePrefix:source.bluetoothNamePrefix,
          nfcTagId:source.nfcTagId,
          networkGatewayId:source.networkGatewayId,
          geofenceEnabled:false,
          radiusM:100
        };
        if(existing){
          const wasArchived=Boolean(existing.archivedAt);
          const row=await tx.worksite.update({where:{id:existing.id},data});
          if(wasArchived)restored++;else updated++;
          copied.push({sourceId:source.id,targetId:row.id,name:row.name,action:wasArchived?'RESTORED':'UPDATED'});
        }else{
          const row=await tx.worksite.create({data:{tenantId:targetTenantId,...data,sourceKey:null,qrSecretHash:null}});
          created++;
          copied.push({sourceId:source.id,targetId:row.id,name:row.name,action:'CREATED'});
        }
      }
      await tx.auditEvent.create({data:{tenantId:targetTenantId,actorUserId:request.user.userId,action:'WORKSITES_COPIED_BETWEEN_TENANTS',entityType:'Tenant',entityId:targetTenantId,metadataJson:{sourceTenantId:sourceTenant.id,sourceTenantName:sourceTenant.name,targetTenantId,targetTenantName:targetTenant.name,total:sourceRows.length,created,updated,restored,copied} as any}});
      return {created,updated,restored,copied};
    });
    return {ok:true,source:{id:sourceTenant.id,name:sourceTenant.name},target:{id:targetTenant.id,name:targetTenant.name},total:sourceRows.length,...result};
  });

  app.get('/super-admin/admins',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const rows=await prisma.user.findMany({where:{role:Role.ADMIN,active:true},include:{tenant:true,employee:{select:{id:true,tenantId:true,name:true,employeeNumber:true,active:true}},adminTenantAccesses:{include:{tenant:true},orderBy:{tenant:{name:'asc'}}},_count:{select:{webAuthnCredentials:true}}},orderBy:[{displayName:'asc'},{email:'asc'}]});
    return rows.map(user=>({id:user.id,username:user.email,name:user.displayName??user.email,contactEmail:user.contactEmail??null,homeTenantId:user.tenantId,homeTenant:user.tenant.name,attendanceTenantId:user.employee?.tenantId??null,mustChangePassword:user.mustChangePassword,passkeys:user._count.webAuthnCredentials,tenantIds:user.adminTenantAccesses.map(x=>x.tenantId),tenants:user.adminTenantAccesses.map(x=>({id:x.tenant.id,name:x.tenant.name}))}));
  });

  app.post('/super-admin/admins',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const body=z.object({username:z.string().trim().min(2).max(120),name:z.string().trim().min(2).max(140),contactEmail:z.union([z.string().trim().email(),z.literal('')]).optional(),tenantIds:z.array(z.string()).min(1)}).parse(request.body);
    const username=body.username.toLowerCase().replace(/\s+/g,' ').trim();
    const duplicate=await prisma.user.findFirst({where:{email:username,active:true}});if(duplicate)return reply.code(409).send({error:'Já existe um usuário ativo com esse nome de acesso.'});
    const tenants=await prisma.tenant.findMany({where:{id:{in:body.tenantIds},document:{not:'99999999999999'}},select:{id:true,name:true}});if(tenants.length!==new Set(body.tenantIds).size)return reply.code(400).send({error:'Uma ou mais empresas selecionadas são inválidas.'});
    const systemTenant=await prisma.tenant.findUnique({where:{document:'99999999999999'}});if(!systemTenant)return reply.code(500).send({error:'Tenant global do Apontamento Digital não encontrado.'});
    const initPassword=temporaryPassword();const passwordHash=await bcrypt.hash(initPassword,12);
    const attendanceTenantId=body.tenantIds[0];
    const user=await prisma.$transaction(async tx=>{const created=await tx.user.create({data:{tenantId:systemTenant.id,email:username,displayName:body.name,contactEmail:body.contactEmail||null,passwordHash,role:Role.ADMIN,active:true,mustChangePassword:true}});for(const tenantId of new Set(body.tenantIds))await tx.adminTenantAccess.create({data:{userId:created.id,tenantId,createdByUserId:request.user.userId}});await ensureAdminAttendanceEmployee(tx,{userId:created.id,tenantId:attendanceTenantId,name:body.name});return created;});
    return reply.code(201).send({id:user.id,username:user.email,name:user.displayName,temporaryPassword:initPassword,mustChangePassword:true,tenantIds:body.tenantIds});
  });


  app.patch('/super-admin/admins/:id',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({username:z.string().trim().min(2).max(120),name:z.string().trim().min(2).max(140),contactEmail:z.union([z.string().trim().email(),z.literal(''),z.null()]).optional(),tenantIds:z.array(z.string()).min(1)}).parse(request.body);
    const admin=await prisma.user.findFirst({where:{id,role:Role.ADMIN,active:true},include:{employee:true,adminTenantAccesses:true}});if(!admin)return reply.code(404).send({error:'Administrador não encontrado'});
    const username=body.username.toLowerCase().replace(/\s+/g,' ').trim();
    const duplicate=await prisma.user.findFirst({where:{email:username,active:true,id:{not:id}},select:{id:true}});if(duplicate)return reply.code(409).send({error:'Já existe um usuário ativo com esse nome de acesso.'});
    const tenantIds=Array.from(new Set(body.tenantIds));
    const tenants=await prisma.tenant.findMany({where:{id:{in:tenantIds},document:{not:'99999999999999'}},select:{id:true,name:true}});if(tenants.length!==tenantIds.length)return reply.code(400).send({error:'Uma ou mais empresas selecionadas são inválidas.'});
    if(admin.employee&&!tenantIds.includes(admin.employee.tenantId))return reply.code(409).send({error:'A empresa-base da jornada deste administrador precisa permanecer entre as empresas permitidas. Para trocar a empresa-base, preserve o histórico e faça a realocação pela gestão de jornada.'});
    const updated=await prisma.$transaction(async tx=>{
      const user=await tx.user.update({where:{id},data:{email:username,displayName:body.name,contactEmail:body.contactEmail||null}});
      if(admin.employee)await tx.employee.update({where:{id:admin.employee.id},data:{name:body.name}});
      await tx.adminTenantAccess.deleteMany({where:{userId:id}});
      for(const tenantId of tenantIds)await tx.adminTenantAccess.create({data:{userId:id,tenantId,createdByUserId:request.user.userId}});
      await tx.auditEvent.create({data:{tenantId:admin.employee?.tenantId??tenantIds[0],actorUserId:request.user.userId,action:'ADMIN_UPDATED_BY_SUPER_ADMIN',entityType:'User',entityId:id,beforeJson:{username:admin.email,name:admin.displayName,contactEmail:admin.contactEmail,tenantIds:admin.adminTenantAccesses.map(x=>x.tenantId)} as any,afterJson:{username:user.email,name:user.displayName,contactEmail:user.contactEmail,tenantIds} as any}});
      return user;
    });
    return {ok:true,id:updated.id,username:updated.email,name:updated.displayName,contactEmail:updated.contactEmail,tenantIds};
  });

  app.post('/super-admin/admins/:id/reset-password',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const admin=await prisma.user.findFirst({where:{id,role:Role.ADMIN,active:true},include:{employee:true}});if(!admin)return reply.code(404).send({error:'Administrador não encontrado'});
    const temporaryPassword=randomBytes(9).toString('base64url');const passwordHash=await bcrypt.hash(temporaryPassword,12);const auditTenantId=admin.employee?.tenantId??admin.tenantId;
    await prisma.$transaction(async tx=>{await tx.user.update({where:{id},data:{passwordHash,mustChangePassword:true,passwordChangedAt:null,onboardingCompletedAt:null}});await tx.webAuthnChallenge.deleteMany({where:{userId:id}});await tx.auditEvent.create({data:{tenantId:auditTenantId,actorUserId:request.user.userId,action:'ADMIN_PASSWORD_RESET_BY_SUPER_ADMIN',entityType:'User',entityId:id,metadataJson:{username:admin.email,name:admin.displayName,temporaryPasswordIssued:true,forcePasswordChange:true}}});});
    return {ok:true,id,username:admin.email,name:admin.displayName??admin.email,temporaryPassword,mustChangePassword:true};
  });

  app.post('/super-admin/admins/:id/reset-biometrics',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const admin=await prisma.user.findFirst({where:{id,role:Role.ADMIN,active:true},include:{employee:true}});if(!admin)return reply.code(404).send({error:'Administrador não encontrado'});
    const credentials=await prisma.webAuthnCredential.findMany({where:{userId:id},select:{id:true}});let faceRefs:Array<{id:string;storageKey:string|null}>=[];let submissions:Array<{id:string;imagesJson:unknown}>=[];
    if(admin.employee){[faceRefs,submissions]=await Promise.all([prisma.faceReference.findMany({where:{employeeId:admin.employee.id},select:{id:true,storageKey:true}}),prisma.faceEnrollmentSubmission.findMany({where:{employeeId:admin.employee.id},select:{id:true,imagesJson:true}})]);}
    for(const ref of faceRefs){if(ref.storageKey)await removeEncryptedEvidence(ref.storageKey).catch(()=>undefined);}for(const submission of submissions){const images=Array.isArray(submission.imagesJson)?submission.imagesJson as Array<{storageKey?:string}>:[];for(const image of images){if(image?.storageKey)await removeEncryptedEvidence(image.storageKey).catch(()=>undefined);}}
    const auditTenantId=admin.employee?.tenantId??admin.tenantId;
    await prisma.$transaction(async tx=>{await tx.webAuthnCredential.deleteMany({where:{userId:id}});await tx.webAuthnChallenge.deleteMany({where:{userId:id}});await tx.user.update({where:{id},data:{onboardingCompletedAt:null}});if(admin.employee){await tx.faceEnrollmentSubmission.deleteMany({where:{employeeId:admin.employee.id}});await tx.faceReference.deleteMany({where:{employeeId:admin.employee.id}});await tx.employee.update({where:{id:admin.employee.id},data:{faceEnrollmentStatus:FaceEnrollmentStatus.NOT_ENROLLED,faceEnrolledAt:null,faceTemplateVersion:{increment:1},failedBiometricAttempts:0,biometricLockedUntil:null}});}await tx.auditEvent.create({data:{tenantId:auditTenantId,actorUserId:request.user.userId,action:'ADMIN_BIOMETRICS_RESET_BY_SUPER_ADMIN',entityType:'User',entityId:id,metadataJson:{username:admin.email,name:admin.displayName,deletedPasskeys:credentials.length,deletedFaceReferences:faceRefs.length,deletedFaceSubmissions:submissions.length,selfiesPreserved:true}}});});
    let faceApiDeleted=false;if(admin.employee)faceApiDeleted=await deleteFaceEmployee(admin.employee.id).then(()=>true).catch(()=>false);
    return {ok:true,id,username:admin.email,deletedPasskeys:credentials.length,deletedFaceReferences:faceRefs.length,deletedFaceSubmissions:submissions.length,faceApiDeleted,selfiesPreserved:true};
  });

  app.delete('/super-admin/admins/:id',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const admin=await prisma.user.findFirst({where:{id,role:Role.ADMIN,active:true},include:{employee:true,adminTenantAccesses:true}});if(!admin)return reply.code(404).send({error:'Administrador não encontrado'});
    const auditTenantId=admin.employee?.tenantId??admin.adminTenantAccesses[0]?.tenantId??admin.tenantId;const now=new Date();
    await prisma.$transaction(async tx=>{await tx.adminTenantAccess.deleteMany({where:{userId:id}});await tx.webAuthnCredential.deleteMany({where:{userId:id}});await tx.webAuthnChallenge.deleteMany({where:{userId:id}});await tx.user.update({where:{id},data:{active:false,onboardingCompletedAt:null}});if(admin.employee)await tx.employee.update({where:{id:admin.employee.id},data:{active:false,archivedAt:now}});await tx.auditEvent.create({data:{tenantId:auditTenantId,actorUserId:request.user.userId,action:'ADMIN_ARCHIVED_BY_SUPER_ADMIN',entityType:'User',entityId:id,beforeJson:{username:admin.email,name:admin.displayName,tenantIds:admin.adminTenantAccesses.map(x=>x.tenantId),active:true} as any,afterJson:{active:false,accessesRemoved:true,passkeysRevoked:true,historyPreserved:true} as any}});});
    return {ok:true,id,archived:true,historyPreserved:true};
  });

  app.patch('/super-admin/admins/:id/access',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);const body=z.object({tenantIds:z.array(z.string()).min(1)}).parse(request.body);
    const admin=await prisma.user.findFirst({where:{id,role:Role.ADMIN,active:true},include:{tenant:true,employee:true}});if(!admin)return reply.code(404).send({error:'Administrador não encontrado'});
    const tenantIds=Array.from(new Set(body.tenantIds));const tenants=await prisma.tenant.findMany({where:{id:{in:tenantIds},document:{not:'99999999999999'}},select:{id:true}});if(tenants.length!==tenantIds.length)return reply.code(400).send({error:'Uma ou mais empresas selecionadas são inválidas.'});
    if(admin.employee&&!tenantIds.includes(admin.employee.tenantId))return reply.code(409).send({error:'A empresa-base da jornada deste administrador precisa permanecer entre as empresas permitidas.'});
    await prisma.$transaction(async tx=>{await tx.adminTenantAccess.deleteMany({where:{userId:id}});for(const tenantId of tenantIds)await tx.adminTenantAccess.create({data:{userId:id,tenantId,createdByUserId:request.user.userId}});});
    return {ok:true,tenantIds};
  });



  app.patch('/super-admin/tenants/:id/late-justification-policy',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const body=z.object({mode:z.nativeEnum(LateJustificationMode)}).parse(request.body);
    const tenant=await prisma.tenant.findUnique({where:{id},select:{id:true,name:true}});
    if(!tenant)return reply.code(404).send({error:'Empresa não encontrada'});
    const before=await prisma.timeRuleSettings.upsert({where:{tenantId:id},update:{},create:{tenantId:id}});
    const rules=await prisma.timeRuleSettings.update({where:{tenantId:id},data:{lateJustificationMode:body.mode}});
    await prisma.auditEvent.create({data:{tenantId:id,actorUserId:request.user.userId,action:'LATE_JUSTIFICATION_POLICY_UPDATED_BY_SUPER_ADMIN',entityType:'TimeRuleSettings',entityId:rules.id,beforeJson:{lateJustificationMode:before.lateJustificationMode} as any,afterJson:{lateJustificationMode:rules.lateJustificationMode} as any}});
    return {tenantId:id,tenantName:tenant.name,lateJustificationMode:rules.lateJustificationMode};
  });

  app.patch('/super-admin/tenants/:id',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);const body=z.object({name:z.string().trim().min(2).max(120).optional(),legalName:z.string().trim().max(180).nullable().optional(),document:z.string().trim().max(30).nullable().optional(),timezone:z.string().trim().min(3).max(80).refine(isValidTimeZone,'Fuso horário inválido').optional()}).parse(request.body);
    const before=await prisma.tenant.findUnique({where:{id}});if(!before)return reply.code(404).send({error:'Empresa não encontrada'});

    const tenant=await prisma.tenant.update({where:{id},data:{...body,document:body.document===undefined?undefined:cleanDoc(body.document)}});
    if(body.name&&body.name!==before.name){
      const previousUsername=usernameFor(before.name);const previousDisplay=displayAdminFor(before.name);
      const genericAdmin=await prisma.user.findFirst({where:{tenantId:id,role:Role.ADMIN,OR:[{email:previousUsername},{displayName:previousDisplay}]},include:{employee:true},orderBy:{createdAt:'asc'}});
      if(genericAdmin){const nextUsername=usernameFor(body.name),nextDisplay=displayAdminFor(body.name);await prisma.user.update({where:{id:genericAdmin.id},data:{email:nextUsername,displayName:nextDisplay}});if(genericAdmin.employee)await prisma.employee.update({where:{id:genericAdmin.employee.id},data:{name:nextDisplay}});}
    }
    await prisma.auditEvent.create({data:{tenantId:id,actorUserId:request.user.userId,action:'TENANT_UPDATED_BY_SUPER_ADMIN',entityType:'Tenant',entityId:id,beforeJson:{name:before.name,legalName:before.legalName,document:before.document,timezone:before.timezone} as any,afterJson:{name:tenant.name,legalName:tenant.legalName,document:tenant.document,timezone:tenant.timezone} as any}});
    return {...tenant,brand:tenantBrand(tenant)};
  });

  app.get('/super-admin/tenants/:id/users',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const tenant=await prisma.tenant.findUnique({where:{id},select:{id:true,name:true,document:true}});if(!tenant||tenant.document==='99999999999999')return reply.code(404).send({error:'Empresa não encontrada'});
    const users=await prisma.user.findMany({where:{tenantId:id,role:{not:Role.SUPER_ADMIN}},include:{employee:{select:{id:true,name:true,employeeNumber:true,active:true,archivedAt:true,faceEnrollmentStatus:true,worksite:{select:{id:true,name:true}},department:{select:{id:true,name:true}}}},_count:{select:{webAuthnCredentials:true}}},orderBy:[{active:'desc'},{displayName:'asc'},{email:'asc'}]});
    return users.map(user=>({id:user.id,username:user.email,name:user.displayName??user.employee?.name??user.email,contactEmail:user.contactEmail??null,role:user.role,active:user.active,mustChangePassword:user.mustChangePassword,passwordChangedAt:user.passwordChangedAt,specialLabel:user.role===Role.TIME_REVIEWER?'Auditoria de jornada':null,visibleOnlyToSuperAdmin:user.role===Role.TIME_REVIEWER,employee:user.employee?{id:user.employee.id,name:user.employee.name,employeeNumber:user.employee.employeeNumber,active:user.employee.active,archivedAt:user.employee.archivedAt,faceEnrollmentStatus:user.employee.faceEnrollmentStatus,worksite:user.employee.worksite,department:user.employee.department}:null,passkeys:user._count.webAuthnCredentials}));
  });

  app.patch('/super-admin/tenants/:tenantId/users/:userId',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {tenantId,userId}=z.object({tenantId:z.string(),userId:z.string()}).parse(request.params);
    const body=z.object({username:z.string().trim().min(2).max(120).optional(),name:z.string().trim().min(2).max(150).optional(),contactEmail:z.union([z.string().trim().email(),z.literal(''),z.null()]).optional(),password:z.string().min(6).max(128).optional(),mustChangePassword:z.boolean().optional(),active:z.boolean().optional()}).parse(request.body);
    const before=await prisma.user.findFirst({where:{id:userId,tenantId,role:{not:Role.SUPER_ADMIN}},include:{employee:true}});if(!before)return reply.code(404).send({error:'Usuário não encontrado nesta empresa.'});
    const username=body.username?.toLowerCase().replace(/\s+/g,' ').trim();
    if(username&&username!==before.email){const conflict=await prisma.user.findFirst({where:{tenantId,email:username,id:{not:userId}},select:{id:true}});if(conflict)return reply.code(409).send({error:'Este usuário de acesso já está em uso nesta empresa.'});}
    const passwordHash=body.password?await bcrypt.hash(body.password,12):undefined;
    const updated=await prisma.$transaction(async tx=>{
      const user=await tx.user.update({where:{id:userId},data:{...(username?{email:username}:{}),...(body.name?{displayName:body.name}:{}),...(body.contactEmail!==undefined?{contactEmail:body.contactEmail||null}:{}),...(passwordHash?{passwordHash,passwordChangedAt:new Date(),mustChangePassword:body.mustChangePassword??false}:{...(body.mustChangePassword!==undefined?{mustChangePassword:body.mustChangePassword}:{})}),...(body.active!==undefined?{active:body.active}:{})}});
      if(before.employee&&(body.name!==undefined||body.active!==undefined))await tx.employee.update({where:{id:before.employee.id},data:{...(body.name!==undefined?{name:body.name}:{}),...(body.active!==undefined?{active:body.active}:{})}});
      await tx.auditEvent.create({data:{tenantId,actorUserId:request.user.userId,action:'TENANT_USER_UPDATED_BY_SUPER_ADMIN',entityType:'User',entityId:userId,beforeJson:{username:before.email,name:before.displayName,contactEmail:before.contactEmail,active:before.active,mustChangePassword:before.mustChangePassword} as any,afterJson:{username:user.email,name:user.displayName,contactEmail:user.contactEmail,active:user.active,mustChangePassword:user.mustChangePassword,passwordChanged:Boolean(passwordHash)} as any}});
      return user;
    });
    return {id:updated.id,username:updated.email,name:updated.displayName,contactEmail:updated.contactEmail,active:updated.active,mustChangePassword:updated.mustChangePassword,passwordUpdated:Boolean(passwordHash)};
  });

  app.post('/super-admin/tenants/:tenantId/users/:userId/reset-password',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {tenantId,userId}=z.object({tenantId:z.string(),userId:z.string()}).parse(request.params);
    const user=await prisma.user.findFirst({where:{id:userId,tenantId,role:{not:Role.SUPER_ADMIN}},select:{id:true,email:true,displayName:true,role:true,active:true}});
    if(!user)return reply.code(404).send({error:'Usuário não encontrado nesta empresa.'});
    const temporaryPassword=randomBytes(9).toString('base64url');
    const passwordHash=await bcrypt.hash(temporaryPassword,12);
    await prisma.$transaction(async tx=>{
      await tx.user.update({where:{id:userId},data:{passwordHash,mustChangePassword:true,passwordChangedAt:null,onboardingCompletedAt:null}});
      await tx.webAuthnChallenge.deleteMany({where:{tenantId,userId}});
      await tx.auditEvent.create({data:{tenantId,actorUserId:request.user.userId,action:'TENANT_USER_PASSWORD_RESET_BY_SUPER_ADMIN',entityType:'User',entityId:userId,metadataJson:{username:user.email,name:user.displayName,role:user.role,temporaryPasswordIssued:true,forcePasswordChange:true}}});
    });
    return {ok:true,userId,username:user.email,name:user.displayName??user.email,temporaryPassword,mustChangePassword:true};
  });

  app.post('/super-admin/tenants/:tenantId/users/:userId/reset-biometrics',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {tenantId,userId}=z.object({tenantId:z.string(),userId:z.string()}).parse(request.params);
    const user=await prisma.user.findFirst({where:{id:userId,tenantId,role:{not:Role.SUPER_ADMIN}},include:{employee:{select:{id:true,name:true,faceEnrollmentStatus:true}}}});
    if(!user)return reply.code(404).send({error:'Usuário não encontrado nesta empresa.'});

    const credentials=await prisma.webAuthnCredential.findMany({where:{tenantId,userId},select:{id:true}});
    let faceRefs:Array<{id:string;storageKey:string|null}>=[];
    let submissions:Array<{id:string;imagesJson:unknown}>=[];
    if(user.employee){
      [faceRefs,submissions]=await Promise.all([
        prisma.faceReference.findMany({where:{tenantId,employeeId:user.employee.id},select:{id:true,storageKey:true}}),
        prisma.faceEnrollmentSubmission.findMany({where:{tenantId,employeeId:user.employee.id},select:{id:true,imagesJson:true}})
      ]);
    }

    for(const ref of faceRefs){if(ref.storageKey)await removeEncryptedEvidence(ref.storageKey).catch(()=>undefined);}
    for(const submission of submissions){
      const images=Array.isArray(submission.imagesJson)?submission.imagesJson as Array<{storageKey?:string}>:[];
      for(const image of images){if(image?.storageKey)await removeEncryptedEvidence(image.storageKey).catch(()=>undefined);}
    }

    await prisma.$transaction(async tx=>{
      await tx.webAuthnCredential.deleteMany({where:{tenantId,userId}});
      await tx.webAuthnChallenge.deleteMany({where:{tenantId,userId}});
      if(user.employee){
        await tx.faceEnrollmentSubmission.deleteMany({where:{tenantId,employeeId:user.employee.id}});
        await tx.faceReference.deleteMany({where:{tenantId,employeeId:user.employee.id}});
        await tx.employee.update({where:{id:user.employee.id},data:{faceEnrollmentStatus:FaceEnrollmentStatus.NOT_ENROLLED,faceEnrolledAt:null,faceTemplateVersion:{increment:1},failedBiometricAttempts:0,biometricLockedUntil:null}});
      }
      await tx.auditEvent.create({data:{tenantId,actorUserId:request.user.userId,action:'TENANT_USER_BIOMETRICS_RESET_BY_SUPER_ADMIN',entityType:'User',entityId:userId,metadataJson:{username:user.email,name:user.displayName,role:user.role,employeeId:user.employee?.id??null,deletedPasskeys:credentials.length,deletedFaceReferences:faceRefs.length,deletedFaceSubmissions:submissions.length,selfiesPreserved:true}}});
    });

    let faceApiDeleted=false;
    if(user.employee)faceApiDeleted=await deleteFaceEmployee(user.employee.id).then(()=>true).catch(()=>false);
    return {ok:true,userId,username:user.email,deletedPasskeys:credentials.length,deletedFaceReferences:faceRefs.length,deletedFaceSubmissions:submissions.length,faceApiDeleted,selfiesPreserved:true};
  });

  app.patch('/super-admin/tenants/:id/theme',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);const body=z.object({primary:hex(),secondary:hex(),surface:hex(),text:hex()}).parse(request.body);
    const tenant=await prisma.tenant.update({where:{id},data:{brandPrimary:body.primary,brandSecondary:body.secondary,brandSurface:body.surface,brandText:body.text,brandSource:'MANUAL_COLORS',brandUpdatedAt:new Date()}}).catch(()=>null);
    if(!tenant)return reply.code(404).send({error:'Empresa não encontrada'});
    await prisma.auditEvent.create({data:{tenantId:id,actorUserId:request.user.userId,action:'TENANT_THEME_UPDATED',entityType:'Tenant',entityId:id,afterJson:body as any}});
    return {brand:tenantBrand(tenant)};
  });

  app.post('/super-admin/tenants/:id/brand-guide',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);const body=z.object({fileName:z.string().max(180),dataUrl:z.string().min(100)}).parse(request.body);
    const tenant=await prisma.tenant.findUnique({where:{id}});if(!tenant)return reply.code(404).send({error:'Empresa não encontrada'});
    try{const imported=await importBrandGuide({tenantId:id,fileName:body.fileName,dataUrl:body.dataUrl});await prisma.auditEvent.create({data:{tenantId:id,actorUserId:request.user.userId,action:'TENANT_BRAND_GUIDE_IMPORTED',entityType:'Tenant',entityId:id,metadataJson:{fileName:body.fileName,detectedColors:imported.palette.detected,pageCount:imported.palette.pageCount,logoExtracted:imported.logoExtracted} as any}});return {brand:tenantBrand(imported.tenant),palette:imported.palette,logoExtracted:imported.logoExtracted};}catch(error){return reply.code(400).send({error:error instanceof Error?error.message:'Não foi possível importar o manual da marca.'});}
  });

  app.post('/super-admin/tenants/:id/logo',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!isSuper(request))return reply.code(403).send({error:'Acesso exclusivo do Super Adm'});
    const {id}=z.object({id:z.string()}).parse(request.params);const body=z.object({dataUrl:z.string().min(100)}).parse(request.body);const exists=await prisma.tenant.findUnique({where:{id}});if(!exists)return reply.code(404).send({error:'Empresa não encontrada'});
    try{const tenant=await saveBrandLogo({tenantId:id,dataUrl:body.dataUrl});await prisma.auditEvent.create({data:{tenantId:id,actorUserId:request.user.userId,action:'TENANT_LOGO_UPDATED',entityType:'Tenant',entityId:id}});return {brand:tenantBrand(tenant)};}catch(error){return reply.code(400).send({error:error instanceof Error?error.message:'Não foi possível salvar a logo.'});}
  });
}
