/** Pure UTC date-only vacation rules (no local TZ or DST drift). */
export function vacationDateMs(key:string){
  if(!/^\d{4}-\d{2}-\d{2}$/.test(key))throw new Error('Data de férias inválida.');
  const date=new Date(key+'T12:00:00.000Z');
  if(!Number.isFinite(date.getTime())||date.toISOString().slice(0,10)!==key)throw new Error('Data de férias inválida.');
  return Date.UTC(date.getUTCFullYear(),date.getUTCMonth(),date.getUTCDate());
}
export function vacationEligibleFrom(admissionDate:Date|null){
  if(!admissionDate)return null;
  const dateKey=admissionDate.toISOString().slice(0,10);
  const dt=new Date(dateKey+'T12:00:00.000Z');
  dt.setUTCFullYear(dt.getUTCFullYear()+1);
  return dt.toISOString().slice(0,10);
}
export function validateVacationPeriod(admissionDate:Date|null,startDate:string,endDate:string){
  const start=vacationDateMs(startDate),end=vacationDateMs(endDate);
  if(end<start)throw new Error('O término das férias deve ser no mesmo dia ou depois do início.');
  const days=(end-start)/86_400_000+1;
  if(days>30)throw new Error('As férias podem ter no máximo 30 dias corridos (datas inclusivas).');
  const eligibleFrom=vacationEligibleFrom(admissionDate);
  if(!eligibleFrom)throw new Error('Informe a data de admissão no cadastro do colaborador antes de programar férias.');
  if(startDate<eligibleFrom)throw new Error(`Férias só podem começar a partir de ${eligibleFrom.split('-').reverse().join('/')}, quando se completam 12 meses da admissão.`);
  return {days,eligibleFrom};
}
