import { useEffect,useRef,useState } from 'react';
import { api,apiWithToken,clearToken,type SessionUser } from '../lib/api';

type Props={user:SessionUser|null};
type Recognized={recognitionToken:string;employee:{id:string;name:string;employeeNumber:string;jobTitle?:string|null};usedTypes:string[];workDate:string;expiresInSeconds:number;selfieDataUrl?:string};
const labels:Record<string,string>={CLOCK_IN:'Entrada',BREAK_START:'Iniciar intervalo',BREAK_END:'Retorno do intervalo',PAUSE_START:'Iniciar pausa',PAUSE_END:'Retorno da pausa',CLOCK_OUT:'Saída'};

function captureFrame(video:HTMLVideoElement){
 const canvas=document.createElement('canvas');
 const maxWidth=480; const ratio=(video.videoHeight||640)/(video.videoWidth||480);
 canvas.width=Math.min(maxWidth,video.videoWidth||480);canvas.height=Math.round(canvas.width*ratio);
 const ctx=canvas.getContext('2d');if(!ctx)throw new Error('Câmera indisponível');
 ctx.drawImage(video,0,0,canvas.width,canvas.height);
 return canvas.toDataURL('image/jpeg',0.82);
}

export default function KioskMode({user}:Props){
 const [token,setKioskToken]=useState(()=>localStorage.getItem('nh_ponto_kiosk_token')||'');
 const [name,setName]=useState('Totem Apontamento Digital'); const [worksiteId,setWorksiteId]=useState('');
 const [worksites,setWorksites]=useState<any[]>([]); const [recognized,setRecognized]=useState<Recognized|null>(null);
 const [status,setStatus]=useState(token?'Abrindo câmera…':'Ative este aparelho uma única vez com Administrador ou Super Adm.');
 const [busy,setBusy]=useState(false); const [cameraReady,setCameraReady]=useState(false); const [lastSuccess,setLastSuccess]=useState('');
 const videoRef=useRef<HTMLVideoElement|null>(null);const streamRef=useRef<MediaStream|null>(null);const pollingRef=useRef(false);

 useEffect(()=>{if(!token&&user&&['ADMIN','HR'].includes(user.role))api<any[]>('/admin/worksites').then(setWorksites).catch(()=>{});},[token,user?.id]);
 useEffect(()=>{
   if(!token)return;
   let cancelled=false;
   (async()=>{try{
     const stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:'user',width:{ideal:720},height:{ideal:960}},audio:false});
     if(cancelled){stream.getTracks().forEach(t=>t.stop());return;}streamRef.current=stream;
     if(videoRef.current){videoRef.current.srcObject=stream;await videoRef.current.play();setCameraReady(true);setStatus('Olhe de frente para a câmera. O reconhecimento é automático.');}
   }catch(e){setStatus(e instanceof Error?`Não foi possível abrir a câmera: ${e.message}`:'Não foi possível abrir a câmera.');}})();
   return()=>{cancelled=true;streamRef.current?.getTracks().forEach(t=>t.stop());streamRef.current=null;};
 },[token]);

 useEffect(()=>{
   if(!token||!cameraReady||recognized)return;
   const timer=window.setInterval(async()=>{
     if(pollingRef.current||!videoRef.current||videoRef.current.readyState<2)return;
     pollingRef.current=true;
     try{
       const imageDataUrl=captureFrame(videoRef.current);
       const result=await api<Recognized>('/kiosk/recognize',{method:'POST',body:JSON.stringify({kioskToken:token,imageDataUrl})});
       setRecognized({...result,selfieDataUrl:imageDataUrl});setStatus(`${result.employee.name} reconhecido. Escolha a ação; o Totem tirará uma selfie simples no momento da confirmação.`);
     }catch(e){
       const msg=e instanceof Error?e.message:'';
       if(/Totem não autorizado|Ative novamente/i.test(msg)){setStatus(msg);}
       else setStatus('Aguardando um colaborador olhar de frente para a câmera…');
     }finally{pollingRef.current=false;}
   },1800);
   return()=>window.clearInterval(timer);
 },[token,cameraReady,recognized]);

 useEffect(()=>{if(!recognized)return;const timer=window.setTimeout(()=>{setRecognized(null);setStatus('Olhe de frente para a câmera. O reconhecimento é automático.');},30000);return()=>window.clearTimeout(timer);},[recognized?.recognitionToken]);

 async function activate(){
   if(!user||!['ADMIN','HR'].includes(user.role)){setStatus('Entre como Administrador ou Super Adm para ativar o Totem.');return;}
   setBusy(true);try{
     const res=await api<any>('/kiosk/activate',{method:'POST',body:JSON.stringify({name,worksiteId:worksiteId||null})});
     localStorage.setItem('nh_ponto_kiosk_token',res.kioskToken);localStorage.setItem('nh_ponto_kiosk_active','1');localStorage.setItem('nh_ponto_kiosk_name',res.name||name);
     clearToken();setKioskToken(res.kioskToken);setStatus('Totem ativado. Abrindo câmera…');
   }catch(e){setStatus(e instanceof Error?e.message:'Não foi possível ativar o Totem.');}finally{setBusy(false);}
 }
 async function kioskLocation(){
   if(!navigator.geolocation)throw new Error('Este aparelho não disponibiliza geolocalização.');
   return await new Promise<Record<string,number|string>>((resolve,reject)=>navigator.geolocation.getCurrentPosition(
     p=>resolve({latitude:p.coords.latitude,longitude:p.coords.longitude,accuracyM:p.coords.accuracy,locationCapturedAt:new Date(p.timestamp).toISOString()}),
     ()=>reject(new Error('Ative a localização/GPS deste aparelho e permita o acesso para registrar o ponto.')),{enableHighAccuracy:true,timeout:10000,maximumAge:0}
   ));
 }
 async function punch(type:string){
   if(!recognized||!recognized.selfieDataUrl)return;setBusy(true);
   try{
     setStatus('Criando desafio desta marcação…');
     const challenge=await apiWithToken<any>('/punches/challenge',recognized.recognitionToken,{method:'POST',body:JSON.stringify({type})});
     if(!videoRef.current||videoRef.current.readyState<2)throw new Error('A câmera não está pronta para tirar a selfie da marcação.');
     const selfieDataUrl=captureFrame(videoRef.current);
     const location=await kioskLocation();
     setStatus('Selfie capturada com latitude/longitude. Salvando e enviando para aprovação…');
     const res=await apiWithToken<any>('/punches',recognized.recognitionToken,{method:'POST',body:JSON.stringify({type,clientEventId:crypto.randomUUID(),challengeId:challenge.id,occurredAt:new Date().toISOString(),timezone:Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Maceio',offline:false,deviceFingerprint:`KIOSK:${localStorage.getItem('nh_ponto_kiosk_name')||'Apontamento Digital'}`,selfieDataUrl,clientSecureContext:window.isSecureContext,...location})});
     setLastSuccess(`${recognized.employee.name}: ${labels[type]} registrada às ${new Date(res.occurredAt).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}.`);
     setStatus(type==='CLOCK_OUT'?'Jornada finalizada. As evidências do dia estão disponíveis para aprovação do Administrador/Super Adm.':'Marcação registrada com selfie + GPS. As evidências serão reunidas para aprovação após a saída do dia.');setRecognized(null);
     window.setTimeout(()=>setLastSuccess(''),6000);
   }catch(e){setStatus(e instanceof Error?e.message:'Não foi possível registrar a marcação.');}
   finally{setBusy(false);}
 }
 function exitKiosk(){
   if(!confirm('Desativar o modo Totem neste aparelho? Para reativar será necessário entrar como Administrador ou Super Adm.'))return;
   streamRef.current?.getTracks().forEach(t=>t.stop());localStorage.removeItem('nh_ponto_kiosk_token');localStorage.removeItem('nh_ponto_kiosk_active');localStorage.removeItem('nh_ponto_kiosk_name');
   location.href=location.pathname;
 }
 function goLogin(){localStorage.removeItem('nh_ponto_kiosk_active');location.href=location.pathname;}
 function goBackToPanel(){
   localStorage.removeItem('nh_ponto_kiosk_active');
   const cleanUrl=`${location.origin}${location.pathname}`;
   location.replace(cleanUrl);
 }

 if(!token)return <main className="kiosk-shell setup"><section className="kiosk-setup-card">
   <div className="kiosk-setup-topbar">
     {user&&['ADMIN','HR'].includes(user.role)&&<button className="kiosk-back-button" type="button" onClick={goBackToPanel}>← Voltar para o painel</button>}
   </div>
   <div className="kiosk-setup-brand"><img className="nh-ponto-logo kiosk-setup-logo" src={user?.brand?.logoUrl||"/ponto/identidade-pendente.svg"} alt={user?.tenant||"Apontamento Digital"}/></div>
   <div className="kiosk-setup-content">
     <div className="eyebrow">MODO TOTEM FACIAL</div>
     <h1>Ativar este aparelho</h1>
     <p>O celular ficará fixo com a câmera frontal aberta. O colaborador olha para a câmera, é identificado pela foto 3x4 cadastrada pelo RH e confirma a marcação.</p>
     {user&&['ADMIN','HR'].includes(user.role)?<>
       <label>Nome do aparelho<input value={name} onChange={e=>setName(e.target.value)}/></label>
       <label>Local<select value={worksiteId} onChange={e=>setWorksiteId(e.target.value)}><option value="">Sem local específico</option>{worksites.map(w=><option key={w.id} value={w.id}>{w.name}</option>)}</select></label>
       <button className="primary kiosk-activate-button" disabled={busy} onClick={activate}>{busy?'Ativando…':'Ativar Totem Facial'}</button>
     </>:<button className="primary kiosk-activate-button" onClick={goLogin}>Entrar como RH / Administrador para ativar</button>}
     <div className="security-note kiosk-security-note">A ativação não mantém a sessão do RH/Admin aberta. O Totem recebe uma credencial própria para reconhecimento. Depois do reconhecimento, cada ação tira uma selfie e salva latitude/longitude do aparelho no mesmo registro; a marcação fica pendente de aprovação. O colaborador não usa passkey.</div>
     {status&&<div className="warning-box kiosk-warning-box">{status}</div>}
   </div>
 </section></main>;

 return <main className="kiosk-shell"><header className="kiosk-header"><div><img className="nh-ponto-logo" src={user?.brand?.logoUrl||"/ponto/identidade-pendente.svg"} alt={user?.tenant||"Apontamento Digital"}/><div><b>Apontamento Digital · Totem Facial</b><span>{localStorage.getItem('nh_ponto_kiosk_name')||'Totem Apontamento Digital'}</span></div></div><button className="ghost" onClick={exitKiosk}>Desativar Totem</button></header><section className="kiosk-stage"><div className="kiosk-camera-wrap"><video ref={videoRef} autoPlay playsInline muted/><div className="kiosk-face-guide"><span/></div><div className="kiosk-camera-status">{status}</div></div><div className="kiosk-action-card">{recognized?<><div className="kiosk-recognized"><div className="kiosk-avatar">{recognized.employee.name.slice(0,2).toUpperCase()}</div><div><div className="eyebrow">ROSTO RECONHECIDO</div><h1>{recognized.employee.name}</h1><p>{recognized.employee.employeeNumber}{recognized.employee.jobTitle?` · ${recognized.employee.jobTitle}`:''}</p></div></div><h3>Confirme sua marcação</h3><p className="muted">Ao escolher a ação, o Totem tira uma nova selfie e salva a latitude/longitude do aparelho. Não existe geocerca; depois, Administrador/Super Adm confere a foto e as coordenadas e aprova ou rejeita.</p><div className="kiosk-actions">{Object.entries(labels).map(([type,label])=>{const used=recognized.usedTypes.includes(type);return <button key={type} className={used?'punch-used':''} disabled={busy||used} onClick={()=>punch(type)}>{used?'✓ ':''}{label}</button>})}</div><button className="ghost" disabled={busy} onClick={()=>{setRecognized(null);setStatus('Olhe de frente para a câmera. O reconhecimento é automático.');}}>Não sou eu / cancelar</button></>:<><div className="kiosk-wait-icon">◎</div><h1>Olhe para a câmera</h1><p>Fique de frente. Não precisa virar para os lados, olhar para cima ou para baixo.</p><p className="muted">Quando o sistema reconhecer você, seu nome aparecerá aqui para confirmar Entrada, Saída, Intervalo ou Pausa.</p></>}</div></section>{lastSuccess&&<div className="kiosk-success-toast">✓ {lastSuccess}</div>}<footer className="nh-ponto-footer">Tecnologia: Gestão Software LTDA.</footer></main>;
}
