package br.com.gestaosoftware.control.service;

import java.util.Map;
import java.util.UUID;

/** Integração financeira da Gestão (mensalidades SaaS), independente das cobranças do associado. */
public interface PlatformPaymentProvider {
    String code();
    boolean ready();
    Map<String,Object> checkout(UUID invoiceId);
}
