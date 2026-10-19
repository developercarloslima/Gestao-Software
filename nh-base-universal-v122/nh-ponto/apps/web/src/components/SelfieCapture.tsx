import { useEffect,useRef,useState } from 'react';
import { captureVideoFrame } from '../lib/biometrics';

type Props={title?:string;onCapture:(sample:{imageDataUrl:string})=>void;onCancel:()=>void;allowCancel?:boolean};
type CameraState='idle'|'opening'|'preview'|'ready'|'error';

function stopStream(stream:MediaStream|null){
 try{stream?.getTracks().forEach(track=>track.stop());}catch{}
}

function cameraErrorMessage(error:unknown){
 const name=error instanceof DOMException?error.name:'';
 if(name==='NotAllowedError'||name==='SecurityError'){
  const installed=(window.navigator as Navigator & {standalone?:boolean}).standalone===true;
  return installed
   ? 'O iPhone bloqueou o acesso à câmera neste contexto. Permita a câmera para o site. Se a permissão não aparecer no app instalado, abra o endereço HTTPS desta empresa diretamente no Safari, permita a câmera e tente novamente.'
   : 'O navegador bloqueou o acesso à câmera. Permita a câmera para o endereço HTTPS desta empresa nas configurações/permissões do site e tente novamente.';
 }
 if(name==='NotFoundError'||name==='DevicesNotFoundError')return 'Nenhuma câmera compatível foi encontrada neste aparelho.';
 if(name==='NotReadableError'||name==='TrackStartError')return 'A câmera está ocupada ou indisponível. Feche outros aplicativos que estejam usando a câmera e tente novamente.';
 if(name==='OverconstrainedError'||name==='ConstraintNotSatisfiedError')return 'A câmera não aceitou a configuração solicitada. Tente novamente para usar o modo de compatibilidade.';
 if(name==='AbortError')return 'A abertura da câmera foi interrompida pelo aparelho. Tente novamente.';
 if(error instanceof Error&&error.message)return error.message;
 return 'Não foi possível abrir a câmera neste aparelho.';
}

export default function SelfieCapture({title='Tire a selfie desta marcação',onCapture,onCancel,allowCancel=true}:Props){
 const videoRef=useRef<HTMLVideoElement>(null);
 const streamRef=useRef<MediaStream|null>(null);
 const mountedRef=useRef(true);
 const [state,setState]=useState<CameraState>('idle');
 const [error,setError]=useState('');
 const [needsPlaybackTap,setNeedsPlaybackTap]=useState(false);

 useEffect(()=>{
  mountedRef.current=true;
  document.body.classList.add('journey-selfie-open');
  const onKey=(event:KeyboardEvent)=>{if(event.key==='Escape'&&allowCancel)cancel();};
  window.addEventListener('keydown',onKey);
  return()=>{
   mountedRef.current=false;
   window.removeEventListener('keydown',onKey);
   document.body.classList.remove('journey-selfie-open');
   stopStream(streamRef.current);
   streamRef.current=null;
  };
 },[allowCancel]);

 async function attachStream(stream:MediaStream){
  if(!mountedRef.current){stopStream(stream);return;}
  streamRef.current=stream;
  const video=videoRef.current;
  if(!video)throw new Error('A prévia da câmera ainda não está pronta. Tente novamente.');
  video.srcObject=stream;
  setState('preview');
  setNeedsPlaybackTap(false);
  try{
   await video.play();
  }catch(e){
   if(!mountedRef.current)return;
   // Em Safari/iOS a câmera pode estar autorizada, mas a reprodução automática
   // da prévia ainda exigir um segundo toque do usuário.
   setNeedsPlaybackTap(true);
   setError('A câmera foi autorizada, mas o navegador exige outro toque para exibir a prévia.');
  }
 }

 async function openCamera(){
  // IMPORTANTE: esta função só é chamada diretamente pelo clique do usuário.
  // Não coloque requisições de rede ou await antes do getUserMedia.
  if(state==='opening')return;
  setError('');
  setNeedsPlaybackTap(false);
  if(!window.isSecureContext){setState('error');setError('A câmera exige uma conexão HTTPS segura.');return;}
  if(!navigator.mediaDevices?.getUserMedia){setState('error');setError('Este navegador não disponibilizou acesso à câmera. Atualize o navegador ou abra o site diretamente no Safari/Chrome.');return;}
  stopStream(streamRef.current);streamRef.current=null;
  setState('opening');
  try{
   let stream:MediaStream;
   try{
    stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:{ideal:'user'}},audio:false});
   }catch(firstError){
    const name=firstError instanceof DOMException?firstError.name:'';
    // Não repete a solicitação quando a permissão foi negada; isso só geraria
    // outro bloqueio. Para incompatibilidades de constraints, usa modo simples.
    if(name==='NotAllowedError'||name==='SecurityError')throw firstError;
    stream=await navigator.mediaDevices.getUserMedia({video:true,audio:false});
   }
   await attachStream(stream);
  }catch(e){
   stopStream(streamRef.current);streamRef.current=null;
   if(!mountedRef.current)return;
   setState('error');
   setError(cameraErrorMessage(e));
  }
 }

 async function resumePreview(){
  const video=videoRef.current;
  if(!video)return;
  setError('');
  try{
   await video.play();
   setNeedsPlaybackTap(false);
   setState('preview');
  }catch(e){
   setNeedsPlaybackTap(true);
   setError(cameraErrorMessage(e));
  }
 }

 function onVideoReady(){
  if(!streamRef.current)return;
  const video=videoRef.current;
  if(video&&video.videoWidth>0&&video.videoHeight>0){setState('ready');setNeedsPlaybackTap(false);setError('');}
 }

 function capture(){
  if(state!=='ready'||!videoRef.current)return;
  const imageDataUrl=captureVideoFrame(videoRef.current);
  setState('preview');
  stopStream(streamRef.current);streamRef.current=null;
  onCapture({imageDataUrl});
 }

 function cancel(){
  stopStream(streamRef.current);streamRef.current=null;
  onCancel();
 }

 return <div className="journey-selfie-overlay" role="dialog" aria-modal="true" aria-label="Câmera para selfie da jornada">
   <video ref={videoRef} className="journey-selfie-video" playsInline muted autoPlay onLoadedMetadata={onVideoReady} onCanPlay={onVideoReady} onPlaying={onVideoReady}/>
   {(state==='idle'||state==='error'||needsPlaybackTap)&&<div className="journey-camera-start">
    <div className="journey-camera-start-copy"><b>{title}</b>{error&&<span>{error}</span>}{!error&&<span>A câmera será aberta somente após seu toque.</span>}</div>
    <div className="journey-camera-start-actions">
     {needsPlaybackTap?<button type="button" className="journey-camera-primary" onClick={resumePreview}>Exibir câmera</button>:<button type="button" className="journey-camera-primary" onClick={openCamera}>{state==='error'?'Tentar novamente':'Abrir câmera'}</button>}
     {allowCancel&&<button type="button" className="journey-camera-secondary" onClick={cancel}>Voltar</button>}
    </div>
   </div>}
   {state==='opening'&&<div className="journey-camera-loading" aria-live="polite"><b>Abrindo câmera…</b></div>}
   {(state==='preview'||state==='ready')&&!needsPlaybackTap&&<button className="journey-selfie-shutter" type="button" disabled={state!=='ready'} onClick={capture}>{state==='ready'?'Tirar selfie':'Preparando câmera…'}</button>}
 </div>;
}
