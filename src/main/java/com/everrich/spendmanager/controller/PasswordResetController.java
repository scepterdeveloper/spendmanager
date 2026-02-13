package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.PasswordReset;
import com.everrich.spendmanager.service.PasswordResetService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.Optional;

/**
 * Controller for handling password reset operations.
 */
@Controller
@RequestMapping("/password-reset")
public class PasswordResetController {

    private static final Logger logger = LoggerFactory.getLogger(PasswordResetController.class);

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /**
     * Display the forgot password form.
     */
    @GetMapping("/forgot")
    public String showForgotPasswordForm() {
        return "forgot-password";
    }

    /**
     * Handle the forgot password form submission.
     * Send a password reset email if the email exists.
     */
    @PostMapping("/forgot")
    public String processForgotPassword(@RequestParam String email, Model model) {
        logger.info("Processing forgot password request for email: {}", email);
        
        // Always return success message to prevent email enumeration
        passwordResetService.initiatePasswordReset(email);
        
        model.addAttribute("emailSent", true);
        model.addAttribute("email", email);
        return "forgot-password";
    }

    /**
     * Display the password reset form when user clicks the link from email.
     */
    @GetMapping("/reset/{token}")
    public String showResetPasswordForm(@PathVariable String token, Model model) {
        logger.info("Password reset form requested for token: {}", token);
        
        Optional<PasswordReset> resetOpt = passwordResetService.validateToken(token);
        
        if (resetOpt.isEmpty()) {
            model.addAttribute("valid", false);
            model.addAttribute("error", "This password reset link is invalid or has expired. Please request a new one.");
            return "password-reset";
        }
        
        PasswordReset passwordReset = resetOpt.get();
        AppUser user = passwordReset.getUser();
        
        model.addAttribute("valid", true);
        model.addAttribute("token", token);
        model.addAttribute("userName", user.getFirstName());
        
        return "password-reset";
    }

    /**
     * Process the password reset form submission.
     */
    @PostMapping("/reset/{token}")
    public String processResetPassword(
            @PathVariable String token,
            @RequestParam String password,
            @RequestParam String confirmPassword,
            Model model) {
        
        logger.info("Processing password reset for token: {}", token);
        
        // Validate token again
        Optional<PasswordReset> resetOpt = passwordResetService.validateToken(token);
        
        if (resetOpt.isEmpty()) {
            model.addAttribute("valid", false);
            model.addAttribute("error", "This password reset link is invalid or has expired. Please request a new one.");
            return "password-reset";
        }
        
        // Validate passwords match
        if (!password.equals(confirmPassword)) {
            model.addAttribute("valid", true);
            model.addAttribute("token", token);
            model.addAttribute("userName", resetOpt.get().getUser().getFirstName());
            model.addAttribute("error", "Passwords do not match. Please try again.");
            return "password-reset";
        }
        
        // Validate password length
        if (password.length() < 8) {
            model.addAttribute("valid", true);
            model.addAttribute("token", token);
            model.addAttribute("userName", resetOpt.get().getUser().getFirstName());
            model.addAttribute("error", "Password must be at least 8 characters long.");
            return "password-reset";
        }
        
        // Reset the password
        boolean success = passwordResetService.resetPassword(token, password);
        
        if (!success) {
            model.addAttribute("valid", false);
            model.addAttribute("error", "Failed to reset password. Please try again or request a new reset link.");
            return "password-reset";
        }
        
        logger.info("Password reset successful for token: {}", token);
        
        // Redirect to success page
        return "redirect:/password-reset/success";
    }

    /**
     * Display the password reset success page.
     */
    @GetMapping("/success")
    public String showResetSuccessPage() {
        return "password-reset-success";
    }
}