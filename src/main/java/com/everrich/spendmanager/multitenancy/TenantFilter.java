package com.everrich.spendmanager.multitenancy;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter that sets the tenant context for each request based on the authenticated user.
 * This filter runs after Spring Security's authentication filter and extracts
 * the tenant ID from the TenantUserDetails to set in the TenantContext.
 */
@Component
public class TenantFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, 
                                    HttpServletResponse response, 
                                    FilterChain filterChain)
            throws ServletException, IOException {
        
        try {
            // Get the current authentication from the security context
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            
            if (authentication != null && authentication.isAuthenticated() 
                    && authentication.getPrincipal() instanceof TenantUserDetails) {
                
                TenantUserDetails userDetails = (TenantUserDetails) authentication.getPrincipal();
                String tenantId = userDetails.getTenantId();
                
                // Set the tenant context
                // For SUPERADMIN (tenantId is null), we use the default schema
                if (tenantId != null && !tenantId.isEmpty()) {
                    TenantContext.setTenantId(tenantId);
                } else {
                    // SUPERADMIN or no tenant - use default schema
                    TenantContext.setTenantId(TenantContext.DEFAULT_SCHEMA);
                }
            } else {
                // No authentication or not a TenantUserDetails - use default schema
                TenantContext.setTenantId(TenantContext.DEFAULT_SCHEMA);
            }
            
            // Continue with the filter chain
            filterChain.doFilter(request, response);
            
        } finally {
            // Always clear the tenant context after the request is processed
            // This prevents tenant leakage between requests
            TenantContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip filtering for static resources
        String path = request.getRequestURI();
        return path.startsWith("/css/") 
                || path.startsWith("/js/") 
                || path.startsWith("/images/")
                || path.startsWith("/favicon");
    }
}