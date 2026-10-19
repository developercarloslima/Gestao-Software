export function fmtMinutes(total: number) {
  const sign = total < 0 ? '-' : '';
  const value = Math.abs(total || 0);
  return `${sign}${Math.floor(value / 60)}h${String(value % 60).padStart(2, '0')}`;
}

export function fmtMoney(cents: number) {
  return (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
}

export function monthRange(date = new Date()) {
  const year = date.getFullYear();
  const month = date.getMonth();
  const from = `${year}-${String(month + 1).padStart(2, '0')}-01`;
  const last = new Date(year, month + 1, 0).getDate();
  const to = `${year}-${String(month + 1).padStart(2, '0')}-${String(last).padStart(2, '0')}`;
  return { from, to, label: `${year}-${String(month + 1).padStart(2, '0')}` };
}

export function fmtRateCents(cents: number, decimals = 5) {
  const value = (Number(cents || 0) / 100);
  return `R$ ${value.toLocaleString('pt-BR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals })}`;
}

export function fmtSecondsDuration(totalSeconds: number) {
  const sign = totalSeconds < 0 ? '-' : '';
  const value = Math.abs(Math.round(totalSeconds || 0));
  const h = Math.floor(value / 3600);
  const m = Math.floor((value % 3600) / 60);
  const s = value % 60;
  return `${sign}${h}h${String(m).padStart(2, '0')}m${String(s).padStart(2, '0')}s`;
}
