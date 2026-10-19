import { FastifyInstance } from 'fastify';
import { LateJustificationDocumentType, LateJustificationMode, PunchDecision, PunchType, Role } from '@prisma/client';
import { z } from 'zod';
import { prisma } from '../lib/prisma.js';
import { employeeClosureForDate } from '../services/calendar-closure.service.js';
import { createImmutablePunch } from '../services/punch.service.js';
import { addDaysKey, localDateKey, localTimeKey, zonedDateTimeToUtc } from '../lib/time.js';
import { employeeScheduleWindow, getClockInAccess, resolveEmployeeDaySchedule } from '../services/work-schedule-access.service.js';
import { persistEmployeeLedger } from '../services/time-engine.service.js';
import { MANAGEMENT_ROLES, hasRole } from '../lib/access.js';
import { readEncryptedImage, removeEncryptedEvidence, saveEncryptedEvidenceDataUrl, saveEncryptedImageDataUrl } from '../services/biometric-storage.service.js';
import { adjustmentStatusLabel, getLateJustificationMode, lateJustificationTypeLabel } from '../services/late-justification.service.js';

function safeFileName(value:string){
  return value.normalize('NFD').replace(/[\u0300-\u036f]/g,'').replace(/[\r\n\\/]+/g,' ').replace(/[^A-Za-z0-9._() -]+/g,'').trim().slice(0,180)||'comprovante';
}

function publicAdjustmentRow(row:any){
  const {selfieStorageKey,supportingDocumentStorageKey,...safe}=row;
  return {
    ...safe,
    selfieAvailable:Boolean(selfieStorageKey),
    supportingDocumentAvailable:Boolean(supportingDocumentStorageKey),
    lateJustificationTypeLabel:row.lateJustificationMode?lateJustificationTypeLabel(row.lateJustificationMode,row.supportingDocumentType):null
  };
}

