import crypto from 'node:crypto';
import type { FastifyInstance } from 'fastify';
import { FacePose, Role, WebAuthnChallengePurpose } from '@prisma/client';
import { z } from 'zod';
import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
  type AuthenticationResponseJSON,
  type RegistrationResponseJSON,
  type AuthenticatorTransport
} from '@simplewebauthn/server';
import { prisma } from '../lib/prisma.js';
import { JOURNEY_REVIEW_ROLES, PASSKEY_ACCOUNT_ROLES } from '../lib/access.js';
import { getOrCreateSecuritySettings } from '../services/security-settings.service.js';
import { createPresenceToken } from '../services/presence.service.js';
import { acknowledgeBiometricNotice, BIOMETRIC_NOTICE_HASH, BIOMETRIC_NOTICE_TEXT, BIOMETRIC_NOTICE_VERSION, getCurrentAcknowledgement } from '../services/biometric-notice.service.js';
import { enrollFaceReference } from '../services/face-enrollment.service.js';
import { refreshOnboardingCompletion } from '../services/onboarding.service.js';
import { createAsyncFaceEnrollment, kickFaceEnrollmentWorker, publicSubmission, recoverLegacyTmpFaceEnrollmentForUser, recoverStuckFaceEnrollmentForUser, retryFaceEnrollmentSubmission } from '../services/face-enrollment-async.service.js';
import { MONTH_RE, timesheetAcceptanceStatus } from '../services/timesheet-acceptance.service.js';

