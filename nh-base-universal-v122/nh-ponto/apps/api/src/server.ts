import 'dotenv/config';
import Fastify from 'fastify';
import cors from '@fastify/cors';
import helmet from '@fastify/helmet';
import jwt from '@fastify/jwt';
import rateLimit from '@fastify/rate-limit';
import fastifyStatic from '@fastify/static';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { authRoutes } from './routes/auth.js';
import { meRoutes } from './routes/me.js';
import { punchRoutes } from './routes/punches.js';
import { adjustmentRoutes } from './routes/adjustments.js';
import { dashboardRoutes } from './routes/dashboard.js';
import { deviceRoutes } from './routes/devices.js';
import { assistantRoutes } from './routes/assistant.js';
import { adminRoutes } from './routes/admin.js';
import { timeRoutes } from './routes/time.js';
import { payrollRoutes } from './routes/payroll.js';
import { auditRoutes } from './routes/audit.js';
import { securityRoutes } from './routes/security.js';
import { internalRoutes } from './routes/internal.js';
import { kioskRoutes } from './routes/kiosk.js';
import { superAdminRoutes } from './routes/super-admin.js';
import { attendanceRoutes } from './routes/attendance.js';
import { monitoringRoutes } from './routes/monitoring.js';
import { purgeLegacyBiometricPhotosOnce, runBiometricRetention } from './services/retention.service.js';
import { getOnboardingState } from './services/onboarding.service.js';
import { prewarmFaceEngines, runFaceEnrollmentWorkerOnce } from './services/face-enrollment-async.service.js';
import { resetAllPasskeysOnce } from './services/all-passkey-reset.service.js';
import { prisma } from './lib/prisma.js';
import { syncNovoHorizonteWorksites } from './services/worksite.service.js';
import { runAutoCloseExpiredJourneys } from './services/auto-close.service.js';
import { ensureDefaultShiftModels } from './services/shift-models.service.js';
import { closeStaleWorkMonitoringSessions, runWorkMonitorRetention } from './services/work-monitor.service.js';
import { runJourneyApprovalMaintenance } from './services/journey-approval-maintenance.service.js';
import { runBankHoursMaintenance } from './services/bank-hours-maintenance.service.js';
import { assertPontoEnabled } from './services/gestao-entitlement.service.js';

const app = Fastify({ logger: true, bodyLimit: 20 * 1024 * 1024 });

// Render exposes the public hostname automatically. In a single-service deployment
// we can derive the WebAuthn RP and browser origin from it without hardcoding a URL.
const renderHostname = process.env.RENDER_EXTERNAL_HOSTNAME?.trim();
const inferredOrigin = renderHostname ? `https://${renderHostname}` : undefined;
if (!process.env.WEB_ORIGIN && inferredOrigin) process.env.WEB_ORIGIN = inferredOrigin;
if (!process.env.WEBAUTHN_RP_ID && renderHostname) process.env.WEBAUTHN_RP_ID = renderHostname;
if (!process.env.WEBAUTHN_ORIGIN && inferredOrigin) process.env.WEBAUTHN_ORIGIN = inferredOrigin;

const jwtSecret = process.env.JWT_SECRET ?? 'dev-secret-change-me';
if (process.env.NODE_ENV === 'production') {
  if(jwtSecret.length < 32) throw new Error('JWT_SECRET deve ter pelo menos 32 caracteres em produção');
  for(const key of ['BIOMETRIC_ENCRYPTION_KEY','AUDIT_PRIVACY_HASH_KEY','PRESENCE_SIGNING_SECRET','WEBAUTHN_RP_ID','WEBAUTHN_ORIGIN'] as const){if(!process.env[key])throw new Error(`${key} é obrigatória em produção`);}
  if(!String(process.env.WEBAUTHN_ORIGIN).startsWith('https://'))throw new Error('WEBAUTHN_ORIGIN deve usar HTTPS em produção');
}
await app.register(cors, { origin: process.env.WEB_ORIGIN?.split(',') ?? true, credentials: true });
await app.register(helmet);
await app.register(rateLimit, { max: 180, timeWindow: '1 minute' });
await app.register(jwt, { secret: jwtSecret });

// Gestão Software: a licença da empresa e do módulo Apontamento Digital é
// verificada antes de qualquer rota operacional. /health permanece disponível
// para o Render e para diagnóstico mesmo quando a empresa está suspensa.
app.addHook('onRequest',async(request,reply)=>{
  const path=String(request.url??'').split('?')[0];
  if(path==='/health')return;
  const entitlement=await assertPontoEnabled();
  if(!entitlement.allowed)return reply.code(403).send({error:entitlement.message,code:entitlement.code});
});

