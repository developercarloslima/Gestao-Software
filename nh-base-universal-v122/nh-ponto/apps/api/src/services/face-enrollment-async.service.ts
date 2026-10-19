import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath, pathToFileURL } from 'node:url';
import jpeg from 'jpeg-js';
import { FacePose, Prisma } from '@prisma/client';
import { prisma } from '../lib/prisma.js';
import { decryptDatabaseImage, encryptImageDataUrlForDatabase, encryptJson, readEncryptedImage, removeEncryptedEvidence } from './biometric-storage.service.js';
import { getOrCreateSecuritySettings } from './security-settings.service.js';
import { FaceApiError, registerFaceEmployeeBytes } from './face-api.service.js';

export type EnrollmentPhoto = { pose: FacePose; imageDataUrl: string };
type StoredPhoto = { pose: FacePose; encryptedData?: string; storageKey?: string; mime?: string; byteLength?: number };
type EngineMode = 'detect' | 'fast' | 'secure';
type ProcessedPhoto = StoredPhoto & {
  embedding:number[];
  quality:number;
  liveness:number | null;
  antiSpoof:number | null;
  faceCount:number;
  gestures:string[];
  yaw:number | null;
  pitch:number | null;
};

type HumanConstructor = new (config?: any) => any;

const require = createRequire(import.meta.url);
let humanCtor: HumanConstructor | null = null;
let detectHumanPromise: Promise<any> | null = null;
let fastHumanPromise: Promise<any> | null = null;
let secureHumanPromise: Promise<any> | null = null;
let workerBusy = false;
let prewarmStarted = false;
let humanFileFetchPatched = false;

/**
 * @vladmandic/human's WASM bundle loads model manifests/weights through fetch().
 * Node 22's native fetch does not implement file:// URLs, which caused every
 * local model (blazeface, facemesh, antispoof, liveness) to fail at startup.
 * Keep the models local and teach fetch how to read only files inside Human's
 * packaged models directory; HTTP/HTTPS behavior is delegated unchanged.
 */
function ensureHumanFileFetchSupport(){
  if(humanFileFetchPatched)return;
  const {modelsDir}=humanPackagePaths();
  const allowedRoot=path.resolve(modelsDir)+path.sep;
  const nativeFetch=globalThis.fetch.bind(globalThis);
  (globalThis as any).fetch=async(input:any,init?:any)=>{
    const rawUrl=typeof input==='string'?input:input instanceof URL?input.href:String(input?.url??input??'');
    if(!rawUrl.startsWith('file://'))return nativeFetch(input,init);
    try{
      const filePath=path.resolve(fileURLToPath(rawUrl));
      if(!filePath.startsWith(allowedRoot))return new Response('Forbidden',{status:403});
      const method=String(init?.method??input?.method??'GET').toUpperCase();
      const stat=await fs.promises.stat(filePath);
      if(!stat.isFile())return new Response('Not found',{status:404});
      const contentType=filePath.endsWith('.json')?'application/json':'application/octet-stream';
      if(method==='HEAD')return new Response(null,{status:200,headers:{'content-type':contentType,'content-length':String(stat.size)}});
      const bytes=await fs.promises.readFile(filePath);
      const copy=new Uint8Array(bytes.byteLength);
      copy.set(bytes);
      return new Response(copy.buffer,{status:200,headers:{'content-type':contentType,'content-length':String(copy.byteLength)}});
    }catch(error:any){
      if(error?.code==='ENOENT')return new Response('Not found',{status:404});
      throw error;
    }
  };
  humanFileFetchPatched=true;
}

const ENGINE_LOAD_TIMEOUT_MS = 6_000;
const ENGINE_WARMUP_TIMEOUT_MS = 4_000;
const ENGINE_DETECT_TIMEOUT_MS: Record<EngineMode, number> = { detect: 2_000, fast: 4_000, secure: 6_500 };
const ENROLLMENT_HARD_TIMEOUT_MS = 12_000;

class FaceEngineTimeoutError extends Error {
  code = 'FACE_ENGINE_TIMEOUT';
  constructor(message:string){ super(message); this.name='FaceEngineTimeoutError'; }
}

async function withDeadline<T>(promise:Promise<T>,ms:number,message:string):Promise<T>{
  let timer:ReturnType<typeof setTimeout>|undefined;
  try{
    return await Promise.race([
      promise,
      new Promise<T>((_,reject)=>{timer=setTimeout(()=>reject(new FaceEngineTimeoutError(message)),ms);})
    ]);
  }finally{ if(timer)clearTimeout(timer); }
}

