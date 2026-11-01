package br.com.gestaosoftware.control.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class PlatformPaymentGateway {
    private final String selected;
    private final Map<String,PlatformPaymentProvider> providers;

    public PlatformPaymentGateway(List<PlatformPaymentProvider> adapters,
                                  @Value("${app.financial.provider:ASAAS}") String selected) {
        this.selected=(selected==null||selected.isBlank()?"NONE":selected.trim()).toUpperCase(Locale.ROOT);
        this.providers=adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                p->p.code().toUpperCase(Locale.ROOT),p->p));
    }
    public boolean ready(){PlatformPaymentProvider p=providers.get(selected);return p!=null&&p.ready();}
    public String code(){return selected;}
    public Map<String,Object> status(){return Map.of("provider",selected,"configured",ready(),
            "paymentsEnabled",ready(),"message",ready()?"Financeira configurada.":
                    "Aguardando escolha, credenciais e homologação da financeira.");}
    public Map<String,Object> checkout(UUID invoiceId){
        PlatformPaymentProvider p=providers.get(selected);
        if(p==null||!p.ready())throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "Configure e homologue a financeira antes de gerar uma cobrança.");
        return p.checkout(invoiceId);
    }
}
