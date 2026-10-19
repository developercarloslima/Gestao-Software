import { prisma } from '../lib/prisma.js';
import { addDaysKey, dateKeyToUtcNoon, localDateKey, weekdayKey } from '../lib/time.js';
import { getOrCreateRules, persistEmployeeLedger } from './time-engine.service.js';

function monthFromDateKey(dateKey:string){return dateKey.slice(0,7);}
function monthBounds(month:string){
  if(!/^\d{4}-\d{2}$/.test(month))throw Object.assign(new Error('Competência inválida.'),{statusCode:400});
  const [year,monthNumber]=month.split('-').map(Number);
  if(monthNumber<1||monthNumber>12)throw Object.assign(new Error('Competência inválida.'),{statusCode:400});
  const last=new Date(Date.UTC(year,monthNumber,0)).getUTCDate();
  return {from:`${month}-01`,to:`${month}-${String(last).padStart(2,'0')}`};
}
function previousMonth(month:string){
  const [year,m]=month.split('-').map(Number);
  const d=new Date(Date.UTC(year,m-2,1));
  return `${d.getUTCFullYear()}-${String(d.getUTCMonth()+1).padStart(2,'0')}`;
}
function mondayForDate(dateKey:string){
  const day=weekdayKey(dateKey);
  const offset:Record<string,number>={MON:0,TUE:-1,WED:-2,THU:-3,FRI:-4,SAT:-5,SUN:-6};
  return addDaysKey(dateKey,offset[day]??0);
}

export async function bankMonthRange(tenantId:string,month?:string,now=new Date()){
  const tenant=await prisma.tenant.findUnique({where:{id:tenantId},select:{timezone:true}});
  if(!tenant)throw Object.assign(new Error('Empresa não encontrada.'),{statusCode:404});
  const timeZone=tenant.timezone||'America/Maceio';
  const today=localDateKey(now,timeZone);
  const currentMonth=monthFromDateKey(today);
  const selected=month??currentMonth;
  if(selected>currentMonth)throw Object.assign(new Error('Não é possível consultar uma competência futura.'),{statusCode:400});
  const bounds=monthBounds(selected);
  return {...bounds,monthTo:bounds.to,to:selected===currentMonth?today:bounds.to,month:selected,timeZone,today,currentMonth};
}

async function ensureCurrentWeekPolicy(tenantId:string,timeZone:string,today:string){
  const rules=await getOrCreateRules(tenantId);
  const monday=mondayForDate(today);
  const currentPolicy=localDateKey(rules.bankPolicyV2At,timeZone);
  if(currentPolicy<=monday)return {changed:false,from:currentPolicy};
  await prisma.timeRuleSettings.update({where:{tenantId},data:{bankPolicyV2At:dateKeyToUtcNoon(monday)}});
  await prisma.auditEvent.create({data:{tenantId,actorUserId:null,action:'BANK_HOURS_CURRENT_WEEK_BACKFILL_ENABLED',entityType:'TimeRuleSettings',entityId:rules.id,metadataJson:{previousPolicyDate:currentPolicy,newPolicyDate:monday,reason:'V83.83_REBUILD_CURRENT_WEEK'}}});
  return {changed:true,from:monday};
}

export async function refreshTenantBankMonth(tenantId:string,month?:string,now=new Date(),options:{ensureWeekPolicy?:boolean}={}){
  const range=await bankMonthRange(tenantId,month,now);
  if(options.ensureWeekPolicy!==false&&range.month===range.currentMonth)await ensureCurrentWeekPolicy(tenantId,range.timeZone,range.today);
  // V83.86: o ledger nunca deve carregar dias futuros. Limpa resíduos de versões anteriores
  // para impedir saldo negativo por jornadas que ainda nem aconteceram.
  await prisma.timeBalanceLedger.deleteMany({where:{tenantId,workDate:{gt:dateKeyToUtcNoon(range.today)}}});
  const employees=await prisma.employee.findMany({where:{tenantId,active:true,archivedAt:null},select:{id:true}});
  let refreshed=0,failed=0;
  for(const employee of employees){
    try{await persistEmployeeLedger(employee.id,tenantId,range.from,range.to);refreshed++;}
    catch{failed++;}
  }
  return {...range,employees:employees.length,refreshed,failed};
}

export async function runBankHoursMaintenance(now=new Date(),includePreviousMonth=false){
  const tenants=await prisma.tenant.findMany({select:{id:true,timezone:true}});
  let refreshed=0,failed=0;
  for(const tenant of tenants){
    try{
      const current=await refreshTenantBankMonth(tenant.id,undefined,now,{ensureWeekPolicy:true});
      refreshed+=current.refreshed;failed+=current.failed;
      if(includePreviousMonth){
        const prev=previousMonth(current.currentMonth);
        const previous=await refreshTenantBankMonth(tenant.id,prev,now,{ensureWeekPolicy:false});
        refreshed+=previous.refreshed;failed+=previous.failed;
      }
    }catch{failed++;}
  }
  return {tenants:tenants.length,refreshed,failed,includePreviousMonth};
}