function resetEngine(mode:EngineMode){
  if(mode==='detect')detectHumanPromise=null;
  else if(mode==='fast')fastHumanPromise=null;
  else secureHumanPromise=null;
}

function clamp(v:number){ return Math.max(0,Math.min(1,v)); }
function cosine(a:number[],b:number[]){
  if(!a.length||a.length!==b.length)return 0;
  let dot=0,aa=0,bb=0;
  for(let i=0;i<a.length;i++){dot+=a[i]*b[i];aa+=a[i]*a[i];bb+=b[i]*b[i];}
  return aa&&bb?clamp(dot/(Math.sqrt(aa)*Math.sqrt(bb))):0;
}

function humanPackagePaths(){
  const defaultEntry=require.resolve('@vladmandic/human');
  const distDir=path.dirname(defaultEntry);
  const wasmEntry=path.join(distDir,'human.node-wasm.js');
  const modelsDir=path.resolve(distDir,'../models');
  if(!fs.existsSync(wasmEntry))throw new Error(`Motor facial Human/WASM não encontrado em ${wasmEntry}`);
  if(!fs.existsSync(modelsDir))throw new Error(`Modelos faciais Human não encontrados em ${modelsDir}`);
  return {defaultEntry,distDir,wasmEntry,modelsDir};
}

function humanModelsUrl(){
  const {modelsDir}=humanPackagePaths();
  return pathToFileURL(modelsDir).href.replace(/\/$/,'')+'/';
}

function wasmFilesPath(){
  try {
    const entry=require.resolve('@tensorflow/tfjs-backend-wasm');
    return path.dirname(entry)+path.sep;
  } catch {
    return path.resolve(process.cwd(),'node_modules/@tensorflow/tfjs-backend-wasm/dist')+path.sep;
  }
}

function getHumanConstructor():HumanConstructor{
  if(humanCtor)return humanCtor;
  ensureHumanFileFetchSupport();
  const {wasmEntry}=humanPackagePaths();
  const mod:any=require(wasmEntry);
  const ctor=mod?.default??mod?.Human;
  if(typeof ctor!=='function')throw new Error('Motor facial Human/WASM não pôde ser inicializado');
  humanCtor=ctor as HumanConstructor;
  return humanCtor;
}

function engineConfig(mode:EngineMode){
  const secure=mode==='secure';
  // O descriptor de identidade é gerado pela Face API/dlib. No modo secure
  // carregamos somente o necessário para detecção, liveness e anti-spoof.
  const descriptor=mode==='fast';
  return {
    backend:'wasm',
    wasmPath:wasmFilesPath(),
    wasmPlatformFetch:false,
    modelBasePath:humanModelsUrl(),
    cacheModels:true,
    validateModels:false,
    async:true,
    // Warmup is executed explicitly below with a hard deadline. Keeping it out
    // of the constructor prevents a hidden second warmup from stalling requests.
    debug:false,
    filter:{enabled:false,equalization:false,flip:false},
    face:{
      enabled:true,
      detector:{rotation:false,maxDetected:2,minConfidence:0.48,skipFrames:0,skipTime:0},
      mesh:{enabled:secure},
      iris:{enabled:false},
      description:{enabled:descriptor,skipFrames:0,skipTime:0},
      antispoof:{enabled:secure,skipFrames:0,skipTime:0},
      liveness:{enabled:secure,skipFrames:0,skipTime:0},
      emotion:{enabled:false}
    },
    body:{enabled:false},hand:{enabled:false},object:{enabled:false},gesture:{enabled:secure}
  } as any;
}

async function buildHuman(mode:EngineMode){
  const Human=getHumanConstructor();
  const human:any=new Human(engineConfig(mode));
  await withDeadline(Promise.resolve(human.load()),ENGINE_LOAD_TIMEOUT_MS,`Motor facial ${mode} excedeu o tempo de carregamento`);
  // Keep the shared promise unresolved until kernels are compiled. If a request
  // arrives during warmup it reuses the same promise instead of competing with it.
  if(typeof human.warmup==='function')await withDeadline(Promise.resolve(human.warmup()),ENGINE_WARMUP_TIMEOUT_MS,`Motor facial ${mode} excedeu o tempo de aquecimento`);
  return human;
}

