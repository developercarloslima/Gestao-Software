import crypto from 'node:crypto';
import { FastifyInstance } from 'fastify';
import { Role } from '@prisma/client';
import bcrypt from 'bcryptjs';
import { z } from 'zod';
import {
  generateAuthenticationOptions,
  verifyAuthenticationResponse,
  type AuthenticationResponseJSON,
  type AuthenticatorTransport
} from '@simplewebauthn/server';
import { prisma } from '../lib/prisma.js';
import { getOnboardingState, refreshOnboardingCompletion } from '../services/onboarding.service.js';
import { analyzeFaceLoginDataUrl, analyzeFaceReferenceDataUrl } from '../services/face-enrollment-async.service.js';
import { decryptJson } from '../services/biometric-storage.service.js';
import { getOrCreateSecuritySettings } from '../services/security-settings.service.js';
import { FaceApiError, faceApiSimilarity, registerFaceEmployeeDataUrl, registerFaceEmployeeEncoding, verifyFaceEmployeeDataUrl } from '../services/face-api.service.js';
import { tenantBrand } from '../services/brand-guide.service.js';

const authChallengeTtlMs=3*60_000;

function collapseKnownCompanyAliases(rows:any[]){return rows;}

function transports(value:unknown):AuthenticatorTransport[]|undefined{
  return Array.isArray(value)?value as AuthenticatorTransport[]:undefined;
}

