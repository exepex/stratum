package com.exepex.stratum.data.it.orders;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import org.jspecify.annotations.Nullable;

/** An order as the {@code orders} table stores it. */
@Entity
@Table(name = "orders")
@Getter
public class PlacedOrder {

  /** The order's number. */
  @Id private @Nullable Long id;

  /** Where the order is in its life, such as {@code PLACED}. */
  private @Nullable String status;
}