async function getServerHuman(mode:EngineMode){
  const current=mode==='detect'?detectHumanPromise:mode==='fast'?fastHumanPromise:secureHumanPromise;
  if(current)return current;
  const created=buildHuman(mode).catch(err=>{
    resetEngine(mode);
    throw err;
  });
  if(mode==='detect')detectHumanPromise=created;else if(mode==='fast')fastHumanPromise=created;else secureHumanPromise=created;
  return created;
}

/**
 * Preloads and compiles the face engines after Fastify starts listening.
 * FAST is warmed first because enrollment and reference matching use only
 * detector + descriptor. SECURE is warmed afterwards for liveness/antispoof.
 */
export async function prewarmFaceEngines(){
  if(prewarmStarted)return;
  prewarmStarted=true;
  // Cadastro e comparação de identidade foram movidos para a Face API.
  // O Human/WASM permanece somente para liveness/antispoof das provas em tempo real.
  setTimeout(()=>{
    void (async()=>{
      try{await getServerHuman('secure');}
      catch(error){prewarmStarted=false;console.warn('[PontoProof] prewarm secure liveness engine failed',error);}
    })();
  },2500).unref();
}

function decodeStoredPhoto(photo:StoredPhoto){
  const raw=photo.encryptedData
    ? decryptDatabaseImage(photo.encryptedData)
    : photo.storageKey
      ? readEncryptedImage(photo.storageKey)
      : null;
  return raw;
}

function rgbTensorForDecoded(decoded:any,human:any,maxDimension:number){
  const srcW=Number(decoded.width),srcH=Number(decoded.height);
  const scale=Math.min(1,maxDimension/Math.max(srcW,srcH));
  const width=Math.max(1,Math.round(srcW*scale));
  const height=Math.max(1,Math.round(srcH*scale));
  const rgb=new Uint8Array(width*height*3);
  if(scale===1){
    for(let s=0,d=0;s<decoded.data.length;s+=4){rgb[d++]=decoded.data[s];rgb[d++]=decoded.data[s+1];rgb[d++]=decoded.data[s+2];}
  }else{
    let d=0;
    for(let y=0;y<height;y++){
      const sy=Math.min(srcH-1,Math.floor(y/scale));
      for(let x=0;x<width;x++){
        const sx=Math.min(srcW-1,Math.floor(x/scale));
        const s=(sy*srcW+sx)*4;
        rgb[d++]=decoded.data[s];rgb[d++]=decoded.data[s+1];rgb[d++]=decoded.data[s+2];
      }
    }
  }
  return {tensor:human.tf.tensor3d(rgb,[height,width,3],'int32'),width,height};
}

async function detectDecoded(photo:StoredPhoto,decoded:any,human:any,mode:EngineMode):Promise<ProcessedPhoto>{
  // Smaller tensors cut CPU/WASM time considerably while preserving enough
  // facial detail for the descriptor used by the reference matcher.
  const input=rgbTensorForDecoded(decoded,human,mode==='detect'?208:mode==='fast'?272:304);
  const detection=Promise.resolve(human.detect(input.tensor));
  // Do not dispose a tensor while the WASM call is still using it. If the call
  // times out, disposal happens when/if that abandoned inference eventually settles.
  void detection.finally(()=>{try{human.tf.dispose(input.tensor);}catch{}}).catch(()=>undefined);
  try{
    const result:any=await withDeadline(detection,ENGINE_DETECT_TIMEOUT_MS[mode],`A leitura facial ${mode} excedeu o limite de ${ENGINE_DETECT_TIMEOUT_MS[mode]/1000}s`);
    const faces=result?.face??[];
    if(faces.length!==1)return {...photo,embedding:[],quality:0,liveness:null,antiSpoof:null,faceCount:faces.length,gestures:[],yaw:null,pitch:null};
    const face=faces[0];
    const box=face?.box??[0,0,0,0];
    const minDim=Math.max(1,Math.min(input.width,input.height));
    const relativeSize=Math.min(Number(box[2]??0),Number(box[3]??0))/minDim;
    const confidence=Math.max(Number(face?.faceScore??0),Number(face?.boxScore??0),Number(face?.score??0));
    const quality=clamp(confidence*0.72+clamp(relativeSize/0.42)*0.28);
    const rawEmbedding=face?.embedding;
    const embedding=Array.isArray(rawEmbedding)||ArrayBuffer.isView(rawEmbedding)?Array.from(rawEmbedding as ArrayLike<number>,Number):[];
    const gestures:string[]=(result?.gesture??[]).map((g:any)=>String(g?.gesture??'')).filter(Boolean);
    const yaw=Number.isFinite(Number(face?.rotation?.angle?.yaw))?Number(face.rotation.angle.yaw):null;
    const pitch=Number.isFinite(Number(face?.rotation?.angle?.pitch))?Number(face.rotation.angle.pitch):null;
    return {
      ...photo,
      embedding,
      quality,
      liveness:mode==='secure'?Number(face?.live??0):null,
      antiSpoof:mode==='secure'?Number(face?.real??0):null,
      faceCount:faces.length,
      gestures,yaw,pitch
    };
  }catch(error){
    if(error instanceof FaceEngineTimeoutError)resetEngine(mode);
    throw error;
  }
}

