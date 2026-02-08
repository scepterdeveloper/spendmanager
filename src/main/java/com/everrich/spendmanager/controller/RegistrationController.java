package com.everrich.spendmanager.controller;

import com.everrich.spendmanager.entities.AppUser;
import com.everrich.spendmanager.entities.Registration;
import com.everrich.spendmanager.service.RegistrationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.util.Optional;

@Controller
@RequestMapping("/register")
public class RegistrationController {

    private static final Logger logger = LoggerFactory.getLogger(RegistrationController.class);

    private final RegistrationService registrationService;

    public RegistrationController(RegistrationService registrationService) {
        this.registrationService = registrationService;
    }

    @GetMapping
    public String showRegistrationForm() {
        return "register";
    }

    @PostMapping
    public String processRegistration(
            @RequestParam String firstName,
            @RequestParam String lastName,
            @RequestParam String email,
            RedirectAttributes redirectAttributes) {
        
        try {
            registrationService.initiateRegistration(firstName, lastName, email);
            redirectAttributes.addFlashAttribute("success", true);
            redirectAttributes.addFlashAttribute("message", 
                "Registration initiated! Please check your email to complete the registration process.");
            return "redirect:/register/success";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/register";
        } catch (Exception e) {
            logger.error("Error during registration: ", e);
            redirectAttributes.addFlashAttribute("error", 
                "An error occurred during registration. Please try again.");
            return "redirect:/register";
        }
    }

    @GetMapping("/success")
    public String showRegistrationSuccess() {
        return "register-success";
    }

    @GetMapping("/complete/{registrationId}")
    public String showCompleteRegistrationForm(@PathVariable String registrationId, Model model) {
        Optional<Registration> registration = registrationService.findOpenRegistration(registrationId);
        
        if (registration.isEmpty()) {
            model.addAttribute("error", "This registration link is invalid or has already been used.");
            return "register-complete";
        }

        Registration reg = registration.get();
        AppUser user = reg.getUsers().isEmpty() ? null : reg.getUsers().get(0);
        
        model.addAttribute("registrationId", registrationId);
        model.addAttribute("valid", true);
        if (user != null) {
            model.addAttribute("userEmail", user.getEmail());
            model.addAttribute("userName", user.getFirstName());
        }
        
        return "register-complete";
    }

    @PostMapping("/complete/{registrationId}")
    public String processCompleteRegistration(
            @PathVariable String registrationId,
            @RequestParam String password,
            @RequestParam String confirmPassword,
            RedirectAttributes redirectAttributes) {
        
        // Validate passwords match
        if (!password.equals(confirmPassword)) {
            redirectAttributes.addFlashAttribute("error", "Passwords do not match.");
            return "redirect:/register/complete/" + registrationId;
        }

        // Validate password strength (minimum 8 characters)
        if (password.length() < 8) {
            redirectAttributes.addFlashAttribute("error", "Password must be at least 8 characters long.");
            return "redirect:/register/complete/" + registrationId;
        }

        try {
            registrationService.completeRegistration(registrationId, password);
            redirectAttributes.addFlashAttribute("registrationComplete", true);
            return "redirect:/login";
        } catch (IllegalArgumentException e) {
            redirectAttributes.addFlashAttribute("error", e.getMessage());
            return "redirect:/register/complete/" + registrationId;
        } catch (Exception e) {
            logger.error("Error completing registration: ", e);
            redirectAttributes.addFlashAttribute("error", 
                "An error occurred while completing registration. Please try again.");
            return "redirect:/register/complete/" + registrationId;
        }
    }
}