app.decorate('authenticate', async function(request: any, reply: any) {
  try { await request.jwtVerify(); }
  catch { return reply.code(401).send({ error: 'Não autenticado' }); }

  const path = String(request.routeOptions?.url ?? request.url ?? '').split('?')[0];
  const requestedTenantHeader = request.headers?.['x-tenant-id'];
  const requestedTenantId = typeof requestedTenantHeader === 'string' ? requestedTenantHeader.trim() : '';

  // V83.36 — contexto multiempresa. SUPER_ADMIN pode administrar qualquer empresa.
  // ADMIN comum só pode trocar para empresas explicitamente liberadas pelo Super Adm.
  // Rotas de autenticação/segurança permanecem sempre no tenant de origem da conta,
  // para que senha e passkey não mudem de vínculo ao trocar a empresa em gestão.
  const tenantScopedPath = !path.startsWith('/auth/') && !path.startsWith('/security/') && path !== '/me';
  const method=String(request.method??'GET').toUpperCase();
  // V83.44 — a empresa selecionada no seletor do ADMIN afeta somente a gestão.
  // A jornada pessoal sempre usa o tenant do próprio Employee do administrador.
  const personalAttendancePath =
    path==='/punches' || path==='/punches/actions' || path==='/punches/precheck' || path==='/punches/challenge' || path==='/punches/me' ||
    path==='/time/me' || path==='/bank/me' || path==='/assistant/explain-my-day' || path==='/adjustments/me' || path==='/attendance/calendar' || path==='/attendance/absence-justifications' ||
    (path==='/adjustments' && method==='POST') || path==='/devices/register' || path.startsWith('/monitoring/');
  // Sessão SSO do administrador principal vem do backend NH já autenticado.
  // Ela não representa um usuário multiempresa do Apontamento Digital.
  if (request.user?.internalAdmin === true) return;

  if(personalAttendancePath && request.user?.employeeId){
    const attendanceEmployee=await prisma.employee.findFirst({where:{id:request.user.employeeId,active:true,archivedAt:null},select:{tenantId:true}});
    if(attendanceEmployee)request.user.tenantId=attendanceEmployee.tenantId;
  } else if (tenantScopedPath) {
    if (request.user?.role === 'SUPER_ADMIN') {
      if(requestedTenantId){const tenant = await prisma.tenant.findUnique({ where: { id: requestedTenantId }, select: { id: true } });if (!tenant) return reply.code(404).send({ error: 'Empresa não encontrada' });request.user.tenantId = tenant.id;}
    } else if (request.user?.role === 'ADMIN') {
      const access=requestedTenantId
        ? await prisma.adminTenantAccess.findUnique({where:{userId_tenantId:{userId:request.user.userId,tenantId:requestedTenantId}}})
        : await prisma.adminTenantAccess.findFirst({where:{userId:request.user.userId},orderBy:{createdAt:'asc'}});
      if(!access)return reply.code(403).send({error:'Você não possui nenhuma empresa liberada pelo Super Adm.'});
      request.user.tenantId=access.tenantId;
    } else if (request.user?.role === 'TIME_REVIEWER') {
      // O responsável global de ponto pode alternar entre empresas, mas as rotas
      // que ele acessa continuam limitadas pelo perfil TIME_REVIEWER.
      const targetId=requestedTenantId||request.user.tenantId;
      const tenant=await prisma.tenant.findFirst({where:{id:targetId,document:{not:'99999999999999'}},select:{id:true}});
      if(!tenant)return reply.code(404).send({error:'Empresa não encontrada ou indisponível para conferência.'});
      request.user.tenantId=tenant.id;
    }
  }

  if (request.user?.role === 'SUPER_ADMIN') return;
  const onboardingAllowed = [
    '/auth/onboarding-status', '/auth/change-password', '/security/status',
    '/security/biometric-notice', '/security/biometric-notice/acknowledge',
    '/security/face-enrollment/challenge', '/security/face-enrollment',
    '/security/face-enrollment/submit', '/security/face-enrollment/submission/latest',
    '/security/webauthn/register/options', '/security/webauthn/register/verify'
  ];
  const onboardingDynamicAllowed = path.startsWith('/security/face-enrollment/submission/') && path.endsWith('/retry');
  if (!onboardingAllowed.includes(path) && !onboardingDynamicAllowed) {
    const onboarding = await getOnboardingState(request.user.userId);
    if (!onboarding.accessReady) return reply.code(428).send({ error: 'Primeiro acesso incompleto. Conclua a troca de senha obrigatória para abrir o painel.', code: 'ONBOARDING_REQUIRED', onboarding });
  }
});

declare module 'fastify' { interface FastifyInstance { authenticate: any } }

