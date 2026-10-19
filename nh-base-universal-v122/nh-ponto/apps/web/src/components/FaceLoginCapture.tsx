import { useEffect,useRef,useState } from 'react';

type Props={onCapture:(images:string[])=>Promise<void>|void;onCancel:()=>void};

function capture(video:HTMLVideoElement){
  const canvas=document.createElement('canvas');
  const width=Math.max(320,Math.min(384,video.videoWidth||640));
  const ratio=(video.videoHeight||480)/(video.videoWidth||640);
  canvas.width=width;canvas.height=Math.round(width*ratio);
  const ctx=canvas.getContext('2d');if(!ctx)throw new Error('Canvas indisponível');
  ctx.drawImage(video,0,0,canvas.width,canvas.height);
  return canvas.toDataURL('image/jpeg',0.68);
}

export default function FaceLoginCapture({onCapture,onCancel}:Props){
 const videoRef=useRef<HTMLVideoElement>(null);const streamRef=useRef<MediaStream|null>(null);const [ready,setReady]=useState(false);const [busy,setBusy]=useState(false);const [error,setError]=useState('');
 useEffect(()=>{let mounted=true;(async()=>{try{const stream=await navigator.mediaDevices.getUserMedia({video:{facingMode:'user',width:{ideal:480,max:640},height:{ideal:360,max:480},frameRate:{ideal:18,max:24}},audio:false});if(!mounted){stream.getTracks().forEach(t=>t.stop());return;}streamRef.current=stream;if(videoRef.current){videoRef.current.srcObject=stream;await videoRef.current.play();setReady(true);}}catch(e){setError(e instanceof Error?e.message:'Não foi possível abrir a câmera');}})();return()=>{mounted=false;streamRef.current?.getTracks().forEach(t=>t.stop());};},[]);
 async function confirm(){if(!videoRef.current||!ready)return;setBusy(true);setError('');try{await onCapture([capture(videoRef.current)]);streamRef.current?.getTracks().forEach(t=>t.stop());}catch(e){setError(e instanceof Error?e.message:'Falha ao capturar o rosto');setBusy(false);}}
 return <div className="status-popup-backdrop"><div className="status-popup face-login-popup"><div className="eyebrow">LOGIN FACIAL DO ADMINISTRADOR</div><h2>Olhe de frente para a câmera</h2><p className="muted">Uma única captura frontal é comparada no servidor com a referência do Administrador. Não precisa virar para os lados, olhar para cima ou para baixo.</p><div className="face-login-video-wrap"><video ref={videoRef} playsInline muted/></div>{error&&<div className="error-box">{error}</div>}<div className="inline-actions"><button className="primary" disabled={!ready||busy} onClick={confirm}>{busy?'Confirmando…':'Entrar com reconhecimento facial'}</button><button className="ghost" disabled={busy} onClick={onCancel}>Cancelar</button></div></div></div>;
}
