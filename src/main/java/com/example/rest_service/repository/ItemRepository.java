package com.example.rest_service.repository;

import com.example.rest_service.model.Item;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.Set;

public interface ItemRepository extends JpaRepository<Item, Long> {

  //For 200,000 rows, this means 200,000 separate network calls to the database, which will be incredibly slow.
  //instead use the findAllExternalIds to load all ids once and then using in memory checks
  boolean existsByExternalId(String externalId);

  //New method to fetch all existing IDs for efficient in-memory validation
  @Query("SELECT i.externalId FROM Item i")
  Set<String> findAllExternalIds(); // <-- Fetches all IDs in one query
}
