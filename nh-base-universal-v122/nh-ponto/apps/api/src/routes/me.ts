import { FastifyInstance } from 'fastify';
import { prisma } from '../lib/prisma.js';
import { tenantBrand } from '../services/brand-guide.service.js';

export async function meRoutes(app: FastifyInstance) {
  app.get('/me', { preHandler: [app.authenticate] }, async request => {
    const user = await prisma.user.findUniqueOrThrow({
      where: { id: request.user.userId },
      include: { employee: { include: { shift: true } }, tenant: true }
    });
    return {
      id: user.id,
      tenantId: user.tenantId,
      username: user.email,
      email: user.contactEmail ?? null,
      role: user.role,
      name: user.displayName ?? user.employee?.name ?? user.email,
      tenant: { id: user.tenant.id, name: user.tenant.name },
      brand: tenantBrand(user.tenant),
      employee: user.employee && {
        id: user.employee.id,
        name: user.employee.name,
        employeeNumber: user.employee.employeeNumber,
        shift: user.employee.shift
      }
    };
  });
}