function getWebAuthnConfig(){
  const renderHostname=process.env.RENDER_EXTERNAL_HOSTNAME?.trim();
  const production=process.env.NODE_ENV==='production';
  const configuredRp=(process.env.WEBAUTHN_RP_ID??'').trim();
  const configuredOrigin=(process.env.WEBAUTHN_ORIGIN??'').trim();

  // In production on Render, never keep localhost as RP/origin.
  // Route modules are evaluated before server.ts finishes inferring env vars,
  // so the values must be resolved at request time rather than module load time.
  const useRenderRp=Boolean(renderHostname) && (
    !configuredRp || (production && ['localhost','127.0.0.1'].includes(configuredRp.toLowerCase()))
  );
  const useRenderOrigin=Boolean(renderHostname) && (
    !configuredOrigin || (production && /^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(configuredOrigin))
  );

  const rpID=(useRenderRp ? renderHostname! : (configuredRp || 'localhost'))
    .replace(/^https?:\/\//i,'').split('/')[0].split(':')[0];
  const origin=useRenderOrigin ? `https://${renderHostname}` : (configuredOrigin || 'http://localhost:5173');
  const rpName=process.env.WEBAUTHN_RP_NAME?.trim() || 'Apontamento Digital';
  return {rpID,rpName,expectedOrigin:origin};
}
const challengeTtlMs = 5 * 60_000;

function transports(value: unknown): AuthenticatorTransport[] | undefined {
  return Array.isArray(value) ? value as AuthenticatorTransport[] : undefined;
}

function authenticationTransports(value: unknown): AuthenticatorTransport[] | undefined {
  const saved=transports(value)??[];
  return Array.from(new Set([...saved,'hybrid' as AuthenticatorTransport]));
}

function registrationClientChallenge(response:any):string|null {
  try {
    const encoded=String(response?.response?.clientDataJSON??'');
    if(!encoded)return null;
    const parsed=JSON.parse(Buffer.from(encoded,'base64url').toString('utf8')) as {challenge?:string};
    return typeof parsed.challenge==='string'&&parsed.challenge?parsed.challenge:null;
  } catch { return null; }
}

async function assertAdmin(request:any, reply:any) {
  const adminRoles: Role[] = [Role.SUPER_ADMIN, Role.ADMIN, Role.HR];
  if (!adminRoles.includes(request.user.role as Role)) { reply.code(403).send({error:'Sem permissão'}); return false; }
  return true;
}

async function assertPasskeyAccount(request:any, reply:any) {
  if (!PASSKEY_ACCOUNT_ROLES.includes(request.user.role as Role)) { reply.code(403).send({error:'Sem permissão'}); return false; }
  return true;
}

async function assertJourneyReviewer(request:any, reply:any) {
  if (!JOURNEY_REVIEW_ROLES.includes(request.user.role as Role)) { reply.code(403).send({error:'A aprovação diária da jornada é exclusiva de responsáveis autorizados.'}); return false; }
  return true;
}


async function managementTenantId(request:any,reply:any):Promise<string|null>{
  const raw=request.headers?.['x-tenant-id'];
  const requested=typeof raw==='string'?raw.trim():'';
  if(request.user.role===Role.SUPER_ADMIN){
    if(!requested){reply.code(400).send({error:'Selecione a empresa que deseja administrar.'});return null;}
    const tenant=await prisma.tenant.findFirst({where:{id:requested,document:{not:'99999999999999'}},select:{id:true}});
    if(!tenant){reply.code(404).send({error:'Empresa não encontrada.'});return null;}
    return tenant.id;
  }
  if(request.user.role===Role.ADMIN){
    const target=requested||request.user.tenantId;
    const access=await prisma.adminTenantAccess.findUnique({where:{userId_tenantId:{userId:request.user.userId,tenantId:target}}});
    if(!access){reply.code(403).send({error:'Você não tem permissão para administrar esta empresa.'});return null;}
    return target;
  }
  if(request.user.role===Role.TIME_REVIEWER){
    const target=requested||request.user.tenantId;
    const tenant=await prisma.tenant.findFirst({where:{id:target,document:{not:'99999999999999'}},select:{id:true}});
    if(!tenant){reply.code(404).send({error:'Empresa não encontrada ou indisponível para conferência.'});return null;}
    return tenant.id;
  }
  return request.user.tenantId;
}

function assertFacialLoginAdmin(request:any,reply:any){
  if(request.user.role!==Role.ADMIN){reply.code(403).send({error:'Cadastro facial de login é exclusivo do Administrador. No registro de jornada do colaborador é usada apenas uma selfie simples, sem reconhecimento facial.'});return false;}
  return true;
}

export async function securityRoutes(app: FastifyInstance) {
  app.get('/security/status', { preHandler:[app.authenticate] }, async (request:any) => {
    await recoverLegacyTmpFaceEnrollmentForUser(request.user.userId);
    await recoverStuckFaceEnrollmentForUser(request.user.userId);
    const [policy, credentials, employee, acknowledgement, submission] = await Promise.all([
      getOrCreateSecuritySettings(request.user.tenantId),
      prisma.webAuthnCredential.findMany({ where:{tenantId:request.user.tenantId,userId:request.user.userId}, select:{id:true,label:true,deviceType:true,backedUp:true,createdAt:true,lastUsedAt:true} }),
      request.user.employeeId ? prisma.employee.findUnique({ where:{id:request.user.employeeId}, select:{faceEnrollmentStatus:true,faceEnrolledAt:true,faceTemplateVersion:true,worksite:{select:{id:true,name:true}}} }) : null,
      getCurrentAcknowledgement(request.user.tenantId, request.user.userId),
      request.user.employeeId ? prisma.faceEnrollmentSubmission.findFirst({where:{tenantId:request.user.tenantId,employeeId:request.user.employeeId},orderBy:{submittedAt:'desc'}}) : null
    ]);
    const {rpID,expectedOrigin}=getWebAuthnConfig();
    return { policy:{...policy,requireGeofence:false,requireAccurateGps:false}, credentials, employee, acknowledgement, faceEnrollmentSubmission:submission?publicSubmission(submission):null, biometricNotice:{version:BIOMETRIC_NOTICE_VERSION,hash:BIOMETRIC_NOTICE_HASH,text:BIOMETRIC_NOTICE_TEXT}, webAuthn:{rpID,origin:expectedOrigin,secureContextRequired:true} }; 
  });

  app.get('/security/biometric-notice', { preHandler:[app.authenticate] }, async (request:any) => {
    const acknowledgement=await getCurrentAcknowledgement(request.user.tenantId,request.user.userId);
    return {version:BIOMETRIC_NOTICE_VERSION,hash:BIOMETRIC_NOTICE_HASH,text:BIOMETRIC_NOTICE_TEXT,acknowledged:Boolean(acknowledgement),acknowledgedAt:acknowledgement?.acknowledgedAt??null};
  });

  app.post('/security/biometric-notice/acknowledge', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    const user=await prisma.user.findUnique({where:{id:request.user.userId},select:{mustChangePassword:true}});
    if(!user || user.mustChangePassword)return reply.code(409).send({error:'Troque a senha temporária antes de continuar o cadastro biométrico'});
    const item=await acknowledgeBiometricNotice({tenantId:request.user.tenantId,userId:request.user.userId,employeeId:request.user.employeeId,ip:request.ip,userAgent:String(request.headers['user-agent']??'')});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'BIOMETRIC_NOTICE_ACKNOWLEDGED',entityType:'User',entityId:request.user.userId,metadataJson:{policyVersion:BIOMETRIC_NOTICE_VERSION,noticeHash:BIOMETRIC_NOTICE_HASH}}});
    return {ok:true,acknowledgedAt:item.acknowledgedAt,version:item.policyVersion,onboarding:await refreshOnboardingCompletion(request.user.userId)};
  });

  app.post('/security/biometric-notice/revoke', { preHandler:[app.authenticate] }, async (request:any) => {
    await prisma.biometricNoticeAcknowledgement.updateMany({where:{tenantId:request.user.tenantId,userId:request.user.userId,revokedAt:null},data:{revokedAt:new Date()}});
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'BIOMETRIC_NOTICE_ACKNOWLEDGEMENT_REVOKED',entityType:'User',entityId:request.user.userId}});
    return {ok:true};
  });

  app.post('/security/face-enrollment/submit', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!assertFacialLoginAdmin(request,reply))return;
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const user=await prisma.user.findUnique({where:{id:request.user.userId}});
    if(!user || user.mustChangePassword)return reply.code(409).send({error:'Troque a senha temporária antes de cadastrar o rosto'});
    const acknowledgement=await getCurrentAcknowledgement(request.user.tenantId,request.user.userId);
    if(!acknowledgement)return reply.code(409).send({error:'Registre a ciência do aviso biométrico antes do cadastro facial'});
    const body=z.object({photos:z.array(z.object({pose:z.literal(FacePose.FRONT),imageDataUrl:z.string().min(100)})).length(1)}).parse(request.body);
    const submission=await createAsyncFaceEnrollment({tenantId:request.user.tenantId,employeeId:request.user.employeeId,userId:request.user.userId,photos:body.photos});
    kickFaceEnrollmentWorker();
    return reply.code(202).send({submission:publicSubmission(submission),message:'Selfie recebida. A análise facial rápida foi iniciada imediatamente no servidor.'});
  });

  app.get('/security/face-enrollment/submission/latest', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!assertFacialLoginAdmin(request,reply))return;
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    await recoverLegacyTmpFaceEnrollmentForUser(request.user.userId);
    await recoverStuckFaceEnrollmentForUser(request.user.userId);
    const row=await prisma.faceEnrollmentSubmission.findFirst({where:{tenantId:request.user.tenantId,employeeId:request.user.employeeId,userId:request.user.userId},orderBy:{submittedAt:'desc'}});
    if(row?.status==='PENDING')kickFaceEnrollmentWorker();
    return {submission:row?publicSubmission(row):null};
  });

  app.post('/security/face-enrollment/submission/:id/retry', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!assertFacialLoginAdmin(request,reply))return;
    const {id}=z.object({id:z.string()}).parse(request.params);
    try{const row=await retryFaceEnrollmentSubmission({tenantId:request.user.tenantId,userId:request.user.userId,submissionId:id});return {submission:publicSubmission(row)};}
    catch(error){return reply.code(409).send({error:error instanceof Error?error.message:'Não foi possível reenviar a análise'});}
  });

  app.get('/security/face-enrollment/challenge', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!assertFacialLoginAdmin(request,reply))return;
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const user=await prisma.user.findUnique({where:{id:request.user.userId}});
    if(!user || user.mustChangePassword)return reply.code(409).send({error:'Troque a senha temporária antes de cadastrar o rosto'});
    const acknowledgement=await getCurrentAcknowledgement(request.user.tenantId,request.user.userId);
    if(!acknowledgement)return reply.code(409).send({error:'Registre a ciência do aviso biométrico antes do cadastro facial'});
    const policy=await getOrCreateSecuritySettings(request.user.tenantId);
    const refs=await prisma.faceReference.findMany({where:{tenantId:request.user.tenantId,employeeId:request.user.employeeId,revokedAt:null},select:{pose:true}});
    const currentPoses=refs.map(r=>r.pose);
    const nextPose=FacePose.FRONT;
    const token=app.jwt.sign({kind:'FACE_ENROLLMENT',userId:request.user.userId,tenantId:request.user.tenantId,role:request.user.role,employeeId:request.user.employeeId,facePose:nextPose,jti:crypto.randomUUID()},{expiresIn:'5m'});
    return {challengeAction:null,enrollmentToken:token,faceReferenceMin:1,currentPoses,nextPose,mode:'FRONT_ONLY',instructions:'Olhe de frente para a câmera. Não precisa virar a cabeça.'};
  });

  app.post('/security/face-enrollment', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!assertFacialLoginAdmin(request,reply))return;
    if(!request.user.employeeId)return reply.code(403).send({error:'Usuário sem vínculo de colaborador'});
    const body=z.object({enrollmentToken:z.string().min(20),pose:z.nativeEnum(FacePose),imageDataUrl:z.string().min(100),embedding:z.array(z.number()).min(64).max(4096),faceQualityScore:z.number().min(0).max(1),livenessScore:z.number().min(0).max(1),antiSpoofScore:z.number().min(0).max(1),livenessChallengePassed:z.literal(true)}).parse(request.body);
    let proof:any;try{proof=app.jwt.verify(body.enrollmentToken);}catch{return reply.code(400).send({error:'Challenge de cadastro facial inválido ou expirado'});}
    if(proof.kind!=='FACE_ENROLLMENT'||proof.userId!==request.user.userId||proof.tenantId!==request.user.tenantId||proof.employeeId!==request.user.employeeId||proof.facePose!==body.pose)return reply.code(400).send({error:'Challenge facial não pertence a este usuário ou pose'});
    const ref=await enrollFaceReference({tenantId:request.user.tenantId,employeeId:request.user.employeeId,actorUserId:request.user.userId,pose:body.pose,imageDataUrl:body.imageDataUrl,embedding:body.embedding,faceQualityScore:body.faceQualityScore,livenessScore:body.livenessScore,antiSpoofScore:body.antiSpoofScore,livenessChallengePassed:body.livenessChallengePassed});
    const onboarding=await refreshOnboardingCompletion(request.user.userId);
    return reply.code(201).send({id:ref.id,pose:ref.pose,onboarding});
  });

  app.post('/security/webauthn/register/options', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!(await assertPasskeyAccount(request,reply)))return;
    const user = await prisma.user.findUniqueOrThrow({ where:{id:request.user.userId}, include:{employee:{select:{id:true,name:true,faceEnrollmentStatus:true}}} });
    if(user.mustChangePassword)return reply.code(409).send({error:'Troque a senha temporária antes de cadastrar a passkey'});
    const existing = await prisma.webAuthnCredential.findMany({ where:{tenantId:user.tenantId,userId:user.id} });
    if(existing.length)return reply.code(409).send({error:'Esta conta já possui uma passkey cadastrada. Para trocar, revogue a credencial atual antes de cadastrar outra.'});
    const {rpID,rpName,expectedOrigin}=getWebAuthnConfig();
    const options = await generateRegistrationOptions({
      rpName, rpID, userName:user.email, userDisplayName:user.employee?.name??user.email,
      userID:new TextEncoder().encode(user.id), attestationType:'none',
      authenticatorSelection:{ residentKey:'preferred', userVerification:'required' },
      excludeCredentials:[]
    });
    // Evita que duas telas/duplos cliques deixem um challenge mais novo invalidando
    // a resposta que o Google Password Manager/iCloud acabou de criar.
    await prisma.webAuthnChallenge.updateMany({where:{tenantId:user.tenantId,userId:user.id,purpose:WebAuthnChallengePurpose.REGISTRATION,usedAt:null},data:{usedAt:new Date()}});
    await prisma.webAuthnChallenge.create({ data:{tenantId:user.tenantId,userId:user.id,challenge:options.challenge,purpose:WebAuthnChallengePurpose.REGISTRATION,expiresAt:new Date(Date.now()+challengeTtlMs)} });
    request.log.info({userId:user.id,role:user.role,rpID,expectedOrigin,challenge:options.challenge.slice(0,10)},'webauthn registration options issued');
    return options;
  });

  app.post('/security/webauthn/register/verify', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!(await assertPasskeyAccount(request,reply)))return;
    const body = z.object({ response:z.any(), label:z.string().max(80).optional() }).parse(request.body) as {response:RegistrationResponseJSON;label?:string};
    const existingCount=await prisma.webAuthnCredential.count({where:{tenantId:request.user.tenantId,userId:request.user.userId}});
    if(existingCount)return reply.code(409).send({error:'Esta conta já possui uma passkey. Nenhuma credencial existente foi substituída.'});
    const clientChallenge=registrationClientChallenge(body.response);
    if(!clientChallenge){
      request.log.warn({userId:request.user.userId,responseId:(body.response as any)?.id},'webauthn registration missing client challenge');
      return reply.code(400).send({error:'A resposta da passkey veio incompleta. Atualize a página e tente cadastrar novamente.',code:'PASSKEY_CLIENT_DATA_INVALID'});
    }
    const challenge = await prisma.webAuthnChallenge.findFirst({ where:{tenantId:request.user.tenantId,userId:request.user.userId,purpose:WebAuthnChallengePurpose.REGISTRATION,challenge:clientChallenge,usedAt:null,expiresAt:{gt:new Date()}}, orderBy:{createdAt:'desc'} });
    if (!challenge){
      request.log.warn({userId:request.user.userId,clientChallenge:clientChallenge.slice(0,10)},'webauthn registration challenge not found');
      return reply.code(400).send({error:'Desafio da passkey expirado ou não corresponde a esta tentativa. Tente cadastrar novamente.',code:'PASSKEY_CHALLENGE_MISMATCH'});
    }
    const {rpID,expectedOrigin}=getWebAuthnConfig();
    let verification:any;
    try{
      verification = await verifyRegistrationResponse({ response:body.response, expectedChallenge:challenge.challenge, expectedOrigin, expectedRPID:rpID, requireUserVerification:true });
    }catch(error:any){
      request.log.warn({userId:request.user.userId,role:request.user.role,rpID,expectedOrigin,responseId:(body.response as any)?.id,errorName:error?.name,errorMessage:error?.message},'webauthn registration verification failed');
      return reply.code(400).send({error:'A passkey foi confirmada no aparelho, mas o servidor não conseguiu validá-la. Atualize a página e tente novamente.',code:'PASSKEY_VERIFY_FAILED',detail:process.env.NODE_ENV==='production'?undefined:String(error?.message??'')});
    }
    if (!verification?.verified || !verification?.registrationInfo){
      request.log.warn({userId:request.user.userId,responseId:(body.response as any)?.id},'webauthn registration not verified');
      return reply.code(400).send({error:'Biometria/passkey não pôde ser validada.',code:'PASSKEY_NOT_VERIFIED'});
    }
    const info = verification.registrationInfo;
    const cred = info.credential;
    try{
      await prisma.$transaction(async tx=>{
        const already=await tx.webAuthnCredential.count({where:{tenantId:request.user.tenantId,userId:request.user.userId}});
        if(already)throw Object.assign(new Error('Esta conta já possui uma passkey. Nenhuma credencial existente foi substituída.'),{statusCode:409});
        await tx.webAuthnCredential.create({data:{tenantId:request.user.tenantId,userId:request.user.userId,credentialId:cred.id,publicKey:Buffer.from(cred.publicKey),counter:BigInt(cred.counter),transports:cred.transports as any,deviceType:info.credentialDeviceType,backedUp:info.credentialBackedUp,label:body.label}});
        await tx.webAuthnChallenge.update({where:{id:challenge.id},data:{usedAt:new Date()}});
        await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'WEBAUTHN_ENROLLED_PRIVILEGED_ACCOUNT',entityType:'User',entityId:request.user.userId,metadataJson:{credentialId:cred.id,deviceType:info.credentialDeviceType,backedUp:info.credentialBackedUp,role:request.user.role}}});
      },{isolationLevel:'Serializable'});
    }catch(error:any){
      if(error?.statusCode===409)return reply.code(409).send({error:error.message});
      request.log.error({err:error,userId:request.user.userId},'webauthn credential save failed');
      return reply.code(500).send({error:'A passkey foi validada, mas não foi possível salvá-la. Tente novamente.',code:'PASSKEY_SAVE_FAILED'});
    }
    request.log.info({userId:request.user.userId,role:request.user.role,credentialId:cred.id},'webauthn credential enrolled');
    return {verified:true,onboarding:await refreshOnboardingCompletion(request.user.userId)};
  });

  // V83.23: colaboradores não usam passkey nas marcações. A autenticação é a sessão
  // de usuário/senha e cada ação exige uma selfie simples para revisão posterior.
  app.post('/security/webauthn/auth/options', { preHandler:[app.authenticate] }, async (_request:any, reply) => {
    return reply.code(410).send({error:'Passkey não é mais utilizada nas marcações do colaborador. Tire a selfie solicitada para registrar a jornada.',code:'PUNCH_PASSKEY_DISABLED'});
  });

  app.post('/security/webauthn/auth/verify', { preHandler:[app.authenticate] }, async (_request:any, reply) => {
    return reply.code(410).send({error:'Passkey não é mais utilizada nas marcações do colaborador.',code:'PUNCH_PASSKEY_DISABLED'});
  });

  app.post('/security/webauthn/review/options', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!(await assertJourneyReviewer(request,reply)))return;
    const targetTenantId=await managementTenantId(request,reply);if(!targetTenantId)return;
    const homeTenantId=request.user.tenantId;
    const {punchId}=z.object({punchId:z.string()}).parse(request.body);
    const punch=await prisma.punch.findFirst({where:{id:punchId,tenantId:targetTenantId,decision:'REVIEW'},select:{id:true}});
    if(!punch)return reply.code(404).send({error:'Marcação pendente não encontrada'});
    const creds=await prisma.webAuthnCredential.findMany({where:{tenantId:homeTenantId,userId:request.user.userId}});
    if(!creds.length)return reply.code(409).send({error:'Cadastre a biometria/passkey deste aprovador antes de aprovar ou rejeitar marcações.',code:'APPROVER_PASSKEY_REQUIRED'});
    const {rpID}=getWebAuthnConfig();
    const options=await generateAuthenticationOptions({rpID,userVerification:'required',allowCredentials:creds.map(x=>({id:x.credentialId,transports:authenticationTransports(x.transports)}))});
    await prisma.webAuthnChallenge.create({data:{tenantId:homeTenantId,userId:request.user.userId,challenge:options.challenge,purpose:WebAuthnChallengePurpose.PUNCH,boundPunchChallengeId:`REVIEW:${targetTenantId}:${punchId}`,expiresAt:new Date(Date.now()+challengeTtlMs)}});
    return options;
  });

  app.post('/security/webauthn/review/verify', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if(!(await assertJourneyReviewer(request,reply)))return;
    const targetTenantId=await managementTenantId(request,reply);if(!targetTenantId)return;
    const homeTenantId=request.user.tenantId;
    const body=z.object({punchId:z.string(),response:z.any()}).parse(request.body) as {punchId:string;response:AuthenticationResponseJSON};
    const bound=`REVIEW:${targetTenantId}:${body.punchId}`;
    const challenge=await prisma.webAuthnChallenge.findFirst({where:{tenantId:homeTenantId,userId:request.user.userId,purpose:WebAuthnChallengePurpose.PUNCH,boundPunchChallengeId:bound,usedAt:null,expiresAt:{gt:new Date()}},orderBy:{createdAt:'desc'}});
    if(!challenge)return reply.code(400).send({error:'Desafio de aprovação expirado'});
    const saved=await prisma.webAuthnCredential.findFirst({where:{tenantId:homeTenantId,userId:request.user.userId,credentialId:body.response.id}});
    if(!saved)return reply.code(404).send({error:'A passkey usada não pertence a este aprovador'});
    const {rpID,expectedOrigin}=getWebAuthnConfig();
    const verification=await verifyAuthenticationResponse({response:body.response,expectedChallenge:challenge.challenge,expectedOrigin,expectedRPID:rpID,requireUserVerification:true,credential:{id:saved.credentialId,publicKey:new Uint8Array(saved.publicKey),counter:Number(saved.counter),transports:transports(saved.transports)}});
    if(!verification.verified)return reply.code(400).send({error:'Biometria/passkey do aprovador não validada'});
    await prisma.$transaction([prisma.webAuthnCredential.update({where:{id:saved.id},data:{counter:BigInt(verification.authenticationInfo.newCounter),lastUsedAt:new Date()}}),prisma.webAuthnChallenge.update({where:{id:challenge.id},data:{usedAt:new Date()}})]);
    const biometricProofToken=app.jwt.sign({kind:'PUNCH_BIOMETRIC',userId:request.user.userId,tenantId:targetTenantId,role:request.user.role,employeeId:request.user.employeeId,credentialId:saved.credentialId,punchChallengeId:bound,jti:crypto.randomUUID()},{expiresIn:'3m'});
    return {verified:true,biometricProofToken,credentialId:saved.credentialId};
  });

  app.post('/security/webauthn/timesheet/options', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    const body=z.object({employeeId:z.string(),month:z.string().regex(MONTH_RE),signer:z.enum(['ADMIN','EMPLOYEE'])}).parse(request.body);
    let targetTenantId:string;
    if(body.signer==='ADMIN'){
      if(!([Role.SUPER_ADMIN,Role.ADMIN] as Role[]).includes(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador ou Super Adm pode assinar o espelho pela empresa.'});
      const target=await managementTenantId(request,reply);if(!target)return;targetTenantId=target;
      const employee=await prisma.employee.findFirst({where:{id:body.employeeId,tenantId:targetTenantId,active:true,archivedAt:null},select:{id:true}});
      if(!employee)return reply.code(404).send({error:'Colaborador não encontrado nesta empresa.'});
    }else{
      if(!request.user.employeeId||request.user.employeeId!==body.employeeId)return reply.code(403).send({error:'O aceite do colaborador só pode ser realizado pelo próprio titular do espelho.'});
      const employee=await prisma.employee.findFirst({where:{id:body.employeeId,userId:request.user.userId,active:true,archivedAt:null},select:{tenantId:true}});
      if(!employee)return reply.code(404).send({error:'Vínculo de colaborador não encontrado.'});
      targetTenantId=employee.tenantId;
    }
    const status=await timesheetAcceptanceStatus(targetTenantId,body.employeeId,body.month);
    if(body.signer==='ADMIN'){
      if(!status.adminCanAccept){
        if(status.hasPendingReviews)return reply.code(409).send({error:'Existem jornadas desta competência aguardando aprovação. Conclua a conferência antes de assinar o espelho.',code:'TIMESHEET_HAS_PENDING_JOURNEYS'});
        return reply.code(409).send({error:`O aceite mensal fica disponível a partir do último dia da competência (${status.acceptanceOpensAt}).`,code:'TIMESHEET_NOT_CLOSED'});
      }
    }else{
      if(status.employeeAcceptedAt)return reply.code(409).send({error:'Este espelho já possui seu aceite WebAuthn.',code:'TIMESHEET_ALREADY_ACCEPTED'});
      if(!status.adminAcceptedAt)return reply.code(409).send({error:'O espelho ainda aguarda o aceite WebAuthn do Administrador.',code:status.outdated?'TIMESHEET_ADMIN_REACCEPT_REQUIRED':'TIMESHEET_ADMIN_ACCEPTANCE_REQUIRED'});
      if(!status.employeeCanAccept)return reply.code(409).send({error:'O espelho ainda não está liberado para o aceite do colaborador.',code:'TIMESHEET_EMPLOYEE_NOT_READY'});
    }
    const homeTenantId=request.user.tenantId;
    const creds=await prisma.webAuthnCredential.findMany({where:{tenantId:homeTenantId,userId:request.user.userId}});
    if(!creds.length)return reply.code(409).send({error:body.signer==='ADMIN'?'Cadastre a biometria/passkey desta conta administrativa antes de assinar o espelho.':'Cadastre uma passkey neste aparelho para assinar seu espelho de ponto.',code:'TIMESHEET_PASSKEY_REQUIRED'});
    const {rpID}=getWebAuthnConfig();
    const options=await generateAuthenticationOptions({rpID,userVerification:'required',allowCredentials:creds.map(x=>({id:x.credentialId,transports:authenticationTransports(x.transports)}))});
    const bound=`TIMESHEET:${body.signer}:${targetTenantId}:${body.employeeId}:${body.month}:${status.snapshotHash}`;
    await prisma.webAuthnChallenge.create({data:{tenantId:homeTenantId,userId:request.user.userId,challenge:options.challenge,purpose:WebAuthnChallengePurpose.TIMESHEET_ACCEPTANCE,boundPunchChallengeId:bound,expiresAt:new Date(Date.now()+challengeTtlMs)}});
    return {...options,timesheet:{employeeId:body.employeeId,month:body.month,signer:body.signer,snapshotHash:status.snapshotHash}};
  });

  app.post('/security/webauthn/timesheet/verify', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    const body=z.object({employeeId:z.string(),month:z.string().regex(MONTH_RE),signer:z.enum(['ADMIN','EMPLOYEE']),response:z.any()}).parse(request.body) as {employeeId:string;month:string;signer:'ADMIN'|'EMPLOYEE';response:AuthenticationResponseJSON};
    let targetTenantId:string;
    if(body.signer==='ADMIN'){
      if(!([Role.SUPER_ADMIN,Role.ADMIN] as Role[]).includes(request.user.role as Role))return reply.code(403).send({error:'Somente Administrador ou Super Adm pode assinar o espelho pela empresa.'});
      const target=await managementTenantId(request,reply);if(!target)return;targetTenantId=target;
      const employee=await prisma.employee.findFirst({where:{id:body.employeeId,tenantId:targetTenantId,active:true,archivedAt:null},select:{id:true}});if(!employee)return reply.code(404).send({error:'Colaborador não encontrado nesta empresa.'});
    }else{
      if(!request.user.employeeId||request.user.employeeId!==body.employeeId)return reply.code(403).send({error:'O aceite do colaborador só pode ser realizado pelo próprio titular do espelho.'});
      const employee=await prisma.employee.findFirst({where:{id:body.employeeId,userId:request.user.userId,active:true,archivedAt:null},select:{tenantId:true}});if(!employee)return reply.code(404).send({error:'Vínculo de colaborador não encontrado.'});targetTenantId=employee.tenantId;
    }
    const status=await timesheetAcceptanceStatus(targetTenantId,body.employeeId,body.month);
    if(body.signer==='ADMIN'&&!status.adminCanAccept)return reply.code(409).send({error:status.hasPendingReviews?'Existem jornadas pendentes nesta competência.':'A competência ainda não foi encerrada para aceite.',code:'TIMESHEET_NOT_READY'});
    if(body.signer==='EMPLOYEE'&&!status.employeeCanAccept)return reply.code(409).send({error:status.employeeAcceptedAt?'Este espelho já possui seu aceite WebAuthn.':'O espelho ainda aguarda um aceite válido do Administrador.',code:'TIMESHEET_EMPLOYEE_NOT_READY'});
    const homeTenantId=request.user.tenantId;
    const bound=`TIMESHEET:${body.signer}:${targetTenantId}:${body.employeeId}:${body.month}:${status.snapshotHash}`;
    const challenge=await prisma.webAuthnChallenge.findFirst({where:{tenantId:homeTenantId,userId:request.user.userId,purpose:WebAuthnChallengePurpose.TIMESHEET_ACCEPTANCE,boundPunchChallengeId:bound,usedAt:null,expiresAt:{gt:new Date()}},orderBy:{createdAt:'desc'}});
    if(!challenge)return reply.code(400).send({error:'Desafio de assinatura do espelho expirado. Gere um novo aceite.'});
    const saved=await prisma.webAuthnCredential.findFirst({where:{tenantId:homeTenantId,userId:request.user.userId,credentialId:body.response.id}});
    if(!saved)return reply.code(404).send({error:'A passkey utilizada não pertence a esta conta.'});
    const {rpID,expectedOrigin}=getWebAuthnConfig();
    const verification=await verifyAuthenticationResponse({response:body.response,expectedChallenge:challenge.challenge,expectedOrigin,expectedRPID:rpID,requireUserVerification:true,credential:{id:saved.credentialId,publicKey:new Uint8Array(saved.publicKey),counter:Number(saved.counter),transports:transports(saved.transports)}});
    if(!verification.verified)return reply.code(400).send({error:'A assinatura WebAuthn não pôde ser validada.'});
    const acceptedAt=new Date();
    await prisma.$transaction(async tx=>{
      await tx.webAuthnCredential.update({where:{id:saved.id},data:{counter:BigInt(verification.authenticationInfo.newCounter),lastUsedAt:acceptedAt}});
      await tx.webAuthnChallenge.update({where:{id:challenge.id},data:{usedAt:acceptedAt}});
      if(body.signer==='ADMIN'){
        const existing=await tx.timesheetAcceptance.findUnique({where:{tenantId_employeeId_month:{tenantId:targetTenantId,employeeId:body.employeeId,month:body.month}}});
        const sameSnapshot=existing?.snapshotHash===status.snapshotHash;
        await tx.timesheetAcceptance.upsert({
          where:{tenantId_employeeId_month:{tenantId:targetTenantId,employeeId:body.employeeId,month:body.month}},
          create:{tenantId:targetTenantId,employeeId:body.employeeId,month:body.month,snapshotHash:status.snapshotHash,adminUserId:request.user.userId,adminCredentialId:saved.credentialId,adminAcceptedAt:acceptedAt},
          update:{snapshotHash:status.snapshotHash,adminUserId:request.user.userId,adminCredentialId:saved.credentialId,adminAcceptedAt:acceptedAt,...(!sameSnapshot?{employeeUserId:null,employeeCredentialId:null,employeeAcceptedAt:null}:{})}
        });
        await tx.auditEvent.create({data:{tenantId:targetTenantId,actorUserId:request.user.userId,action:'MONTHLY_TIMESHEET_ADMIN_ACCEPTED_WEBAUTHN',entityType:'Employee',entityId:body.employeeId,metadataJson:{month:body.month,snapshotHash:status.snapshotHash,credentialId:saved.credentialId,acceptedAt:acceptedAt.toISOString()}}});
      }else{
        const updated=await tx.timesheetAcceptance.updateMany({where:{tenantId:targetTenantId,employeeId:body.employeeId,month:body.month,snapshotHash:status.snapshotHash,adminAcceptedAt:{not:null},employeeAcceptedAt:null},data:{employeeUserId:request.user.userId,employeeCredentialId:saved.credentialId,employeeAcceptedAt:acceptedAt}});
        if(!updated.count)throw Object.assign(new Error('O espelho mudou ou o aceite do Administrador não está mais válido. Gere o documento novamente.'),{statusCode:409});
        await tx.auditEvent.create({data:{tenantId:targetTenantId,actorUserId:request.user.userId,action:'MONTHLY_TIMESHEET_EMPLOYEE_ACCEPTED_WEBAUTHN',entityType:'Employee',entityId:body.employeeId,metadataJson:{month:body.month,snapshotHash:status.snapshotHash,credentialId:saved.credentialId,acceptedAt:acceptedAt.toISOString()}}});
      }
    });
    return {verified:true,acceptance:await timesheetAcceptanceStatus(targetTenantId,body.employeeId,body.month)};
  });

  app.delete('/security/webauthn/credentials/:id', { preHandler:[app.authenticate] }, async (_request:any, reply) => {
    return reply.code(403).send({error:'A passkey vinculada não pode ser substituída pelo próprio usuário. Para trocar a credencial, o ADMIN/RH deve revogá-la na gestão de colaboradores.'});
  });

  app.get('/security/worksites/:id/presence-code', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    if (!(await assertAdmin(request,reply))) return;
    const {id}=z.object({id:z.string()}).parse(request.params);
    const targetTenantId=await managementTenantId(request,reply);if(!targetTenantId)return;const ws=await prisma.worksite.findFirst({where:{id,tenantId:targetTenantId,active:true}});
    if(!ws)return reply.code(404).send({error:'Local não encontrado'});
    return {worksiteId:id,token:createPresenceToken(id,'QR',90),expiresInSeconds:90};
  });

  app.get('/security/face-reference/:id/image', { preHandler:[app.authenticate] }, async (_request:any, reply) => {
    return reply.code(410).send({error:'O Apontamento Digital não mantém fotos faciais após o processamento. Somente o template biométrico necessário ao reconhecimento é preservado.'});
  });

  app.get('/security/attempts/:id/selfie', { preHandler:[app.authenticate] }, async (_request:any, reply) => {
    return reply.code(410).send({error:'Selfies de tentativas não são mantidas pelo Apontamento Digital após a validação.'});
  });

  app.get('/security/punches/:id/selfie', { preHandler:[app.authenticate] }, async (_request:any, reply) => {
    return reply.code(410).send({error:'Selfies de marcações não são mantidas pelo Apontamento Digital após a validação.'});
  });

}
