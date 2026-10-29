package com.example.backenddummy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class TenantFilter extends OncePerRequestFilter {

    @Value("${SERVICE_NAME:order-service}")
    private String serviceName;

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String customer = request.getHeader("X-Customer-Id");
        String tenant = request.getHeader("X-Tenant-Id");

        if (StringUtils.hasText(customer) && StringUtils.hasText(tenant)) {
            TenantCredentialService.Credentials creds =
                    TenantCredentialService.derive(customer, tenant, serviceName);

            TenantContext ctx = new TenantContext();
            ctx.setCustomerId(customer);
            ctx.setTenantId(tenant);
            ctx.setDbName(creds.dbName());
            ctx.setSchema(creds.schema());
            ctx.setService(serviceName);
            TenantContext.set(ctx);
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }
}
