export type HolidayScope = 'NATIONAL' | 'STATE';
export type HolidayInfo = { date:string; name:string; scope:HolidayScope; stateCode?:string };

function iso(year:number,month:number,day:number){
  return `${year}-${String(month).padStart(2,'0')}-${String(day).padStart(2,'0')}`;
}

// Meeus/Jones/Butcher Gregorian Easter algorithm.
function easterSunday(year:number){
  const a=year%19,b=Math.floor(year/100),c=year%100,d=Math.floor(b/4),e=b%4;
  const f=Math.floor((b+8)/25),g=Math.floor((b-f+1)/3),h=(19*a+b-d-g+15)%30;
  const i=Math.floor(c/4),k=c%4,l=(32+2*e+2*i-h-k)%7,m=Math.floor((a+11*h+22*l)/451);
  const month=Math.floor((h+l-7*m+114)/31); const day=((h+l-7*m+114)%31)+1;
  return new Date(Date.UTC(year,month-1,day,12,0,0));
}
function addUtcDays(date:Date,days:number){const d=new Date(date);d.setUTCDate(d.getUTCDate()+days);return d;}
function key(date:Date){return date.toISOString().slice(0,10);}

export function nationalHolidays(year:number):HolidayInfo[]{
  const goodFriday=key(addUtcDays(easterSunday(year),-2));
  return [
    {date:iso(year,1,1),name:'Confraternização Universal',scope:'NATIONAL'},
    {date:goodFriday,name:'Paixão de Cristo',scope:'NATIONAL'},
    {date:iso(year,4,21),name:'Tiradentes',scope:'NATIONAL'},
    {date:iso(year,5,1),name:'Dia Mundial do Trabalho',scope:'NATIONAL'},
    {date:iso(year,9,7),name:'Independência do Brasil',scope:'NATIONAL'},
    {date:iso(year,10,12),name:'Nossa Senhora Aparecida',scope:'NATIONAL'},
    {date:iso(year,11,2),name:'Finados',scope:'NATIONAL'},
    {date:iso(year,11,15),name:'Proclamação da República',scope:'NATIONAL'},
    {date:iso(year,11,20),name:'Dia Nacional de Zumbi e da Consciência Negra',scope:'NATIONAL'},
    {date:iso(year,12,25),name:'Natal',scope:'NATIONAL'}
  ];
}

// Alagoas: São João, São Pedro, Emancipação Política e Dia Estadual do Evangélico.
// 20/11 também possui histórico estadual em AL, mas hoje já é feriado nacional e é deduplicado.
const STATE_FIXED:Record<string,Array<[number,number,string]>>={
  AL:[
    [6,24,'São João'],
    [6,29,'São Pedro'],
    [9,16,'Emancipação Política de Alagoas'],
    [11,30,'Dia Estadual do Evangélico']
  ]
};

export function stateHolidays(year:number,stateCode?:string|null):HolidayInfo[]{
  const code=String(stateCode??'').trim().toUpperCase();
  return (STATE_FIXED[code]??[]).map(([month,day,name])=>({date:iso(year,month,day),name,scope:'STATE' as const,stateCode:code}));
}

export function holidaysForYear(year:number,stateCodes:string[]=[]):HolidayInfo[]{
  const map=new Map<string,HolidayInfo>();
  for(const h of nationalHolidays(year))map.set(`${h.date}|${h.name}`,h);
  for(const state of [...new Set(stateCodes.map(s=>String(s).toUpperCase()).filter(Boolean))]){
    for(const h of stateHolidays(year,state))map.set(`${h.date}|${h.name}`,h);
  }
  return [...map.values()].sort((a,b)=>a.date.localeCompare(b.date)||a.name.localeCompare(b.name));
}

export function holidayForDate(dateKey:string,stateCode?:string|null):HolidayInfo|undefined{
  const year=Number(dateKey.slice(0,4));
  if(!Number.isInteger(year))return undefined;
  return [...nationalHolidays(year),...stateHolidays(year,stateCode)].find(h=>h.date===dateKey);
}
