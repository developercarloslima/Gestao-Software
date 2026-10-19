import { prisma } from '../lib/prisma.js';
import { removeEncryptedEvidence } from './biometric-storage.service.js';

const LEGACY_PHOTO_PURGE_ACTION='BIOMETRIC_PHOTOS_PURGED_V83_12';

/**
 * Limpeza única da V83.12.
 * Remove todas as imagens biométricas antigas sem apagar marcações, métricas,
 * hashes de integridade ou os templates vetoriais necessários ao reconhecimento.
 * Fotos de marcações antigas mantêm somente a chave histórica no hash e recebem
 * selfiePurgedAt, preservando a verificabilidade da cadeia.
 */
export async function purgeLegacyBiometricPhotosOnce(){
  const tenants=await prisma.tenant.findMany({select:{id:true}});
  const totals={tenants:0,faceReferencePhotos:0,punchSelfies:0,attemptSelfies:0,enrollmentPhotos:0,orphanFiles:0,skippedTenants:0};
  for(const tenant of tenants){
    const already=await prisma.auditEvent.findFirst({where:{tenantId:tenant.id,action:LEGACY_PHOTO_PURGE_ACTION},select:{id:true}});
    if(already){totals.skippedTenants++;continue;}
    const now=new Date();

    const refs=await prisma.faceReference.findMany({where:{tenantId:tenant.id,storageKey:{not:null}},select:{id:true,storageKey:true}});
    for(const ref of refs){if(ref.storageKey)await removeEncryptedEvidence(ref.storageKey).catch(()=>undefined);}
    if(refs.length){await prisma.faceReference.updateMany({where:{tenantId:tenant.id,storageKey:{not:null}},data:{storageKey:null}});totals.faceReferencePhotos+=refs.length;}

    const evidences=await prisma.punchEvidence.findMany({where:{punch:{tenantId:tenant.id},selfieStorageKey:{not:null},selfiePurgedAt:null},select:{id:true,selfieStorageKey:true}});
    for(const evidence of evidences){
      if(evidence.selfieStorageKey)await removeEncryptedEvidence(evidence.selfieStorageKey).catch(()=>undefined);
      await prisma.punchEvidence.update({where:{id:evidence.id},data:{selfiePurgedAt:now}});
    }
    totals.punchSelfies+=evidences.length;

    const attempts=await prisma.punchAttempt.findMany({where:{tenantId:tenant.id,selfieStorageKey:{not:null}},select:{id:true,selfieStorageKey:true}});
    for(const attempt of attempts){if(attempt.selfieStorageKey)await removeEncryptedEvidence(attempt.selfieStorageKey).catch(()=>undefined);}
    if(attempts.length)await prisma.punchAttempt.updateMany({where:{tenantId:tenant.id,selfieStorageKey:{not:null}},data:{selfieStorageKey:null}});
    totals.attemptSelfies+=attempts.length;

    const submissions=await prisma.faceEnrollmentSubmission.findMany({where:{tenantId:tenant.id},select:{id:true,status:true,imagesJson:true}});
    for(const item of submissions){
      const images=Array.isArray(item.imagesJson)?item.imagesJson as Array<{storageKey?:string;encryptedData?:string}>:[];
      if(!images.length)continue;
      for(const image of images){if(image?.storageKey)await removeEncryptedEvidence(image.storageKey).catch(()=>undefined);}
      const needsRetake=['PENDING','PROCESSING','FAILED'].includes(item.status);
      await prisma.faceEnrollmentSubmission.update({where:{id:item.id},data:{
        ...(needsRetake?{status:'NEEDS_RETAKE' as any,progress:100,processingStartedAt:null,processedAt:now,reasonsJson:{code:'PHOTO_PRIVACY_PURGE',message:'A selfie temporária foi descartada pela nova política de privacidade. Refaça somente a leitura facial; senha, aceite e biometria/passkey continuam preservados.'} as any}:{}),
        imagesJson:[] as any
      }});
      totals.enrollmentPhotos+=images.length;
    }

    await prisma.auditEvent.create({data:{tenantId:tenant.id,actorUserId:null,action:LEGACY_PHOTO_PURGE_ACTION,entityType:'Tenant',entityId:tenant.id,metadataJson:{faceReferencePhotos:refs.length,punchSelfies:evidences.length,attemptSelfies:attempts.length,enrollmentPhotos:submissions.reduce((n,s)=>n+(Array.isArray(s.imagesJson)?s.imagesJson.length:0),0),policy:'NO_LONG_TERM_BIOMETRIC_IMAGES'}}});
    totals.tenants++;
  }
  // Não varrer o diretório inteiro aqui: novas selfies de jornada podem ter sido
  // criadas depois da migração V83.12 e precisam permanecer até a retenção normal.
  totals.orphanFiles=0;
  return totals;
}

