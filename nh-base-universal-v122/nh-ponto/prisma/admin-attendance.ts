import { Prisma, PrismaClient } from '@prisma/client';

type DbClient = Prisma.TransactionClient | PrismaClient;

type EnsureAdminAttendanceInput = {
  userId: string;
  tenantId: string;
  name: string;
  departmentId?: string | null;
  shiftId?: string | null;
  worksiteId?: string | null;
};

/**
 * Runtime-safe helper for Prisma seed/bootstrap.
 *
 * V83.47: prisma/seed.ts runs inside the final Docker image, where only
 * apps/api/dist is copied. Therefore seed/bootstrap must not import a source
 * file from apps/api/src. This local Prisma helper ships with /app/prisma and
 * preserves the same ADMIN attendance rule used by the API:
 * ADMIN participates in attendance; SUPER_ADMIN remains exempt.
 */
export async function ensureAdminAttendanceEmployee(db: DbClient, input: EnsureAdminAttendanceInput) {
  const current = await db.employee.findUnique({ where: { userId: input.userId } });

  const [department, shift, worksite] = await Promise.all([
    input.departmentId
      ? db.department.findFirst({ where: { id: input.departmentId, tenantId: input.tenantId, active: true }, select: { id: true } })
      : db.department.findFirst({ where: { tenantId: input.tenantId, active: true }, orderBy: [{ name: 'asc' }], select: { id: true } }),
    input.shiftId
      ? db.shift.findFirst({ where: { id: input.shiftId, tenantId: input.tenantId, active: true }, select: { id: true } })
      : db.shift.findFirst({ where: { tenantId: input.tenantId, active: true }, orderBy: [{ name: 'asc' }], select: { id: true } }),
    input.worksiteId
      ? db.worksite.findFirst({ where: { id: input.worksiteId, tenantId: input.tenantId, active: true }, select: { id: true } })
      : db.worksite.findFirst({ where: { tenantId: input.tenantId, active: true }, orderBy: [{ name: 'asc' }], select: { id: true } })
  ]);

  if (current) {
    // Never silently move an administrator's attendance history to another tenant.
    if (current.tenantId !== input.tenantId) return current;
    return db.employee.update({
      where: { id: current.id },
      data: {
        name: input.name,
        jobTitle: current.jobTitle || 'Administrador',
        active: true,
        archivedAt: null,
        biometricRequired: false,
        selfieRequiredOnPunch: true,
        departmentId: current.departmentId ?? department?.id ?? null,
        shiftId: current.shiftId ?? shift?.id ?? null,
        worksiteId: current.worksiteId ?? worksite?.id ?? null
      }
    });
  }

  const employeeNumber = `ADM-${input.userId.toUpperCase()}`.slice(0, 40);
  return db.employee.create({
    data: {
      tenantId: input.tenantId,
      userId: input.userId,
      name: input.name,
      employeeNumber,
      jobTitle: 'Administrador',
      departmentId: department?.id ?? null,
      shiftId: shift?.id ?? null,
      worksiteId: worksite?.id ?? null,
      salaryCents: 0,
      biometricRequired: false,
      selfieRequiredOnPunch: true,
      active: true
    }
  });
}