async function analyzePhoto(photo:StoredPhoto,mode:EngineMode='fast'):Promise<ProcessedPhoto>{
  const maybeRaw=decodeStoredPhoto(photo);
  const raw=maybeRaw instanceof Promise?await maybeRaw:maybeRaw;
  if(!raw)throw new Error('Evidência biométrica temporária ausente');
  const decoded=jpeg.decode(raw,{useTArray:true,formatAsRGBA:true});
  if(!decoded?.width||!decoded?.height||!decoded?.data)throw new Error('Imagem JPEG inválida');
  const human:any=await getServerHuman(mode);
  return detectDecoded(photo,decoded,human,mode);
}

export async function analyzeFaceReferenceDataUrl(imageDataUrl:string){
  const saved=encryptImageDataUrlForDatabase(imageDataUrl);
  const processed=await analyzePhoto({pose:FacePose.FRONT,encryptedData:saved.encryptedData,mime:saved.mime,byteLength:saved.byteLength},'fast');
  return {
    embedding:processed.embedding,
    quality:processed.quality,
    liveness:processed.liveness,
    antiSpoof:processed.antiSpoof,
    faceCount:processed.faceCount,
    gestures:processed.gestures,
    yaw:processed.yaw,
    pitch:processed.pitch
  };
}

export async function analyzeFaceLoginDataUrl(imageDataUrl:string){
  const saved=encryptImageDataUrlForDatabase(imageDataUrl);
  const processed=await analyzePhoto({pose:FacePose.FRONT,encryptedData:saved.encryptedData,mime:saved.mime,byteLength:saved.byteLength},'secure');
  return {
    embedding:processed.embedding,
    quality:processed.quality,
    liveness:processed.liveness??0,
    antiSpoof:processed.antiSpoof??0,
    faceCount:processed.faceCount,
    gestures:processed.gestures,
    yaw:processed.yaw,
    pitch:processed.pitch
  };
}

export async function createAsyncFaceEnrollment(input:{tenantId:string;employeeId:string;userId:string;photos:EnrollmentPhoto[]}){
  await recoverStuckFaceEnrollmentForUser(input.userId);
  const required=[FacePose.FRONT];
  if(input.photos.length!==1)throw new Error('Envie exatamente 1 selfie frontal para o cadastro facial');
  for(const pose of required){if(input.photos.filter(p=>p.pose===pose).length!==1)throw new Error(`Foto ${pose} ausente ou duplicada`);}
  const existing=await prisma.faceEnrollmentSubmission.findFirst({where:{tenantId:input.tenantId,employeeId:input.employeeId,status:{in:['PENDING','PROCESSING']}},orderBy:{submittedAt:'desc'}});
  if(existing){
    if(hasRecoverableEnrollmentImage(existing))return existing;
    await markNeedsRetake(existing,[{pose:'FRONT',code:'PHOTO_MISSING',message:'A tentativa anterior não possui mais a selfie temporária. Uma nova captura será usada agora.'}],'FACE_ENROLLMENT_ORPHANED_SUBMISSION');
  }
  const stored:StoredPhoto[]=[];
  try{
    for(const photo of input.photos){
      const saved=encryptImageDataUrlForDatabase(photo.imageDataUrl);
      stored.push({pose:photo.pose,encryptedData:saved.encryptedData,mime:saved.mime,byteLength:saved.byteLength});
    }
    return await prisma.$transaction(async tx=>{
      await tx.employee.update({where:{id:input.employeeId},data:{faceEnrollmentStatus:'PENDING',faceEnrolledAt:null}});
      const submission=await tx.faceEnrollmentSubmission.create({data:{tenantId:input.tenantId,employeeId:input.employeeId,userId:input.userId,status:'PENDING',imagesJson:stored as any,progress:5}});
      await tx.auditEvent.create({data:{tenantId:input.tenantId,actorUserId:input.userId,action:'FACE_ENROLLMENT_SUBMITTED',entityType:'FaceEnrollmentSubmission',entityId:submission.id,metadataJson:{poses:required,processing:'FASTAPI_FACE_RECOGNITION_DLIB',targetMs:8000,deepLivenessAtPunch:true}}});
      return submission;
    });
  }catch(err){
    await Promise.all(stored.map(x=>x.storageKey?removeEncryptedEvidence(x.storageKey):Promise.resolve()));
    throw err;
  }
}

