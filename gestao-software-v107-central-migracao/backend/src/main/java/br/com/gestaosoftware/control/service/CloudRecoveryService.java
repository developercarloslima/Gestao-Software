package br.com.gestaosoftware.control.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

/** Não reimplanta automaticamente jobs possivelmente ativos: marca revisão e conserva o consumo de capacidade. */
@Service
public class CloudRecoveryService {
 private final JdbcTemplate jdbc;
 private final boolean enabled;
 public CloudRecoveryService(JdbcTemplate jdbc,@Value("${app.cloud.orchestrator-enabled:false}") boolean enabled){
    this.jdbc=jdbc;this.enabled=enabled;
 }
 @Scheduled(fixedDelayString="${app.cloud.recovery-interval-ms:300000}",initialDelay=60000)
 @Transactional
 public void markStaleForReview(){
    if(!enabled)return;
    List<UUID> stale=jdbc.query("""
      SELECT id FROM gs_deployments
      WHERE (status='PROVISIONING' AND claimed_at < NOW()-INTERVAL '45 minutes')
         OR (status='VERIFYING' AND updated_at < NOW()-INTERVAL '24 hours')
      ORDER BY created_at LIMIT 50 FOR UPDATE SKIP LOCKED
      """,(rs,n)->rs.getObject(1,UUID.class));
    for(UUID id:stale){
       jdbc.update("""
         UPDATE gs_deployments SET status='FAILED',last_message='Execução interrompida ou não validada. Verifique a VPS antes de tentar novamente.',updated_at=NOW()
         WHERE id=? AND status IN ('PROVISIONING','VERIFYING')
         """,id);
       jdbc.update("""
         UPDATE gs_cloud_domains SET status='ERROR',updated_at=NOW()
         WHERE company_id=(SELECT company_id FROM gs_deployments WHERE id=?)
         """,id);
       jdbc.update("""
         UPDATE gs_cloud_capacity_reservations SET status='HELD_FAILED',updated_at=NOW()
         WHERE company_id=(SELECT company_id FROM gs_deployments WHERE id=?)
         """,id);
       jdbc.update("INSERT INTO gs_deployment_events(id,deployment_id,event,message) VALUES(?,?, 'FAILED','Tempo excedido; revisão e limpeza do ambiente obrigatórias antes de liberar recursos.')",
           UUID.randomUUID(),id);
    }
 }
}
