package com.example.rest_service.model;
import jakarta.persistence.*;
import java.time.LocalDate;

@Entity
@Table(name = "items", indexes = {
        @Index(columnList = "externalId")
})
public class Item {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable=false, unique=true)
  private String externalId; // e.g., product code

  @Column(nullable=false)
  private String name;

  private Integer quantity;

  private LocalDate expiryDate;

  public Long getId() {
    return id;
  }
  public void setId(Long id) {
    this.id = id;
  }
  public String getExternalId() {
    return externalId;
  }
  public void setExternalId(String externalId) {
    this.externalId = externalId;
  }
  public String getName() {
    return name;
  }
  public void setName(String name) {
    this.name = name;
  }
  public Integer getQuantity() {
    return quantity;
  }
  public void setQuantity(Integer quantity) {
    this.quantity = quantity;
  }
  public LocalDate getExpiryDate() {
    return expiryDate;
  }
  public void setExpiryDate(LocalDate expiryDate) {
    this.expiryDate = expiryDate;
  }
}
