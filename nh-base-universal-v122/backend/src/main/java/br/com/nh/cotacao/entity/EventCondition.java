package br.com.nh.cotacao.entity;

/**
 * Origem/causa do evento exigida pela cobertura. Separada do escopo do dano
 * para permitir combinações como "derivado de colisão + somente dano total".
 */
public enum EventCondition {
    ANY_ORIGIN,
    COLLISION_DERIVED
}
