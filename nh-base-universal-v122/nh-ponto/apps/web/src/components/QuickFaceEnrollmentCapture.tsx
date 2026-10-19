import { useEffect,useRef,useState } from 'react';

type Pose='FRONT';
export type QuickEnrollmentPhoto={pose:Pose;imageDataUrl:string};
type Props={onSubmit:(photos:QuickEnrollmentPhoto[])=>Promise<void>|void;onCancel:()=>void};

function captureFrame(video:HTMLVideoElement){
  const sw=video.videoWidth||640,sh=video.videoHeight||480;
  const width=Math.min(384,sw);const height=Math.max(1,Math.round(sh*(width/sw)));
  const canvas=document.createElement('canvas');canvas.width=width;canvas.height=height;
  const ctx=canvas.getContext('2d');if(!ctx)throw new Error('Canvas indisponível');
  ctx.drawImage(video,0,0,width,height);
  return canvas.toDataURL('image/jpeg',0.68);
}

export default function QuickFaceEnrollmentCapture({onSubmit,onCancel}:Props){
  const videoRef=useRef<HTMLVideoElement>(null);const streamRef=useRef<MediaStream|null>(null);
  const [photo,setPhoto]=useState<QuickEnrollmentPhoto|null>(null);const [error,setError]=useState('');const [busy,setBusy]=useState(false);const [cameraReady,setCameraReady]=useState(false);
  useEffect(()=>{let mounted=true;(async()=>{try{
    const stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:'user',width:{ideal:420,max:640},height:{ideal:315,max:480},frameRate:{ideal:18,max:24}},audio:false});
    if(!mounted){stream.getTracks().forEach(t=>t.stop());return;}streamRef.current=stream;
    const video=videoRef.current;if(!video)return;video.srcObject=stream;await video.play();if(mounted)setCameraReady(true);
  }catch(e){setError(e instanceof Error?e.message:'Não foi possível abrir a câmera');}})();return()=>{mounted=false;streamRef.current?.getTracks().forEach(t=>t.stop());};},[]);
  function take(){if(!cameraReady||!videoRef.current||busy)return;try{setPhoto({pose:'FRONT',imageDataUrl:captureFrame(videoRef.current)});setError('');}catch(e){setError(e instanceof Error?e.message:'Falha ao capturar foto');}}
  async function submit(){if(!photo)return;setBusy(true);setError('');try{await onSubmit([photo]);streamRef.current?.getTracks().forEach(t=>t.stop());}catch(e){setError(e instanceof Error?e.message:'Falha ao enviar selfie');setBusy(false);}}
  function reset(){setPhoto(null);setError('');}
  return <div className="bio-overlay"><div className="bio-dialog enrollment-dialog"><div className="bio-head"><div><div className="eyebrow">CADASTRO FACIAL DO ADMIN</div><h2>Uma única captura frontal.</h2></div><button className="ghost" onClick={onCancel} disabled={busy}>Fechar</button></div>
    <p className="muted">Olhe normalmente de frente para a câmera. Não precisa virar a cabeça, olhar para cima, olhar para baixo ou fazer uma sequência de poses. A assinatura facial é criada no servidor.</p>
    <div className="quick-face-grid single-face"><div className="camera-stage quick-camera"><video ref={videoRef} playsInline muted autoPlay/><div className="face-ring"/><div className="camera-instruction">{photo?'Selfie pronta ✓':'Centralize o rosto, olhe para a câmera e mantenha boa iluminação.'}</div></div>
      <div className="quick-face-side"><div className="quick-progress"><div className={photo?'done':'current'}><b>{photo?'✓':'1'}</b><span>Selfie frontal</span></div></div>
        {photo&&<div className="quick-thumbs single-thumb"><div><img src={photo.imageDataUrl}/><small>Selfie frontal</small></div></div>}
        {!photo?<button className="primary" disabled={!cameraReady||busy} onClick={take}>{cameraReady?'Capturar selfie':'Abrindo câmera…'}</button>:<><button className="primary success-button" disabled={busy} onClick={submit}>{busy?'Enviando…':'Usar esta selfie e analisar'}</button><button className="ghost" disabled={busy} onClick={reset}>Refazer selfie</button></>}
        <small className="muted">A análise facial tem watchdog: se o servidor não concluir dentro do limite, a tela mostra uma nova tentativa em vez de ficar carregando indefinidamente.</small>{error&&<div className="error-box">{error}</div>}</div>
    </div>
  </div></div>;
}
