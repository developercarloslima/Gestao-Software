const FACE_API_URL=(process.env.FACE_API_URL??'http://face-api:8000').replace(/\/$/,'');
const FACE_API_KEY=process.env.FACE_API_KEY??'';
const FACE_API_TIMEOUT_MS=Math.max(2500,Number(process.env.FACE_API_TIMEOUT_MS??8000));

export class FaceApiError extends Error{
  status?:number;
  detail?:unknown;
  constructor(message:string,status?:number,detail?:unknown){super(message);this.name='FaceApiError';this.status=status;this.detail=detail;}
}

export type FaceApiRegistration={id:number;external_id:string;name:string;status:string;quality:number;encoding:number[]};
export type FaceApiVerification={external_id:string;matched:boolean;distance:number;tolerance:number;quality:number;status:string;registered:true}|{external_id:string;matched:false;distance:null;tolerance:null;quality:null;status:string;registered:false};
export type FaceApiRecognition={id:number;external_id:string;name:string;status:string;distance:number;tolerance:number;quality:number};

function imageFromDataUrl(value:string){
  const match=/^data:([^;,]+);base64,(.+)$/s.exec(value);
  if(!match)throw new FaceApiError('Selfie facial inválida para envio ao serviço de reconhecimento');
  const bytes=Buffer.from(match[2],'base64');
  if(!bytes.length)throw new FaceApiError('Selfie facial vazia');
  return {mime:match[1]||'image/jpeg',bytes};
}

async function request(path:string,init:RequestInit){
  const controller=new AbortController();
  const timer=setTimeout(()=>controller.abort(),FACE_API_TIMEOUT_MS);
  try{
    const headers=new Headers(init.headers??{});
    if(FACE_API_KEY)headers.set('X-API-Key',FACE_API_KEY);
    const response=await fetch(`${FACE_API_URL}${path}`,{...init,headers,signal:controller.signal});
    const body=await response.json().catch(()=>null);
    if(!response.ok){
      const detail=body?.detail;
      const message=typeof detail==='string'?detail:(detail?.message??body?.error??`Face API respondeu ${response.status}`);
      throw new FaceApiError(String(message),response.status,detail);
    }
    return body;
  }catch(error:any){
    if(error?.name==='AbortError')throw new FaceApiError(`Serviço facial excedeu ${FACE_API_TIMEOUT_MS/1000}s`);
    if(error instanceof FaceApiError)throw error;
    throw new FaceApiError(error instanceof Error?error.message:'Falha de comunicação com o serviço facial');
  }finally{clearTimeout(timer);}
}

function imageForm(externalId:string,bytes:Uint8Array,mime:string,name?:string){
  const form=new FormData();
  form.set('external_id',externalId);
  if(name!==undefined)form.set('name',name);
  const safeBytes=new Uint8Array(bytes.byteLength);
  safeBytes.set(bytes);
  form.set('image',new Blob([safeBytes.buffer],{type:mime||'image/jpeg'}),'selfie.jpg');
  return form;
}

export async function registerFaceEmployeeBytes(input:{employeeId:string;name:string;bytes:Uint8Array;mime:string}){
  return request('/api/v1/register',{method:'POST',body:imageForm(input.employeeId,input.bytes,input.mime,input.name)}) as Promise<FaceApiRegistration>;
}

export async function registerFaceEmployeeDataUrl(input:{employeeId:string;name:string;imageDataUrl:string}){
  const parsed=imageFromDataUrl(input.imageDataUrl);
  return registerFaceEmployeeBytes({employeeId:input.employeeId,name:input.name,bytes:parsed.bytes,mime:parsed.mime});
}

export async function registerFaceEmployeeEncoding(input:{employeeId:string;name:string;encoding:number[]}){
  return request('/api/v1/register-encoding',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({external_id:input.employeeId,name:input.name,encoding:input.encoding})});
}

export async function verifyFaceEmployeeDataUrl(input:{employeeId:string;imageDataUrl:string}):Promise<FaceApiVerification>{
  const parsed=imageFromDataUrl(input.imageDataUrl);
  try{
    const result=await request('/api/v1/verify',{method:'POST',body:imageForm(input.employeeId,parsed.bytes,parsed.mime)});
    return {...result,registered:true} as FaceApiVerification;
  }catch(error){
    if(error instanceof FaceApiError&&error.status===404)return {external_id:input.employeeId,matched:false,distance:null,tolerance:null,quality:null,status:'Referência ainda não sincronizada',registered:false};
    throw error;
  }
}


export async function recognizeFaceEmployeeDataUrl(imageDataUrl:string):Promise<FaceApiRecognition>{
  const parsed=imageFromDataUrl(imageDataUrl);
  const form=new FormData();
  const safeBytes=new Uint8Array(parsed.bytes.byteLength);safeBytes.set(parsed.bytes);
  form.set('image',new Blob([safeBytes.buffer],{type:parsed.mime||'image/jpeg'}),'face.jpg');
  // A mesma Face API também atende o portal principal, cujas referências usam
  // o prefixo `portal-`. O Totem Apontamento Digital deve comparar somente com
  // colaboradores do ponto para evitar colisão entre os dois cadastros.
  form.set('exclude_external_id_prefix','portal-');
  return request('/api/v1/recognize',{method:'POST',body:form}) as Promise<FaceApiRecognition>;
}

export async function deleteFaceEmployee(employeeId:string){
  return request(`/api/v1/employees/${encodeURIComponent(employeeId)}`,{method:'DELETE'});
}

export function faceApiSimilarity(distance:number|null){
  if(distance===null||!Number.isFinite(distance))return 0;
  return Math.max(0,Math.min(1,1-distance));
}
