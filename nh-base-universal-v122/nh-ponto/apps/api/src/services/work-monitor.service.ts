import { prisma } from '../lib/prisma.js';
import { removeEncryptedWorkMonitorChunk } from './work-monitor-storage.service.js';

const HEARTBEAT_CAP_SECONDS=30;
const STALE_AFTER_MS=90_000;

export function monitoringRetentionDays(){
  const parsed=Number(process.env.WORK_MONITOR_RETENTION_DAYS??3);
  return Number.isFinite(parsed)?Math.min(30,Math.max(1,Math.trunc(parsed))):3;
}

export function heartbeatDeltaSeconds(lastHeartbeatAt:Date,now=new Date()){
  const seconds=Math.floor((now.getTime()-lastHeartbeatAt.getTime())/1000);
  return Math.max(0,Math.min(HEARTBEAT_CAP_SECONDS,seconds));
}

export async function closeStaleWorkMonitoringSessions(){
  const now=new Date();
  const cutoff=new Date(now.getTime()-STALE_AFTER_MS);
  const stale=await prisma.workMonitorSession.findMany({where:{status:'ACTIVE',lastHeartbeatAt:{lt:cutoff}},select:{id:true,lastHeartbeatAt:true}});
  for(const row of stale){
    await prisma.workMonitorSession.updateMany({where:{id:row.id,status:'ACTIVE'},data:{status:'INTERRUPTED',endedAt:row.lastHeartbeatAt,stopReason:'HEARTBEAT_TIMEOUT'}});
  }
  return stale.length;
}

export async function runWorkMonitorRetention(){
  const cutoff=new Date(Date.now()-monitoringRetentionDays()*24*60*60*1000);
  // Retenção apaga somente o vídeo. As sessões e seus minutos permanecem no banco
  // para relatórios históricos e auditoria da jornada.
  const chunks=await prisma.workMonitorChunk.findMany({
    where:{session:{createdAt:{lt:cutoff}}},
    select:{id:true,storageKey:true},
    take:2000
  });
  let removedChunks=0;
  for(const chunk of chunks){
    await removeEncryptedWorkMonitorChunk(chunk.storageKey);
    const removed=await prisma.workMonitorChunk.deleteMany({where:{id:chunk.id}});
    removedChunks+=removed.count;
  }
  return {removedChunks,retentionDays:monitoringRetentionDays(),historyPreserved:true};
}
