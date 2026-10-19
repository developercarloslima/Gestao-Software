import { useEffect, useRef, useState } from 'react';
import Login from './components/Login';
import PunchPanel from './components/PunchPanel';
import MyJourney from './components/MyJourney';
import Adjustments from './components/Adjustments';
import TeamAdjustments from './components/management/TeamAdjustments';
import Management from './components/Management';
import Audit from './components/Audit';
import SecurityCenter from './components/SecurityCenter';
import FirstAccessOnboarding from './components/FirstAccessOnboarding';
import KioskMode from './components/KioskMode';
import SuperAdminPanel from './components/SuperAdminPanel';
import QuickFaceEnrollmentCapture, { type QuickEnrollmentPhoto } from './components/QuickFaceEnrollmentCapture';
import { activeManagedTenant, api, cacheUser, clearToken, getCachedUser, getToken, setActiveTenantId, SessionUser } from './lib/api';
import { applyBrandTheme } from './lib/theme';

type FaceSubmission={
 id:string;
 status:'PENDING'|'PROCESSING'|'APPROVED'|'NEEDS_RETAKE'|'FAILED';
 progress:number;
 reasons?:any;
};
type FacePopup={kind:'approved'|'retake'|'failed';title:string;messages:string[];submissionId?:string|null};

const INACTIVITY_TIMEOUT_MS=10*60*1000;
const LAST_ACTIVITY_STORAGE_KEY='pontoproof_last_activity_at';

function reasonMessages(reasons:any):string[]{
 if(Array.isArray(reasons))return reasons.map(r=>String(r?.message??r?.code??'A leitura facial precisa ser refeita.'));
 if(reasons?.message)return [String(reasons.message)];
 return [];
}

