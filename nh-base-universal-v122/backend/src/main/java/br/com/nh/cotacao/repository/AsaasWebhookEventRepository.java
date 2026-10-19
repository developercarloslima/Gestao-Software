package br.com.nh.cotacao.repository;

import br.com.nh.cotacao.entity.AsaasWebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AsaasWebhookEventRepository extends JpaRepository<AsaasWebhookEvent, String> {}