function publicSubmission(row:any){
  const processingMs=row?.processingStartedAt&&row?.processedAt?Math.max(0,new Date(row.processedAt).getTime()-new Date(row.processingStartedAt).getTime()):null;
  return {id:row.id,status:row.status,progress:row.progress,reasons:row.reasonsJson??null,submittedAt:row.submittedAt,processingStartedAt:row.processingStartedAt,processedAt:row.processedAt,processingMs};
}
export { publicSubmission };

function usesLegacyTmpEvidence(row:any){
  const images=Array.isArray(row?.imagesJson)?row.imagesJson as StoredPhoto[]:[];
  return images.some(photo=>Boolean(photo?.storageKey)&&!photo?.encryptedData);
}

function submissionImages(row:any):StoredPhoto[]{
  return Array.isArray(row?.imagesJson)?row.imagesJson as StoredPhoto[]:[];
}

function hasRecoverableEnrollmentImage(row:any){
  return submissionImages(row).some(photo=>Boolean(photo?.encryptedData)||Boolean(photo?.storageKey));
}

async function markNeedsRetake(submission:any, reasons:any[], auditAction='FACE_ENROLLMENT_NEEDS_RETAKE'){
  const images=(submission.imagesJson??[]) as StoredPhoto[];
  await Promise.all(images.map(x=>x.storageKey?removeEncryptedEvidence(x.storageKey):Promise.resolve()));
  const [updated]=await prisma.$transaction([
    prisma.faceEnrollmentSubmission.update({where:{id:submission.id},data:{status:'NEEDS_RETAKE',progress:100,reasonsJson:reasons as any,imagesJson:[] as any,processedAt:new Date(),processingStartedAt:null}}),
    prisma.employee.update({where:{id:submission.employeeId},data:{faceEnrollmentStatus:'NOT_ENROLLED',faceEnrolledAt:null}}),
    prisma.auditEvent.create({data:{tenantId:submission.tenantId,actorUserId:submission.userId,action:auditAction,entityType:'FaceEnrollmentSubmission',entityId:submission.id,metadataJson:{reasons,scope:'FACE_ONLY',passwordPreserved:true,biometricNoticePreserved:true,webauthnPreserved:true}}})
  ]);
  return updated;
}

async function markTechnicalFailure(submission:any, code:string, message:string, auditAction='FACE_ENROLLMENT_PROCESSING_FAILED'){
  const now=new Date();
  const images=(submission.imagesJson??[]) as StoredPhoto[];
  await Promise.all(images.map(x=>x.storageKey?removeEncryptedEvidence(x.storageKey):Promise.resolve()));
  const privacyMessage=`${message} A selfie temporária foi descartada por privacidade; refaça somente a leitura facial.`;
  const changed=await prisma.faceEnrollmentSubmission.updateMany({
    where:{id:submission.id,status:{in:['PENDING','PROCESSING']}},
    data:{status:'NEEDS_RETAKE',progress:100,imagesJson:[] as any,reasonsJson:{code,message:privacyMessage,retryable:false,photoRetained:false,hardTimeoutMs:ENROLLMENT_HARD_TIMEOUT_MS} as any,processedAt:now,processingStartedAt:null}
  });
  if(changed.count){
    await prisma.$transaction([
      prisma.employee.update({where:{id:submission.employeeId},data:{faceEnrollmentStatus:'NOT_ENROLLED',faceEnrolledAt:null}}),
      prisma.auditEvent.create({data:{tenantId:submission.tenantId,actorUserId:submission.userId,action:auditAction,entityType:'FaceEnrollmentSubmission',entityId:submission.id,metadataJson:{code,photoRetained:false,retryable:false,hardTimeoutMs:ENROLLMENT_HARD_TIMEOUT_MS,scope:'FACE_ONLY',passwordPreserved:true,biometricNoticePreserved:true,webauthnPreserved:true}}})
    ]);
  }
  return prisma.faceEnrollmentSubmission.findUnique({where:{id:submission.id}});
}

