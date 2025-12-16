package com.example.rest_service.repository;

import com.example.rest_service.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByUsername(String username);

  Optional<List<User>> getAllByEnabled(boolean enabled);

  boolean existsByUsername(String username);

  boolean existsByEmail(String email);
}
