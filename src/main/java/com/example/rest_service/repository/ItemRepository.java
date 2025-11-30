package com.example.rest_service.repository;

import com.example.rest_service.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ItemRepository extends JpaRepository<Item, Long> {
  boolean existsByExternalId(String externalId);
}
