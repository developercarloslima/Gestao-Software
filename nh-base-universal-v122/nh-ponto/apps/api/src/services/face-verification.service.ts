import { prisma } from '../lib/prisma.js';
import { decryptJson, saveEncryptedImageDataUrl } from './biometric-storage.service.js';
import { getOrCreateSecuritySettings } from './security-settings.service.js';
import { FaceApiError, faceApiSimilarity, registerFaceEmployeeDataUrl, registerFaceEmployeeEncoding, verifyFaceEmployeeDataUrl } from './face-api.service.js';
import { analyzeFaceReferenceDataUrl } from './face-enrollment-async.service.js';

export type FaceTelemetry = {
  selfieDataUrl?: string;
  embedding?: number[];
  faceCount?: number;
  faceQualityScore?: number;
  livenessScore?: number;
  antiSpoofScore?: number;
  livenessChallengePassed?: boolean;
  livenessAction?: string;
  faceDetected?: boolean;
};

function cosineSimilarity(a: number[], b: number[]) {
  if (!a.length || a.length !== b.length) return 0;
  let dot = 0, aa = 0, bb = 0;
  for (let i = 0; i < a.length; i++) { dot += a[i] * b[i]; aa += a[i] * a[i]; bb += b[i] * b[i]; }
  if (!aa || !bb) return 0;
  return Math.max(0, Math.min(1, dot / (Math.sqrt(aa) * Math.sqrt(bb))));
}

function bestLegacyMatch(current:number[]|undefined,references:any[]){
  if(!current?.length)return 0;
  let score=0;
  for(const ref of references){
    if(!ref.embeddingEncrypted||ref.embeddingModel==='dlib-face-recognition-128d')continue;
    const saved=decryptJson<number[]>(ref.embeddingEncrypted);
    score=Math.max(score,cosineSimilarity(saved,current));
  }
  return score;
}

function dlibReference(references:any[]){
  for(const ref of references){
    if(!ref.embeddingEncrypted||ref.embeddingModel!=='dlib-face-recognition-128d')continue;
    const saved=decryptJson<number[]>(ref.embeddingEncrypted);
    if(saved.length===128&&saved.every(Number.isFinite))return saved;
  }
  return null;
}

