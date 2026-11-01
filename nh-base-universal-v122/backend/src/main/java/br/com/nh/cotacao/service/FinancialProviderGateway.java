package br.com.nh.cotacao.service;

import br.com.nh.cotacao.entity.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Seleção única da financeira por instalação, sem fallback silencioso para outra conta. */
@Service
public class FinancialProviderGateway {
    private final String selected;
    private final Map<String, FinancialProvider> providers;

    public FinancialProviderGateway(List<FinancialProvider> adapters,
                                    @Value("${app.financial.provider:ASAAS}") String provider) {
        this.selected = (provider == null || provider.isBlank() ? "NONE" : provider.trim()).toUpperCase(Locale.ROOT);
        this.providers = adapters.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                adapter -> adapter.code().toUpperCase(Locale.ROOT), adapter -> adapter));
    }

    public String code() { return selected; }
    public boolean enabled() { FinancialProvider adapter=providers.get(selected);return adapter!=null && adapter.enabled(); }
    public Map<String, Object> status() {
        return Map.of("provider", selected, "configured", enabled(),
                "paymentsEnabled", enabled(), "message", enabled() ? "Integração financeira ativa."
                        : "Aguardando credenciais e homologação da financeira escolhida.");
    }
    private FinancialProvider active() {
        FinancialProvider adapter=providers.get(selected);
        if(adapter==null || !adapter.enabled()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "A financeira ainda não foi configurada ou homologada para esta instalação.");
        return adapter;
    }
    public String createCustomer(Associate a){return active().createCustomer(a);}
    public FinancialProvider.PaymentData createCharge(String c,FinancialBillingType b,BigDecimal v,LocalDate d,String desc,String ref){return active().createCharge(c,b,v,d,desc,ref);}
    public String createMonthlySubscription(String c,FinancialBillingType b,BigDecimal v,LocalDate d,String desc,String ref){return active().createMonthlySubscription(c,b,v,d,desc,ref);}
    public FinancialProvider.PaymentData refreshPayment(String id){return active().refreshPayment(id);}
    public BigDecimal balance(){return active().balance();}
    public FinancialProvider.TransferData transferPix(BigDecimal a,PixKeyType k,String key,String desc,String ref){return active().transferPix(a,k,key,desc,ref);}
}
