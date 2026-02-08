package com.everrich.spendmanager.service;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.Registration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
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

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
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
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(fromEmail);
            message.setTo(user.getEmail());
            message.setSubject(subject);
            message.setText(body);
            
            mailSender.send(message);
            logger.info("Registration email sent successfully to: {}", user.getEmail());
        } catch (Exception e) {
            logger.error("Failed to send registration email to: {}. Error: {}", user.getEmail(), e.getMessage());
        }
    }
}
