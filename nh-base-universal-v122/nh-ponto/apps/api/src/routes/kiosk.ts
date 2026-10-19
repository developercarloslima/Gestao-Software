import crypto from 'node:crypto';
import { FastifyInstance } from 'fastify';
import { PunchDecision, PunchType, Role } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { recognizeFaceEmployeeDataUrl, faceApiSimilarity, FaceApiError } from '../services/face-api.service.js';
import { analyzeFaceLoginDataUrl } from '../services/face-enrollment-async.service.js';
import { getOrCreateSecuritySettings } from '../services/security-settings.service.js';
import { createImmutablePunch } from '../services/punch.service.js';
import { persistEmployeeLedger, workDateKeyForInstant } from '../services/time-engine.service.js';
import { addDaysKey, zonedDateTimeToUtc } from '../lib/time.js';

const validPrevious:Record<PunchType,PunchType[]>={
  CLOCK_IN:[PunchType.CLOCK_OUT],BREAK_START:[PunchType.CLOCK_IN,PunchType.BREAK_END,PunchType.PAUSE_END],BREAK_END:[PunchType.BREAK_START],
  PAUSE_START:[PunchType.CLOCK_IN,PunchType.BREAK_END,PunchType.PAUSE_END],PAUSE_END:[PunchType.PAUSE_START],CLOCK_OUT:[PunchType.CLOCK_IN,PunchType.BREAK_END,PunchType.PAUSE_END]
};

function verifyKioskToken(app:FastifyInstance,token:string){
  try{
    const decoded=(app.jwt as any).verify(token) as any;
    if(decoded?.kind!=='NH_PONTO_KIOSK'||!decoded?.tenantId)throw new Error('invalid');
    return decoded;
  }catch{throw Object.assign(new Error('Totem não autorizado. Ative novamente este aparelho com uma conta de Administrador ou Super Adm.'),{statusCode:401});}
}

function verifyRecognitionToken(app:FastifyInstance,token:string,tenantId:string){
  try{
    const decoded=(app.jwt as any).verify(token) as any;
    if(decoded?.kind!=='KIOSK_FACE_RECOGNITION'||decoded?.tenantId!==tenantId||!decoded?.employeeId)throw new Error('invalid');
    return decoded;
  }catch{throw Object.assign(new Error('Reconhecimento expirado. Olhe novamente para a câmera.'),{statusCode:401});}
}

async function punchUsageForTurn(tenantId:string,employeeId:string,instant:Date){
  const employee=await prisma.employee.findFirst({where:{id:employeeId,tenantId},include:{shift:true,tenant:true}});
  if(!employee)throw Object.assign(new Error('Colaborador não encontrado'),{statusCode:404});
  const timeZone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
  const workDate=workDateKeyForInstant(instant,employee.shift,timeZone);
  const start=zonedDateTimeToUtc(addDaysKey(workDate,-1),'00:00',timeZone);
  const end=zonedDateTimeToUtc(addDaysKey(workDate,2),'00:00',timeZone);
  const rows=await prisma.punch.findMany({where:{tenantId,employeeId,decision:{not:PunchDecision.BLOCKED},occurredAt:{gte:start,lt:end}},select:{type:true,occurredAt:true}});
  const usedTypes=[...new Set(rows.filter(row=>workDateKeyForInstant(row.occurredAt,employee.shift,timeZone)===workDate).map(row=>row.type))];
  return {workDate,timeZone,usedTypes};
}

