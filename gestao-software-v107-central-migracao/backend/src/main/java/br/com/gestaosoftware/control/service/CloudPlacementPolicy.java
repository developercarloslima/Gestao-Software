package br.com.gestaosoftware.control.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Algoritmo determinístico, puro e testável sem rede/banco de dados.
 * Não interpreta utilização instantânea como reserva. Os dois limites são verificados separadamente.
 */
public final class CloudPlacementPolicy {
    public record Demand(double cpu, double ramGb, double diskGb) {
        public Demand {
            if (!positive(cpu) || !positive(ramGb) || !positive(diskGb))
                throw new IllegalArgumentException("Dimensionamento inválido.");
        }
    }
    public record Node(String id, String name, boolean active, boolean agentOnline,
                       int usedSlots, int maxSlots, int unmanagedTenants,
                       double cpuCores, double ramGb, double diskGb,
                       double cpuUsage, double memoryUsage, double diskUsage,
                       double committedCpu, double committedRamGb, double committedDiskGb) {}
    public record Decision(boolean eligible, double score, List<String> reasons,
                           double freeCpuAfter, double freeRamAfter, double freeDiskAfter) {}

    private static boolean positive(double v) {return Double.isFinite(v) && v > 0;}
    private static boolean percentage(double v) {return Double.isFinite(v) && v >= 0 && v <= 100;}

    /** CPU <=70%, RAM <=75%, disco <=75%, além dos limites instantâneos. */
    public static Decision evaluate(Node n, Demand d) {
        List<String> reasons=new ArrayList<>();
        if (!n.active()) reasons.add("VPS desativada");
        if (!n.agentOnline()) reasons.add("Agente offline ou métrica antiga");
        if (n.usedSlots() >= n.maxSlots()) reasons.add("Limite de empresas atingido");
        if (n.unmanagedTenants() > 0) reasons.add("Existem clientes sem reserva de capacidade; conciliar antes de automatizar");
        if (!positive(n.cpuCores()) || !positive(n.ramGb()) || !positive(n.diskGb())) reasons.add("Capacidade física não cadastrada");
        if (!percentage(n.cpuUsage()) || !percentage(n.memoryUsage()) || !percentage(n.diskUsage())) reasons.add("Métricas inválidas ou indisponíveis");
        if (percentage(n.cpuUsage()) && n.cpuUsage() >= 70) reasons.add("CPU acima do limite operacional (70%)");
        if (percentage(n.memoryUsage()) && n.memoryUsage() >= 75) reasons.add("RAM acima do limite operacional (75%)");
        if (percentage(n.diskUsage()) && n.diskUsage() >= 75) reasons.add("Disco acima do limite operacional (75%)");
        double freeCpu=n.cpuCores()*.70-n.committedCpu()-d.cpu();
        double freeRam=n.ramGb()*.75-n.committedRamGb()-d.ramGb();
        double freeDisk=n.diskGb()*.75-n.committedDiskGb()-d.diskGb();
        if (!Double.isFinite(freeCpu) || freeCpu < -1e-7) reasons.add("vCPU reservadas insuficientes");
        if (!Double.isFinite(freeRam) || freeRam < -1e-7) reasons.add("RAM reservada insuficiente");
        if (!Double.isFinite(freeDisk) || freeDisk < -1e-7) reasons.add("Armazenamento reservado insuficiente");
        if (!reasons.isEmpty()) return new Decision(false, 0, List.copyOf(reasons),freeCpu,freeRam,freeDisk);
        // Classificação best-fit: conserva VPS maiores para clientes maiores; penaliza sobrecarga observada.
        double waste=(freeCpu/(n.cpuCores()*.70)+freeRam/(n.ramGb()*.75)+freeDisk/(n.diskGb()*.75))/3.0;
        double health=(n.cpuUsage()/70+n.memoryUsage()/75+n.diskUsage()/75)/3.0;
        double score=100 - 45*waste - 55*health;
        return new Decision(true,Math.round(score*100.0)/100.0,List.of(),freeCpu,freeRam,freeDisk);
    }
}
