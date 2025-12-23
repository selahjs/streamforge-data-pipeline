package com.example.rest_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  // Inject the custom filter we just created
  private final JwtAuthenticationFilter jwtAuthFilter;
  private final HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository;


  // Inject the custom UserDetailsService (which is also the CustomUserDetailsService)
  private final UserDetailsService userDetailsService;

  private final OAuth2LoginSuccessHandler oauth2SuccessHandler;

  // Inject the AuthenticationManager configuration utility
  // private final AuthenticationConfiguration authConfiguration; // Not needed if using the @Bean approach

  public SecurityConfig(
          JwtAuthenticationFilter jwtAuthFilter, HttpCookieOAuth2AuthorizationRequestRepository cookieAuthorizationRequestRepository,
          UserDetailsService userDetailsService, OAuth2LoginSuccessHandler oauth2SuccessHandler // Injecting by interface
  ) {
    this.jwtAuthFilter = jwtAuthFilter;
    this.cookieAuthorizationRequestRepository = cookieAuthorizationRequestRepository;
    this.userDetailsService = userDetailsService;
    this.oauth2SuccessHandler = oauth2SuccessHandler;
  }
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder();
  }

  @Bean
  public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
    return config.getAuthenticationManager();
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

    http
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth
                    // Public endpoints for registration and login
                    .requestMatchers("/api/v1/auth/**").permitAll()
                    // Any other request requires authentication
                    .anyRequest().authenticated()
            )
            .sessionManagement(session -> session
                    // Tell Spring to NOT create session state (STATELSS is required for JWTs)
                    .sessionCreationPolicy(SessionCreationPolicy.STATELESS)
            )
            .authenticationProvider(authenticationProvider()) // Set the custom provider
            // Add the custom JWT filter BEFORE the standard Spring filter
            .oauth2Login(oauth2 -> oauth2
                    .authorizationEndpoint(authorization -> authorization
                            .baseUri("/oauth2/authorization")
                            .authorizationRequestRepository(cookieAuthorizationRequestRepository) // USE COOKIE STORAGE
                    )
                    .redirectionEndpoint(redirection -> redirection.baseUri("/login/oauth2/code/*"))
                    .successHandler(oauth2SuccessHandler)
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

    return http.build();
  }

  /**
   * Define the Authentication Provider (similar to the logic moved out of the old AuthenticationManager)
   */
  @Bean
  public DaoAuthenticationProvider authenticationProvider() {
    DaoAuthenticationProvider authProvider = new DaoAuthenticationProvider(userDetailsService);
    authProvider.setPasswordEncoder(passwordEncoder());
    return authProvider;
  }
}