export type CompensationTotals = {
  workedMinutes: number;
  overtime50Minutes: number;
  overtime100Minutes: number;
  nightMinutes: number;
  missingMinutes: number;
  workedSeconds?: number;
  overtime50Seconds?: number;
  overtime100Seconds?: number;
  nightSeconds?: number;
  missingSeconds?: number;
};

function secondsValue(seconds: number | undefined, minutes: number) {
  if (Number.isFinite(seconds) && ((seconds ?? 0) !== 0 || !minutes)) return Math.max(0, Math.round(seconds ?? 0));
  return Math.max(0, Math.round((minutes || 0) * 60));
}

function moneyForSeconds(salaryCents: number, divisor: number, seconds: number, additionalPercent = 0) {
  if (!salaryCents || !seconds || !divisor) return 0;
  const hourlyCents = salaryCents / divisor;
  return Math.round((seconds / 3600) * hourlyCents * (1 + additionalPercent / 100));
}

function additionalOnlySeconds(salaryCents: number, divisor: number, seconds: number, percent: number) {
  if (!salaryCents || !seconds || !divisor) return 0;
  return Math.round((seconds / 3600) * (salaryCents / divisor) * (percent / 100));
}

export function calculateCompensation(input: {
  salaryCents: number;
  monthlyHoursDivisor: number;
  overtime50Percent: number;
  overtime100Percent: number;
  nightAdditionalPercent: number;
  bankHoursEnabled: boolean;
  overtimeToBank: boolean;
  totals: CompensationTotals;
}) {
  const divisor = input.monthlyHoursDivisor || 220;
  const hourlyRateCentsExact = input.salaryCents ? input.salaryCents / divisor : 0;
  const minuteRateCentsExact = hourlyRateCentsExact / 60;
  const secondRateCentsExact = minuteRateCentsExact / 60;
  const hourlyRateCents = Math.round(hourlyRateCentsExact);

  const workedSeconds = secondsValue(input.totals.workedSeconds, input.totals.workedMinutes);
  const missingSeconds = secondsValue(input.totals.missingSeconds, input.totals.missingMinutes);
  const overtime50Seconds = secondsValue(input.totals.overtime50Seconds, input.totals.overtime50Minutes);
  const overtime100Seconds = secondsValue(input.totals.overtime100Seconds, input.totals.overtime100Minutes);
  const nightSeconds = secondsValue(input.totals.nightSeconds, input.totals.nightMinutes);

  const missingDiscountCents = moneyForSeconds(input.salaryCents, divisor, missingSeconds, 0);
  const overtime50ReferenceCents = moneyForSeconds(input.salaryCents, divisor, overtime50Seconds, input.overtime50Percent);
  const overtime100ReferenceCents = moneyForSeconds(input.salaryCents, divisor, overtime100Seconds, input.overtime100Percent);
  const overtimeReferenceCents = overtime50ReferenceCents + overtime100ReferenceCents;
  const overtimeToBank = input.bankHoursEnabled && input.overtimeToBank;
  const overtimePayableCents = overtimeToBank ? 0 : overtimeReferenceCents;
  const nightAdditionalCents = additionalOnlySeconds(input.salaryCents, divisor, nightSeconds, input.nightAdditionalPercent);
  const estimatedPayableCents = Math.max(0, input.salaryCents - missingDiscountCents + overtimePayableCents + nightAdditionalCents);

  return {
    salaryCents: input.salaryCents,
    monthlyHoursDivisor: divisor,
    weeklyHoursReference: 44,
    dsrIncluded: true,
    hourlyRateCents,
    hourlyRateCentsExact,
    minuteRateCentsExact,
    secondRateCentsExact,
    workedSeconds,
    missingSeconds,
    overtime50Seconds,
    overtime100Seconds,
    nightSeconds,
    missingDiscountCents,
    overtime50ReferenceCents,
    overtime100ReferenceCents,
    overtimeReferenceCents,
    overtimePayableCents,
    overtimeToBank,
    nightAdditionalCents,
    estimatedPayableCents
  };
}
