import { FastifyInstance } from 'fastify';
import { Role } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { localDateKey, zonedDateTimeToUtc } from '../lib/time.js';

export async function dashboardRoutes(app: FastifyInstance) {
  app.get('/dashboard/overview', { preHandler: [app.authenticate] }, async request => {
    const tenantId = request.user.tenantId;
    const tenant = await prisma.tenant.findUniqueOrThrow({ where: { id: tenantId } });
    const tz = tenant.timezone || 'America/Maceio';
    const todayKey = localDateKey(new Date(), tz);
    const start = zonedDateTimeToUtc(todayKey, '00:00', tz);
    const end = zonedDateTimeToUtc(todayKey, '23:59', tz);

    let employeeFilter: any = { tenantId, active: true, archivedAt: null };
    if (request.user.role === Role.SUPERVISOR && request.user.employeeId) employeeFilter = { ...employeeFilter, OR: [{ id: request.user.employeeId }, { supervisorId: request.user.employeeId }] };
    const employeeRows = await prisma.employee.findMany({ where: employeeFilter, select: { id: true } });
    const employeeIds = employeeRows.map(e => e.id);

    const [todayPunches, pendingAdjustments, alertRows, lowProofCount] = await Promise.all([
      prisma.punch.findMany({ where: { tenantId, employeeId: { in: employeeIds }, decision: 'APPROVED', occurredAt: { gte: start, lte: end } }, select: { employeeId: true, type: true, occurredAt: true } }),
      prisma.adjustmentRequest.count({ where: { tenantId, employeeId: { in: employeeIds }, status: 'PENDING' } }),
      prisma.alert.findMany({ where: { tenantId, employeeId: { in: employeeIds }, resolved: false }, include:{employee:{select:{name:true}}}, orderBy: { createdAt: 'desc' }, take: 10 }),
      prisma.punchEvidence.count({ where: { punch: { tenantId, employeeId: { in: employeeIds }, occurredAt: { gte: start, lte: end } }, proofScore: { lt: 45 } } })
    ]);
    const alerts=alertRows.map(alert=>{
      const employeeName=alert.employee?.name?.trim();
      if(!employeeName)return alert;
      let title=alert.title;
      if(alert.code==='PUNCH_REVIEW')title=`Marcação de ${employeeName} aguardando revisão de integridade`;
      else if(alert.code==='PUNCH_SEQUENCE')title=`Sequência de marcação de ${employeeName} aguardando revisão`;
      else if(alert.code==='ADJUSTMENT_REVIEW')title=`Ajuste de jornada de ${employeeName} aguardando aprovação`;
      else if(alert.code==='POINT_AUTO_LOCK'||alert.code==='POINT_AUTO_LOCK_REPEATED')title=`Jornada de ${employeeName} aguardando revisão após trava automática`;
      else if(!title.toLocaleLowerCase('pt-BR').includes(employeeName.toLocaleLowerCase('pt-BR')))title=`${title} — ${employeeName}`;
      return {...alert,title};
    });
    const latest = new Map<string, string>();
    for (const p of todayPunches.sort((a,b) => a.occurredAt.getTime() - b.occurredAt.getTime())) latest.set(p.employeeId, p.type);
    const working = [...latest.values()].filter(t => t === 'CLOCK_IN' || t === 'BREAK_END' || t === 'PAUSE_END').length;
    const onBreak = [...latest.values()].filter(t => t === 'BREAK_START' || t === 'PAUSE_START').length;
    const finished = [...latest.values()].filter(t => t === 'CLOCK_OUT').length;
    return { employees: employeeIds.length, working, onBreak, finished, withoutPunch: Math.max(0, employeeIds.length - latest.size), pendingAdjustments, lowProofCount, alerts };
  });
  app.post('/alerts/:id/resolve', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!['SUPER_ADMIN','ADMIN','HR','SUPERVISOR'].includes(request.user.role)) return reply.code(403).send({ error: 'Sem permissão' });
    const id = (request.params as any).id as string;
    const alert = await prisma.alert.findFirst({ where: { id, tenantId: request.user.tenantId }, include: { employee: true } });
    if (!alert) return reply.code(404).send({ error: 'Alerta não encontrado' });
    if (request.user.role === 'SUPERVISOR' && request.user.employeeId && alert.employeeId && alert.employee?.supervisorId !== request.user.employeeId && alert.employeeId !== request.user.employeeId) return reply.code(403).send({ error: 'Alerta fora da sua equipe' });
    await prisma.alert.update({ where: { id }, data: { resolved: true, resolvedAt: new Date() } });
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'ALERT_RESOLVED', entityType: 'Alert', entityId: id } });
    return { ok: true };
  });

}
