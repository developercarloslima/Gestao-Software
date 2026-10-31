package br.com.nh.cotacao.platform;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.Map;

@Component
public class ModuleEntitlementInterceptor implements HandlerInterceptor {
    private final GestaoTenantConfigService config;
    public ModuleEntitlementInterceptor(GestaoTenantConfigService config){this.config=config;}

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        Map<String,Object> tenant = config.config();
        if (Boolean.FALSE.equals(tenant.get("active"))) {
            return deny(res, "Empresa desativada pela Gestão Software. Entre em contato com o responsável administrativo.", "COMPANY_DISABLED");
        }

        String path = req.getRequestURI();
        // O histórico de cotações e vistorias pertence aos módulos operacionais,
        // não exige a contratação do módulo financeiro.
        if (path != null && path.startsWith("/api/consultant-dashboard")) {
            if (config.enabled("COTA") || config.enabled("VISTORIA_EVENTOS")) return true;
            return deny(res, "Módulo não contratado ou desativado para esta empresa.", "MODULE_DISABLED");
        }
        String module = moduleFor(path);
        if (module == null || config.enabled(module)) return true;
        return deny(res, "Módulo não contratado ou desativado para esta empresa.", "MODULE_DISABLED");
    }

    private boolean deny(HttpServletResponse res, String message, String code) throws Exception {
        res.setStatus(403);
        res.setContentType("application/json;charset=UTF-8");
        res.getWriter().write("{\"message\":\"" + message.replace("\"", "\\\"") + "\",\"code\":\"" + code + "\"}");
        return false;
    }

    private String moduleFor(String p){
        if(p==null)return null;
        if(p.startsWith("/api/quotes")||p.startsWith("/api/consultants")||p.startsWith("/api/admin/quotes")||p.startsWith("/api/admin/catalog")||p.startsWith("/api/admin/consultants")||p.startsWith("/api/public/quotes")) return "COTA";
        if(p.startsWith("/api/admin/inspections")||p.startsWith("/api/analysis")||p.startsWith("/api/supervision")||p.startsWith("/api/checklist")||p.startsWith("/api/workshop")||p.startsWith("/api/tow")||p.startsWith("/api/procurement")) return "VISTORIA_EVENTOS";
        if(p.startsWith("/api/commercial")||p.startsWith("/api/admin/commercial")||p.startsWith("/api/associate-portal")||p.startsWith("/api/finance-office")||p.startsWith("/api/public/financial")) return "GESTAO_FINANCEIRA_COMERCIAL";
        if(p.startsWith("/api/admin/nh-ponto")) return "APONTAMENTO_DIGITAL";
        return null;
    }
}
