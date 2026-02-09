package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.TenantCreationStatus;
import com.everrich.spendmanager.entities.UserRole;
import com.everrich.spendmanager.multitenancy.TenantUserDetails;
import com.everrich.spendmanager.repository.AppUserRepository;
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

    private final AppUserRepository appUserRepository;

    public CustomUserDetailsService(AppUserRepository appUserRepository) {
        this.appUserRepository = appUserRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String email) throws UsernameNotFoundException {
        AppUser appUser = appUserRepository.findByEmail(email)
                .orElseThrow(() -> new UsernameNotFoundException("User not found with email: " + email));
        
        // Check if user has completed registration (has password set)
        if (!appUser.hasPassword()) {
            throw new UsernameNotFoundException("Registration not completed. Please check your email to complete registration.");
        }
        
        // Get the registration for tenant information
        Registration registration = appUser.getRegistration();
        
        // For non-SUPERADMIN users, check tenant creation status
        if (appUser.getRole() != UserRole.SUPERADMIN) {
            TenantCreationStatus tenantStatus = registration.getTenantCreationStatus();
            
            // If tenant schema is not yet completed, prevent login
            if (tenantStatus == null || tenantStatus == TenantCreationStatus.INITIATED) {
                throw new UsernameNotFoundException(
                        "Your application is being prepared. Please try to login after some minutes.");
            }
            
            // If tenant creation failed, prevent login with appropriate message
            if (tenantStatus == TenantCreationStatus.FAILED) {
                throw new UsernameNotFoundException(
                        "There was an issue setting up your account. Please contact support.");
            }
        }
        
        // Determine the tenant ID
        // For SUPERADMIN users, tenant ID is null (they use the default schema)
        String tenantId = appUser.getRole() == UserRole.SUPERADMIN 
                ? null 
                : registration.getRegistrationId();
        
        // Create and return TenantUserDetails with all necessary information
        return new TenantUserDetails(
                appUser.getEmail(),
                appUser.getPasswordHash(),
                Collections.singletonList(new SimpleGrantedAuthority("ROLE_" + appUser.getRole().name())),
                tenantId,
                appUser.getId(),
                appUser.getFullName(),
                appUser.getRole()
        );
    }
}