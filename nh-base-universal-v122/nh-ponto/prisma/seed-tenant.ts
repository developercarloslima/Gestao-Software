/** Inicialização de um tenant novo e isolado, sem dados ou contas de clientes anteriores.
 * Executado APENAS em GS_TENANT_MODE=true. Idempotente: não redefine senhas existentes.
 */
import { PrismaClient, Role } from '@prisma/client';
import bcrypt from 'bcryptjs';
import { ensureAdminAttendanceEmployee } from './admin-attendance.ts';

const prisma = new PrismaClient();
const requireValue=(key:string)=>{
  const value=String(process.env[key]||'').trim();
  if(!value || (key.includes('PASSWORD') && value.length<12))throw new Error(`Variável obrigatória não configurada: ${key}`);
  return value;
};

async function main(){
  const doc=requireValue('NH_PONTO_TENANT_DOCUMENT').replace(/\D/g,'');
  if(![11,14].includes(doc.length))throw new Error('Documento da empresa inválido para Apontamento Digital.');
  const name=requireValue('TENANT_DISPLAY_NAME');
  const adminEmail=requireValue('PONTO_TENANT_ADMIN_USERNAME').toLowerCase();
  const superEmail=requireValue('PONTO_SUPER_ADMIN_USERNAME').toLowerCase();
  const adminHash=await bcrypt.hash(requireValue('PONTO_DEFAULT_PASSWORD'),12);
  const superHash=await bcrypt.hash(requireValue('PONTO_SUPER_ADMIN_PASSWORD'),12);
  const tz=process.env.TENANT_TIMEZONE||'America/Sao_Paulo';
  const system=await prisma.tenant.upsert({where:{id:'gestao-control-plane'},
    create:{id:'gestao-control-plane',name:'Gestão Software Control Plane',legalName:'Gestão Software LTDA',document:null,timezone:tz},update:{}});
  const owner=await prisma.user.upsert({where:{tenantId_email:{tenantId:system.id,email:superEmail}},
    create:{tenantId:system.id,email:superEmail,displayName:'Administrador de infraestrutura',passwordHash:superHash,role:Role.SUPER_ADMIN,active:true,mustChangePassword:true},update:{active:true}});
  const tenant=await prisma.tenant.upsert({where:{document:doc},
    create:{name,legalName:name,document:doc,timezone:tz},update:{}});
  const dept=await prisma.department.upsert({where:{tenantId_name:{tenantId:tenant.id,name:'Administração'}},
    create:{tenantId:tenant.id,name:'Administração',code:'ADM'},update:{}});
  const schedules={SUN:{enabled:false,startTime:'08:00',endTime:'12:00',breakMinutes:0},MON:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},TUE:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},WED:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},THU:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},FRI:{enabled:true,startTime:'08:00',endTime:'18:00',breakMinutes:120},SAT:{enabled:true,startTime:'08:00',endTime:'12:00',breakMinutes:0}};
  const shift=await prisma.shift.upsert({where:{tenantId_name:{tenantId:tenant.id,name:'Administrativo 44h'}},
    create:{tenantId:tenant.id,name:'Administrativo 44h',timezone:tz,startTime:'08:00',endTime:'18:00',breakMinutes:120,dailyMinutes:480,weeklyMinutes:2640,overtimeToleranceMin:10,scheduleDays:['MON','TUE','WED','THU','FRI','SAT'],daySchedules:schedules},update:{}});
  await prisma.timeRuleSettings.upsert({where:{tenantId:tenant.id},create:{tenantId:tenant.id,bankHoursEnabled:true,monthlyHoursDivisor:220},update:{}});
  await prisma.attendanceSecuritySettings.upsert({where:{tenantId:tenant.id},
    create:{tenantId:tenant.id,requireSelfie:true,requireFaceMatch:true,requireLiveness:true,requireAntiSpoof:true,requirePlatformBiometric:true,blockWithoutEnrollment:true,blockOnFaceFailure:true,requireGeofence:false,requireAccurateGps:false,maxGpsAccuracyM:5000,allowOffline:false,offlineRequiresReview:true},update:{}});
  const admin=await prisma.user.upsert({where:{tenantId_email:{tenantId:tenant.id,email:adminEmail}},
    create:{tenantId:tenant.id,email:adminEmail,displayName:`Administrador · ${name}`,passwordHash:adminHash,role:Role.ADMIN,active:true,mustChangePassword:true},update:{}});
  await prisma.adminTenantAccess.upsert({where:{userId_tenantId:{userId:admin.id,tenantId:tenant.id}},
    create:{userId:admin.id,tenantId:tenant.id,createdByUserId:owner.id},update:{}});
  await ensureAdminAttendanceEmployee(prisma,{userId:admin.id,tenantId:tenant.id,name:admin.displayName||adminEmail,departmentId:dept.id,shiftId:shift.id});
  console.log('Tenant inicializado sem colaboradores fictícios:',tenant.id);
}
main().catch(error=>{console.error('Falha ao inicializar tenant:',error?.message||'erro');process.exitCode=1}).finally(()=>prisma.$disconnect());
