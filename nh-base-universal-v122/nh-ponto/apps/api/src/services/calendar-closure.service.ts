import { prisma } from '../lib/prisma.js';
import { dateKeyToUtcNoon, listDateKeys, localDateKey } from '../lib/time.js';
import { holidayForDate } from './holiday.service.js';

export type EmployeeClosure = {
  type:'VACATION'|'HOLIDAY';
  name:string;
  date:string;
  source:'VACATION'|'NATIONAL'|'STATE'|'CITY';
  vacationId?:string;
  holidayId?:string;
};

function normalized(value?:string|null){return String(value??'').trim();}

export async function employeeClosuresForRange(input:{tenantId:string;employeeId:string;from:string;to:string;city?:string|null;stateCode?:string|null}){
  const start=dateKeyToUtcNoon(input.from),end=dateKeyToUtcNoon(input.to);
  const city=normalized(input.city),state=normalized(input.stateCode).toUpperCase();
  const [vacations,cityHolidays]=await Promise.all([
    prisma.vacationPeriod.findMany({
      where:{tenantId:input.tenantId,employeeId:input.employeeId,startDate:{lte:end},endDate:{gte:start}},
      select:{id:true,startDate:true,endDate:true},orderBy:{startDate:'asc'}
    }),
    city?prisma.municipalHoliday.findMany({
      where:{tenantId:input.tenantId,date:{gte:start,lte:end},city:{equals:city,mode:'insensitive'},...(state?{OR:[{stateCode:null},{stateCode:''},{stateCode:{equals:state,mode:'insensitive'}}]}:{})},
      select:{id:true,date:true,name:true,city:true,stateCode:true},orderBy:{date:'asc'}
    }):Promise.resolve([])
  ]);
  const map=new Map<string,EmployeeClosure>();
  for(const key of listDateKeys(input.from,input.to)){
    const vacation=vacations.find(v=>key>=localDateKey(v.startDate,'UTC')&&key<=localDateKey(v.endDate,'UTC'));
    if(vacation){map.set(key,{type:'VACATION',name:'Férias',date:key,source:'VACATION',vacationId:vacation.id});continue;}
    const staticHoliday=holidayForDate(key,state);
    if(staticHoliday){map.set(key,{type:'HOLIDAY',name:staticHoliday.name,date:key,source:staticHoliday.scope});continue;}
    const custom=cityHolidays.find(h=>localDateKey(h.date,'UTC')===key);
    if(custom)map.set(key,{type:'HOLIDAY',name:custom.name,date:key,source:'CITY',holidayId:custom.id});
  }
  return map;
}

export async function employeeClosureForDate(input:{tenantId:string;employeeId:string;date:string;city?:string|null;stateCode?:string|null}){
  const map=await employeeClosuresForRange({...input,from:input.date,to:input.date});
  return map.get(input.date)??null;
}
