package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.PasswordReset;
import com.everrich.spendmanager.repository.AppUserRepository;
import com.everrich.spendmanager.repository.PasswordResetRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Service for handling password reset operations.
 */
@Service
public class PasswordResetService {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetService.class);
    
    private final PasswordResetRepository passwordResetRepository;
    private final AppUserRepository appUserRepository;
    private final EmailService emailService;
    private final PasswordEncoder passwordEncoder;
    
    @Value("${app.password-reset.expiration-hours:24}")
    private int tokenExpirationHours;

    public PasswordResetService(
            PasswordResetRepository passwordResetRepository,
            AppUserRepository appUserRepository,
            EmailService emailService,
            PasswordEncoder passwordEncoder) {
        this.passwordResetRepository = passwordResetRepository;
        this.appUserRepository = appUserRepository;
        this.emailService = emailService;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * Initiate a password reset for a user by their email.
     * Creates a new token and sends a password reset email.
     * 
     * @param email the user's email address
     * @return true if the email was found and reset email was sent, false otherwise
     */
    @Transactional
    public boolean initiatePasswordReset(String email) {
        logger.info("Password reset requested for email: {}", email);
        
        Optional<AppUser> userOpt = appUserRepository.findByEmail(email.toLowerCase().trim());
        
        if (userOpt.isEmpty()) {
            logger.warn("Password reset requested for non-existent email: {}", email);
            // Return true to prevent email enumeration attacks
            // We don't want attackers to know if an email exists or not
            return true;
        }
        
        AppUser user = userOpt.get();
        
        // Check if user has completed registration (has a password)
        if (!user.hasPassword()) {
            logger.warn("Password reset requested for user who hasn't completed registration: {}", email);
            // Still return true to prevent enumeration
            return true;
        }
        
        // Invalidate any existing unused tokens for this user
        passwordResetRepository.invalidateAllTokensForUser(user, LocalDateTime.now());
        
        // Create a new password reset token
        PasswordReset passwordReset = new PasswordReset(user, tokenExpirationHours);
        passwordResetRepository.save(passwordReset);
        
        logger.info("Created password reset token for user: {}, expires at: {}", 
            user.getEmail(), passwordReset.getExpiresAt());
        
        // Send the password reset email
        emailService.sendPasswordResetEmail(user, passwordReset);
        
        return true;
    }

    /**
     * Validate a password reset token.
     * 
     * @param token the reset token
     * @return the PasswordReset entity if valid, empty otherwise
     */
    @Transactional(readOnly = true)
    public Optional<PasswordReset> validateToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return Optional.empty();
        }
        
        Optional<PasswordReset> resetOpt = passwordResetRepository.findByResetToken(token);
        
        if (resetOpt.isEmpty()) {
            logger.warn("Invalid password reset token attempted: {}", token);
            return Optional.empty();
        }
        
        PasswordReset passwordReset = resetOpt.get();
        
        if (!passwordReset.isValid()) {
            logger.warn("Expired or already used password reset token: {}", token);
            return Optional.empty();
        }
        
        return Optional.of(passwordReset);
    }

    /**
     * Complete the password reset by setting the new password.
     * 
     * @param token the reset token
     * @param newPassword the new password
     * @return true if the password was successfully changed, false otherwise
     */
    @Transactional
    public boolean resetPassword(String token, String newPassword) {
        Optional<PasswordReset> resetOpt = validateToken(token);
        
        if (resetOpt.isEmpty()) {
            logger.error("Attempted to reset password with invalid token: {}", token);
            return false;
        }
        
        PasswordReset passwordReset = resetOpt.get();
        AppUser user = passwordReset.getUser();
        
        // Update the user's password
        String encodedPassword = passwordEncoder.encode(newPassword);
        user.setPassword(encodedPassword);
        appUserRepository.save(user);
        
        // Mark the token as used
        passwordReset.markAsUsed();
        passwordResetRepository.save(passwordReset);
        
        logger.info("Password successfully reset for user: {}", user.getEmail());
        
        return true;
    }

    /**
     * Clean up expired tokens from the database.
     * This should be called periodically (e.g., via a scheduled task).
     * 
     * @return the number of tokens deleted
     */
    @Transactional
    public int cleanupExpiredTokens() {
        int deleted = passwordResetRepository.deleteExpiredTokens(LocalDateTime.now());
        if (deleted > 0) {
            logger.info("Cleaned up {} expired password reset tokens", deleted);
        }
        return deleted;
    }
}