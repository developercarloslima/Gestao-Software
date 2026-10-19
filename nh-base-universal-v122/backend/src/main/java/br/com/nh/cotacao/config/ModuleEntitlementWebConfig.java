package br.com.nh.cotacao.config;

import br.com.nh.cotacao.platform.ModuleEntitlementInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class ModuleEntitlementWebConfig implements WebMvcConfigurer {
    private final ModuleEntitlementInterceptor interceptor;
    public ModuleEntitlementWebConfig(ModuleEntitlementInterceptor interceptor){this.interceptor=interceptor;}
    @Override public void addInterceptors(InterceptorRegistry registry){
        registry.addInterceptor(interceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/health", "/api/health/**", "/api/public/tenant-config");
    }
}
