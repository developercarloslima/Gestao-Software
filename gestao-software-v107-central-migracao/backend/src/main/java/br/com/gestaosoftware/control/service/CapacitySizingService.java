package br.com.gestaosoftware.control.service;

import org.springframework.stereotype.Service;
import java.util.*;

@Service
public class CapacitySizingService {
    public record Assessment(int employees, int simultaneousUsers, long salesPerMonth, long paymentsPerMonth,
                             int workDaysPerMonth, int marksPerDay, int photoKilobytes, int retentionDays,
                             boolean faceRecognition, boolean highAvailability, String hosting,
                             List<String> modules) {}

    public Map<String,Object> estimate(Assessment a) {
        if (a == null) throw new IllegalArgumentException("Informe os dados da empresa.");
        if (a.employees()<1 || a.employees()>10_000_000 || a.simultaneousUsers()<1 || a.simultaneousUsers()>a.employees()
            || a.salesPerMonth()<0 || a.salesPerMonth()>100_000_000 || a.paymentsPerMonth()<0 || a.paymentsPerMonth()>100_000_000
            || a.workDaysPerMonth()<1 || a.workDaysPerMonth()>31 || a.marksPerDay()<0 || a.marksPerDay()>30
            || a.photoKilobytes()<0 || a.photoKilobytes()>15000 || a.retentionDays()<1 || a.retentionDays()>3650)
            throw new IllegalArgumentException("Os volumes informados estão fora dos limites aceitos.");
        Set<String> validHosts = Set.of("MANAGED_VPS","CLIENT_VPS","RENDER","AWS");
        if (!validHosts.contains(a.hosting())) throw new IllegalArgumentException("Escolha uma hospedagem válida.");
        List<String> modules = a.modules()==null ? List.of() : a.modules().stream().distinct().toList();
        if(modules.isEmpty() || modules.size()>15 || modules.stream().anyMatch(x->x==null || !x.matches("[A-Z0-9_]{2,60}")))
            throw new IllegalArgumentException("Selecione módulos válidos.");
        boolean point=modules.contains("APONTAMENTO_DIGITAL");
        long marks=point ? (long)a.employees()*a.workDaysPerMonth()*a.marksPerDay() : 0L;
        double photosGb=(marks*a.photoKilobytes()/1024.0/1024.0)*a.retentionDays()/Math.max(1,a.workDaysPerMonth()*30.0/22.0);
        double estimatedStorageGb=Math.ceil((photosGb + a.paymentsPerMonth()*0.00003 + a.salesPerMonth()*0.00008 + 20)*1.4);
        int cpu=(int)Math.min(256,Math.max(2,Math.ceil(a.simultaneousUsers()/90.0)+(a.faceRecognition()?Math.ceil(a.simultaneousUsers()/35.0):0)));
        int ram=(int)Math.min(1024,Math.max(4,cpu*2));
        String tier=a.employees()>5000 || a.simultaneousUsers()>1000 || a.salesPerMonth()>150000 || a.paymentsPerMonth()>150000 ? "ENTERPRISE" :
                    a.employees()>250 || a.simultaneousUsers()>50 || a.salesPerMonth()>7500 ? "BUSINESS" : "START";
        boolean review="ENTERPRISE".equals(tier) || a.highAvailability() || a.faceRecognition();
        Map<String,Object> result=new LinkedHashMap<>();
        result.put("tier",tier);result.put("requiresArchitectureReview",review);
        result.put("recommendedCpuVcpu",cpu);result.put("recommendedRamGb",ram);
        result.put("estimatedStorageGb",(long)estimatedStorageGb);
        result.put("estimatedMarksPerMonth",marks);result.put("photosGbPerMonth",Math.round(photosGb*a.workDaysPerMonth()*30.0/(Math.max(1,a.retentionDays())*22.0)*100.0)/100.0);
        result.put("hosting",a.hosting());result.put("modules",modules);
        result.put("explanation", "Estimativa inicial para planejamento; não é capacidade garantida. Realizar teste de pico, biometria, restauração, IOPS, rede e integrações antes de produção.");
        result.put("nextStep",review?"Revisão técnica e teste de carga obrigatórios antes da ativação.":"Registrar empresa e módulos, assinar contrato, confirmar pagamento via provedor e iniciar provisionamento.");
        return result;
    }
}
