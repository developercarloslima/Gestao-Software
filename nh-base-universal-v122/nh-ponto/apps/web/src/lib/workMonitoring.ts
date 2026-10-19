// V83.87 — gravação de tela removida.
// Este módulo permanece apenas como compatibilidade interna para impedir que qualquer
// código legado tente iniciar MediaRecorder/getDisplayMedia após o deploy.
export type WorkMonitoringState={supported:boolean;phase:'idle';sessionId:null;startedAt:null;message:string;captureReady:false};
const disabledState:WorkMonitoringState={supported:false,phase:'idle',sessionId:null,startedAt:null,message:'Gravação de tela desativada. O sistema utiliza somente os contadores da jornada.',captureReady:false};
export function getWorkMonitoringState(){return {...disabledState};}
export function subscribeWorkMonitoring(listener:(state:WorkMonitoringState)=>void){listener({...disabledState});return()=>undefined;}
export function isWorkMonitoringActive(){return false;}
export function hasPreparedWorkCapture(){return false;}
export function isPersonalMobileDevice(){return /Android|webOS|iPhone|iPad|iPod|IEMobile|Opera Mini|Mobile/i.test(navigator.userAgent);}
export async function prepareWorkMonitoringCapture(){return {prepared:false,supported:false};}
export async function startWorkMonitoring(_trigger:string){return {started:false,supported:false};}
export async function resumeHeldWorkMonitoring(_trigger='DISABLED'){return {started:false,supported:false};}
export async function pauseWorkMonitoring(_reason='DISABLED'){return;}
export async function stopWorkMonitoring(_reason='DISABLED'){return;}
export async function abandonWorkMonitoringLocally(){return;}
