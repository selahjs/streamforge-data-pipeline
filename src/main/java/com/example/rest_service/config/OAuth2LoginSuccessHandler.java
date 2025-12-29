package com.example.rest_service.config;

import com.example.rest_service.dto.AuthenticationResponse;
import com.example.rest_service.service.AuthenticationService;
import com.example.rest_service.service.JwtService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

  // Inject the URL from properties
  @Value("${application.frontend.url}")
  private String frontendUrl;

  private final AuthenticationService authService; // Inject the service

  public OAuth2LoginSuccessHandler( @Lazy AuthenticationService authService) {
    this.authService = authService;
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
                                      Authentication authentication) throws IOException {

    // 1. Extract Google user details
    DefaultOidcUser oidcUser = (DefaultOidcUser) authentication.getPrincipal();
    String email = oidcUser.getEmail();
    String name = oidcUser.getFullName();

    // 2. Call the service to find/save user and get the JWT
    // This resolves the "expected UserDetails" warning!
    AuthenticationResponse authResponse = authService.processOAuthPostLogin(email, name);

    // 3. Redirect to your frontend (React/Vue/etc.) with the token in the URL
    // Your frontend should grab this token from the URL and store it in LocalStorage
    // OAuth2LoginSuccessHandler.java
    String targetUrl = String.format(
            "%s/oauth2/redirect?token=%s&username=%s",
            frontendUrl,
            authResponse.token(),
            authResponse.username() // Assuming your DTO has this
    );
    getRedirectStrategy().sendRedirect(request, response, targetUrl);
  }
}