package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.PasswordReset;
import com.everrich.spendmanager.entities.Registration;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;

    @Value("${app.mail.from:noreply@everrich.app}")
    private String fromEmail;

    @Value("${app.base-url:http://localhost:8080}")
    private String baseUrl;
    
    @Value("${app.mail.enabled:true}")
    private boolean emailEnabled;
    
    @Value("${spring.mail.host:not-configured}")
    private String mailHost;
    
    @Value("${spring.mail.port:0}")
    private int mailPort;
    
    @Value("${spring.mail.username:not-configured}")
    private String mailUsername;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }
    
    @PostConstruct
    public void logMailConfiguration() {
        logger.info("=== Email Service Configuration ===");
        logger.info("Email enabled: {}", emailEnabled);
        logger.info("From email: {}", fromEmail);
        logger.info("Base URL: {}", baseUrl);
        logger.info("SMTP Host: {}", mailHost);
        logger.info("SMTP Port: {}", mailPort);
        logger.info("SMTP Username: {}", maskEmail(mailUsername));
        logger.info("SMTP Username length: {}", mailUsername != null ? mailUsername.length() : 0);
        logger.info("SMTP Username is empty: {}", mailUsername == null || mailUsername.isEmpty() || "not-configured".equals(mailUsername));
        
        // Log additional details from JavaMailSenderImpl if available
        if (mailSender instanceof JavaMailSenderImpl) {
            JavaMailSenderImpl impl = (JavaMailSenderImpl) mailSender;
            logger.info("JavaMailSender configured host: {}", impl.getHost());
            logger.info("JavaMailSender configured port: {}", impl.getPort());
            logger.info("JavaMailSender configured username: {}", maskEmail(impl.getUsername()));
            logger.info("JavaMailSender password configured: {}", impl.getPassword() != null && !impl.getPassword().isEmpty());
            logger.info("JavaMailSender password length: {}", impl.getPassword() != null ? impl.getPassword().length() : 0);
            
            if (impl.getJavaMailProperties() != null) {
                logger.info("JavaMail properties:");
                impl.getJavaMailProperties().forEach((key, value) -> 
                    logger.info("  {} = {}", key, value)
                );
            }
        }
        logger.info("=== End Email Configuration ===");
    }
    
    private String maskEmail(String email) {
        if (email == null || email.isEmpty() || "not-configured".equals(email)) {
            return email;
        }
        int atIndex = email.indexOf('@');
        if (atIndex > 2) {
            return email.substring(0, 2) + "***" + email.substring(atIndex);
        }
        return "***" + (atIndex >= 0 ? email.substring(atIndex) : "");
    }

    public void sendRegistrationEmail(Registration registration, AppUser user) {
        String completeRegistrationUrl = baseUrl + "/register/complete/" + registration.getRegistrationId();
        
        // Always log the registration URL for development/debugging purposes
        logger.info("Complete registration URL for {}: {}", user.getEmail(), completeRegistrationUrl);
        
        // If email is disabled, just log and return
        if (!emailEnabled) {
            logger.info("Email sending is disabled. Skipping email to: {}", user.getEmail());
            return;
        }
        
        String subject = "Welcome to EverRich Spend Manager - Complete Your Registration";
        String body = String.format(
            "Dear %s,\n\n" +
            "Thank you for registering with EverRich Spend Manager!\n\n" +
            "To complete your registration and set up your password, please click the link below:\n\n" +
            "%s\n\n" +
            "If you did not create this account, please ignore this email.\n\n" +
            "Best regards,\n" +
            "The EverRich Team",
            user.getFirstName(),
            completeRegistrationUrl
        );

        try {
            logger.info("Preparing to send registration email to: {}", user.getEmail());
            logger.info("Using from address: {}", fromEmail);
            
            // Log current mail sender configuration before sending
            if (mailSender instanceof JavaMailSenderImpl) {
                JavaMailSenderImpl impl = (JavaMailSenderImpl) mailSender;
                logger.debug("Current SMTP config - Host: {}, Port: {}, Username: {}", 
                    impl.getHost(), impl.getPort(), maskEmail(impl.getUsername()));
            }
            
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(user.getEmail());
            message.setSubject(subject);
            message.setText(body);
            
            logger.info("Attempting to send email via SMTP...");
            mailSender.send(message);
            logger.info("Registration email sent successfully to: {}", user.getEmail());
            
        } catch (MailAuthenticationException e) {
            logger.error("=== SMTP Authentication Failed ===");
            logger.error("Failed to authenticate with SMTP server");
            logger.error("Recipient: {}", user.getEmail());
            logger.error("SMTP Host: {}", mailHost);
            logger.error("SMTP Port: {}", mailPort);
            logger.error("SMTP Username: {}", maskEmail(mailUsername));
            logger.error("Error message: {}", e.getMessage());
            if (e.getCause() != null) {
                logger.error("Cause: {}", e.getCause().getMessage());
                if (e.getCause().getCause() != null) {
                    logger.error("Root cause: {}", e.getCause().getCause().getMessage());
                }
            }
            logger.error("Full stack trace:", e);
            logger.error("=== End Authentication Error ===");
            logger.error("");
            logger.error("TROUBLESHOOTING TIPS:");
            logger.error("1. For Gmail, ensure you're using an App Password (not your regular password)");
            logger.error("2. Create an App Password at: https://myaccount.google.com/apppasswords");
            logger.error("3. Verify MAIL_USERNAME and MAIL_PASSWORD environment variables are set correctly");
            logger.error("4. Ensure 2-Step Verification is enabled on your Google account");

        } catch (MailSendException e) {
            logger.error("=== SMTP Send Failed ===");
            logger.error("Failed to send email to: {}", user.getEmail());
            logger.error("Error message: {}", e.getMessage());
            if (e.getFailedMessages() != null && !e.getFailedMessages().isEmpty()) {
                e.getFailedMessages().forEach((msg, ex) -> {
                    logger.error("Failed message error: {}", ex.getMessage());
                });
            }
            logger.error("Full stack trace:", e);
            logger.error("=== End Send Error ===");
            
        } catch (Exception e) {
            logger.error("=== Unexpected Email Error ===");
            logger.error("Failed to send registration email to: {}", user.getEmail());
            logger.error("Exception type: {}", e.getClass().getName());
            logger.error("Error message: {}", e.getMessage());
            if (e.getCause() != null) {
                logger.error("Cause: {}", e.getCause().getMessage());
            }
            logger.error("Full stack trace:", e);
            logger.error("=== End Unexpected Error ===");
        }
    }

    /**
     * Send a password reset email to a user with a reset link.
     *
     * @param user the user who requested the password reset
     * @param passwordReset the password reset entity containing the token
     */
    public void sendPasswordResetEmail(AppUser user, PasswordReset passwordReset) {
        String resetUrl = baseUrl + "/password-reset/reset/" + passwordReset.getResetToken();

        // Always log the reset URL for development/debugging purposes
        logger.info("Password reset URL for {}: {}", user.getEmail(), resetUrl);

        // If email is disabled, just log and return
        if (!emailEnabled) {
            logger.info("Email sending is disabled. Skipping password reset email to: {}", user.getEmail());
            return;
        }

        String subject = "Reset Your Password - EverRich Spend Manager";
        String body = String.format(
            "Dear %s,\n\n" +
            "We received a request to reset the password for your EverRich Spend Manager account.\n\n" +
            "To reset your password, please click the link below:\n\n" +
            "%s\n\n" +
            "This link will expire in 24 hours for security reasons.\n\n" +
            "If you did not request a password reset, please ignore this email. Your password will remain unchanged.\n\n" +
            "Best regards,\n" +
            "The EverRich Team",
            user.getFirstName(),
            resetUrl
        );

        try {
            logger.info("Preparing to send password reset email to: {}", user.getEmail());

            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(user.getEmail());
            message.setSubject(subject);
            message.setText(body);

            logger.info("Attempting to send password reset email via SMTP...");
            mailSender.send(message);
            logger.info("Password reset email sent successfully to: {}", user.getEmail());

        } catch (MailAuthenticationException e) {
            logger.error("SMTP Authentication Failed for password reset email to: {}", user.getEmail());
            logger.error("Error message: {}", e.getMessage());

        } catch (MailSendException e) {
            logger.error("Failed to send password reset email to: {}", user.getEmail());
            logger.error("Error message: {}", e.getMessage());

        } catch (Exception e) {
            logger.error("Unexpected error sending password reset email to: {}", user.getEmail());
            logger.error("Exception type: {}", e.getClass().getName());
            logger.error("Error message: {}", e.getMessage());
        }
    }
}
