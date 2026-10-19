import '@fastify/jwt';

type PontoProofJwtPayload = {
  userId: string;
  tenantId: string;
  role: string;
  employeeId?: string;
  kind?: 'PUNCH_BIOMETRIC' | 'FACE_ENROLLMENT' | 'NH_PONTO_KIOSK' | 'KIOSK_FACE_RECOGNITION';
  livenessAction?: string;
  facePose?: string;
  credentialId?: string;
  punchChallengeId?: string | null;
  jti?: string;
  internalAdmin?: boolean;
  worksiteId?: string | null;
  name?: string;
  activatedByUserId?: string;
  kioskJti?: string;
  faceDistance?: number;
  faceQuality?: number;
  liveness?: number;
  antiSpoof?: number;
};

declare module '@fastify/jwt' {
  interface FastifyJWT {
    payload: PontoProofJwtPayload;
    user: PontoProofJwtPayload;
  }
}

declare module 'jpeg-js' {
  export type DecodeResult = { width:number; height:number; data:Uint8Array };
  export function decode(data: Buffer | Uint8Array, options?: Record<string,unknown>): DecodeResult;
  export function encode(image: {width:number;height:number;data:Uint8Array}, quality?:number): {data:Uint8Array;width:number;height:number};
  const jpeg: { decode: typeof decode; encode: typeof encode };
  export default jpeg;
}