app.get('/health', async () => ({ ok: true, service: 'nh-ponto', version: '0.8.20-v83.84', faceEngine: 'FastAPI face_recognition/dlib', punchIdentity: 'USERNAME_PASSWORD_PLUS_SELFIE_REVIEW', at: new Date().toISOString() }));
await app.register(authRoutes);
await app.register(meRoutes);
await app.register(punchRoutes);
await app.register(adjustmentRoutes);
await app.register(dashboardRoutes);
await app.register(deviceRoutes);
await app.register(assistantRoutes);
await app.register(adminRoutes);
await app.register(timeRoutes);
await app.register(payrollRoutes);
await app.register(auditRoutes);
await app.register(securityRoutes);
await app.register(internalRoutes);
await app.register(kioskRoutes);
await app.register(superAdminRoutes);
await app.register(attendanceRoutes);
await app.register(monitoringRoutes);

// Render deployment: serve the Vite build from the same Fastify process.
// This keeps WebAuthn, cookies/CORS and the API under one HTTPS origin.
if (process.env.SERVE_WEB === 'true') {
  const currentDir = path.dirname(fileURLToPath(import.meta.url));
  const webDist = path.resolve(currentDir, '../../web/dist');
  await app.register(fastifyStatic, { root: webDist, prefix: '/', setHeaders(res:any,filePath:string){
    if(filePath.includes(`${path.sep}models${path.sep}human${path.sep}`)) res.setHeader('Cache-Control','public, max-age=604800, immutable');
  } });
}

app.setErrorHandler((error, request, reply) => {
  app.log.error({ err:error, requestId:request.id, method:request.method, url:request.url }, 'request failed');
  const raw:any=error as any;
  let status=Number(raw?.statusCode)||0;
  let message=error instanceof Error?error.message:'Erro de requisição';
  let code=typeof raw?.code==='string'?raw.code:'REQUEST_FAILED';

  if(raw?.name==='ZodError'){
    status=400;code='VALIDATION_ERROR';
    message=Array.isArray(raw?.issues)&&raw.issues[0]?.message?raw.issues[0].message:'Confira os dados informados e tente novamente.';
  }else if(code==='P2002'){
    status=409;message='Já existe um registro com estes dados. Atualize a página e tente novamente.';
  }else if(code==='P2003'){
    status=409;message='Não foi possível concluir porque existe um vínculo de dados inválido ou removido. Atualize a página e tente novamente.';
  }else if(code==='P2025'){
    status=404;message='O registro solicitado não existe mais. Atualize a página.';
  }else if(['P2024','P1001','P1002','P1017'].includes(code)){
    status=503;message='O banco de dados está temporariamente ocupado ou indisponível. Tente novamente em alguns segundos.';
  }else if(!status){
    status=500;code='UNEXPECTED_ERROR';message='Não foi possível concluir esta operação. Nenhum dado deve ser reenviado repetidamente; atualize a página e tente novamente.';
  }else if(status>=500){
    code=code==='REQUEST_FAILED'?'SERVER_ERROR':code;
    message='Não foi possível concluir esta operação agora. Tente novamente em alguns segundos.';
  }

  reply.code(status>=400&&status<600?status:500).send({error:message,code,requestId:request.id});
});

// V83.23: reset único das passkeys do Apontamento Digital para limpar credenciais antigas.
// Colaboradores deixam de usar passkey; somente ADMIN/RH cadastram novamente.
try{const allPasskeyReset=await resetAllPasskeysOnce();app.log.info({allPasskeyReset},'all passkey reset migration completed');}
catch(error){app.log.error(error,'all passkey reset migration failed');throw error;}

try{const defaults=await ensureDefaultShiftModels();app.log.info({defaults},'default shift models ensured');}catch(error){app.log.error(error,'default shift models failed');}

const port = Number(process.env.PORT ?? process.env.API_PORT ?? 3333);
await app.listen({ port, host: '0.0.0.0' });

// V83.50: mantém a lista de unidades oficiais sincronizada sem qualquer geocerca.
// A localização usada na jornada vem exclusivamente do GPS capturado junto da selfie.
void (async()=>{
  try{
    if(process.env.GS_TENANT_MODE==='true')return;
    const tenants=await prisma.tenant.findMany({where:{name:{contains:'Empresa Modelo',mode:'insensitive'}},select:{id:true,name:true}});
    for(const tenant of tenants){
      const result=await syncNovoHorizonteWorksites(tenant.id);
      app.log.info({tenant:tenant.name,result},'NH office sync completed');
    }
  }catch(error){app.log.error(error,'NH office sync failed');}
})();


