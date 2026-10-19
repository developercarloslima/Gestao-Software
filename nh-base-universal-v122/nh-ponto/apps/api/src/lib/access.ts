import { Role } from '@prisma/client';

export const MANAGEMENT_ROLES: Role[] = [Role.SUPER_ADMIN, Role.ADMIN, Role.HR, Role.SUPERVISOR];
export const ADMIN_ROLES: Role[] = [Role.SUPER_ADMIN, Role.ADMIN, Role.HR];
export const JOURNEY_REVIEW_ROLES: Role[] = [Role.SUPER_ADMIN, Role.ADMIN, Role.TIME_REVIEWER];
export const PASSKEY_ACCOUNT_ROLES: Role[] = [Role.SUPER_ADMIN, Role.ADMIN, Role.HR, Role.SUPERVISOR, Role.EMPLOYEE, Role.TIME_REVIEWER];

export function hasRole(role: string, allowed: Role[]) {
  return allowed.includes(role as Role);
}
