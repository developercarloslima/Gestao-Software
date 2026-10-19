package br.com.nh.cotacao.entity;

/**
 * Escopo do dano exigido para a cobertura: qualquer dano, somente total ou
 * somente parcial. A origem do evento é configurada separadamente.
 */
public enum DamageCondition {
    ANY_DAMAGE,
    TOTAL_ONLY,
    PARTIAL_ONLY
}