// V83.74: toda saída manual exige selfie. Após 10 minutos do fim previsto o ponto
// trava e permite somente a finalização manual com selfie. Ao completar 15 minutos
// sem saída, o servidor encerra automaticamente sem selfie (única exceção) e deixa
// a saída em REVIEW para decisão humana.
const autoCloseTimer=setInterval(()=>runAutoCloseExpiredJourneys().then(r=>{if(r.closed||r.failed)app.log.info({autoClose:r},'automatic journey close scan');}).catch(err=>app.log.error(err,'automatic journey close failed')),20_000);
autoCloseTimer.unref();
setTimeout(()=>runAutoCloseExpiredJourneys().then(r=>{if(r.closed||r.failed)app.log.info({autoClose:r},'initial automatic journey close scan');}).catch(err=>app.log.error(err,'initial automatic journey close failed')),5_000).unref();

// V83.78: jornadas de ADMIN são consolidadas sem depender de aprovação humana.
// Jornadas comuns que permanecem pendentes por 4 dias são aprovadas automaticamente.
const journeyApprovalTimer=setInterval(()=>runJourneyApprovalMaintenance().then(r=>{if(r.adminApproved||r.expiredApproved||r.failed)app.log.info({journeyApproval:r},'journey approval maintenance');}).catch(err=>app.log.error(err,'journey approval maintenance failed')),15*60*1000);
journeyApprovalTimer.unref();
setTimeout(()=>runJourneyApprovalMaintenance().then(r=>{if(r.adminApproved||r.expiredApproved||r.failed)app.log.info({journeyApproval:r},'initial journey approval maintenance');}).catch(err=>app.log.error(err,'initial journey approval maintenance failed')),10_000).unref();

// V83.83: mantém o demonstrativo mensal do banco sincronizado automaticamente.
// No primeiro start também reconstrói o mês anterior para cobrir a semana corrente
// quando ela atravessa a virada de competência, sem apagar marcações históricas.
const bankHoursTimer=setInterval(()=>runBankHoursMaintenance(new Date(),false).then(r=>{if(r.refreshed||r.failed)app.log.info({bankHours:r},'bank hours monthly refresh');}).catch(err=>app.log.error(err,'bank hours refresh failed')),15*60*1000);
bankHoursTimer.unref();
setTimeout(()=>runBankHoursMaintenance(new Date(),true).then(r=>app.log.info({bankHours:r},'initial bank hours current-week/month backfill')).catch(err=>app.log.error(err,'initial bank hours backfill failed')),18_000).unref();

// V83.12: limpeza única das fotos biométricas legadas. Templates vetoriais,
// métricas de prova e hashes de integridade são preservados.
try{const purge=await purgeLegacyBiometricPhotosOnce();app.log.info({purge},'legacy biometric photos purge completed');}
catch(error){app.log.error(error,'legacy biometric photos purge failed');}

// Privacy-by-design: remove punch selfies and blocked attempts after the tenant retention window.
const retentionTimer=setInterval(()=>runBiometricRetention().then(r=>app.log.info({retention:r},'biometric retention completed')).catch(err=>app.log.error(err,'biometric retention failed')),6*60*60*1000);
retentionTimer.unref();
setTimeout(()=>runBiometricRetention().catch(err=>app.log.error(err,'initial biometric retention failed')),15_000).unref();

// Durable asynchronous enrollment worker: jobs live in PostgreSQL and are resumed after restarts.
const faceWorkerTimer=setInterval(()=>runFaceEnrollmentWorkerOnce().catch(err=>app.log.error(err,'async face enrollment worker failed')),750);
faceWorkerTimer.unref();
setTimeout(()=>runFaceEnrollmentWorkerOnce().catch(err=>app.log.error(err,'initial async face enrollment worker failed')),350).unref();

// Warm the lightweight reference engine first, then the secure liveness engine.
// This happens after the HTTP port is open, so deploy health checks are not delayed.
setTimeout(()=>prewarmFaceEngines().catch(err=>app.log.error(err,'face engine prewarm failed')),100).unref();

// V83.87: a captura de tela foi removida. Estes dois jobs permanecem temporariamente
// apenas para encerrar sessões legadas e cumprir a retenção dos arquivos antigos já existentes.
const workMonitorStaleTimer=setInterval(()=>closeStaleWorkMonitoringSessions().then(count=>{if(count)app.log.info({count},'stale work monitoring sessions closed');}).catch(err=>app.log.error(err,'work monitoring stale cleanup failed')),30_000);
workMonitorStaleTimer.unref();
setTimeout(()=>closeStaleWorkMonitoringSessions().catch(err=>app.log.error(err,'initial work monitoring stale cleanup failed')),8_000).unref();
const workMonitorRetentionTimer=setInterval(()=>runWorkMonitorRetention().then(result=>{if(result.removedChunks)app.log.info({result},'work monitoring retention completed');}).catch(err=>app.log.error(err,'work monitoring retention failed')),60*60*1000);
workMonitorRetentionTimer.unref();
setTimeout(()=>runWorkMonitorRetention().catch(err=>app.log.error(err,'initial work monitoring retention failed')),30_000).unref();
