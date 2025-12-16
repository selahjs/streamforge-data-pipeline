package com.example.rest_service.config;

import com.example.rest_service.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

  private final JwtService jwtService;
  private final UserDetailsService userDetailsService;

  public JwtAuthenticationFilter(JwtService jwtService, UserDetailsService userDetailsService) {
    this.jwtService = jwtService;
    this.userDetailsService = userDetailsService;
  }

  @Override
  protected void doFilterInternal(
          HttpServletRequest request,
          HttpServletResponse response,
          FilterChain filterChain // List of other filters to execute
  ) throws ServletException, IOException {

    final String authHeader = request.getHeader("Authorization");
    final String jwt;
    final String username;

    // 1. Check for JWT existence and format
    if (authHeader == null || !authHeader.startsWith("Bearer ")) {
      filterChain.doFilter(request, response);
      return; // Exit and continue with the rest of the filters
    }

    // 2. Extract JWT
    jwt = authHeader.substring(7);
    username = jwtService.extractUsername(jwt);

    // 3. Validation and Context Setup
    // Check if user is extracted and they are NOT already authenticated (SecurityContextHolder == null)
    if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {

      // Load user from the database (using our CustomUserDetailsService)
      UserDetails userDetails = this.userDetailsService.loadUserByUsername(username);

      if (jwtService.isTokenValid(jwt, userDetails)) {
        // Token is valid, update the security context:
        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                userDetails,
                null, // Credentials should be null after authentication
                userDetails.getAuthorities()
        );
        authToken.setDetails(
                new WebAuthenticationDetailsSource().buildDetails(request)
        );

        // Final step: Update the SecurityContextHolder to mark the user as authenticated
        SecurityContextHolder.getContext().setAuthentication(authToken);
      }
    }

    // Pass the request to the next filter in the chain
    filterChain.doFilter(request, response);
  }
}