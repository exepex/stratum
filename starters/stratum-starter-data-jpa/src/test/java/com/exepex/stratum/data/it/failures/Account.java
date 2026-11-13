package com.exepex.stratum.data.it.failures;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.jspecify.annotations.Nullable;

/** An account whose row carries a version, so a change made meanwhile is noticed. */
@Entity
@Table(name = "accounts")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Account {

  /** The account's number. */
  @Id private @Nullable Long id;

  /** The balance, in cents. */
  private long balance;

  /** The version of the row, which Hibernate checks when it writes the row. */
  @Version private long version;

  /**
   * Adds money to the balance.
   *
   * @param cents how much, in cents
   */
  void deposit(long cents) {
    balance += cents;
  }
}
