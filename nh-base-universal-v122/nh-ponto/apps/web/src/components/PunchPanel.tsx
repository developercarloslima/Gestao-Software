import { FormEvent,useEffect,useRef,useState } from 'react';
import { api } from '../lib/api';
import { getDeviceFingerprint,getLocation } from '../lib/device';
import { collectBluetoothName,collectNetworkGatewayToken,collectNfcTag } from '../lib/presence';
import ProofGauge from './ProofGauge';
import QrTokenInput from './QrTokenInput';
import SelfieCapture from './SelfieCapture';

type Punch={id:string;recordNumber:string;type:string;occurredAt:string;integrityHash:string;decision:string;source?:string;reviewReason?:string|null;proof?:{proofScore:number;proofLevel:string;reasonsJson:string[];missingRequirementsJson?:string[];latitude?:number;longitude?:number;accuracyM?:number}};
type ClockInAccess={allowed:boolean;code:string;message?:string|null;earlyMinutes?:number;lateMinutes?:number;schedule?:{startTime:string;endTime:string;breakMinutes:number}|null;clockInOpensAt?:string|null;clockInClosesAt?:string|null;scheduledStartAt?:string|null;scheduledEndAt?:string|null;nextStartAt?:string|null;nextWorkDate?:string|null;nextSchedule?:{startTime:string;endTime:string;breakMinutes:number}|null;lateJustificationRequired?:boolean};
type LateMode='VARIED'|'PROFESSIONAL';
type LateDocumentType='HOURS_DECLARATION'|'ATTENDANCE_PROOF';
type TurnUsage={workDate:string;timeZone?:string;usedTypes:string[];lastPunchType?:string|null;hasClockOut?:boolean;canReopenOvertime?:boolean;reopenedOvertimeActive?:boolean;overtimeExtensionMinutes?:number;overtimeGrantCount?:number;closedDay?:boolean;closedReason?:string|null;scheduled?:boolean;scheduledEndAt?:string|null;journeyOpen?:boolean;autoLockRequired?:boolean;autoFinalizeDue?:boolean;warning?:string|null;cutoffAt?:string|null;autoCloseAt?:string|null;consecutiveAutoLocks?:number;clockInAccess?:ClockInAccess;lateJustificationMode?:LateMode;lateArrivalPending?:{id:string;requestedTime:string;reason:string;requestedAt:string;lateJustificationMode?:LateMode;supportingDocumentType?:LateDocumentType|null}|null;breakStartedAt?:string|null;breakReturnAllowedAt?:string|null;breakReturnRemainingSeconds?:number;breakReturnLocked?:boolean;minimumBreakMinutes?:number};
type PunchLocation={latitude?:number;longitude?:number;accuracyM?:number;altitudeM?:number;altitudeAccuracyM?:number;headingDeg?:number;speedMps?:number;locationCapturedAt?:string};
type PendingCapture={type:string;challenge:any;forcedAutoLock?:boolean};
const labels:Record<string,string>={CLOCK_IN:'Iniciar jornada',BREAK_START:'Iniciar intervalo',BREAK_END:'Retorno do intervalo',PAUSE_START:'Iniciar pausa',PAUSE_END:'Retorno da pausa',CLOCK_OUT:'Finalizar jornada'};

function fileDataUrl(file:File){
 return new Promise<string>((resolve,reject)=>{const r=new FileReader();r.onload=()=>resolve(String(r.result));r.onerror=()=>reject(new Error('Não foi possível ler o documento anexado.'));r.readAsDataURL(file);});
}

