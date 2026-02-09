package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.entities.RegistrationStatus;
import com.everrich.spendmanager.entities.UserRole;
import com.everrich.spendmanager.repository.AppUserRepository;
import com.everrich.spendmanager.repository.RegistrationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class RegistrationService {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationService.class);

    private final RegistrationRepository registrationRepository;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;

    public RegistrationService(RegistrationRepository registrationRepository,
                               AppUserRepository appUserRepository,
                               EmailService emailService,
                               PasswordEncoder passwordEncoder) {
        this.registrationRepository = registrationRepository;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public Registration initiateRegistration(String firstName, String lastName, String email) {
        // Check if email already exists
        if (appUserRepository.existsByEmail(email)) {
            throw new IllegalArgumentException("An account with this email already exists.");
        }

        // Create new registration
        Registration registration = new Registration(RegistrationStatus.OPEN);
        
        // Create new user with OWNER role
        AppUser user = new AppUser(firstName, lastName, email, UserRole.OWNER);
        registration.addUser(user);
        
        // First save to get the sequence-generated ID
        registration = registrationRepository.save(registration);
        
        // Generate the 6-digit registration ID from the sequence-generated primary key
        registration.generateRegistrationId();
        
        // Save again to persist the registrationId
        registration = registrationRepository.save(registration);
        
        logger.info("Registration initiated for email: {}, Registration ID: {}", email, registration.getRegistrationId());
        
        // Send registration email
        emailService.sendRegistrationEmail(registration, user);
        
        return registration;
    }

    public Optional<Registration> findOpenRegistration(String registrationId) {
        return registrationRepository.findByRegistrationIdAndStatus(registrationId, RegistrationStatus.OPEN);
    }

    public Optional<Registration> findRegistration(String registrationId) {
        return registrationRepository.findByRegistrationId(registrationId);
    }

    @Transactional
    public void completeRegistration(String registrationId, String password) {
        Registration registration = registrationRepository.findByRegistrationIdAndStatus(registrationId, RegistrationStatus.OPEN)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired registration link."));

        // Encode and set password for all users in this registration
        String encodedPassword = passwordEncoder.encode(password);
        for (AppUser user : registration.getUsers()) {
            user.setPassword(encodedPassword);
            appUserRepository.save(user);
        }

        // Mark registration as complete
        registration.complete();
        registrationRepository.save(registration);

        logger.info("Registration completed for Registration ID: {}", registrationId);
    }

    public boolean isEmailRegistered(String email) {
        return appUserRepository.existsByEmail(email);
    }

    public boolean isRegistrationPending(String email) {
        Optional<AppUser> user = appUserRepository.findByEmail(email);
        return user.isPresent() && !user.get().hasPassword();
    }
}