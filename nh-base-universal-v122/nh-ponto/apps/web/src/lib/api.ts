const API_URL = import.meta.env.VITE_API_URL ?? '/ponto-api';

export type BrandTheme = { primary:string; secondary:string; surface:string; text:string; logoUrl?:string|null; source?:string|null; updatedAt?:string|null; hasGuide?:boolean };
export type ManagedTenant = { id:string; name:string; legalName?:string|null; document?:string|null; brand?:BrandTheme };

export type SessionUser = {
  id: string;
  tenantId: string;
  homeTenantId?: string;
  username: string;
  email?: string | null;
  role: string;
  employeeId?: string;
  name: string;
  tenant: string;
  brand?: BrandTheme;
  managedTenants?: ManagedTenant[];
  onboarding?: { required:boolean; accessReady?:boolean; mustChangePassword:boolean; passwordChanged:boolean; biometricNoticeAcknowledged?:boolean; faceEnrolled?:boolean; facePending?:boolean; faceEnrollmentStatus?:string|null; faceEnrollmentSubmissionStatus?:string|null; faceEnrollmentSubmissionId?:string|null; platformBiometricEnrolled:boolean; completed:boolean; completedAt?:string|null };
};

export function getToken() { return localStorage.getItem('pontoproof_token'); }
export function setToken(token: string) { localStorage.setItem('pontoproof_token', token); }
export function clearToken() { localStorage.removeItem('pontoproof_token'); localStorage.removeItem('pontoproof_user'); localStorage.removeItem('pontoproof_active_tenant'); }
export function getCachedUser(): SessionUser | null {
  const raw = localStorage.getItem('pontoproof_user');
  return raw ? JSON.parse(raw) : null;
}
export function cacheUser(user: SessionUser) {
  localStorage.setItem('pontoproof_user', JSON.stringify(user));
  if(['ADMIN','SUPER_ADMIN','TIME_REVIEWER'].includes(user.role) && user.managedTenants?.length){
    const current=localStorage.getItem('pontoproof_active_tenant');
    if(!current || !user.managedTenants.some(t=>t.id===current))localStorage.setItem('pontoproof_active_tenant',user.tenantId||user.managedTenants[0].id);
  }
}
export function getActiveTenantId(){return localStorage.getItem('pontoproof_active_tenant');}
export function setActiveTenantId(id:string){localStorage.setItem('pontoproof_active_tenant',id);}
export function activeManagedTenant(user:SessionUser|null){const id=getActiveTenantId();return user?.managedTenants?.find(t=>t.id===id)??user?.managedTenants?.find(t=>t.id===user.tenantId)??user?.managedTenants?.[0]??null;}
function tenantScopedPath(path:string){return !path.startsWith('/auth/')&&!path.startsWith('/security/')&&path!=='/me';}
function needsTenantHeader(path:string){return tenantScopedPath(path)||path.startsWith('/security/webauthn/review/')||path.startsWith('/security/webauthn/timesheet/')||path.startsWith('/security/worksites/');}


export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = getToken();
  const hasBody=init.body!==undefined&&init.body!==null;
  const response = await fetch(`${API_URL}${path}`, {
    ...init,
    headers: {
      ...(hasBody ? { 'Content-Type': 'application/json' } : {}),
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(token && needsTenantHeader(path) && getActiveTenantId() ? { 'X-Tenant-Id': getActiveTenantId()! } : {}),
      ...(init.headers ?? {})
    }
  });
  const rawText=await response.text();
  let data:any={};
  if(rawText){try{data=JSON.parse(rawText);}catch{data={error:rawText};}}
  if (!response.ok) {
    if (response.status === 502 || response.status === 503 || response.status === 504) {
      throw new Error(data.error || 'Apontamento Digital está iniciando ou temporariamente indisponível. Aguarde alguns segundos e tente novamente.');
    }
    const backendMessage=String(data.error??data.message??'').trim();
    if(response.status>=500 && (!backendMessage || /internal server error|erro interno/i.test(backendMessage))){
      throw new Error('Não foi possível concluir esta operação agora. Atualize a página e tente novamente.');
    }
    throw new Error(backendMessage || `Não foi possível concluir a operação (HTTP ${response.status}).`);
  }
  return data as T;
}


export async function apiWithToken<T>(path:string, token:string, init:RequestInit={}):Promise<T>{
  const response=await fetch(`${API_URL}${path}`,{
    ...init,
    headers:{'Content-Type':'application/json',Authorization:`Bearer ${token}`,...(init.headers??{})}
  });
  const rawText=await response.text();let data:any={};if(rawText){try{data=JSON.parse(rawText);}catch{data={error:rawText};}}
  if(!response.ok){const message=String(data.error??data.message??'').trim();if(response.status>=500&&(!message||/internal server error|erro interno/i.test(message)))throw new Error('Não foi possível concluir esta operação agora. Atualize a página e tente novamente.');throw new Error(message||`Não foi possível concluir a operação (HTTP ${response.status}).`);}return data as T;
}

export async function apiBlob(path:string):Promise<Blob>{
  const token=getToken();
  const response=await fetch(`${API_URL}${path}`,{headers:{...(token?{Authorization:`Bearer ${token}`}:{}) ,...(token&&needsTenantHeader(path)&&getActiveTenantId()?{'X-Tenant-Id':getActiveTenantId()!}:{})}});
  if(!response.ok){const data=await response.json().catch(()=>({}));throw new Error(data.error??`Erro HTTP ${response.status}`);}
  return response.blob();
}
