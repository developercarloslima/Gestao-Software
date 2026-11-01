package br.com.nh.cotacao.service;

import br.com.nh.cotacao.dto.CommercialFinanceDtos.*;
import br.com.nh.cotacao.entity.FinancialBillingType;
import br.com.nh.cotacao.entity.FinancialChargeStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Segunda via sem consulta pública por CPF: o associado já deve estar autenticado. */
@Service
public class SecondCopyService {
    private final CommercialFinanceService finance;
    private final FinancialProviderGateway provider;

    public SecondCopyService(CommercialFinanceService finance, FinancialProviderGateway provider) {
        this.finance=finance;this.provider=provider;
    }

    @Transactional(readOnly=true)
    public SecondCopyResponse list(UUID associateId) {
        AssociatePortalProfile associate=finance.associatePortal(associateId);
        List<SecondCopyBill> bills=new ArrayList<>();
        for(AssociateVehicleSummary vehicle: associate.vehicles()) {
            AssociateVehicleDetail detail=finance.associateVehicle(associateId, vehicle.id());
            for(ChargeResponse charge: detail.charges()) {
                if(charge.billingType()==FinancialBillingType.BOLETO) {
                    bills.add(convert(charge,vehicle.plate(),vehicle.model()));
                }
            }
        }
        return new SecondCopyResponse(new SecondCopyAssociate(associate.name()), bills,
                provider.enabled(),provider.code());
    }

    public static SecondCopyBill convert(ChargeResponse charge,String plate,String vehicle) {
        FinancialChargeStatus status=charge.status();
        boolean settled=status==FinancialChargeStatus.RECEIVED;
        boolean overdue=status==FinancialChargeStatus.OVERDUE ||
                (charge.dueDate()!=null && charge.dueDate().isBefore(LocalDate.now()) && !settled);
        String url=httpsOnly(charge.bankSlipUrl());
        if(url==null)url=httpsOnly(charge.invoiceUrl());
        String code=charge.identificationField()!=null?charge.identificationField():charge.barCode();
        boolean eligible=!settled && !overdue && status!=FinancialChargeStatus.CANCELLED
                && status!=FinancialChargeStatus.REFUNDED && status!=FinancialChargeStatus.ERROR;
        boolean available=eligible && (url!=null || (code!=null && !code.isBlank()));
        String message=settled?"Pagamento já confirmado."
                : overdue?"Boleto vencido. Solicite a atualização ao financeiro antes de pagar."
                : !available?"Boleto ainda não emitido pela financeira. Consulte o setor financeiro.":null;
        String label=settled?"Pago":overdue?"Vencido":status==FinancialChargeStatus.CONFIRMED?"Confirmado":"Pendente";
        return new SecondCopyBill(plate,vehicle,charge.totalCharged(),charge.dueDate(),label,
                available?url:null,available?code:null,available?charge.barCode():null,available,message);
    }

    private static String httpsOnly(String raw) {
        if(raw==null)return null;
        try {
            java.net.URI uri=java.net.URI.create(raw);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost()!=null &&
                    uri.getUserInfo()==null ? uri.toASCIIString() : null;
        }catch(IllegalArgumentException ex){return null;}
    }

    public record SecondCopyAssociate(String nome) {}
    public record SecondCopyResponse(SecondCopyAssociate associado,List<SecondCopyBill> boletos,
                                     boolean financeiraConfigurada,String provedor) {}
    public record SecondCopyBill(String placa,String veiculo,BigDecimal valor,LocalDate vencimento,
                                 String status,String pdf,String linhaDigitavel,String codigoBarras,
                                 boolean disponivel,String mensagem) {}
}