export async function verifyFaceEvidence(tenantId: string, employeeId: string, telemetry: FaceTelemetry, namespace: string) {
  const policy = await getOrCreateSecuritySettings(tenantId);
  const employee = await prisma.employee.findFirst({ where: { id: employeeId, tenantId }, select: { name:true, faceEnrollmentStatus: true, selfieRequiredOnPunch: true, biometricRequired: true } });
  if (!employee) throw new Error('Colaborador não encontrado');

  const activeReferences = await prisma.faceReference.findMany({ where: { tenantId, employeeId, revokedAt: null }, orderBy: { createdAt: 'desc' } });
  const missing: string[] = [];
  const reasons: string[] = [];
  const selfieRequired = policy.requireSelfie && employee.selfieRequiredOnPunch;
  const enrollmentRequired = policy.requireFaceMatch && employee.biometricRequired;

  // A selfie da marcação é uma evidência auditável: analisada no servidor e
  // persistida criptografada (AES-256-GCM) pelo prazo de retenção configurado.
  let storageKey: string | undefined;
  let analyzed: Awaited<ReturnType<typeof analyzeFaceReferenceDataUrl>> | undefined;
  if (telemetry.selfieDataUrl) {
    analyzed = await analyzeFaceReferenceDataUrl(telemetry.selfieDataUrl);
    const saved = await saveEncryptedImageDataUrl(telemetry.selfieDataUrl, namespace);
    storageKey = saved.storageKey;
    reasons.push('Selfie da marcação capturada no ato, analisada e armazenada criptografada para auditoria');
  } else if (selfieRequired) missing.push('SELFIE');

  if (enrollmentRequired && (employee.faceEnrollmentStatus !== 'ACTIVE' || activeReferences.length < policy.faceReferenceMin)) missing.push('FACE_ENROLLMENT');

  const faceCount = telemetry.faceCount ?? analyzed?.faceCount ?? 0;
  const faceDetected = telemetry.faceDetected ?? faceCount === 1;
  if (selfieRequired && !faceDetected) missing.push('FACE_NOT_DETECTED');
  if (faceCount > policy.maxFaceCount) missing.push('MULTIPLE_FACES');

  const quality = telemetry.faceQualityScore ?? analyzed?.quality ?? 0;
  if (selfieRequired && quality < policy.minFaceQualityScore) missing.push('FACE_QUALITY');

  const live = telemetry.livenessScore ?? analyzed?.liveness ?? 0;
  const antiSpoof = telemetry.antiSpoofScore ?? analyzed?.antiSpoof ?? 0;
  if (policy.requireLiveness && (live < policy.minLivenessScore || !telemetry.livenessChallengePassed)) missing.push('LIVENESS');
  if (policy.requireAntiSpoof && antiSpoof < policy.minAntiSpoofScore) missing.push('ANTI_SPOOF');

  let faceMatchScore = 0;
  let faceVerified = !enrollmentRequired;
  let legacyEmbeddingCache:number[]|undefined=telemetry.embedding?.length?telemetry.embedding:undefined;
  async function legacyEmbedding(){
    if(legacyEmbeddingCache?.length)return legacyEmbeddingCache;
    if(!telemetry.selfieDataUrl)return undefined;
    const analyzed=await analyzeFaceReferenceDataUrl(telemetry.selfieDataUrl);
    legacyEmbeddingCache=analyzed.embedding;
    return legacyEmbeddingCache;
  }
  if(enrollmentRequired && telemetry.selfieDataUrl && employee.faceEnrollmentStatus==='ACTIVE'){
    try{
      const remote=await verifyFaceEmployeeDataUrl({employeeId,imageDataUrl:telemetry.selfieDataUrl});
      if(remote.registered){
        faceMatchScore=faceApiSimilarity(remote.distance);
        faceVerified=remote.matched;
        reasons.push(`Reconhecimento facial processado pela Face API: distância ${remote.distance?.toFixed(4)} (limite ${remote.tolerance?.toFixed(2)})`);
      }else{
        const apiEncoding=dlibReference(activeReferences);
        if(apiEncoding){
          await registerFaceEmployeeEncoding({employeeId,name:employee.name,encoding:apiEncoding});
          const retried=await verifyFaceEmployeeDataUrl({employeeId,imageDataUrl:telemetry.selfieDataUrl});
          if(retried.registered){
            faceMatchScore=faceApiSimilarity(retried.distance);faceVerified=retried.matched;
            reasons.push('Referência facial da Face API foi restaurada automaticamente e a comparação foi concluída');
          }
        }else{
          // Migração transparente das referências criadas antes da Face API.
          const legacy=bestLegacyMatch(await legacyEmbedding(),activeReferences);
          faceMatchScore=legacy;
          faceVerified=legacy>=policy.minFaceMatchScore;
          if(faceVerified){
            await registerFaceEmployeeDataUrl({employeeId,name:employee.name,imageDataUrl:telemetry.selfieDataUrl});
            reasons.push('Referência facial anterior validada e migrada automaticamente para a Face API');
          }else reasons.push('A referência facial antiga ainda precisa ser sincronizada com a Face API');
        }
      }
    }catch(error){
      // Continuidade operacional: referências legadas ainda podem validar uma batida
      // se o serviço facial estiver reiniciando. Referências novas dependem da Face API.
      const hasLegacy=activeReferences.some(ref=>ref.embeddingEncrypted&&ref.embeddingModel!=='dlib-face-recognition-128d');
      const legacy=hasLegacy?bestLegacyMatch(await legacyEmbedding(),activeReferences):0;
      faceMatchScore=legacy;
      faceVerified=legacy>=policy.minFaceMatchScore;
      reasons.push(faceVerified?'Face API temporariamente indisponível; validação legada segura utilizada nesta marcação':`Face API indisponível: ${error instanceof FaceApiError?error.message:'falha temporária'}`);
    }
  }else if(enrollmentRequired && telemetry.embedding?.length && activeReferences.length){
    faceMatchScore=bestLegacyMatch(await legacyEmbedding(),activeReferences);
    faceVerified=faceMatchScore>=policy.minFaceMatchScore;
  }
  if (enrollmentRequired && !faceVerified) missing.push('FACE_MATCH');

  return {
    policy, storageKey, faceDetected, faceCount, faceQualityScore: quality,
    livenessScore: live, antiSpoofScore: antiSpoof, faceMatchScore, faceVerified,
    livenessOk: !policy.requireLiveness || (live >= policy.minLivenessScore && Boolean(telemetry.livenessChallengePassed)),
    antiSpoofOk: !policy.requireAntiSpoof || antiSpoof >= policy.minAntiSpoofScore,
    missing, reasons
  };
}