export default function App(){
 const [platformGate,setPlatformGate]=useState<'checking'|'active'|'company-disabled'|'module-disabled'>('checking');
 const [user,setUser]=useState<SessionUser|null>(getToken()?getCachedUser():null);
 const [tab,setTab]=useState('ponto');
 const [onboardingChecked,setOnboardingChecked]=useState(false);
 const [facePopup,setFacePopup]=useState<FacePopup|null>(null);
 const [faceCaptureOpen,setFaceCaptureOpen]=useState(false);
 const [faceActionBusy,setFaceActionBusy]=useState(false);
 const kioskRequested=new URLSearchParams(location.search).get('kiosk')==='1'||localStorage.getItem('nh_ponto_kiosk_active')==='1';
 const loginFaceEnabled=false; // v83.18: facial removida de todos os logins
 const previousFaceStatus=useRef<string|null>(user?.onboarding?.faceEnrollmentSubmissionStatus??null);
 const shownFaceActionPopup=useRef<string|null>(null);
 const activeCompany=activeManagedTenant(user);

 useEffect(()=>{
   let cancelled=false;
   const check=async()=>{
     try{
       const response=await fetch('/api/public/tenant-config',{cache:'no-store'});
       if(!response.ok)throw new Error('tenant-config');
       const cfg=await response.json();
       if(cancelled)return;
       if(cfg?.active===false){setPlatformGate('company-disabled');return;}
       const modules=Array.isArray(cfg?.enabledModules)?cfg.enabledModules.map(String):[];
       setPlatformGate(modules.includes('APONTAMENTO_DIGITAL')?'active':'module-disabled');
     }catch{
       if(!cancelled)setPlatformGate('active');
     }
   };
   void check();
   const timer=window.setInterval(check,30_000);
   return()=>{cancelled=true;window.clearInterval(timer);};
 },[]);

 useEffect(()=>{applyBrandTheme(activeCompany?.brand??user?.brand);},[user?.id,activeCompany?.id,activeCompany?.brand?.primary,activeCompany?.brand?.secondary,activeCompany?.brand?.surface,activeCompany?.brand?.text,user?.brand?.primary,user?.brand?.secondary]);

 function applyOnboarding(onboarding:any){
   setUser(prev=>{
     if(!prev)return prev;
     const next={...prev,onboarding};
     cacheUser(next);
     return next;
   });
 }

 useEffect(()=>{if('serviceWorker' in navigator) navigator.serviceWorker.register(`${import.meta.env.BASE_URL}sw.js`).catch(()=>{});},[]);
 useEffect(()=>{if(user?.role==='AUDITOR')setTab('auditoria');else if((user?.role==='ADMIN'&&!user?.employeeId)||user?.role==='TIME_REVIEWER')setTab('gestao');},[user?.role,user?.employeeId]);
 // While the app is open during a work shift, keep the service warm.
 useEffect(()=>{
   if(!user)return;
   const ping=()=>fetch('/ponto-api/health',{cache:'no-store'}).catch(()=>undefined);
   ping();const timer=window.setInterval(ping,4*60*1000);return()=>window.clearInterval(timer);
 },[user?.id]);

 // Segurança da sessão: após 10 minutos sem interação do usuário, encerra somente
 // a sessão web. Nenhuma marcação de CLOCK_OUT é criada aqui; se uma jornada
 // estiver aberta, ela continua normalmente no servidor e poderá ser retomada
 // depois de um novo login. O modo Totem fica fora desta regra para permanecer
 // disponível no dispositivo fixo. A última atividade é compartilhada entre abas.
 useEffect(()=>{
   if(!user||kioskRequested)return;
   let lastWrite=0;
   let loggedOut=false;

   const readLastActivity=()=>{
     const raw=Number(localStorage.getItem(LAST_ACTIVITY_STORAGE_KEY));
     return Number.isFinite(raw)&&raw>0?raw:0;
   };
   const recordActivity=(force=false)=>{
     const now=Date.now();
     if(!force&&now-lastWrite<1000)return;
     lastWrite=now;
     localStorage.setItem(LAST_ACTIVITY_STORAGE_KEY,String(now));
   };
   const logoutForInactivity=()=>{
     if(loggedOut)return;
     loggedOut=true;
     localStorage.removeItem(LAST_ACTIVITY_STORAGE_KEY);
     clearToken();
     setUser(null);
   };
   const checkInactivity=()=>{
     const last=readLastActivity();
     if(!last){recordActivity(true);return;}
     if(Date.now()-last>=INACTIVITY_TIMEOUT_MS)logoutForInactivity();
   };
   const onActivity=()=>recordActivity();
   const onFocus=()=>{
     checkInactivity();
     if(getToken())recordActivity(true);
   };
   const onVisibilityChange=()=>{
     if(document.visibilityState!=='visible')return;
     checkInactivity();
     if(getToken())recordActivity(true);
   };

   recordActivity(true);
   const activityEvents=['pointerdown','pointermove','keydown','touchstart','wheel','scroll'] as const;
   activityEvents.forEach(event=>window.addEventListener(event,onActivity,{passive:true}));
   window.addEventListener('focus',onFocus);
   document.addEventListener('visibilitychange',onVisibilityChange);
   const timer=window.setInterval(checkInactivity,1_000);

   return()=>{
     activityEvents.forEach(event=>window.removeEventListener(event,onActivity));
     window.removeEventListener('focus',onFocus);
     document.removeEventListener('visibilitychange',onVisibilityChange);
     window.clearInterval(timer);
   };
 },[user?.id,kioskRequested]);

 // Revalida o primeiro acesso ao abrir/recarregar a aplicação. Se uma análise
 // anterior terminou pedindo nova selfie ou uma nova tentativa técnica, mostra
 // o aviso assim que o usuário entrar no perfil.
 useEffect(()=>{
   if(!user){setOnboardingChecked(true);return;}
   let cancelled=false;
   setOnboardingChecked(false);
   (async()=>{
     try{
       const onboarding=await api<any>('/auth/onboarding-status');
       if(cancelled)return;
       previousFaceStatus.current=String(onboarding.faceEnrollmentSubmissionStatus??'')||null;
       applyOnboarding(onboarding);
       if(loginFaceEnabled&&user.role==='ADMIN'&&user.employeeId){
         const res=await api<{submission:FaceSubmission|null}>('/security/face-enrollment/submission/latest').catch(()=>({submission:null}));
         if(cancelled)return;
         const submission=res.submission;
         if(submission?.status==='NEEDS_RETAKE')setFacePopup({kind:'retake',title:'Precisamos refazer sua leitura facial',messages:reasonMessages(submission.reasons),submissionId:submission.id});
         if(submission?.status==='FAILED')setFacePopup({kind:'failed',title:'A análise facial encontrou um erro técnico',messages:reasonMessages(submission.reasons),submissionId:submission.id});
       }
     }catch{}
     finally{if(!cancelled)setOnboardingChecked(true);}
   })();
   return()=>{cancelled=true;};
 },[user?.id]);

 useEffect(()=>{
   if(!user)return;
   const refreshPasskeyState=()=>{void api<any>('/auth/onboarding-status').then(applyOnboarding).catch(()=>undefined);};
   window.addEventListener('nh-passkey-updated',refreshPasskeyState);
   return()=>window.removeEventListener('nh-passkey-updated',refreshPasskeyState);
 },[user?.id]);

 // Depois que o primeiro acesso foi liberado, a análise facial continua no
 // servidor. O usuário permanece no perfil e recebe um pop-up quando a análise
 // termina: aprovada, recaptura necessária ou falha técnica recuperável.
 useEffect(()=>{
   if(!loginFaceEnabled||!user||user.role!=='ADMIN'||user.onboarding?.faceEnrolled)return;
   const status=String(user.onboarding?.faceEnrollmentSubmissionStatus??'');
   const pending=Boolean(user.onboarding?.facePending)||status==='PENDING'||status==='PROCESSING';
   if(!pending)return;
   let cancelled=false;
   const check=async()=>{
     try{
       const [onboarding,res]=await Promise.all([
         api<any>('/auth/onboarding-status'),
         api<{submission:FaceSubmission|null}>('/security/face-enrollment/submission/latest')
       ]);
       if(cancelled)return;
       const submission=res.submission;
       const nextStatus=String(submission?.status??onboarding.faceEnrollmentSubmissionStatus??'')||null;
       const prevStatus=previousFaceStatus.current;
       applyOnboarding(onboarding);
       if(nextStatus!==prevStatus){
         if(nextStatus==='APPROVED')setFacePopup({kind:'approved',title:'Reconhecimento facial aprovado ✓',messages:['Sua leitura facial foi validada e já está ativa. O reconhecimento facial frontal do Administrador está ativo. Colaboradores usam usuário/senha + selfie simples; o Totem usa a foto cadastrada pelo RH.'],submissionId:submission?.id});
         if(nextStatus==='NEEDS_RETAKE')setFacePopup({kind:'retake',title:'Precisamos refazer sua leitura facial',messages:reasonMessages(submission?.reasons),submissionId:submission?.id});
         if(nextStatus==='FAILED')setFacePopup({kind:'failed',title:'A análise facial encontrou um erro técnico',messages:reasonMessages(submission?.reasons),submissionId:submission?.id});
       }
       previousFaceStatus.current=nextStatus;
     }catch{}
   };
   void check();
   const timer=window.setInterval(check,1200);
   return()=>{cancelled=true;window.clearInterval(timer);};
 },[user?.id,user?.onboarding?.faceEnrolled,user?.onboarding?.facePending,user?.onboarding?.faceEnrollmentSubmissionStatus]);

 // Se a análise terminou muito rápido (antes mesmo de a biometria/passkey
 // terminar), o usuário pode chegar ao perfil já com NEEDS_RETAKE/FAILED.
 // Mostra o pop-up uma vez nesse cenário também.
 useEffect(()=>{
   if(!loginFaceEnabled||!user||user.role!=='ADMIN'||user.onboarding?.required)return;
   const status=String(user.onboarding?.faceEnrollmentSubmissionStatus??'');
   if(status!=='NEEDS_RETAKE'&&status!=='FAILED')return;
   const key=`${user.onboarding?.faceEnrollmentSubmissionId??'latest'}:${status}`;
   if(shownFaceActionPopup.current===key)return;
   shownFaceActionPopup.current=key;
   void api<{submission:FaceSubmission|null}>('/security/face-enrollment/submission/latest').then(res=>{
     const submission=res.submission;if(!submission)return;
     if(submission.status==='NEEDS_RETAKE')setFacePopup({kind:'retake',title:'Precisamos refazer sua leitura facial',messages:reasonMessages(submission.reasons),submissionId:submission.id});
     if(submission.status==='FAILED')setFacePopup({kind:'failed',title:'A análise facial encontrou um erro técnico',messages:reasonMessages(submission.reasons),submissionId:submission.id});
   }).catch(()=>undefined);
 },[user?.id,user?.onboarding?.required,user?.onboarding?.faceEnrollmentSubmissionStatus,user?.onboarding?.faceEnrollmentSubmissionId]);

 async function submitFaceRetake(photos:QuickEnrollmentPhoto[]){
   setFaceActionBusy(true);
   try{
     const res=await api<{submission:FaceSubmission}>('/security/face-enrollment/submit',{method:'POST',body:JSON.stringify({photos})});
     previousFaceStatus.current=res.submission.status;
     const onboarding=await api<any>('/auth/onboarding-status');
     applyOnboarding(onboarding);
     setFaceCaptureOpen(false);
     setFacePopup(null);
   }finally{setFaceActionBusy(false);}
 }

 async function retryFaceProcessing(){
   setFaceActionBusy(true);
   try{
     let id=facePopup?.submissionId??user?.onboarding?.faceEnrollmentSubmissionId??null;
     if(!id){const res=await api<{submission:FaceSubmission|null}>('/security/face-enrollment/submission/latest');id=res.submission?.id??null;}
     if(!id)throw new Error('Análise facial não encontrada.');
     const res=await api<{submission:FaceSubmission}>(`/security/face-enrollment/submission/${id}/retry`,{method:'POST',body:'{}'});
     previousFaceStatus.current=res.submission.status;
     const onboarding=await api<any>('/auth/onboarding-status');
     applyOnboarding(onboarding);
     setFacePopup(null);
   }catch(e){
     setFacePopup({kind:'failed',title:'Não foi possível reiniciar a análise',messages:[e instanceof Error?e.message:'Tente novamente em alguns segundos.'],submissionId:facePopup?.submissionId});
   }finally{setFaceActionBusy(false);}
 }

 if(kioskRequested&&user?.role!=='SUPER_ADMIN')return <KioskMode user={user}/>;
 if(!user)return <Login onLogin={u=>{setUser(u);setOnboardingChecked(true);}}/>;
 if(user.role==='SUPER_ADMIN')return <SuperAdminPanel user={user}/>;
 if(!onboardingChecked)return <main className="login-shell"><section className="login-card"><h2>Validando primeiro acesso…</h2></section></main>;
 if(user.onboarding?.required)return <FirstAccessOnboarding user={user} onComplete={u=>{cacheUser(u);setUser(u);}}/>;
 const manager=['ADMIN','HR','SUPERVISOR','TIME_REVIEWER'].includes(user.role); const auditor=['ADMIN','HR','AUDITOR'].includes(user.role); const employeeFlow=Boolean(user.employeeId)&&['ADMIN','EMPLOYEE','SUPERVISOR'].includes(user.role);
 const passkeyRequiredForUser=['ADMIN','HR'].includes(user.role);
 const passkeyMissing=passkeyRequiredForUser&&!user.onboarding?.platformBiometricEnrolled;
 const faceStatus=String(user.onboarding?.faceEnrollmentSubmissionStatus??'');
 const facePending=loginFaceEnabled&&user.role==='ADMIN'&&(Boolean(user.onboarding?.facePending)||(!user.onboarding?.faceEnrolled&&['PENDING','PROCESSING'].includes(faceStatus)));
 const faceNeedsAction=loginFaceEnabled&&user.role==='ADMIN'&&!user.onboarding?.faceEnrolled&&['NEEDS_RETAKE','FAILED'].includes(faceStatus);
 const logout=()=>{clearToken();setUser(null);};
 return <div className="app-shell"><aside className="sidebar"><div className="sidebar-brand"><img className="nh-ponto-logo nh-ponto-logo-sidebar" src={activeCompany?.brand?.logoUrl||user.brand?.logoUrl||"/ponto/identidade-pendente.svg"} alt={activeCompany?.name||user.tenant}/><div className="sidebar-brand-copy"><b>Apontamento Digital</b><span>{activeCompany?.name||user.tenant}</span></div><button className="mobile-logout" onClick={logout} aria-label="Sair do Apontamento Digital">Sair</button></div><nav>{employeeFlow&&<><button className={tab==='ponto'?'active':''} onClick={()=>setTab('ponto')}>◉ Registrar ponto</button><button className={tab==='jornada'?'active':''} onClick={()=>setTab('jornada')}>◷ Minha jornada</button>{!manager&&<button className={tab==='ajustes'?'active':''} onClick={()=>setTab('ajustes')}>↺ Ajustes</button>}</>}{manager&&user.role!=='TIME_REVIEWER'&&<button className={tab==='ajustes-equipe'?'active':''} onClick={()=>setTab('ajustes-equipe')}>↺ Ajustes da equipe</button>}{passkeyRequiredForUser&&<button className={tab==='seguranca-pessoal'?'active':''} onClick={()=>setTab('seguranca-pessoal')}>◈ Minha segurança</button>}{manager&&<button className={tab==='gestao'?'active':''} onClick={()=>setTab('gestao')}>▦ Gestão</button>}{['ADMIN','HR'].includes(user.role)&&<button onClick={()=>{localStorage.setItem('nh_ponto_kiosk_active','1');location.href=`${location.pathname}?kiosk=1`;}}>◉ Ativar Totem facial</button>}{auditor&&<button className={tab==='auditoria'?'active':''} onClick={()=>setTab('auditoria')}>⌁ Auditoria</button>}</nav><div className="sidebar-user"><div className="avatar">{user.name.slice(0,2).toUpperCase()}</div><div><b>{user.name}</b><span>{user.role}</span></div><button onClick={logout}>Sair</button></div></aside><main className="content"><header><div><div className="eyebrow">SISTEMA OPERACIONAL DE JORNADA</div><h1>{tab==='ponto'?'Registrar ponto':tab==='jornada'?'Minha jornada':tab==='ajustes'?'Ajustes de jornada':tab==='ajustes-equipe'?'Ajustes da equipe':tab==='seguranca-pessoal'?'Minha segurança':tab==='gestao'?'Central de gestão':'Auditoria e integridade'}</h1></div><div className="header-actions">{['ADMIN','TIME_REVIEWER'].includes(user.role)&&(user.managedTenants?.length??0)>0&&<label className="company-switcher"><span>{user.role==='TIME_REVIEWER'?'Empresa para conferência':'Empresa em gestão'}</span><select value={activeCompany?.id??''} onChange={e=>{setActiveTenantId(e.target.value);location.reload();}}>{user.managedTenants!.map(t=><option value={t.id} key={t.id}>{t.name}</option>)}</select></label>}<div className="live-badge"><i/> Integridade ativa</div></div></header>
 {passkeyMissing&&<div className="passkey-enrollment-banner"><div><b>Cadastre sua biometria/passkey</b><span>Esta conta administrativa precisa de uma passkey individual. O painel está liberado, mas aprovações/rejeições protegidas permanecem bloqueadas até o cadastro.</span></div><button className="primary" onClick={()=>setTab('seguranca-pessoal')}>Cadastrar agora</button></div>}
 {facePending&&<div className="face-analysis-banner"><b>Reconhecimento facial em análise</b><span>Seu primeiro acesso administrativo já foi liberado. A análise frontal continua em segundo plano; as marcações pessoais usam selfie simples e aprovação posterior. Você receberá um aviso quando a facial do Admin for aprovada ou precisar ser refeita.</span></div>}
 {faceNeedsAction&&<div className="face-analysis-banner face-action-required"><b>Leitura facial precisa de atenção</b><span>Seu perfil continua disponível, mas o login facial do Administrador ainda precisa ser corrigido. As marcações pessoais usam selfie simples e aprovação posterior.</span><button className="ghost" onClick={async()=>{const res=await api<{submission:FaceSubmission|null}>('/security/face-enrollment/submission/latest');const s=res.submission;if(s?.status==='NEEDS_RETAKE')setFacePopup({kind:'retake',title:'Precisamos refazer sua leitura facial',messages:reasonMessages(s.reasons),submissionId:s.id});if(s?.status==='FAILED')setFacePopup({kind:'failed',title:'A análise facial encontrou um erro técnico',messages:reasonMessages(s.reasons),submissionId:s.id});}}>Resolver agora</button></div>}
 {tab==='ponto'&&employeeFlow&&<PunchPanel/>}{tab==='jornada'&&employeeFlow&&<MyJourney/>}{tab==='ajustes'&&employeeFlow&&!manager&&<Adjustments/>}{tab==='ajustes-equipe'&&manager&&user.role!=='TIME_REVIEWER'&&<TeamAdjustments/>}{tab==='seguranca-pessoal'&&passkeyRequiredForUser&&<SecurityCenter/>}{tab==='gestao'&&manager&&<Management/>}{tab==='auditoria'&&auditor&&<Audit/>}<div className="nh-ponto-footer app-footer">Tecnologia: Gestão Software LTDA.</div></main>
 {facePopup&&<div className="status-popup-backdrop"><div className={`status-popup ${facePopup.kind}`}><div className="status-popup-icon">{facePopup.kind==='approved'?'✓':facePopup.kind==='retake'?'!':'×'}</div><h2>{facePopup.title}</h2>{facePopup.messages.length>0?<ul>{facePopup.messages.map((m,i)=><li key={i}>{m}</li>)}</ul>:<p className="muted">Siga a ação abaixo para concluir a leitura facial.</p>}<div className="inline-actions">{facePopup.kind==='retake'&&<button className="primary" disabled={faceActionBusy} onClick={()=>{setFacePopup(null);setFaceCaptureOpen(true);}}>Refazer leitura facial</button>}{facePopup.kind==='failed'&&<button className="primary" disabled={faceActionBusy} onClick={()=>{setFacePopup(null);setFaceCaptureOpen(true);}}>Refazer leitura facial</button>}{facePopup.kind==='approved'&&<button className="primary success-button" onClick={()=>setFacePopup(null)}>Entendi</button>}{facePopup.kind!=='approved'&&<button className="ghost" disabled={faceActionBusy} onClick={()=>setFacePopup(null)}>Agora não</button>}</div></div></div>}
 {faceCaptureOpen&&<QuickFaceEnrollmentCapture onSubmit={submitFaceRetake} onCancel={()=>{if(!faceActionBusy)setFaceCaptureOpen(false);}}/>}
 </div>;
}
