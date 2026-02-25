package com.everrich.spendmanager.config;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Custom authentication failure handler that preserves the actual error message
 * from authentication exceptions and passes it to the login page.
 */
@Component
public class CustomAuthenticationFailureHandler extends SimpleUrlAuthenticationFailureHandler {

    @Override
    public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
                                        AuthenticationException exception) throws IOException, ServletException {
        
        String errorMessage = exception.getMessage();
        
        // URL encode the error message to safely pass it as a query parameter
        String encodedMessage = URLEncoder.encode(errorMessage, StandardCharsets.UTF_8);
        
        // Redirect to login page with the actual error message
        String redirectUrl = "/login?error=true&message=" + encodedMessage;
        
        getRedirectStrategy().sendRedirect(request, response, redirectUrl);
    }
}