export async function recoverStuckFaceEnrollmentForUser(userId:string){
  const user=await prisma.user.findUnique({where:{id:userId},select:{tenantId:true,employee:{select:{id:true}}}});
  if(!user?.employee?.id)return null;
  const row=await prisma.faceEnrollmentSubmission.findFirst({
    where:{tenantId:user.tenantId,employeeId:user.employee.id,userId,status:{in:['PENDING','PROCESSING']}},
    orderBy:{submittedAt:'desc'}
  });
  if(!row)return null;
  const startedAt=row.processingStartedAt??row.submittedAt;
  const ageMs=Date.now()-new Date(startedAt).getTime();
  if(ageMs<ENROLLMENT_HARD_TIMEOUT_MS)return row;
  return markTechnicalFailure(
    row,
    row.status==='PROCESSING'?'PROCESSING_TIMEOUT':'QUEUE_TIMEOUT',
    'A análise facial ultrapassou 12 segundos e foi encerrada automaticamente.' ,
    'FACE_ENROLLMENT_WATCHDOG_TIMEOUT'
  );
}

export async function recoverLegacyTmpFaceEnrollmentForUser(userId:string){
  const user=await prisma.user.findUnique({where:{id:userId},select:{tenantId:true,employee:{select:{id:true}}}});
  if(!user?.employee?.id)return null;
  const row=await prisma.faceEnrollmentSubmission.findFirst({
    where:{tenantId:user.tenantId,employeeId:user.employee.id,userId,status:{in:['PENDING','PROCESSING','FAILED']}},
    orderBy:{submittedAt:'desc'}
  });
  if(!row || !usesLegacyTmpEvidence(row))return row;
  return markNeedsRetake(row,[{
    pose:'ALL',
    code:'LEGACY_TMP_RECAPTURE',
    message:'A selfie anterior pertencia ao armazenamento temporário da versão anterior e não pode mais ser recuperada. Refaça somente a leitura facial; sua senha, aceite biométrico e digital/passkey já cadastradas serão preservadas.'
  }],'FACE_ENROLLMENT_LEGACY_TMP_RECOVERY');
}

async function approveSubmission(submission:any, photos:ProcessedPhoto[],processingMs:number){
  await prisma.$transaction(async tx=>{
    await tx.faceReference.updateMany({where:{tenantId:submission.tenantId,employeeId:submission.employeeId,revokedAt:null},data:{revokedAt:new Date()}});
    for(const p of photos){
      if(p.embedding.length<64)continue;
      await tx.faceReference.create({data:{tenantId:submission.tenantId,employeeId:submission.employeeId,pose:p.pose,storageKey:null,embeddingEncrypted:encryptJson(p.embedding),embeddingModel:'dlib-face-recognition-128d',faceQualityScore:p.quality,livenessScore:null,antiSpoofScore:null,createdByUserId:submission.userId}});
    }
    await tx.employee.update({where:{id:submission.employeeId},data:{faceEnrollmentStatus:'ACTIVE',faceEnrolledAt:new Date(),faceTemplateVersion:{increment:1},failedBiometricAttempts:0,biometricLockedUntil:null}});
    await tx.faceEnrollmentSubmission.update({where:{id:submission.id},data:{status:'APPROVED',progress:100,reasonsJson:{message:'A selfie foi validada pela API facial e a assinatura facial foi ativada. Liveness e antispoof continuam obrigatórios nas marcações faciais.',processingMs,targetMs:8000} as any,imagesJson:[] as any,processedAt:new Date()}});
    await tx.auditEvent.create({data:{tenantId:submission.tenantId,actorUserId:submission.userId,action:'FACE_ENROLLMENT_APPROVED_ASYNC',entityType:'FaceEnrollmentSubmission',entityId:submission.id,metadataJson:{engine:'FASTAPI_FACE_RECOGNITION_DLIB',processingMs,targetMs:8000,deepLivenessAtPunch:true,quality:photos.map(p=>({pose:p.pose,quality:p.quality}))}}});
  });
}

