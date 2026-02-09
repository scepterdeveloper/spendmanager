package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.TenantCreationStatus;
import com.everrich.spendmanager.entities.UserRole;
import com.everrich.spendmanager.multitenancy.TenantUserDetails;
import com.everrich.spendmanager.repository.AppUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;

/**
 * Custom UserDetailsService that provides tenant-aware user details.
 * Handles authentication and checks tenant creation status before allowing login.
 */
@Service
public class CustomUserDetailsService implements UserDetailsService {

    private static final Logger log = LoggerFactory.getLogger(CustomUserDetailsService.class);

    private final AppUserRepository appUserRepository;

    public CustomUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        log.info("=== LOGIN ATTEMPT === Email: {}", email);
        
        AppUser appUser = appUserRepository.findByEmail(email)
                .orElseThrow(() -> {
                    log.warn("LOGIN FAILED: User not found with email: {}", email);
                    return new UsernameNotFoundException("User not found with email: " + email);
                });
        
        log.info("User found: id={}, email={}, role={}, hasPassword={}", 
                appUser.getId(), appUser.getEmail(), appUser.getRole(), appUser.hasPassword());
        
        // Check if user has completed registration (has password set)
        if (!appUser.hasPassword()) {
            log.warn("LOGIN FAILED: User {} has no password set (registration not completed)", email);
            throw new UsernameNotFoundException("Registration not completed. Please check your email to complete registration.");
        }
        
        log.debug("Password hash present for user {}: {}", email, 
                appUser.getPasswordHash() != null ? appUser.getPasswordHash().substring(0, Math.min(20, appUser.getPasswordHash().length())) + "..." : "null");
        
        // Get the registration for tenant information
        Registration registration = appUser.getRegistration();
        log.info("User registration: id={}, registrationId={}, status={}, tenantCreationStatus={}", 
                registration.getId(), 
                registration.getRegistrationId(), 
                registration.getStatus(),
                registration.getTenantCreationStatus());
        
        // For non-SUPERADMIN users, check tenant creation status
        if (appUser.getRole() != UserRole.SUPERADMIN) {
            TenantCreationStatus tenantStatus = registration.getTenantCreationStatus();
            log.info("Checking tenant status for non-SUPERADMIN user: tenantStatus={}", tenantStatus);
            
            // If tenant schema is not yet completed, prevent login
            if (tenantStatus == null || tenantStatus == TenantCreationStatus.INITIATED) {
                log.warn("LOGIN FAILED: Tenant schema not ready for user {}. Status: {}", email, tenantStatus);
                throw new UsernameNotFoundException(
                        "Your application is being prepared. Please try to login a few minutes later.");
            }
            
            // If tenant creation failed, prevent login with appropriate message
            if (tenantStatus == TenantCreationStatus.FAILED) {
                log.error("LOGIN FAILED: Tenant creation failed for user {}. Status: {}", email, tenantStatus);
                throw new UsernameNotFoundException(
                        "There was an issue setting up your account. Please contact support.");
            }
        } else {
            log.info("User {} is SUPERADMIN, skipping tenant status check", email);
        }
        
        // Determine the tenant ID
        // For SUPERADMIN users, tenant ID is null (they use the default schema)
        String tenantId = appUser.getRole() == UserRole.SUPERADMIN 
                ? null 
                : registration.getRegistrationId();
        
        log.info("Creating TenantUserDetails for user {}: tenantId={}, role={}", email, tenantId, appUser.getRole());
        
        // Create and return TenantUserDetails with all necessary information
        TenantUserDetails userDetails = new TenantUserDetails(
                appUser.getEmail(),
                appUser.getPasswordHash(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name())),
                tenantId,
                appUser.getId(),
                appUser.getFullName(),
                appUser.getRole()
        );
        
        log.info("=== LOGIN VALIDATION PASSED === User: {}, TenantId: {}, Role: {}", 
                email, tenantId, appUser.getRole());
        
        return userDetails;
    }
}