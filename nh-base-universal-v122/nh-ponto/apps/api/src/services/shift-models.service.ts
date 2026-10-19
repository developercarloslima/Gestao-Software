import { prisma } from '../lib/prisma.js';

const weekday=(startTime:string,endTime:string,breakMinutes:number)=>({enabled:true,startTime,endTime,breakMinutes});
const off=(startTime='08:00',endTime='12:00')=>({enabled:false,startTime,endTime,breakMinutes:0});

const MODELS=[
  {
    name:'Administrativo 44h semanais - diurno',startTime:'08:00',endTime:'18:00',breakMinutes:120,dailyMinutes:480,weeklyMinutes:2640,
    daySchedules:{MON:weekday('08:00','18:00',120),TUE:weekday('08:00','18:00',120),WED:weekday('08:00','18:00',120),THU:weekday('08:00','18:00',120),FRI:weekday('08:00','18:00',120),SAT:weekday('08:00','12:00',0),SUN:off()}
  },
  {
    name:'Administrativo 44h semanais - noturno',startTime:'20:00',endTime:'05:00',breakMinutes:60,dailyMinutes:480,weeklyMinutes:2640,
    daySchedules:{MON:weekday('20:00','05:00',60),TUE:weekday('20:00','05:00',60),WED:weekday('20:00','05:00',60),THU:weekday('20:00','05:00',60),FRI:weekday('20:00','05:00',60),SAT:weekday('20:00','00:00',0),SUN:off('20:00','00:00')}
  },
  {
    name:'Suporte 24h',startTime:'18:00',endTime:'22:00',breakMinutes:0,dailyMinutes:240,weeklyMinutes:1440,
    daySchedules:{MON:weekday('18:00','22:00',0),TUE:weekday('18:00','22:00',0),WED:weekday('18:00','22:00',0),THU:weekday('18:00','22:00',0),FRI:weekday('18:00','22:00',0),SAT:weekday('08:00','12:00',0),SUN:off()}
  }
] as const;

export async function ensureDefaultShiftModelsForTenant(tenantId:string){
  let created=0;
  const target=await prisma.shift.findFirst({where:{tenantId,name:MODELS[0].name},select:{id:true}});
  if(!target){const legacy=await prisma.shift.findFirst({where:{tenantId,name:'Administrativo 44h'}});if(legacy)await prisma.shift.update({where:{id:legacy.id},data:{name:MODELS[0].name,daySchedules:MODELS[0].daySchedules as any,startTime:MODELS[0].startTime,endTime:MODELS[0].endTime,breakMinutes:MODELS[0].breakMinutes,dailyMinutes:MODELS[0].dailyMinutes,weeklyMinutes:MODELS[0].weeklyMinutes,scheduleDays:Object.entries(MODELS[0].daySchedules).filter(([,d])=>d.enabled).map(([k])=>k)}});}

  for(const model of MODELS){
    const existing=await prisma.shift.findFirst({where:{tenantId,name:model.name},select:{id:true}});
    if(existing)continue;
    await prisma.shift.create({data:{tenantId,name:model.name,timezone:'America/Maceio',startTime:model.startTime,endTime:model.endTime,breakMinutes:model.breakMinutes,dailyMinutes:model.dailyMinutes,weeklyMinutes:model.weeklyMinutes,overtimeToleranceMin:10,scheduleDays:Object.entries(model.daySchedules).filter(([,d])=>d.enabled).map(([k])=>k),daySchedules:model.daySchedules as any,active:true}});
    created++;
  }
  return {created};
}
export async function ensureDefaultShiftModels(){
  const tenants=await prisma.tenant.findMany({select:{id:true}});let created=0;
  for(const tenant of tenants)created+=(await ensureDefaultShiftModelsForTenant(tenant.id)).created;
  return {tenants:tenants.length,created};
}