export async function kioskRoutes(app:FastifyInstance){
  app.post('/kiosk/activate',{preHandler:[app.authenticate]},async(request:any,reply)=>{
    if(!([Role.ADMIN,Role.HR] as Role[]).includes(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador ou Super Adm podem ativar um Totem facial.'});
    const body=z.object({name:z.string().trim().min(2).max(80).default('Totem Apontamento Digital'),worksiteId:z.string().nullable().optional()}).parse(request.body??{});
    if(body.worksiteId){
      const worksite=await prisma.worksite.findFirst({where:{id:body.worksiteId,tenantId:request.user.tenantId,active:true},select:{id:true}});
      if(!worksite)return reply.code(404).send({error:'Local do Totem não encontrado ou inativo.'});
    }
    const token=app.jwt.sign({kind:'NH_PONTO_KIOSK',userId:request.user.userId,tenantId:request.user.tenantId,role:request.user.role,worksiteId:body.worksiteId??null,name:body.name,activatedByUserId:request.user.userId,jti:crypto.randomUUID()},{expiresIn:'180d'});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'KIOSK_DEVICE_ACTIVATED',entityType:'Tenant',entityId:request.user.tenantId,metadataJson:{name:body.name,worksiteId:body.worksiteId??null}}});
    return {kioskToken:token,name:body.name,worksiteId:body.worksiteId??null,expiresInDays:180};
  });

  app.post('/kiosk/recognize',async(request,reply)=>{
    const body=z.object({kioskToken:z.string().min(20),imageDataUrl:z.string().min(100)}).parse(request.body);
    let kiosk:any;try{kiosk=verifyKioskToken(app,body.kioskToken);}catch(error:any){return reply.code(error.statusCode??401).send({error:error.message});}
    try{
      const policy=await getOrCreateSecuritySettings(kiosk.tenantId);
      // Captura única e frontal: reconhecimento 1:N + prova de vida passiva/antispoof.
      const [localFace,recognized]=await Promise.all([
        analyzeFaceLoginDataUrl(body.imageDataUrl),
        recognizeFaceEmployeeDataUrl(body.imageDataUrl)
      ]);
      const employee=await prisma.employee.findFirst({where:{id:recognized.external_id,tenantId:kiosk.tenantId,active:true},include:{user:{select:{id:true,active:true}},worksite:true}});
      if(!employee||employee.user?.active===false)return reply.code(404).send({error:'Rosto reconhecido, mas o colaborador está inativo ou não pertence a esta empresa.'});
      const frontalOk=localFace.faceCount===1&&localFace.quality>=policy.minFaceQualityScore;
      const liveOk=localFace.liveness>=policy.minLivenessScore;
      const antiSpoofOk=localFace.antiSpoof>=policy.minAntiSpoofScore;
      if(!frontalOk||!liveOk||!antiSpoofOk)return reply.code(401).send({error:'Não foi possível confirmar uma presença facial real. Olhe de frente para a câmera, mantenha boa iluminação e tente novamente.'});
      const usage=await punchUsageForTurn(kiosk.tenantId,employee.id,new Date());
      const recognitionToken=app.jwt.sign({kind:'KIOSK_FACE_RECOGNITION',userId:employee.userId??kiosk.activatedByUserId,tenantId:kiosk.tenantId,role:Role.EMPLOYEE,employeeId:employee.id,kioskJti:kiosk.jti,worksiteId:kiosk.worksiteId??null,activatedByUserId:kiosk.activatedByUserId,faceDistance:recognized.distance,faceQuality:Math.min(localFace.quality,recognized.quality),liveness:localFace.liveness,antiSpoof:localFace.antiSpoof,jti:crypto.randomUUID()},{expiresIn:'75s'});
      await prisma.auditEvent.create({data:{tenantId:kiosk.tenantId,actorUserId:employee.userId??kiosk.activatedByUserId,action:'KIOSK_FACE_RECOGNIZED',entityType:'Employee',entityId:employee.id,metadataJson:{kioskName:kiosk.name,distance:recognized.distance,quality:recognized.quality,liveness:localFace.liveness,antiSpoof:localFace.antiSpoof}}}).catch(()=>undefined);
      return {recognized:true,recognitionToken,employee:{id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,jobTitle:employee.jobTitle},usedTypes:usage.usedTypes,workDate:usage.workDate,expiresInSeconds:75};
    }catch(error){
      if(error instanceof FaceApiError&&error.status===404)return reply.code(404).send({error:'Rosto não reconhecido. Procure o RH para conferir a foto 3x4 cadastrada.'});
      request.log.warn(error);
      return reply.code(401).send({error:error instanceof Error?error.message:'Não foi possível reconhecer o rosto.'});
    }
  });

  app.post('/kiosk/punch',async(_request,reply)=>{
    return reply.code(410).send({error:'Fluxo antigo do Totem desativado. Toda marcação manual exige uma nova selfie + GPS na própria ação e aprovação do Administrador/Super Adm.'});
  });
}