async function processSubmission(submission:any){
  const processingStartedAt=new Date();
  const claimed=await prisma.faceEnrollmentSubmission.updateMany({where:{id:submission.id,status:'PENDING'},data:{status:'PROCESSING',progress:15,processingStartedAt,processingAttempts:{increment:1}}});
  if(!claimed.count)return;
  const row=await prisma.faceEnrollmentSubmission.findUniqueOrThrow({where:{id:submission.id}});
  const policy=await getOrCreateSecuritySettings(row.tenantId);
  const stored=(row.imagesJson??[]) as StoredPhoto[];
  try{
    const front=stored.find(p=>p.pose===FacePose.FRONT);
    if(!front)throw new Error('Selfie frontal do cadastro facial ausente');
    // O cadastro facial usa a API FastAPI/face_recognition em serviço isolado.
    // A imagem continua criptografada no Apontamento Digital durante a fila e não é persistida pela Face API.
    const maybeRaw=decodeStoredPhoto(front);
    const raw=maybeRaw instanceof Promise?await maybeRaw:maybeRaw;
    if(!raw)throw new Error('Evidência biométrica temporária ausente');
    const employee=await prisma.employee.findUnique({where:{id:row.employeeId},select:{name:true}});
    if(!employee)throw new Error('Colaborador não encontrado');
    const registered=await registerFaceEmployeeBytes({employeeId:row.employeeId,name:employee.name,bytes:raw,mime:front.mime??'image/jpeg'});
    const processed:ProcessedPhoto[]=[{...front,embedding:registered.encoding,quality:registered.quality,liveness:null,antiSpoof:null,faceCount:1,gestures:[],yaw:null,pitch:null}];
    await prisma.faceEnrollmentSubmission.update({where:{id:row.id},data:{progress:82}});
    const reasons:any[]=[];
    for(const p of processed){
      if(p.faceCount===0)reasons.push({pose:p.pose,code:'FACE_NOT_FOUND',message:'Nenhum rosto foi detectado'});
      else if(p.faceCount>1)reasons.push({pose:p.pose,code:'MULTIPLE_FACES',message:'Mais de um rosto apareceu na foto'});
      else if(p.quality<policy.minFaceQualityScore)reasons.push({pose:p.pose,code:'QUALITY',message:'Foto com pouca qualidade, distância inadequada ou iluminação insuficiente'});
      if(p.pose===FacePose.FRONT&&p.embedding.length<64)reasons.push({pose:p.pose,code:'EMBEDDING',message:'Não foi possível extrair a assinatura facial frontal'});
    }
    if(reasons.length)return markNeedsRetake(row,reasons);
    const processingMs=Date.now()-processingStartedAt.getTime();
    return approveSubmission(row,processed,processingMs);
  }catch(error:any){
    const message=error instanceof Error?error.message:'Falha desconhecida no processamento facial';
    if(error?.code==='ENOENT' || /Evidência biométrica temporária ausente|no such file or directory/i.test(message)){
      return markNeedsRetake(row,[{pose:'ALL',code:'EVIDENCE_EXPIRED',message:'A selfie anterior não está mais disponível. Faça uma nova captura; a próxima imagem ficará persistida com segurança até a análise terminar.'}]);
    }
    if(error instanceof FaceApiError && error.status===400){
      return markNeedsRetake(row,[{pose:'FRONT',code:'FACE_API_RETAKE',message}],'FACE_ENROLLMENT_FACE_API_NEEDS_RETAKE');
    }
    const timeout=error instanceof FaceEngineTimeoutError;
    await markTechnicalFailure(row,timeout?'FACE_ENGINE_TIMEOUT':'PROCESSING_ERROR',timeout?'A leitura facial demorou mais que o limite permitido. O processamento foi interrompido para não deixar a tela travada.':message);
  }
}

