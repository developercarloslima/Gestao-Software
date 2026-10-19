import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const storageRoot=path.resolve(process.env.WORK_MONITOR_STORAGE_DIR??'./storage/work-monitor');

function encryptionKey(){
  const configured=process.env.WORK_MONITOR_ENCRYPTION_KEY??process.env.BIOMETRIC_ENCRYPTION_KEY;
  if(configured){
    if(/^[a-f0-9]{64}$/i.test(configured))return Buffer.from(configured,'hex');
    const b64=Buffer.from(configured,'base64');
    if(b64.length===32)return b64;
    if(configured.length>=32)return crypto.createHash('sha256').update(configured,'utf8').digest();
    throw new Error('WORK_MONITOR_ENCRYPTION_KEY inválida: use segredo com pelo menos 32 caracteres, 32 bytes em base64 ou 64 caracteres hexadecimais');
  }
  if(process.env.NODE_ENV==='production')throw new Error('WORK_MONITOR_ENCRYPTION_KEY ou BIOMETRIC_ENCRYPTION_KEY é obrigatória em produção');
  return crypto.createHash('sha256').update(process.env.JWT_SECRET??'nh-ponto-dev-work-monitor-key').digest();
}

function encrypt(data:Buffer){
  const iv=crypto.randomBytes(12);
  const cipher=crypto.createCipheriv('aes-256-gcm',encryptionKey(),iv);
  const ciphertext=Buffer.concat([cipher.update(data),cipher.final()]);
  const tag=cipher.getAuthTag();
  return Buffer.concat([iv,tag,ciphertext]);
}

function decrypt(data:Buffer){
  if(data.length<29)throw new Error('Gravação corrompida');
  const iv=data.subarray(0,12),tag=data.subarray(12,28),ciphertext=data.subarray(28);
  const decipher=crypto.createDecipheriv('aes-256-gcm',encryptionKey(),iv);
  decipher.setAuthTag(tag);
  return Buffer.concat([decipher.update(ciphertext),decipher.final()]);
}

export async function saveEncryptedWorkMonitorChunk(data:Buffer,namespace:string){
  if(!Buffer.isBuffer(data)||data.length<256)throw new Error('Trecho de gravação vazio ou inválido');
  if(data.length>15*1024*1024)throw new Error('Trecho de gravação excede 15 MB');
  await fs.mkdir(storageRoot,{recursive:true});
  const safeNamespace=namespace.replace(/[^a-zA-Z0-9_-]/g,'_');
  const storageKey=`${safeNamespace}-${crypto.randomUUID()}.bin`;
  await fs.writeFile(path.join(storageRoot,storageKey),encrypt(data),{mode:0o600});
  return {storageKey,byteLength:data.length,sha256:crypto.createHash('sha256').update(data).digest('hex')};
}

export async function readEncryptedWorkMonitorChunk(storageKey:string){
  if(!/^[a-zA-Z0-9_.-]+$/.test(storageKey))throw new Error('Chave de armazenamento inválida');
  return decrypt(await fs.readFile(path.join(storageRoot,storageKey)));
}

export async function removeEncryptedWorkMonitorChunk(storageKey?:string|null){
  if(!storageKey||!/^[a-zA-Z0-9_.-]+$/.test(storageKey))return;
  await fs.rm(path.join(storageRoot,storageKey),{force:true}).catch(()=>undefined);
}
