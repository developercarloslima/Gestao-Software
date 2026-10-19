import { prisma } from '../lib/prisma.js';

const mandatoryCore = {
  requireSelfie:true,
  requireFaceMatch:false,
  requireLiveness:false,
  requireAntiSpoof:false,
  requirePlatformBiometric:false,
  requireGeofence:false,
  requireAccurateGps:false,
  maxGpsAccuracyM:5000,
  blockWithoutEnrollment:false,
  blockOnFaceFailure:false,
  maxFaceCount:1,
  fullIntegrityRequiresAllConfigured:true
} as const;

export async function getOrCreateSecuritySettings(tenantId: string) {
  return prisma.attendanceSecuritySettings.upsert({
    where: { tenantId },
    update: mandatoryCore,
    create: { tenantId, ...mandatoryCore }
  });
}
