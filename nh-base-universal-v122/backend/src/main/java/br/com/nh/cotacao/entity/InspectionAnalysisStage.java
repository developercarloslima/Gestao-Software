package br.com.nh.cotacao.entity;

/**
 * Etapa operacional da vistoria dentro do fluxo interno de análise.
 * O status da vistoria continua representando a situação do envio/decisão;
 * esta etapa define em qual painel a vistoria deve aparecer.
 */
public enum InspectionAnalysisStage {
    ANALYST_QUEUE,
    ANALYST_PENDING,
    SUPERVISION_QUEUE,
    FINISHED
}