export async function adjustmentRoutes(app: FastifyInstance) {
  // Entrada após a tolerância: o horário solicitado é sempre o horário do servidor.
  // A política da empresa define se basta motivo + selfie ou se também exige documento comprobatório.
  app.post('/adjustments/late-arrival', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!request.user.employeeId) return reply.code(403).send({ error: 'Usuário sem colaborador' });
    const parsed = z.object({
      reason: z.string().trim().min(5, 'Explique o motivo do atraso com pelo menos 5 caracteres.').max(1000),
      selfieDataUrl: z.string().min(100, 'Tire uma selfie para enviar a justificativa.').max(12_000_000, 'A selfie excede o tamanho permitido.'),
      documentType: z.nativeEnum(LateJustificationDocumentType).optional(),
      documentFileName: z.string().trim().min(1).max(180).optional(),
      documentDataUrl: z.string().min(100).max(12_000_000).optional(),
      latitude: z.number().min(-90).max(90),
      longitude: z.number().min(-180).max(180),
      accuracyM: z.number().min(0).max(5000).optional()
    }).safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: parsed.error.issues[0]?.message || 'Dados da justificativa inválidos.' });

    const now = new Date();
    const [access,justificationMode] = await Promise.all([
      getClockInAccess(request.user.tenantId, request.user.employeeId, now),
      getLateJustificationMode(request.user.tenantId)
    ]);
    if (access.code !== 'LATE_JUSTIFICATION_REQUIRED' || !access.schedule || !access.clockInOpensAt || !access.scheduledEndAt) {
      return reply.code(409).send({
        error: access.allowed
          ? 'A entrada ainda está dentro da tolerância de 15 minutos. Registre normalmente com selfie e GPS.'
          : access.message || 'A justificativa de atraso não está disponível neste momento.',
        code: 'LATE_ARRIVAL_NOT_AVAILABLE',
        clockInAccess: access
      });
    }

    const employee = await prisma.employee.findFirst({
      where: { id: request.user.employeeId, tenantId: request.user.tenantId, active: true, archivedAt: null },
      include: { shift: true, tenant: true }
    });
    if (!employee) return reply.code(409).send({ error: 'Seu vínculo de colaborador não está ativo. Atualize a página ou procure o Administrador.' });

    const schedule = resolveEmployeeDaySchedule(employee.shift, employee.workSchedule, access.workDate);
    if (!schedule) return reply.code(409).send({ error: 'Não existe jornada ativa para esta data.' });
    const window = employeeScheduleWindow(access.workDate, schedule, access.timeZone);
    const requestedTime = now;
    if (requestedTime.getTime() > window.scheduledEndAt.getTime()) {
      return reply.code(400).send({ error: `A justificativa de atraso precisa ser enviada durante a jornada de hoje (${schedule.startTime}–${schedule.endTime}).` });
    }

    const existingClockIn = await prisma.punch.findFirst({
      where: {
        tenantId: request.user.tenantId,
        employeeId: employee.id,
        type: PunchType.CLOCK_IN,
        decision: { not: PunchDecision.BLOCKED },
        occurredAt: { gte: window.clockInOpensAt, lte: window.scheduledEndAt }
      },
      select: { id: true, occurredAt: true }
    });
    if (existingClockIn) return reply.code(409).send({ error: 'Já existe uma entrada registrada para esta jornada. Se o horário estiver incorreto, solicite a correção pela aba Ajustes.' });

    const pending = await prisma.adjustmentRequest.findFirst({
      where: {
        tenantId: request.user.tenantId,
        employeeId: employee.id,
        requestedType: PunchType.CLOCK_IN,
        targetPunchId: null,
        status: 'PENDING',
        requestedTime: { gte: window.clockInOpensAt, lte: window.scheduledEndAt }
      },
      select: { id: true, requestedTime: true, reason: true, requestedAt: true, selfieCapturedAt: true, lateJustificationMode:true, supportingDocumentType:true }
    });
    if (pending) return reply.code(409).send({ error: 'Sua justificativa de entrada atrasada já foi enviada e está aguardando aprovação.', pending });

    let selfieStored: Awaited<ReturnType<typeof saveEncryptedImageDataUrl>>;
    try {
      selfieStored = await saveEncryptedImageDataUrl(parsed.data.selfieDataUrl, `late-arrival-${request.user.tenantId}-${employee.id}`);
    } catch (error) {
      return reply.code(400).send({ error: error instanceof Error ? error.message : 'Não foi possível salvar a selfie da justificativa.' });
    }

    const professional=justificationMode===LateJustificationMode.PROFESSIONAL;
    const documentComplete=Boolean(parsed.data.documentType&&parsed.data.documentFileName&&parsed.data.documentDataUrl);

    // Regra expressa: no fluxo profissional, ausência de comprovante reprova a justificativa automaticamente.
    if(professional&&!documentComplete){
      const autoNote='Reprovada automaticamente: justificativa profissional sem documento comprobatório anexado.';
      try{
        const rejected=await prisma.$transaction(async tx=>{
          const created=await tx.adjustmentRequest.create({data:{
            tenantId:request.user.tenantId,employeeId:employee.id,requestedTime,requestedType:PunchType.CLOCK_IN,
            reason:parsed.data.reason,selfieStorageKey:selfieStored.storageKey,selfieCapturedAt:now,selfieLatitude:parsed.data.latitude,selfieLongitude:parsed.data.longitude,selfieAccuracyM:parsed.data.accuracyM??null,
            lateJustificationMode:LateJustificationMode.PROFESSIONAL,status:'REJECTED',reviewedAt:now,reviewNote:autoNote,automaticRejection:true
          }});
          await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'LATE_CLOCK_IN_JUSTIFICATION_AUTO_REJECTED',entityType:'AdjustmentRequest',entityId:created.id,afterJson:{employeeId:employee.id,employeeName:employee.name,workDate:access.workDate,requestedTime:requestedTime.toISOString(),reason:parsed.data.reason,policy:justificationMode,automaticRejection:true,rejectionReason:autoNote}}});
          return created;
        });
        return reply.code(201).send({...publicAdjustmentRow(rejected),autoRejected:true,error:autoNote,workDate:access.workDate});
      }catch(error){
        await removeEncryptedEvidence(selfieStored.storageKey);
        throw error;
      }
    }

    let documentStored:Awaited<ReturnType<typeof saveEncryptedEvidenceDataUrl>>|null=null;
    if(professional&&parsed.data.documentDataUrl){
      try{
        documentStored=await saveEncryptedEvidenceDataUrl(parsed.data.documentDataUrl,`late-proof-${request.user.tenantId}-${employee.id}`);
      }catch(error){
        await removeEncryptedEvidence(selfieStored.storageKey);
        return reply.code(400).send({error:error instanceof Error?error.message:'Não foi possível salvar o documento comprobatório.'});
      }
    }

    let item;
    try {
      item = await prisma.$transaction(async tx => {
        const created = await tx.adjustmentRequest.create({ data: {
          tenantId: request.user.tenantId,
          employeeId: employee.id,
          requestedTime,
          requestedType: PunchType.CLOCK_IN,
          reason: parsed.data.reason,
          selfieStorageKey: selfieStored.storageKey,
          selfieCapturedAt: now,
          selfieLatitude:parsed.data.latitude,
          selfieLongitude:parsed.data.longitude,
          selfieAccuracyM:parsed.data.accuracyM??null,
          lateJustificationMode:justificationMode,
          supportingDocumentType:professional?parsed.data.documentType:null,
          supportingDocumentStorageKey:professional?documentStored?.storageKey:null,
          supportingDocumentMimeType:professional?documentStored?.mime:null,
          supportingDocumentFileName:professional&&parsed.data.documentFileName?safeFileName(parsed.data.documentFileName):null,
          supportingDocumentUploadedAt:professional&&documentStored?now:null
        } });
        await tx.auditEvent.create({ data: {
          tenantId: request.user.tenantId,
          actorUserId: request.user.userId,
          action: 'LATE_CLOCK_IN_REQUESTED',
          entityType: 'AdjustmentRequest',
          entityId: created.id,
          afterJson: {
            employeeId: employee.id,
            employeeName: employee.name,
            workDate: access.workDate,
            scheduledStart: schedule.startTime,
            directClockInClosedAt: window.clockInClosesAt.toISOString(),
            requestedTime: requestedTime.toISOString(),
            selfieCapturedAt: now.toISOString(),
            selfieLocation:{latitude:parsed.data.latitude,longitude:parsed.data.longitude,accuracyM:parsed.data.accuracyM??null},
            reason: parsed.data.reason,
            policy:justificationMode,
            documentType:professional?parsed.data.documentType:null,
            documentFileName:professional&&parsed.data.documentFileName?safeFileName(parsed.data.documentFileName):null,
            documentAttached:Boolean(documentStored)
          }
        } });
        return created;
      });
    } catch (error) {
      await removeEncryptedEvidence(selfieStored.storageKey);
      if(documentStored)await removeEncryptedEvidence(documentStored.storageKey);
      throw error;
    }

    try {
      await prisma.alert.create({ data: {
        tenantId: request.user.tenantId,
        employeeId: employee.id,
        severity: 'MEDIUM',
        code: 'ADJUSTMENT_REVIEW',
        title: `Entrada atrasada de ${employee.name} aguardando aprovação`,
        description: `Solicitação ${item.id} · chegada registrada pelo servidor ${requestedTime.toISOString()} · início da jornada ${schedule.startTime}. Justificativa: ${parsed.data.reason}${professional?' · documento comprobatório anexado':''}`
      } });
    } catch (alertError) {
      request.log.error({ err: alertError, employeeId: employee.id, adjustmentId: item.id }, 'failed to create late arrival review alert');
    }

    return reply.code(201).send({ ...publicAdjustmentRow(item), lateArrival: true, workDate: access.workDate, schedule: { startTime: schedule.startTime, endTime: schedule.endTime }, clockInClosesAt: window.clockInClosesAt });
  });

  app.post('/adjustments', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!request.user.employeeId) return reply.code(403).send({ error: 'Usuário sem colaborador' });
    const parsed = z.object({
      targetPunchId: z.string().trim().min(1).optional(),
      requestedTime: z.string().datetime(),
      requestedType: z.nativeEnum(PunchType),
      reason: z.string().trim().min(5, 'Informe um motivo com pelo menos 5 caracteres.').max(1000)
    }).safeParse(request.body);
    if (!parsed.success) return reply.code(400).send({ error: parsed.error.issues[0]?.message || 'Dados do ajuste inválidos.' });
    const body = parsed.data;

    const employee = await prisma.employee.findFirst({
      where: { id: request.user.employeeId, tenantId: request.user.tenantId, active: true, archivedAt: null },
      select: { id: true, name: true, employeeNumber: true }
    });
    if (!employee) return reply.code(409).send({ error: 'Seu vínculo de colaborador não está ativo. Atualize a página ou procure o Administrador.' });

    if (body.targetPunchId) {
      const target = await prisma.punch.findFirst({ where: { id: body.targetPunchId, tenantId: request.user.tenantId, employeeId: employee.id } });
      if (!target) return reply.code(400).send({ error: 'A marcação selecionada não existe mais ou não pertence a este colaborador.' });
    }

    const requestedTime = new Date(body.requestedTime);
    if (!Number.isFinite(requestedTime.getTime())) return reply.code(400).send({ error: 'Data/hora do ajuste inválida.' });

    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{timezone:true}});
    const adjustedDate=localDateKey(requestedTime,tenant?.timezone||'America/Maceio');
    const closure=await employeeClosureForDate({tenantId:request.user.tenantId,employeeId:employee.id,date:adjustedDate});
    if(closure?.type==='VACATION')return reply.code(423).send({error:'Ajustes não são permitidos nos dias de férias.',code:'VACATION_LOCK'});

    const duplicate = await prisma.adjustmentRequest.findFirst({
      where: {
        tenantId: request.user.tenantId,
        employeeId: employee.id,
        status: 'PENDING',
        targetPunchId: body.targetPunchId ?? null,
        requestedTime,
        requestedType: body.requestedType,
        reason: body.reason
      },
      select: { id: true, requestedAt: true }
    });
    if (duplicate) return reply.code(409).send({ error: 'Este ajuste já foi enviado e está aguardando análise.' });

    const item = await prisma.$transaction(async tx => {
      const created = await tx.adjustmentRequest.create({ data: {
        tenantId: request.user.tenantId,
        employeeId: employee.id,
        targetPunchId: body.targetPunchId,
        requestedTime,
        requestedType: body.requestedType,
        reason: body.reason
      } });
      await tx.auditEvent.create({ data: {
        tenantId: request.user.tenantId,
        actorUserId: request.user.userId,
        action: 'ADJUSTMENT_REQUESTED',
        entityType: 'AdjustmentRequest',
        entityId: created.id,
        afterJson: { employeeId: employee.id, employeeName: employee.name, targetPunchId: created.targetPunchId, requestedTime: created.requestedTime.toISOString(), requestedType: created.requestedType, reason: created.reason }
      } });
      return created;
    });

    try {
      await prisma.alert.create({ data: {
        tenantId: request.user.tenantId,
        employeeId: employee.id,
        severity: 'MEDIUM',
        code: 'ADJUSTMENT_REVIEW',
        title: `Ajuste de jornada de ${employee.name} aguardando aprovação`,
        description: `Solicitação ${item.id} · ${body.requestedType} para ${requestedTime.toISOString()}. Motivo: ${body.reason}`
      } });
    } catch (alertError) {
      request.log.error({ err: alertError, employeeId: employee.id, adjustmentId: item.id }, 'failed to create adjustment risk alert');
    }

    return reply.code(201).send(item);
  });

  // Central de ajustes da gestão: visualização geral por colaborador e dia.
  // O gestor corrige a marcação (horário/tipo) em vez de lançar minutos manualmente no banco.
  app.get('/adjustments/team-day', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão para consultar ajustes da equipe.' });
    const { date } = z.object({ date:z.string().regex(/^\d{4}-\d{2}-\d{2}$/) }).parse(request.query);
    const tenant=await prisma.tenant.findUnique({where:{id:request.user.tenantId},select:{timezone:true}});
    if(!tenant)return reply.code(404).send({error:'Empresa não encontrada.'});
    const timezone=tenant.timezone||'America/Maceio';
    const employeeWhere:any={tenantId:request.user.tenantId,active:true,archivedAt:null};
    if(request.user.role===Role.SUPERVISOR){
      if(!request.user.employeeId)return reply.code(403).send({error:'Supervisor sem vínculo de colaborador.'});
      employeeWhere.OR=[{id:request.user.employeeId},{supervisorId:request.user.employeeId}];
    }
    const employees=await prisma.employee.findMany({
      where:employeeWhere,
      include:{shift:true,department:{select:{name:true}},worksite:{select:{name:true}}},
      orderBy:{name:'asc'}
    });
    const ids=employees.map(e=>e.id);
    if(!ids.length)return {date,timezone,employees:[]};
    const start=zonedDateTimeToUtc(date,'00:00',timezone);
    const end=zonedDateTimeToUtc(addDaysKey(date,1),'00:00',timezone);
    const [punches,approvedCorrections]=await Promise.all([
      prisma.punch.findMany({
        where:{tenantId:request.user.tenantId,employeeId:{in:ids},occurredAt:{gte:start,lt:end},decision:{not:PunchDecision.BLOCKED}},
        select:{id:true,recordNumber:true,employeeId:true,type:true,occurredAt:true,source:true,decision:true,reviewReason:true},
        orderBy:[{occurredAt:'asc'},{recordNumber:'asc'}]
      }),
      prisma.adjustmentRequest.findMany({
        where:{tenantId:request.user.tenantId,employeeId:{in:ids},status:'APPROVED',targetPunchId:{not:null}},
        select:{targetPunchId:true}
      })
    ]);
    const superseded=new Set(approvedCorrections.map(a=>a.targetPunchId).filter((x):x is string=>Boolean(x)));
    const byEmployee=new Map<string,typeof punches>();
    for(const punch of punches){
      if(superseded.has(punch.id))continue;
      const arr=byEmployee.get(punch.employeeId)??[];
      arr.push(punch);byEmployee.set(punch.employeeId,arr);
    }
    return {
      date,timezone,
      employees:employees.map(employee=>{
        const schedule=resolveEmployeeDaySchedule(employee.shift,employee.workSchedule,date);
        return {
          id:employee.id,name:employee.name,employeeNumber:employee.employeeNumber,jobTitle:employee.jobTitle,
          department:employee.department?.name??null,worksite:employee.worksite?.name??null,
          schedule:schedule?{startTime:schedule.startTime,endTime:schedule.endTime,breakMinutes:schedule.breakMinutes}:null,
          punches:(byEmployee.get(employee.id)??[]).map(p=>({
            id:p.id,recordNumber:p.recordNumber.toString(),type:p.type,occurredAt:p.occurredAt,source:p.source,decision:p.decision,reviewReason:p.reviewReason,
            localDate:localDateKey(p.occurredAt,timezone),localTime:localTimeKey(p.occurredAt,timezone)
          }))
        };
      })
    };
  });

  app.post('/adjustments/direct', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!hasRole(request.user.role, MANAGEMENT_ROLES)) return reply.code(403).send({ error: 'Sem permissão para ajustar marcações da equipe.' });
    const parsed=z.object({
      employeeId:z.string().trim().min(1),
      targetPunchId:z.string().trim().min(1).optional(),
      workDate:z.string().regex(/^\d{4}-\d{2}-\d{2}$/),
      clockTime:z.string().regex(/^(?:[01]\d|2[0-3]):[0-5]\d$/,'Informe um horário válido.'),
      requestedType:z.nativeEnum(PunchType),
      reason:z.string().trim().min(5,'Informe o motivo com pelo menos 5 caracteres.').max(1000)
    }).safeParse(request.body);
    if(!parsed.success)return reply.code(400).send({error:parsed.error.issues[0]?.message||'Dados do ajuste inválidos.'});
    const body=parsed.data;
    const employee=await prisma.employee.findFirst({
      where:{id:body.employeeId,tenantId:request.user.tenantId,active:true,archivedAt:null},
      include:{shift:true,tenant:true}
    });
    if(!employee)return reply.code(404).send({error:'Colaborador não encontrado ou inativo.'});
    if(request.user.role===Role.SUPERVISOR&&request.user.employeeId&&employee.id!==request.user.employeeId&&employee.supervisorId!==request.user.employeeId)return reply.code(403).send({error:'Colaborador fora da sua equipe.'});
    const timezone=employee.shift?.timezone||employee.tenant.timezone||'America/Maceio';
    const today=localDateKey(new Date(),timezone);
    if(body.workDate>today)return reply.code(400).send({error:'Não é possível criar uma marcação administrativa em data futura.'});
    const closure=await employeeClosureForDate({tenantId:request.user.tenantId,employeeId:employee.id,date:body.workDate});
    if(closure?.type==='VACATION')return reply.code(423).send({error:'ADM não pode ajustar marcações durante as férias.',code:'VACATION_LOCK'});
    const requestedTime=zonedDateTimeToUtc(body.workDate,body.clockTime,timezone);
    if(requestedTime.getTime()>Date.now()+60_000)return reply.code(400).send({error:'O horário informado ainda está no futuro.'});

    const target=body.targetPunchId?await prisma.punch.findFirst({where:{id:body.targetPunchId,tenantId:request.user.tenantId,employeeId:employee.id}}):null;
    if(body.targetPunchId&&!target)return reply.code(404).send({error:'A marcação selecionada não existe mais ou não pertence ao colaborador.'});
    if(target){
      const alreadySuperseded=await prisma.adjustmentRequest.findFirst({where:{tenantId:request.user.tenantId,targetPunchId:target.id,status:'APPROVED'},select:{id:true}});
      if(alreadySuperseded)return reply.code(409).send({error:'Essa marcação já foi substituída por outro ajuste. Atualize a tela e ajuste a marcação vigente.'});
    }

    const actor=await prisma.user.findFirst({where:{id:request.user.userId,tenantId:request.user.tenantId},select:{displayName:true,email:true}});
    const actorName=actor?.displayName?.trim()||actor?.email||'Administrador';
    const pending=await prisma.adjustmentRequest.create({data:{
      tenantId:request.user.tenantId,employeeId:employee.id,targetPunchId:target?.id??null,requestedTime,requestedType:body.requestedType,reason:body.reason,status:'PENDING'
    }});
    let correctionPunch:any;
    try{
      correctionPunch=await createImmutablePunch({
        tenantId:request.user.tenantId,employeeId:employee.id,actorUserId:request.user.userId,type:body.requestedType,occurredAt:requestedTime,timezone,
        source:target?'ADMIN_DIRECT_CORRECTION':'ADMIN_DIRECT_ADDITION',offline:false,decision:PunchDecision.APPROVED,
        reviewReason:`Ajuste direto da gestão: ${body.reason}`,
        evidence:{deviceTrusted:false,faceDetected:false,faceCount:0,faceVerified:false,livenessChallengePassed:false,platformBiometricVerified:false,mockLocationRisk:false,appIntegrityStatus:'ADMIN_ADJUSTMENT_NOT_PRESENCE_PROOF',proofScore:0,proofLevel:'AJUSTE_ADMINISTRATIVO',reasons:['Registro criado por ajuste administrativo direto e auditável.'],missingRequirements:[]}
      });
    }catch(error){
      await prisma.adjustmentRequest.delete({where:{id:pending.id}}).catch(()=>undefined);
      throw error;
    }
    const reviewedAt=new Date();
    const updated=await prisma.$transaction(async tx=>{
      const row=await tx.adjustmentRequest.update({where:{id:pending.id},data:{status:'APPROVED',reviewedById:request.user.userId,reviewedAt,reviewNote:'Ajuste direto realizado pela gestão.',resultingPunchId:correctionPunch.id}});
      await tx.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:target?'PUNCH_DIRECT_CORRECTION':'PUNCH_DIRECT_ADDITION',entityType:'AdjustmentRequest',entityId:row.id,beforeJson:target?{punchId:target.id,type:target.type,occurredAt:target.occurredAt.toISOString()}:undefined,afterJson:{employeeId:employee.id,employeeName:employee.name,workDate:body.workDate,type:body.requestedType,occurredAt:requestedTime.toISOString(),reason:body.reason,responsibleName:actorName,resultingPunchId:correctionPunch.id}}});
      return row;
    });
    const affectedDates=new Set<string>([body.workDate]);
    if(target)affectedDates.add(localDateKey(target.occurredAt,target.timezone||timezone));
    for(const workDate of affectedDates)await persistEmployeeLedger(employee.id,request.user.tenantId,workDate,workDate);
    return reply.code(201).send({...publicAdjustmentRow(updated),responsibleName:actorName,resultingPunch:{id:correctionPunch.id,recordNumber:correctionPunch.recordNumber.toString(),type:correctionPunch.type,occurredAt:correctionPunch.occurredAt}});
  });

  app.get('/adjustments/me', { preHandler: [app.authenticate] }, async (request, reply) => {
    if (!request.user.employeeId) return reply.code(403).send({ error: 'Usuário sem colaborador' });
    const rows=await prisma.adjustmentRequest.findMany({
      where: { tenantId: request.user.tenantId, employeeId: request.user.employeeId },
      include: { employee: { select: { name: true, employeeNumber: true } } },
      orderBy: { requestedAt: 'desc' }, take: 200
    });
    return rows.map(publicAdjustmentRow);
  });

  app.get('/adjustments', { preHandler: [app.authenticate] }, async request => {
    const managerRoles: Role[] = [Role.SUPER_ADMIN,Role.ADMIN, Role.HR, Role.SUPERVISOR, Role.AUDITOR, Role.TIME_REVIEWER];
    const where: any = { tenantId: request.user.tenantId };
    if (!managerRoles.includes(request.user.role as Role)) where.employeeId = request.user.employeeId;
    if(request.user.role===Role.TIME_REVIEWER)where.lateJustificationMode={not:null};
    if (request.user.role === Role.SUPERVISOR && request.user.employeeId) {
      const team = await prisma.employee.findMany({ where: { tenantId: request.user.tenantId, archivedAt:null, OR: [{ id: request.user.employeeId }, { supervisorId: request.user.employeeId }] }, select: { id: true } });
      where.employeeId = { in: team.map(x => x.id) };
    }
    const rows=await prisma.adjustmentRequest.findMany({ where, include: { employee: { select: { name: true, employeeNumber: true } } }, orderBy: { requestedAt: 'desc' }, take: 200 });
    return rows.map(publicAdjustmentRow);
  });

  app.get('/adjustments/:id/selfie', { preHandler: [app.authenticate] }, async (request, reply) => {
    const viewerRoles: Role[] = [Role.SUPER_ADMIN,Role.ADMIN, Role.HR, Role.SUPERVISOR, Role.AUDITOR,Role.TIME_REVIEWER];
    if (!viewerRoles.includes(request.user.role as Role)) return reply.code(403).send({ error: 'Sem permissão para visualizar a selfie da justificativa.' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const item = await prisma.adjustmentRequest.findFirst({
      where: { id, tenantId: request.user.tenantId },
      include: { employee: { select: { id:true, supervisorId:true } } }
    });
    if (!item || !item.selfieStorageKey) return reply.code(404).send({ error: 'Selfie da justificativa não encontrada.' });
    if (request.user.role === Role.SUPERVISOR && item.employee.supervisorId !== request.user.employeeId && item.employee.id !== request.user.employeeId) return reply.code(403).send({ error: 'Solicitação fora da sua equipe' });
    const bytes = await readEncryptedImage(item.selfieStorageKey);
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'LATE_JUSTIFICATION_SELFIE_VIEWED', entityType: 'AdjustmentRequest', entityId: item.id } }).catch(()=>undefined);
    return reply.header('Content-Type','image/jpeg').header('Cache-Control','no-store').send(bytes);
  });

  app.get('/adjustments/:id/document', { preHandler: [app.authenticate] }, async (request, reply) => {
    const viewerRoles: Role[] = [Role.SUPER_ADMIN,Role.ADMIN,Role.HR,Role.SUPERVISOR,Role.TIME_REVIEWER];
    if(!viewerRoles.includes(request.user.role as Role))return reply.code(403).send({error:'Sem permissão para visualizar o documento da justificativa.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const item=await prisma.adjustmentRequest.findFirst({where:{id,tenantId:request.user.tenantId},include:{employee:{select:{id:true,supervisorId:true}}}});
    if(!item||!item.supportingDocumentStorageKey)return reply.code(404).send({error:'Documento comprobatório não encontrado.'});
    if(request.user.role===Role.SUPERVISOR&&item.employee.supervisorId!==request.user.employeeId&&item.employee.id!==request.user.employeeId)return reply.code(403).send({error:'Solicitação fora da sua equipe'});
    const bytes=await readEncryptedImage(item.supportingDocumentStorageKey);
    const fileName=safeFileName(item.supportingDocumentFileName||'comprovante');
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'LATE_JUSTIFICATION_DOCUMENT_VIEWED',entityType:'AdjustmentRequest',entityId:item.id}}).catch(()=>undefined);
    return reply.header('Content-Type',item.supportingDocumentMimeType||'application/octet-stream').header('Content-Disposition',`inline; filename="${fileName}"`).header('Cache-Control','no-store').send(bytes);
  });

  app.get('/adjustments/:id/late-justification-detail',{preHandler:[app.authenticate]},async(request,reply)=>{
    const allowed:Role[]=[Role.SUPER_ADMIN,Role.TIME_REVIEWER];
    if(!allowed.includes(request.user.role as Role))return reply.code(403).send({error:'Somente o Super Adm e o responsável de ponto podem abrir os detalhes desta justificativa no espelho.'});
    const {id}=z.object({id:z.string()}).parse(request.params);
    const item=await prisma.adjustmentRequest.findFirst({where:{id,tenantId:request.user.tenantId,lateJustificationMode:{not:null}},include:{employee:{select:{id:true,name:true,employeeNumber:true}}}});
    if(!item)return reply.code(404).send({error:'Justificativa não encontrada.'});
    const reviewer=item.reviewedById?await prisma.user.findFirst({where:{id:item.reviewedById},select:{id:true,displayName:true,email:true,role:true}}):null;
    await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:request.user.userId,action:'LATE_JUSTIFICATION_DETAILS_VIEWED',entityType:'AdjustmentRequest',entityId:item.id}}).catch(()=>undefined);
    return {
      id:item.id,employee:item.employee,requestedTime:item.requestedTime,submittedAt:item.requestedAt,reason:item.reason,
      type:item.lateJustificationMode,typeLabel:lateJustificationTypeLabel(item.lateJustificationMode,item.supportingDocumentType),
      documentType:item.supportingDocumentType,documentFileName:item.supportingDocumentFileName,documentMimeType:item.supportingDocumentMimeType,documentUploadedAt:item.supportingDocumentUploadedAt,documentAvailable:Boolean(item.supportingDocumentStorageKey),
      selfieCapturedAt:item.selfieCapturedAt,selfieAvailable:Boolean(item.selfieStorageKey),selfieLocation:item.selfieLatitude!=null&&item.selfieLongitude!=null?{latitude:item.selfieLatitude,longitude:item.selfieLongitude,accuracyM:item.selfieAccuracyM}:null,status:item.status,statusLabel:adjustmentStatusLabel(item.status),reviewNote:item.reviewNote,reviewedAt:item.reviewedAt,automaticRejection:item.automaticRejection,
      reviewer:item.automaticRejection?{id:null,name:'Sistema',role:'SYSTEM'}:reviewer?{id:reviewer.id,name:reviewer.displayName?.trim()||reviewer.email,role:reviewer.role}:null
    };
  });

  app.get('/adjustments/:id/punches', { preHandler: [app.authenticate] }, async (request, reply) => {
    const viewerRoles: Role[] = [Role.SUPER_ADMIN,Role.ADMIN, Role.HR, Role.SUPERVISOR, Role.AUDITOR,Role.TIME_REVIEWER];
    if (!viewerRoles.includes(request.user.role as Role)) return reply.code(403).send({ error: 'Sem permissão para visualizar as marcações' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const req = await prisma.adjustmentRequest.findFirst({
      where: { id, tenantId: request.user.tenantId },
      include: { employee: { include: { shift: true } } }
    });
    if (!req) return reply.code(404).send({ error: 'Solicitação não encontrada' });
    if (request.user.role === Role.SUPERVISOR && req.employee.supervisorId !== request.user.employeeId && req.employee.id !== request.user.employeeId) {
      return reply.code(403).send({ error: 'Solicitação fora da sua equipe' });
    }
    const punches = await prisma.punch.findMany({
      where: { tenantId: request.user.tenantId, employeeId: req.employeeId },
      orderBy: [{ occurredAt: 'desc' }, { recordNumber: 'desc' }],
      take: 120,
      select: { id:true, recordNumber:true, type:true, occurredAt:true, receivedAt:true, timezone:true, source:true, decision:true, reviewReason:true }
    });
    const timezone = req.employee.shift?.timezone || 'America/Maceio';
    return {
      adjustment: { id:req.id, requestedAt:req.requestedAt, requestedTime:req.requestedTime, requestedType:req.requestedType, reason:req.reason, status:req.status, targetPunchId:req.targetPunchId, selfieCapturedAt:req.selfieCapturedAt, selfieAvailable:Boolean(req.selfieStorageKey), selfieLocation:req.selfieLatitude!=null&&req.selfieLongitude!=null?{latitude:req.selfieLatitude,longitude:req.selfieLongitude,accuracyM:req.selfieAccuracyM}:null, lateJustificationMode:req.lateJustificationMode, lateJustificationTypeLabel:req.lateJustificationMode?lateJustificationTypeLabel(req.lateJustificationMode,req.supportingDocumentType):null, supportingDocumentType:req.supportingDocumentType, supportingDocumentFileName:req.supportingDocumentFileName, supportingDocumentMimeType:req.supportingDocumentMimeType, supportingDocumentAvailable:Boolean(req.supportingDocumentStorageKey), automaticRejection:req.automaticRejection },
      employee: { id:req.employee.id, name:req.employee.name, employeeNumber:req.employee.employeeNumber, jobTitle:req.employee.jobTitle },
      requestedWorkDate: localDateKey(req.requestedTime, timezone),
      punches: punches.map(p => ({ ...p, recordNumber:p.recordNumber.toString(), workDate:localDateKey(p.occurredAt, p.timezone || timezone) }))
    };
  });

  app.post('/adjustments/:id/approve', { preHandler: [app.authenticate] }, async (request, reply) => {
    const approverRoles: Role[] = [Role.SUPER_ADMIN,Role.ADMIN, Role.HR, Role.SUPERVISOR,Role.TIME_REVIEWER];
    if (!approverRoles.includes(request.user.role as Role)) return reply.code(403).send({ error: 'Sem permissão para aprovar' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const { note } = z.object({ note: z.string().max(1000).optional() }).parse(request.body ?? {});
    const req = await prisma.adjustmentRequest.findFirst({ where: { id, tenantId: request.user.tenantId, status: 'PENDING' }, include: { employee: { include: { shift: true, tenant: true } } } });
    if (!req) return reply.code(404).send({ error: 'Solicitação pendente não encontrada' });
    if(request.user.role===Role.TIME_REVIEWER&&!req.lateJustificationMode)return reply.code(403).send({error:'O responsável de ponto só pode decidir justificativas de atraso.'});
    if (request.user.role === Role.SUPERVISOR && req.employee.supervisorId !== request.user.employeeId && req.employee.id !== request.user.employeeId) return reply.code(403).send({ error: 'Solicitação fora da sua equipe' });

    if(req.lateJustificationMode===LateJustificationMode.PROFESSIONAL&&!req.supportingDocumentStorageKey){
      const autoNote='Reprovada automaticamente: justificativa profissional sem documento comprobatório anexado.';
      await prisma.adjustmentRequest.update({where:{id},data:{status:'REJECTED',reviewedAt:new Date(),reviewNote:autoNote,automaticRejection:true}});
      await prisma.auditEvent.create({data:{tenantId:request.user.tenantId,actorUserId:null,action:'LATE_CLOCK_IN_JUSTIFICATION_AUTO_REJECTED',entityType:'AdjustmentRequest',entityId:id,afterJson:{status:'REJECTED',automaticRejection:true,rejectionReason:autoNote}}});
      await prisma.alert.updateMany({where:{tenantId:request.user.tenantId,employeeId:req.employeeId,code:'ADJUSTMENT_REVIEW',resolved:false,description:{contains:id}},data:{resolved:true,resolvedAt:new Date()}});
      return reply.code(409).send({error:autoNote,code:'PROFESSIONAL_JUSTIFICATION_DOCUMENT_REQUIRED'});
    }

    const requestTimeZone=req.employee.shift?.timezone||req.employee.tenant.timezone||'America/Maceio';
    const requestDate=localDateKey(req.requestedTime,requestTimeZone);
    const closure=await employeeClosureForDate({tenantId:request.user.tenantId,employeeId:req.employeeId,date:requestDate});
    if(closure?.type==='VACATION')return reply.code(423).send({error:'Não é possível aprovar ajustes em dias de férias.',code:'VACATION_LOCK'});

    const adjustmentSource = req.targetPunchId ? 'APPROVED_CORRECTION' : req.requestedType===PunchType.CLOCK_IN ? 'APPROVED_CLOCK_IN_ADDITION' : 'APPROVED_ADDITION';
    const correctionPunch = await createImmutablePunch({
      tenantId: request.user.tenantId,
      employeeId: req.employeeId,
      actorUserId: request.user.userId,
      type: req.requestedType,
      occurredAt: req.requestedTime,
      timezone: req.employee.shift?.timezone || req.employee.tenant.timezone || 'America/Maceio',
      source: adjustmentSource,
      offline: false,
      decision: PunchDecision.APPROVED,
      reviewReason: req.lateJustificationMode?`${lateJustificationTypeLabel(req.lateJustificationMode,req.supportingDocumentType)} aprovada: ${req.reason}`:`Ajuste administrativo aprovado: ${req.reason}`,
      evidence: {
        deviceTrusted: false,
        faceDetected: false,
        faceCount: 0,
        faceVerified: false,
        livenessChallengePassed: false,
        platformBiometricVerified: false,
        mockLocationRisk: false,
        appIntegrityStatus: 'ADMIN_ADJUSTMENT_NOT_PRESENCE_PROOF',
        proofScore: 0,
        proofLevel: 'AJUSTE_ADMINISTRATIVO',
        reasons: ['Registro criado por ajuste administrativo formal aprovado.'],
        missingRequirements: []
      }
    });
    const updated = await prisma.adjustmentRequest.update({ where: { id }, data: {
      status: 'APPROVED', reviewedById: request.user.userId, reviewedAt: new Date(), reviewNote: note, resultingPunchId: correctionPunch.id
    } });
    await prisma.auditEvent.create({ data: {
      tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'ADJUSTMENT_APPROVED', entityType: 'AdjustmentRequest', entityId: id,
      afterJson: { status: 'APPROVED', targetPunchId: req.targetPunchId, resultingPunchId: correctionPunch.id, reviewNote: note ?? null, lateJustificationMode:req.lateJustificationMode??null, supportingDocumentType:req.supportingDocumentType??null }
    } });
    await prisma.alert.updateMany({where:{tenantId:request.user.tenantId,employeeId:req.employeeId,code:'ADJUSTMENT_REVIEW',resolved:false,description:{contains:id}},data:{resolved:true,resolvedAt:new Date()}});
    const workDate = localDateKey(req.requestedTime, req.employee.shift?.timezone || req.employee.tenant.timezone || 'America/Maceio');
    await persistEmployeeLedger(req.employeeId, request.user.tenantId, workDate, workDate);
    return publicAdjustmentRow(updated);
  });

  app.post('/adjustments/:id/reject', { preHandler: [app.authenticate] }, async (request, reply) => {
    const approverRoles: Role[] = [Role.SUPER_ADMIN,Role.ADMIN, Role.HR, Role.SUPERVISOR,Role.TIME_REVIEWER];
    if (!approverRoles.includes(request.user.role as Role)) return reply.code(403).send({ error: 'Sem permissão para rejeitar' });
    const { id } = z.object({ id: z.string() }).parse(request.params);
    const { note } = z.object({ note: z.string().min(3).max(1000) }).parse(request.body);
    const req = await prisma.adjustmentRequest.findFirst({ where: { id, tenantId: request.user.tenantId, status: 'PENDING' }, include: { employee: { include: { shift: true, tenant: true } } } });
    if (!req) return reply.code(404).send({ error: 'Solicitação pendente não encontrada' });
    if(request.user.role===Role.TIME_REVIEWER&&!req.lateJustificationMode)return reply.code(403).send({error:'O responsável de ponto só pode decidir justificativas de atraso.'});
    if (request.user.role === Role.SUPERVISOR && req.employee.supervisorId !== request.user.employeeId && req.employee.id !== request.user.employeeId) return reply.code(403).send({ error: 'Solicitação fora da sua equipe' });
    await prisma.adjustmentRequest.update({ where: { id }, data: { status: 'REJECTED', reviewedById: request.user.userId, reviewedAt: new Date(), reviewNote: note } });
    await prisma.auditEvent.create({ data: { tenantId: request.user.tenantId, actorUserId: request.user.userId, action: 'ADJUSTMENT_REJECTED', entityType: 'AdjustmentRequest', entityId: id, afterJson: { status: 'REJECTED', reviewNote: note, lateJustificationMode:req.lateJustificationMode??null, supportingDocumentType:req.supportingDocumentType??null } } });
    await prisma.alert.updateMany({where:{tenantId:request.user.tenantId,employeeId:req.employeeId,code:'ADJUSTMENT_REVIEW',resolved:false,description:{contains:id}},data:{resolved:true,resolvedAt:new Date()}});
    return { ok: true };
  });
}
