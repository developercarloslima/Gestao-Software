import { browserSupportsWebAuthn,startAuthentication,startRegistration } from '@simplewebauthn/browser';
import { api } from './api';

export function supportsWebAuthn(){return browserSupportsWebAuthn();}
export async function enrollPlatformBiometric(label?:string){
  if(!browserSupportsWebAuthn())throw new Error('Este navegador/dispositivo não oferece WebAuthn/passkeys.');
  const options=await api<any>('/security/webauthn/register/options',{method:'POST',body:'{}'});
  const response=await startRegistration({optionsJSON:options});
  return api<{verified:boolean}>('/security/webauthn/register/verify',{method:'POST',body:JSON.stringify({response,label})});
}
export async function performPlatformBiometric(punchChallengeId:string){
  if(!browserSupportsWebAuthn())throw new Error('Biometria/passkey indisponível neste navegador.');
  const options=await api<any>('/security/webauthn/auth/options',{method:'POST',body:JSON.stringify({punchChallengeId})});
  const response=await startAuthentication({optionsJSON:options});
  return api<{verified:boolean;biometricProofToken:string;credentialId:string}>('/security/webauthn/auth/verify',{method:'POST',body:JSON.stringify(response)});
}

export async function performReviewBiometric(punchId:string){
  if(!browserSupportsWebAuthn())throw new Error('Biometria/passkey indisponível neste navegador.');
  const options=await api<any>('/security/webauthn/review/options',{method:'POST',body:JSON.stringify({punchId})});
  const response=await startAuthentication({optionsJSON:options});
  return api<{verified:boolean;biometricProofToken:string;credentialId:string}>('/security/webauthn/review/verify',{method:'POST',body:JSON.stringify({punchId,response})});
}


export async function performTimesheetAcceptance(employeeId:string,month:string,signer:'ADMIN'|'EMPLOYEE'){
  if(!browserSupportsWebAuthn())throw new Error('Biometria/passkey indisponível neste navegador.');
  const payload={employeeId,month,signer};
  const options=await api<any>('/security/webauthn/timesheet/options',{method:'POST',body:JSON.stringify(payload)});
  const response=await startAuthentication({optionsJSON:options});
  return api<{verified:boolean;acceptance:any}>('/security/webauthn/timesheet/verify',{method:'POST',body:JSON.stringify({...payload,response})});
}

export type LoginPasskeyMode='DEFAULT'|'PHONE';

export async function loginWithPasswordAndPasskey(username:string,password:string,tenantDocument?:string,passkeyMode:LoginPasskeyMode='DEFAULT'){
  const start=await api<any>('/auth/login',{method:'POST',body:JSON.stringify({username,password,...(tenantDocument?{tenantDocument}:{}),passkeyMode})});
  // Primeiro acesso ainda sem passkey: a API libera uma sessão restrita pelo onboarding.
  if(!start?.passkeyRequired){
    if(passkeyMode==='PHONE'&&['ADMIN','HR','TIME_REVIEWER'].includes(String(start?.user?.role??'')))throw new Error('Esta conta ainda não possui uma passkey cadastrada. Entre normalmente com usuário e senha e cadastre a passkey em Minha segurança.');
    if(passkeyMode==='PHONE')throw new Error('Este usuário não utiliza passkey no login. Use o botão Entrar.');
    return start;
  }
  if(!browserSupportsWebAuthn())throw new Error('Esta conta exige biometria/passkey, mas este navegador/dispositivo não oferece WebAuthn.');
  const options=passkeyMode==='PHONE'
    ? {...start.options,hints:['hybrid'],allowCredentials:Array.isArray(start.options?.allowCredentials)?start.options.allowCredentials.map((credential:any)=>{const {transports:_transports,...rest}=credential;return rest;}):start.options?.allowCredentials}
    : start.options;
  try{
    const response=await startAuthentication({optionsJSON:options});
    return api<any>('/auth/webauthn/verify',{method:'POST',body:JSON.stringify({challengeId:start.challengeId,response})});
  }catch(error:any){
    if(passkeyMode==='PHONE'&&(error?.name==='NotAllowedError'||/notallowed/i.test(String(error?.name??''))))throw new Error('Autenticação pelo celular cancelada ou indisponível. No computador, escolha a opção de outro dispositivo/telefone no diálogo de passkey e escaneie o QR Code com o celular que possui a passkey.');
    throw error;
  }
}