export default function PunchPanel(){
 const [punches,setPunches]=useState<Punch[]>([]);
 const [busy,setBusy]=useState(false);
 const [message,setMessage]=useState('');
 const [now,setNow]=useState(new Date());
 const [security,setSecurity]=useState<any>(null);
 const [qrToken,setQrToken]=useState('');
 const [turnUsage,setTurnUsage]=useState<TurnUsage|null>(null);
 const [pendingCapture,setPendingCapture]=useState<PendingCapture|null>(null);
 const [lateModalOpen,setLateModalOpen]=useState(false);
 const [lateSelfieOpen,setLateSelfieOpen]=useState(false);
 const [lateReason,setLateReason]=useState('');
 const [lateDocumentType,setLateDocumentType]=useState<LateDocumentType>('HOURS_DECLARATION');
 const [lateDocument,setLateDocument]=useState<File|null>(null);
 const [lateSubmitting,setLateSubmitting]=useState(false);
 const last=punches.find(p=>p.decision!=='BLOCKED');
 const autoLockPrompted=useRef(false);

 async function load(){try{setPunches(await api<Punch[]>('/punches/me'));}catch{}}
 async function loadTurnUsage(){try{const x=await api<TurnUsage>('/punches/actions');setTurnUsage(x);if(!x.autoLockRequired)autoLockPrompted.current=false;return x;}catch{return turnUsage;}}
 async function loadSecurity(){try{const x=await api<any>('/security/status');setSecurity(x);return x;}catch{return security;}}
 useEffect(()=>{const timer=window.setInterval(()=>setNow(new Date()),1000);const lockTimer=window.setInterval(()=>{void loadTurnUsage();},5000);load();loadSecurity();loadTurnUsage();api('/devices/register',{method:'POST',body:JSON.stringify({fingerprint:getDeviceFingerprint(),label:navigator.userAgent.slice(0,90)})}).catch(()=>{});return()=>{window.clearInterval(timer);window.clearInterval(lockTimer);};},[]);
 useEffect(()=>{const needsClockOut=turnUsage?.autoLockRequired&&(!turnUsage.usedTypes?.includes('CLOCK_OUT')||turnUsage.reopenedOvertimeActive);if(needsClockOut&&!pendingCapture&&!busy&&!autoLockPrompted.current){autoLockPrompted.current=true;setMessage(turnUsage.warning??'O ponto atingiu a trava automática. Tire uma selfie para finalizar a jornada.');void begin('CLOCK_OUT',true);}},[turnUsage?.autoLockRequired,turnUsage?.reopenedOvertimeActive,turnUsage?.workDate,pendingCapture,busy]);
 useEffect(()=>{if(pendingCapture?.forcedAutoLock&&turnUsage?.lastPunchType==='CLOCK_OUT'&&!turnUsage?.reopenedOvertimeActive&&!turnUsage?.autoLockRequired){setPendingCapture(null);autoLockPrompted.current=false;setMessage('Jornada finalizada automaticamente pelo sistema ao completar 15 minutos sem saída manual. Esta é a única saída permitida sem selfie.');}},[turnUsage?.lastPunchType,turnUsage?.reopenedOvertimeActive,turnUsage?.autoLockRequired,pendingCapture?.forcedAutoLock]);

 function resetLateForm(){setLateReason('');setLateDocument(null);setLateDocumentType('HOURS_DECLARATION');setLateModalOpen(false);setLateSelfieOpen(false);}
 function openLateJustification(){
   if(turnUsage?.lateArrivalPending){setMessage('Sua justificativa de atraso já está aguardando aprovação.');return;}
   setMessage('');setLateModalOpen(true);
 }
 async function submitLateArrival(e:FormEvent){
   e.preventDefault();
   if(lateSubmitting||lateReason.trim().length<5)return;
   setLateModalOpen(false);setLateSelfieOpen(true);
 }
 async function confirmLateArrivalSelfie(sample:{imageDataUrl:string}){
   if(lateSubmitting)return;
   setLateSubmitting(true);setMessage('Enviando justificativa, selfie e evidências…');
   try{
     const location=await getLocation();
     if(location.latitude==null||location.longitude==null)throw new Error('Não foi possível confirmar a localização da selfie. Ative o GPS/localização e tente novamente.');
     const body:any={reason:lateReason.trim(),selfieDataUrl:sample.imageDataUrl,latitude:location.latitude,longitude:location.longitude,accuracyM:location.accuracyM};
     if(turnUsage?.lateJustificationMode==='PROFESSIONAL'&&lateDocument){
       body.documentType=lateDocumentType;
       body.documentFileName=lateDocument.name;
       body.documentDataUrl=await fileDataUrl(lateDocument);
     }
     const result=await api<any>('/adjustments/late-arrival',{method:'POST',body:JSON.stringify(body)});
     if(result.autoRejected){setMessage('Justificativa reprovada automaticamente porque o documento comprobatório obrigatório não foi anexado.');}
     else setMessage(`Justificativa enviada às ${new Date(result.requestedTime).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'})}. A entrada será criada nesse horário somente após a aprovação.`);
     resetLateForm();await loadTurnUsage();
   }catch(e){setMessage(e instanceof Error?e.message:'Não foi possível enviar a justificativa de atraso.');setLateSelfieOpen(false);setLateModalOpen(true);}
   finally{setLateSubmitting(false);}
 }

 async function sendPunch(type:string,challenge:any,selfieDataUrl:string,location:PunchLocation){
   setMessage('Salvando horário, selfie e localização desta ação…');
   let bluetoothName='',nfcTagId='',networkGatewayToken='';
   if(challenge.policy?.requireBluetoothBeacon)bluetoothName=await collectBluetoothName();
   if(challenge.policy?.requireNfcTag)nfcTagId=await collectNfcTag();
   if(challenge.policy?.requireNetworkAttestation)networkGatewayToken=await collectNetworkGatewayToken();
   const payload:any={type,clientEventId:crypto.randomUUID(),challengeId:challenge.id,occurredAt:new Date().toISOString(),timezone:Intl.DateTimeFormat().resolvedOptions().timeZone||'America/Maceio',offline:false,deviceFingerprint:getDeviceFingerprint(),selfieDataUrl,...location,dynamicQrToken:qrToken||undefined,networkGatewayToken:networkGatewayToken||undefined,bluetoothName:bluetoothName||undefined,nfcTagId:nfcTagId||undefined,clientSecureContext:window.isSecureContext};
   const result=await api<any>('/punches',{method:'POST',body:JSON.stringify(payload)});
   setMessage(result.approvalRequired
     ? `${result.warning??'Jornada finalizada.'} Todas as evidências do dia ficaram disponíveis para aprovação do Administrador/Super Adm.`
     : 'Marcação registrada. Horário, selfie e GPS foram guardados e serão apresentados juntos para aprovação após a finalização da jornada.');
   setQrToken('');
   return result;
 }

 async function begin(type:string,forcedAutoLock=false){
   if(turnUsage?.closedDay){setMessage(`Ponto fechado: ${turnUsage.closedReason??'dia não trabalhado'}.`);return;}
   if(type==='BREAK_END'&&turnUsage?.breakReturnAllowedAt&&new Date(turnUsage.breakReturnAllowedAt).getTime()>Date.now()){const remaining=Math.max(1,Math.ceil((new Date(turnUsage.breakReturnAllowedAt).getTime()-Date.now())/60_000));setMessage(`O retorno do intervalo só será liberado após 105 minutos. Aguarde mais ${remaining} minuto${remaining===1?'':'s'}.`);return;}
   if(type==='CLOCK_IN'&&!turnUsage?.canReopenOvertime&&turnUsage?.clockInAccess?.code==='LATE_JUSTIFICATION_REQUIRED'){openLateJustification();return;}
   if(type==='CLOCK_IN'&&!turnUsage?.canReopenOvertime&&turnUsage?.clockInAccess&&!turnUsage.clockInAccess.allowed){setMessage(turnUsage.clockInAccess.message??'A entrada ainda não está liberada para a sua jornada/escala.');return;}
   const repeatAllowed=(type==='CLOCK_IN'&&Boolean(turnUsage?.canReopenOvertime))||(type==='CLOCK_OUT'&&Boolean(turnUsage?.reopenedOvertimeActive));
   if(turnUsage?.usedTypes?.includes(type)&&!repeatAllowed){setMessage('Esta marcação já foi registrada neste turno e permanece bloqueada até o próximo turno.');return;}
   setMessage(forcedAutoLock?(turnUsage?.warning??'A tolerância de 10 minutos foi atingida. A saída manual continua obrigando selfie até a finalização automática aos 15 minutos.'):'');setBusy(true);
   try{
     if(!navigator.onLine)throw new Error('A marcação precisa de conexão com o servidor para registrar a selfie, a geolocalização e o horário corretamente.');
     if(!forcedAutoLock&&security?.policy?.requireDynamicQr&&!qrToken)throw new Error('Escaneie o QR dinâmico da unidade antes da marcação.');
     await api('/punches/precheck',{method:'POST',body:JSON.stringify({type})});
     const challenge:any=await api('/punches/challenge',{method:'POST',body:JSON.stringify({type})});
     setMessage(forcedAutoLock?'Tire a selfie para finalizar a jornada. Até completar 15 minutos após o fim previsto, toda saída feita pelo colaborador exige selfie.':'Tire a selfie para concluir esta marcação. Entrada, intervalo, retorno, pausa, retorno e saída manual sempre exigem selfie; ao confirmar a foto, o GPS também será salvo.');
     setPendingCapture({type,challenge,forcedAutoLock});
   }catch(e){if(forcedAutoLock)autoLockPrompted.current=false;setMessage(e instanceof Error?e.message:'Não foi possível iniciar a marcação.');}
   finally{setBusy(false);}
 }
 async function confirmSelfie(sample:{imageDataUrl:string}){
   const pending=pendingCapture;if(!pending)return;setBusy(true);
   try{
     setMessage('Obtendo latitude e longitude deste aparelho…');
     const location=await getLocation();
     if(location.latitude==null||location.longitude==null)throw new Error('Não foi possível obter a geolocalização. Ative a localização/GPS do aparelho e permita o acesso para registrar o ponto.');
     await sendPunch(pending.type,pending.challenge,sample.imageDataUrl,location);
     await Promise.all([load(),loadSecurity(),loadTurnUsage()]);
     setPendingCapture(null);autoLockPrompted.current=false;
   }
   catch(e){
     setMessage(e instanceof Error?e.message:'Não foi possível concluir a marcação.');setPendingCapture(null);if(pending.forcedAutoLock)autoLockPrompted.current=false;
   }
   finally{setBusy(false);}
 }

 const locked=Boolean(turnUsage?.autoLockRequired);
 const autoCloseRemaining=locked&&turnUsage?.autoCloseAt?Math.max(0,Math.ceil((new Date(turnUsage.autoCloseAt).getTime()-now.getTime())/1000)):null;
 const autoCloseCountdown=autoCloseRemaining==null?null:`${String(Math.floor(autoCloseRemaining/60)).padStart(2,'0')}:${String(autoCloseRemaining%60).padStart(2,'0')}`;
 const breakReturnRemaining=turnUsage?.breakReturnAllowedAt?Math.max(0,Math.ceil((new Date(turnUsage.breakReturnAllowedAt).getTime()-now.getTime())/1000)):0;
 const breakReturnLocked=Boolean(turnUsage?.lastPunchType==='BREAK_START'&&breakReturnRemaining>0);
 const breakReturnCountdown=`${String(Math.floor(breakReturnRemaining/60)).padStart(2,'0')}:${String(breakReturnRemaining%60).padStart(2,'0')}`;
 const isAdminAdjustment=Boolean(last?.proof?.proofLevel==='AJUSTE_ADMINISTRATIVO'||last?.source?.includes('APPROVED_'));
 const lateRequired=turnUsage?.clockInAccess?.code==='LATE_JUSTIFICATION_REQUIRED';
 const lateProfessional=turnUsage?.lateJustificationMode==='PROFESSIONAL';
 return <>
  <div className="grid-two"><section className="card hero-card"><div className="eyebrow">REGISTRO DE JORNADA COM SELFIE</div><div className="clock">{now.toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit',second:'2-digit'})}</div><p className="muted">{now.toLocaleDateString('pt-BR',{weekday:'long',day:'2-digit',month:'long'})}</p><div className="turn-lock-note"><b>Selfie obrigatória em toda marcação manual:</b> iniciar jornada, iniciar/retornar intervalo, iniciar/retornar pausa e finalizar jornada. A única exceção é a finalização automática do servidor após 15 minutos.</div>
   {turnUsage?.closedDay?<div className="point-closed-warning"><b>Ponto fechado</b><div>{turnUsage.closedReason??'Dia não trabalhado'}.</div></div>:locked?<div className={`point-lock-warning ${Number(turnUsage?.consecutiveAutoLocks??0)>=2?'repeated':''}`}><b>{Number(turnUsage?.consecutiveAutoLocks??0)>=2?'Trava automática repetida':'Ponto travado automaticamente'}</b><div>{turnUsage?.warning}</div><small>Do minuto +10 até o +15, a saída manual continua exigindo selfie. Tempo até a finalização automática: <b>{autoCloseCountdown??'05:00'}</b>. Ao completar +15 minutos sem saída, o sistema finalizará automaticamente sem selfie e enviará a ocorrência para aprovação.</small></div>:null}
   {!turnUsage?.usedTypes?.includes('CLOCK_IN')&&!turnUsage?.canReopenOvertime&&turnUsage?.clockInAccess&&!turnUsage.clockInAccess.allowed&&!turnUsage?.closedDay&&!lateRequired&&<div className="point-closed-warning"><b>Entrada fora da janela da escala</b><div>{turnUsage.clockInAccess.message}</div><small>A marcação direta da entrada é permitida de 15 minutos antes até 15 minutos depois do início previsto. Ex.: 08:00 → 07:45 até 08:15.</small></div>}
   {!turnUsage?.usedTypes?.includes('CLOCK_IN')&&!turnUsage?.canReopenOvertime&&lateRequired&&!turnUsage?.closedDay&&<div className="late-arrival-card"><div className="eyebrow">CHEGADA APÓS A TOLERÂNCIA</div>{turnUsage.lateArrivalPending?<div className="success-box"><b>Justificativa aguardando aprovação</b><div>Chegada registrada: {new Date(turnUsage.lateArrivalPending.requestedTime).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'})}</div><small>{turnUsage.lateArrivalPending.reason}</small></div>:<><h3>{lateProfessional?'Justificativa profissional':'Justificativa de atraso'}</h3><p>{lateProfessional?'Informe o motivo, anexe o documento comprobatório e tire a selfie no momento do envio.':'Informe o motivo do atraso e tire uma selfie no momento do envio.'}</p><button className="primary" type="button" onClick={openLateJustification}>Preencher justificativa</button></>}</div>}
   {breakReturnLocked&&<div className="point-closed-warning"><b>Intervalo mínimo em andamento</b><div>O retorno do intervalo fica bloqueado durante os primeiros 105 minutos.</div><small>Retorno liberado às <b>{new Date(turnUsage!.breakReturnAllowedAt!).toLocaleTimeString('pt-BR',{hour:'2-digit',minute:'2-digit'})}</b> · faltam <b>{breakReturnCountdown}</b>.</small></div>}
   {security?.policy?.requireDynamicQr&&!locked&&!lateRequired&&<QrTokenInput value={qrToken} onChange={setQrToken} required/>}
   <div className="punch-actions six-actions">{Object.entries(labels).map(([type,label])=>{const used=Boolean(turnUsage?.usedTypes?.includes(type));const reopenClockIn=type==='CLOCK_IN'&&Boolean(turnUsage?.canReopenOvertime);const secondClockOut=type==='CLOCK_OUT'&&Boolean(turnUsage?.reopenedOvertimeActive);const effectivelyUsed=used&&!reopenClockIn&&!secondClockOut;const lateClockIn=type==='CLOCK_IN'&&!reopenClockIn&&lateRequired;const clockInOutsideSchedule=type==='CLOCK_IN'&&!reopenClockIn&&Boolean(turnUsage?.clockInAccess&&!turnUsage.clockInAccess.allowed&&!lateClockIn);const waitingForFirstClockIn=!turnUsage?.usedTypes?.includes('CLOCK_IN')&&!turnUsage?.canReopenOvertime&&type!=='CLOCK_IN';const pendingLateClockIn=lateClockIn&&Boolean(turnUsage?.lateArrivalPending);const intervalReturnLocked=type==='BREAK_END'&&breakReturnLocked;const disabled=busy||Boolean(pendingCapture)||effectivelyUsed||Boolean(turnUsage?.closedDay)||locked||clockInOutsideSchedule||waitingForFirstClockIn||pendingLateClockIn||intervalReturnLocked||(Boolean(turnUsage?.hasClockOut)&&!reopenClockIn&&!secondClockOut);return <button key={type} className={effectivelyUsed?'punch-used':''} disabled={disabled} title={turnUsage?.closedDay?`Fechado: ${turnUsage.closedReason??''}`:locked?'Ponto travado: finalize a jornada com selfie':intervalReturnLocked?`Retorno bloqueado por 105 minutos após o início do intervalo. Faltam ${breakReturnCountdown}.`:pendingLateClockIn?'Aguardando aprovação da justificativa de atraso':waitingForFirstClockIn?(turnUsage?.lateArrivalPending?'Aguardando aprovação da entrada informada':'Registre ou regularize a entrada antes das demais ações'):clockInOutsideSchedule?(turnUsage?.clockInAccess?.message??'Entrada fora da escala'):lateClockIn?'Preencher justificativa de atraso':reopenClockIn?'Hora extra liberada: inicie a jornada extra':effectivelyUsed?'Já registrado neste turno':''} onClick={()=>begin(type)}>{effectivelyUsed?'✓ ':''}{reopenClockIn?'↻ ':''}{label}{intervalReturnLocked?` · ${breakReturnCountdown}`:''}</button>})}</div>
   <div className="turn-lock-note">Os botões com ✓ já foram registrados. Depois da saída, uma nova jornada no mesmo dia só é liberada quando um Administrador concede +1h extra. {Number(turnUsage?.overtimeExtensionMinutes??0)>0?`Hora extra liberada hoje: ${Number(turnUsage?.overtimeExtensionMinutes??0)/60}h.`:''}</div>{message&&<p className="muted inline-feedback">{message}</p>}{pendingCapture&&<SelfieCapture title="Tire a selfie desta marcação" onCapture={confirmSelfie} allowCancel={!pendingCapture.forcedAutoLock} onCancel={()=>{setPendingCapture(null);autoLockPrompted.current=false;setMessage('Marcação cancelada antes do envio da selfie.');}}/>}
  </section><section className="card"><div className="card-head"><div><div className="eyebrow">ÚLTIMA MARCAÇÃO</div><h3>{last?labels[last.type]:'Sem marcações'}</h3></div>{last?.proof&&!isAdminAdjustment&&<ProofGauge score={last.proof.proofScore} level={last.proof.proofLevel}/>} {isAdminAdjustment&&<span className="score-pill">AJUSTE ADMINISTRATIVO</span>}</div>{last?<><div className="fact-row"><span>Horário</span><b>{new Date(last.occurredAt).toLocaleString('pt-BR')}</b></div><div className="fact-row"><span>Status</span><b>{last.decision}</b></div><div className="fact-row"><span>NSR interno</span><b>#{last.recordNumber}</b></div>{last.proof?.latitude!=null&&last.proof?.longitude!=null&&<div className="fact-row"><span>GPS da selfie</span><b>{Number(last.proof.latitude).toFixed(6)}, {Number(last.proof.longitude).toFixed(6)}</b></div>}<div className="hash-box"><span>Hash de integridade</span><code>{last.integrityHash}</code></div>{isAdminAdjustment?<div className="notice-box"><b>Ajuste administrativo aprovado</b><p className="muted">Este registro foi criado por um fluxo formal de ajuste e, por isso, não exige selfie, reconhecimento facial ou localização como uma marcação feita pelo colaborador.</p>{last.reviewReason&&<small>{last.reviewReason}</small>}</div>:<><ul className="reason-list">{last.proof?.reasonsJson?.slice(0,10).map((r,i)=><li key={i}>✓ {r}</li>)}</ul>{last.proof?.missingRequirementsJson?.length?<ul className="warning-list">{last.proof.missingRequirementsJson.map((r,i)=><li key={i}>⚠ {r}</li>)}</ul>:null}</>}</>:<p className="muted">Sua primeira marcação aparecerá aqui com a selfie, latitude/longitude, status de aprovação e hash de integridade.</p>}</section></div>

  {lateModalOpen&&<div className="modal-backdrop" role="dialog" aria-modal="true" aria-label="Justificativa de atraso"><form className="modal-card late-justification-modal" onSubmit={submitLateArrival}>
   <div className="card-head"><div><div className="eyebrow">CHEGADA APÓS A TOLERÂNCIA</div><h2>{lateProfessional?'Justificativa profissional':'Justificativa de atraso'}</h2></div><button type="button" className="ghost" onClick={()=>setLateModalOpen(false)}>Fechar</button></div>
   <label>Motivo da justificativa<textarea required minLength={5} maxLength={1000} value={lateReason} onChange={e=>setLateReason(e.target.value)} placeholder="Digite o motivo do atraso."/></label>
   {lateProfessional&&<><label>Tipo de comprovante<select value={lateDocumentType} onChange={e=>setLateDocumentType(e.target.value as LateDocumentType)}><option value="HOURS_DECLARATION">Declaração de horas</option><option value="ATTENDANCE_PROOF">Comprovante de presença</option></select></label><label>Documento comprobatório<input type="file" accept="application/pdf,image/jpeg,image/png,image/webp" onChange={e=>setLateDocument(e.target.files?.[0]??null)}/><small>Envie PDF, JPG, PNG ou WEBP. Se nenhum documento for anexado, a justificativa será enviada e reprovada automaticamente.</small></label>{lateDocument&&<div className="late-document-selected"><b>Documento selecionado</b><span>{lateDocument.name}</span><small>{Math.max(1,Math.round(lateDocument.size/1024))} KB</small></div>}</>}
   <div className="inline-actions"><button className="primary" disabled={lateSubmitting||lateReason.trim().length<5}>{lateSubmitting?'Enviando…':'Continuar para selfie'}</button><button type="button" className="ghost" onClick={()=>setLateModalOpen(false)}>Cancelar</button></div>
   <small className="muted">A selfie será tirada agora, no momento da justificativa. A entrada somente será criada após a aprovação.</small>
  </form></div>}
  {lateSelfieOpen&&<SelfieCapture title={lateProfessional?'Selfie da justificativa profissional':'Selfie da justificativa de atraso'} onCapture={confirmLateArrivalSelfie} onCancel={()=>{setLateSelfieOpen(false);setLateModalOpen(true);}}/>}
 </>;
}
