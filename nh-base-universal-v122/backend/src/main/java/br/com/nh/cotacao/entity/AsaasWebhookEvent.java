package br.com.nh.cotacao.entity;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity @Table(name="asaas_webhook_events")
public class AsaasWebhookEvent {
 @Id @Column(name="event_id",length=120) private String eventId;
 @Column(name="event_type",nullable=false,length=80) private String eventType;
 @Column(nullable=false, columnDefinition="text") private String payload;
 @Column(name="received_at",nullable=false) private OffsetDateTime receivedAt;
 @Column(name="processed_at") private OffsetDateTime processedAt;
 @Column(name="processing_error",length=1000) private String processingError;
 protected AsaasWebhookEvent(){}
 public static AsaasWebhookEvent received(String id,String type,String payload){AsaasWebhookEvent e=new AsaasWebhookEvent();e.eventId=id;e.eventType=type;e.payload=payload;e.receivedAt=OffsetDateTime.now();return e;}
 public void processed(){this.processedAt=OffsetDateTime.now();this.processingError=null;}
 public void failed(String message){this.processingError=message==null?"Erro não informado":message.substring(0,Math.min(1000,message.length()));}
 public String getEventId(){return eventId;}public String getEventType(){return eventType;}public String getPayload(){return payload;}public OffsetDateTime getReceivedAt(){return receivedAt;}public OffsetDateTime getProcessedAt(){return processedAt;}public String getProcessingError(){return processingError;}
}
