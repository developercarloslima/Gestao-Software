type TenantConfig={
  active?: boolean;
  enabledModules?: string[];
  source?: string;
};

let cached:TenantConfig|null=null;
let cachedUntil=0;

const cacheMs=Math.max(10_000,Number(process.env.GESTAO_ENTITLEMENT_CACHE_MS??30_000));
const tenantConfigUrl=(process.env.GESTAO_TENANT_CONFIG_URL??(process.env.NH_MAIN_API_HOST?`http://${process.env.NH_MAIN_API_HOST}:${process.env.NH_MAIN_API_PORT??'10000'}/api/public/tenant-config`:'http://backend:8080/api/public/tenant-config')).trim();

async function fetchConfig():Promise<TenantConfig|null>{
  try{
    const controller=new AbortController();
    const timeout=setTimeout(()=>controller.abort(),4_000);
    try{
      const response=await fetch(tenantConfigUrl,{headers:{accept:'application/json'},signal:controller.signal});
      if(!response.ok)return null;
      return await response.json() as TenantConfig;
    }finally{clearTimeout(timeout);}
  }catch{return null;}
}

export async function getGestaoEntitlement():Promise<TenantConfig>{
  const now=Date.now();
  if(cached&&now<cachedUntil)return cached;
  const remote=await fetchConfig();
  if(remote){cached=remote;cachedUntil=now+cacheMs;return remote;}
  // O backend NH persiste a última configuração da Gestão. Se ele estiver
  // indisponível, não inventamos uma nova licença; mantemos o último estado
  // recebido pelo Ponto enquanto o processo estiver vivo.
  if(cached){cachedUntil=now+Math.min(cacheMs,60_000);return cached;}
  // Bootstrap local: o primeiro start continua disponível. Em produção o
  // backend principal deve estar configurado e fornecer a licença central.
  cached={active:true,enabledModules:['APONTAMENTO_DIGITAL'],source:'PONTO_BOOTSTRAP'};
  cachedUntil=now+10_000;
  return cached;
}

export async function assertPontoEnabled(){
  const config=await getGestaoEntitlement();
  if(config.active===false)return {allowed:false,code:'COMPANY_DISABLED',message:'Empresa desativada pela Gestão Software.'};
  const modules=Array.isArray(config.enabledModules)?config.enabledModules.map(String):[];
  if(!modules.includes('APONTAMENTO_DIGITAL'))return {allowed:false,code:'MODULE_DISABLED',message:'Apontamento Digital não está ativo para esta empresa.'};
  return {allowed:true,config};
}
