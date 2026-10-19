import { useEffect, type ReactNode } from 'react';

type Props={src:string;title?:string;subtitle?:string;onClose:()=>void;children?:ReactNode};

export default function SelfieViewer({src,title='Selfie da marcação',subtitle='Confira a imagem antes de tomar uma decisão.',onClose,children}:Props){
 useEffect(()=>{document.body.classList.add('selfie-viewer-open');const onKey=(event:KeyboardEvent)=>{if(event.key==='Escape')onClose();};window.addEventListener('keydown',onKey);return()=>{document.body.classList.remove('selfie-viewer-open');window.removeEventListener('keydown',onKey);};},[onClose]);
 return <div className="selfie-viewer-overlay" role="dialog" aria-modal="true" aria-label={title} onMouseDown={event=>{if(event.target===event.currentTarget)onClose();}}>
   <div className="selfie-viewer-dialog">
     <div className="selfie-viewer-head"><div><div className="eyebrow">EVIDÊNCIA DA JORNADA</div><h2>{title}</h2><p>{subtitle}</p></div><button className="ghost selfie-viewer-close" type="button" onClick={onClose}>Fechar</button></div>
     <div className="selfie-viewer-stage"><img src={src} alt={title}/></div>
     {children&&<div className="selfie-viewer-actions">{children}</div>}
   </div>
 </div>;
}