export async function runBiometricRetention() {
  const tenants=await prisma.tenant.findMany({select:{id:true,securitySettings:true}});
  let deletedPunchSelfies=0, deletedAttempts=0, purgedFaceReferences=0, expiredEnrollmentFailures=0;
  for(const tenant of tenants){
    const selfieDays=tenant.securitySettings?.selfieRetentionDays ?? 90;
    const blockedDays=tenant.securitySettings?.blockedAttemptRetentionDays ?? 30;
    const revokedFaceDays=tenant.securitySettings?.revokedFaceReferenceRetentionDays ?? 30;
    const selfieCutoff=new Date(Date.now()-selfieDays*86400000);
    const attemptCutoff=new Date(Date.now()-blockedDays*86400000);
    const revokedFaceCutoff=new Date(Date.now()-revokedFaceDays*86400000);
    const evidences=await prisma.punchEvidence.findMany({where:{punch:{tenantId:tenant.id,decision:{not:'REVIEW'}},selfieStorageKey:{not:null},selfiePurgedAt:null,createdAt:{lt:selfieCutoff}},select:{id:true,selfieStorageKey:true},take:500});
    for(const evidence of evidences){
      if(evidence.selfieStorageKey)await removeEncryptedEvidence(evidence.selfieStorageKey).catch(()=>{});
      await prisma.punchEvidence.update({where:{id:evidence.id},data:{selfiePurgedAt:new Date()}}); deletedPunchSelfies++;
    }
    const attempts=await prisma.punchAttempt.findMany({where:{tenantId:tenant.id,attemptedAt:{lt:attemptCutoff}},select:{id:true,selfieStorageKey:true},take:500});
    for(const attempt of attempts){if(attempt.selfieStorageKey)await removeEncryptedEvidence(attempt.selfieStorageKey).catch(()=>{});await prisma.punchAttempt.delete({where:{id:attempt.id}});deletedAttempts++;}
    const revoked=await prisma.faceReference.findMany({where:{tenantId:tenant.id,revokedAt:{lt:revokedFaceCutoff},purgedAt:null},select:{id:true,storageKey:true},take:500});
    for(const ref of revoked){if(ref.storageKey)await removeEncryptedEvidence(ref.storageKey).catch(()=>{});await prisma.faceReference.update({where:{id:ref.id},data:{storageKey:null,embeddingEncrypted:null,purgedAt:new Date()}});purgedFaceReferences++;}
    const failedEnrollments=await prisma.faceEnrollmentSubmission.findMany({where:{tenantId:tenant.id,status:'FAILED',processedAt:{lt:attemptCutoff}},select:{id:true,imagesJson:true},take:200});
    for(const item of failedEnrollments){
      const images=Array.isArray(item.imagesJson)?item.imagesJson as Array<{storageKey?:string}>:[];
      for(const image of images){if(image?.storageKey)await removeEncryptedEvidence(image.storageKey).catch(()=>{});}
      await prisma.faceEnrollmentSubmission.update({where:{id:item.id},data:{status:'NEEDS_RETAKE',imagesJson:[],reasonsJson:{code:'FAILED_RETENTION_EXPIRED',message:'A selfie temporária foi descartada; capture uma nova imagem para continuar.'}}});
      expiredEnrollmentFailures++;
    }
  }
  return {deletedPunchSelfies,deletedAttempts,purgedFaceReferences,expiredEnrollmentFailures};
}
