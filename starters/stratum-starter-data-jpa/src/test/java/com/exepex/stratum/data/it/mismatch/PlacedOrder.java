package com.exepex.stratum.data.it.mismatch;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/** An order with a customer column that no migration added to the {@code orders} table. */
@Entity
@Table(name = "orders")
@Getter
public class PlacedOrder {

  /** The order's number. */
  @Id private @Nullable Long id;

  /** Where the order is in its life, such as {@code PLACED}. */
  private @Nullable String status;

  /** The customer who placed the order. */
  private @Nullable Long customerId;
}
