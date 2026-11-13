package com.exepex.stratum.data.it.failures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** A customer, whose email address the {@code customers} table keeps unique. */
@Entity
@Table(name = "customers")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
public class Customer {

  /** The customer's number. */
  @Id private @Nullable Long id;

  /** The customer's email address, unique among customers. */
  private @Nullable String email;
}