export async function runFaceEnrollmentWorkerOnce(){
  if(workerBusy)return {busy:true};
  workerBusy=true;
  try{
    // Recovery for a process/container interruption. A normal inference is already
    // bounded to a few seconds; anything beyond this threshold must never spin forever.
    const stale=await prisma.faceEnrollmentSubmission.findFirst({where:{status:'PROCESSING',processingStartedAt:{lt:new Date(Date.now()-ENROLLMENT_HARD_TIMEOUT_MS)}},orderBy:{processingStartedAt:'asc'}});
    if(stale)await markTechnicalFailure(stale,'PROCESSING_TIMEOUT','A análise facial excedeu 12 segundos e foi encerrada automaticamente. Tente novamente.','FACE_ENROLLMENT_WATCHDOG_TIMEOUT');
    const next=await prisma.faceEnrollmentSubmission.findFirst({where:{status:'PENDING'},orderBy:{submittedAt:'asc'}});
    if(!next)return {processed:false};
    if(!hasRecoverableEnrollmentImage(next)){
      await markNeedsRetake(next,[{pose:'FRONT',code:'PHOTO_MISSING',message:'A selfie temporária desta tentativa não está mais disponível. Faça uma nova captura para concluir o cadastro facial.'}],'FACE_ENROLLMENT_ORPHANED_SUBMISSION');
      return {processed:false,needsRetake:true,id:next.id};
    }
    const queuedFor=Date.now()-new Date(next.submittedAt).getTime();
    if(queuedFor>=ENROLLMENT_HARD_TIMEOUT_MS){
      await markTechnicalFailure(next,'QUEUE_TIMEOUT','A análise não iniciou dentro de 12 segundos. Tente novamente; sua selfie foi preservada.','FACE_ENROLLMENT_QUEUE_TIMEOUT');
      return {processed:false,timedOut:true,id:next.id};
    }
    await processSubmission(next);
    return {processed:true,id:next.id};
  }finally{workerBusy=false;}
}

/** Fire-and-forget worker kick so a freshly submitted enrollment does not wait for the polling timer. */
export function kickFaceEnrollmentWorker(){
  setImmediate(()=>{void runFaceEnrollmentWorkerOnce().catch(error=>console.error('[PontoProof] immediate face worker failed',error));});
}

export async function retryFaceEnrollmentSubmission(input:{tenantId:string;userId:string;submissionId:string}){
  let row=await prisma.faceEnrollmentSubmission.findFirst({where:{id:input.submissionId,tenantId:input.tenantId,userId:input.userId}});
  if(!row)throw new Error('Solicitação facial não encontrada');
  if(usesLegacyTmpEvidence(row)){
    return markNeedsRetake(row,[{
      pose:'ALL',code:'LEGACY_TMP_RECAPTURE',
      message:'A selfie anterior expirou durante a atualização do armazenamento. Tire novamente somente a selfie facial; as demais etapas já concluídas serão mantidas.'
    }],'FACE_ENROLLMENT_LEGACY_TMP_RECOVERY');
  }
  if(row.status==='PENDING'||row.status==='PROCESSING'){
    const startedAt=row.processingStartedAt??row.submittedAt;
    const ageMs=Date.now()-new Date(startedAt).getTime();
    if(ageMs<ENROLLMENT_HARD_TIMEOUT_MS)throw new Error('A análise ainda está dentro do tempo normal. Aguarde alguns segundos.');
    row=(await markTechnicalFailure(row,'MANUAL_WATCHDOG_TIMEOUT','A análise ultrapassou o limite e foi encerrada.','FACE_ENROLLMENT_MANUAL_WATCHDOG'))??row;
  }
  if(row.status==='NEEDS_RETAKE')return row;
  if(row.status!=='FAILED')throw new Error('Esta análise não precisa ser reenviada');
  if(!hasRecoverableEnrollmentImage(row)){
    return markNeedsRetake(row,[{pose:'FRONT',code:'PHOTO_MISSING',message:'A selfie temporária anterior não está mais disponível. Refaça somente a captura facial; sua senha, aceite e passkey continuam preservados.'}],'FACE_ENROLLMENT_RETRY_REQUIRES_RETAKE');
  }
  const now=new Date();
  const updated=await prisma.$transaction(async tx=>{
    await tx.employee.update({where:{id:row.employeeId},data:{faceEnrollmentStatus:'PENDING',faceEnrolledAt:null}});
    const submission=await tx.faceEnrollmentSubmission.update({where:{id:row.id},data:{status:'PENDING',progress:5,reasonsJson:Prisma.DbNull,processedAt:null,processingStartedAt:null,submittedAt:now}});
    await tx.auditEvent.create({data:{tenantId:row.tenantId,actorUserId:row.userId,action:'FACE_ENROLLMENT_REQUEUED',entityType:'FaceEnrollmentSubmission',entityId:row.id,metadataJson:{queueResetAt:now.toISOString(),photoRetained:true}}});
    return submission;
  });
  kickFaceEnrollmentWorker();
  return updated;
}