function getWebAuthnConfig(){
  const renderHostname=process.env.RENDER_EXTERNAL_HOSTNAME?.trim();
  const production=process.env.NODE_ENV==='production';
  const configuredRp=(process.env.WEBAUTHN_RP_ID??'').trim();
  const configuredOrigin=(process.env.WEBAUTHN_ORIGIN??'').trim();
  const useRenderRp=Boolean(renderHostname)&&(!configuredRp||(production&&['localhost','127.0.0.1'].includes(configuredRp.toLowerCase())));
  const useRenderOrigin=Boolean(renderHostname)&&(!configuredOrigin||(production&&/^https?:\/\/(localhost|127\.0\.0\.1)(:\d+)?$/i.test(configuredOrigin)));
  const rpID=(useRenderRp?renderHostname!:(configuredRp||'localhost')).replace(/^https?:\/\//i,'').split('/')[0].split(':')[0];
  const expectedOrigin=useRenderOrigin?`https://${renderHostname}`:(configuredOrigin||'http://localhost:5173');
  return {rpID,expectedOrigin};
}

function cosine(a:number[],b:number[]){
  if(!a.length||a.length!==b.length)return 0;
  let dot=0,aa=0,bb=0;
  for(let i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
  return aa&&bb?Math.max(0,Math.min(1,dot/(Math.sqrt(aa)*Math.sqrt(bb)))):0;
}

async function within<T>(promise:Promise<T>,ms:number,message:string):Promise<T>{
  let timer:ReturnType<typeof setTimeout>|undefined;
  try{
    return await Promise.race([
      promise,
      new Promise<T>((_,reject)=>{timer=setTimeout(()=>reject(new Error(message)),ms);})
    ]);
  }finally{if(timer)clearTimeout(timer);}
}

async function findLoginUser(input:{username:string;tenantDocument?:string}){
  const candidates=await prisma.user.findMany({
    where:{
      email:input.username.toLowerCase(),active:true,
      ...(input.tenantDocument?{tenant:{document:input.tenantDocument.replace(/\D/g,'')}}:{})
    },
    include:{employee:true,tenant:true},take:2
  });
  if(candidates.length>1&&!input.tenantDocument)throw Object.assign(new Error('Este usuário existe em mais de uma empresa. Informe o CNPJ/identificador da empresa.'),{statusCode:409});
  const user=candidates[0];
  if(!user||(user.employee&&!user.employee.active))throw Object.assign(new Error('Conta não encontrada ou inativa'),{statusCode:401});
  return user;
}

async function sessionResponse(app:FastifyInstance,user:any){
  const token=app.jwt.sign({userId:user.id,tenantId:user.tenantId,role:user.role,employeeId:user.employee?.id},{expiresIn:'12h'});
  const onboarding=user.role===Role.SUPER_ADMIN
    ? {required:false,accessReady:true,mustChangePassword:false,passwordChanged:true,biometricNoticeAcknowledged:true,faceEnrolled:false,facePending:false,faceEnrollmentStatus:null,faceEnrollmentSubmissionStatus:null,faceEnrollmentSubmissionId:null,platformBiometricEnrolled:false,completed:true,completedAt:user.onboardingCompletedAt??new Date()}
    : await getOnboardingState(user.id);

  let managedTenants:any[]=[];
  if(user.role===Role.SUPER_ADMIN||user.role===Role.TIME_REVIEWER){
    // SUPER_ADMIN e o responsável global de ponto podem alternar entre
    // todas as empresas operacionais. O TIME_REVIEWER continua restrito às telas
    // de presença/aprovação/espelho; a troca de tenant não amplia suas permissões.
    managedTenants=collapseKnownCompanyAliases(await prisma.tenant.findMany({where:{document:{not:'99999999999999'}},orderBy:{name:'asc'}}));
  }else if(user.role===Role.ADMIN){
    const accesses=await prisma.adminTenantAccess.findMany({where:{userId:user.id},include:{tenant:true},orderBy:{tenant:{name:'asc'}}});
    managedTenants=collapseKnownCompanyAliases(accesses.map(x=>x.tenant));
  }else if(user.tenant){managedTenants=[user.tenant];}

  const managed=managedTenants.map(t=>({id:t.id,name:t.name,legalName:t.legalName??null,document:t.document??null,brand:tenantBrand(t)}));
  const preferred=managed.find(t=>t.id===user.tenantId)??managed[0]??null;
  return {token,user:{id:user.id,tenantId:preferred?.id??user.tenantId,homeTenantId:user.tenantId,username:user.email,email:user.contactEmail??null,role:user.role,employeeId:user.employee?.id,name:user.displayName??user.employee?.name??user.email,tenant:preferred?.name??user.tenant.name,brand:preferred?.brand??tenantBrand(user.tenant),managedTenants:managed,onboarding}};
}

export async function authRoutes(app: FastifyInstance) {
  app.post('/auth/login', async (request, reply) => {
    const body = z.object({ username: z.string().trim().min(2).max(120), password: z.string().min(6), tenantDocument: z.string().max(30).optional(), passkeyMode:z.enum(['DEFAULT','PHONE']).default('DEFAULT') }).parse(request.body);
    let user:any;
    try{user=await findLoginUser(body);}catch(error:any){return reply.code(error?.statusCode??401).send({error:error?.message??'Credenciais inválidas'});}
    if(!(await bcrypt.compare(body.password,user.passwordHash)))return reply.code(401).send({error:'Credenciais inválidas'});
    // V83.76: ADMIN, RH e o responsável de conferência de ponto usam passkey como segundo fator de conta.
    // Colaboradores e demais perfis entram somente com usuário + senha; as
    // marcações de jornada usam selfie simples e aprovação posterior.
    if(user.role===Role.ADMIN||user.role===Role.HR||user.role===Role.TIME_REVIEWER){
      const creds=await prisma.webAuthnCredential.findMany({where:{tenantId:user.tenantId,userId:user.id}});
      if(creds.length){
        const {rpID}=getWebAuthnConfig();
        const allowCredentials=body.passkeyMode==='PHONE'
          ? creds.map(x=>({id:x.credentialId}))
          : creds.map(x=>({id:x.credentialId,transports:Array.from(new Set([...(transports(x.transports)??[]),'hybrid' as AuthenticatorTransport]))}));
        const options=await generateAuthenticationOptions({
          rpID,
          userVerification:'required',
          allowCredentials,
          ...(body.passkeyMode==='PHONE'?{preferredAuthenticatorType:'remoteDevice'}:{})
        } as any);
        // Em modo celular, reforça a preferência pelo transporte híbrido. Navegadores
        // compatíveis exibem a experiência nativa de "outro dispositivo", normalmente
        // com QR Code. O QR continua pertencendo ao WebAuthn/FIDO do navegador.
        if(body.passkeyMode==='PHONE'){
          (options as any).hints=['hybrid'];
          (options as any).allowCredentials=creds.map(x=>({id:x.credentialId}));
        }
        await prisma.webAuthnChallenge.updateMany({where:{tenantId:user.tenantId,userId:user.id,purpose:'AUTHENTICATION',usedAt:null},data:{usedAt:new Date()}});
        const challenge=await prisma.webAuthnChallenge.create({data:{tenantId:user.tenantId,userId:user.id,challenge:options.challenge,purpose:'AUTHENTICATION',expiresAt:new Date(Date.now()+authChallengeTtlMs)}});
        return {passkeyRequired:true,challengeId:challenge.id,passkeyMode:body.passkeyMode,options};
      }
      // Conta administrativa/conferência ainda sem passkey: libera o painel para permitir o cadastro
      // da credencial; ações administrativas protegidas continuam bloqueadas.
      return sessionResponse(app,user);
    }
    return sessionResponse(app,user);
  });

  // v83.18: o login passwordless foi desativado. A passkey só pode ser
  // solicitada depois que usuário e senha forem validados em /auth/login.
  app.post('/auth/webauthn/options',async(_request,reply)=>{
    return reply.code(410).send({error:'Login direto por passkey desativado. Contas administrativas protegidas devem informar usuário e senha primeiro.'});
  });

  app.post('/auth/webauthn/verify',async(request,reply)=>{
    const body=z.object({challengeId:z.string(),response:z.any()}).parse(request.body) as {challengeId:string;response:AuthenticationResponseJSON};
    const challenge=await prisma.webAuthnChallenge.findFirst({where:{id:body.challengeId,purpose:'AUTHENTICATION',usedAt:null,expiresAt:{gt:new Date()}},include:{user:{include:{employee:true,tenant:true}}}});
    if(!challenge||challenge.challenge.startsWith('FACE_'))return reply.code(400).send({error:'Desafio de login expirado ou inválido'});
    const saved=await prisma.webAuthnCredential.findFirst({where:{tenantId:challenge.tenantId,userId:challenge.userId,credentialId:body.response.id}});
    if(!saved)return reply.code(404).send({error:'Credencial biométrica não reconhecida'});
    const {rpID,expectedOrigin}=getWebAuthnConfig();
    const verification=await verifyAuthenticationResponse({response:body.response,expectedChallenge:challenge.challenge,expectedOrigin,expectedRPID:rpID,requireUserVerification:true,credential:{id:saved.credentialId,publicKey:new Uint8Array(saved.publicKey),counter:Number(saved.counter),transports:transports(saved.transports)}});
    if(!verification.verified)return reply.code(401).send({error:'Biometria do dispositivo não validada'});
    await prisma.$transaction([
      prisma.webAuthnCredential.update({where:{id:saved.id},data:{counter:BigInt(verification.authenticationInfo.newCounter),lastUsedAt:new Date()}}),
      prisma.webAuthnChallenge.update({where:{id:challenge.id},data:{usedAt:new Date()}}),
      prisma.auditEvent.create({data:{tenantId:challenge.tenantId,actorUserId:challenge.userId,action:'LOGIN_WEBAUTHN_SUCCESS',entityType:'User',entityId:challenge.userId}})
    ]);
    return sessionResponse(app,challenge.user);
  });

  // v83.18: reconhecimento facial não é mais método de login do Apontamento Digital.
  // A Face API continua disponível para Totem e demais fluxos faciais do ponto.
  app.post('/auth/face/options',async(_request,reply)=>{
    return reply.code(410).send({error:'Login facial desativado. Use usuário e senha; contas administrativas protegidas também confirmam a passkey quando cadastrada.'});
  });

  app.post('/auth/face/verify',async(_request,reply)=>{
    return reply.code(410).send({error:'Login facial desativado. Use usuário e senha; contas administrativas protegidas também confirmam a passkey quando cadastrada.'});
  });

  app.get('/auth/onboarding-status', { preHandler:[app.authenticate] }, async (request:any) => {
    if(request.user.role===Role.SUPER_ADMIN){
      return {required:false,accessReady:true,mustChangePassword:false,passwordChanged:true,biometricNoticeAcknowledged:true,faceEnrolled:false,facePending:false,faceEnrollmentStatus:null,faceEnrollmentSubmissionStatus:null,faceEnrollmentSubmissionId:null,platformBiometricEnrolled:false,completed:true,completedAt:new Date()};
    }
    if(request.user.internalAdmin&&request.user.role===Role.ADMIN){
      return {required:false,accessReady:true,mustChangePassword:false,passwordChanged:true,biometricNoticeAcknowledged:true,faceEnrolled:true,facePending:false,faceEnrollmentStatus:'ACTIVE',faceEnrollmentSubmissionStatus:'APPROVED',faceEnrollmentSubmissionId:null,platformBiometricEnrolled:true,completed:true,completedAt:new Date()};
    }
    return refreshOnboardingCompletion(request.user.userId);
  });

  app.post('/auth/change-password', { preHandler:[app.authenticate] }, async (request:any, reply) => {
    const body = z.object({ currentPassword:z.string().min(6), newPassword:z.string().min(10).max(128) }).parse(request.body);
    if (body.currentPassword === body.newPassword) return reply.code(400).send({error:'A nova senha deve ser diferente da senha temporária.'});
    const user = await prisma.user.findUnique({where:{id:request.user.userId}});
    if(!user || !(await bcrypt.compare(body.currentPassword,user.passwordHash))) return reply.code(401).send({error:'Senha atual inválida'});
    const passwordHash=await bcrypt.hash(body.newPassword,12);
    await prisma.$transaction([
      prisma.user.update({where:{id:user.id},data:{passwordHash,mustChangePassword:false,passwordChangedAt:new Date(),onboardingCompletedAt:null}}),
      prisma.auditEvent.create({data:{tenantId:user.tenantId,actorUserId:user.id,action:'FIRST_ACCESS_PASSWORD_CHANGED',entityType:'User',entityId:user.id}})
    ]);
    return {ok:true,onboarding:await refreshOnboardingCompletion(user.id)};
  });
}
