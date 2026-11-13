package com.exepex.stratum.data.it.failures;

import static org.awaitility.Awaitility.await;

import jakarta.persistence.EntityManager;
import java.time.Duration;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Runs each kind of database work that fails, as a service's own code would. */
@Service
@RequiredArgsConstructor
public class FailingAccounts {

  /** The email address both new customers try to use. */
  static final String EMAIL = "ada@example.com";

  /** The account every test starts with, at version 0. */
  static final long ACCOUNT_ID = 1;

  /** The second account, which the deadlock locks in the other order. */
  static final long OTHER_ACCOUNT_ID = 2;

  /** How long the transaction in {@link #outliveTransaction()} may take, in seconds. */
  private static final int TRANSACTION_SECONDS = 1;

  /** How long {@link #outliveTransaction()} works: a little more than its transaction may take. */
  private static final Duration PAST_TRANSACTION_LIMIT =
      Duration.ofSeconds(TRANSACTION_SECONDS).plusMillis(200);

  /** Writes a row as another transaction would, through the same connection. */
  private static final String BUMP_VERSION =
      "UPDATE accounts SET version = version + 1 WHERE id = ?";

  /** Stores customers through Spring Data JPA. */
  private final CustomerRepository customers;

  /** Stores accounts through Spring Data JPA. */
  private final AccountRepository accounts;

  /** Runs SQL directly. */
  private final JdbcTemplate jdbcTemplate;

  /** The transaction's entity manager, for an explicit flush. */
  private final EntityManager entityManager;

  /** Stores two customers with the same email address through JPA. */
  @Transactional
  public void addDuplicateCustomer() {
    customers.saveAndFlush(new Customer(1L, EMAIL));
    customers.saveAndFlush(new Customer(2L, EMAIL));
  }

  /** Inserts two customers with the same email address in one JDBC batch. */
  public void addDuplicateCustomersInBatch() {
    jdbcTemplate.batchUpdate(
        "INSERT INTO customers (id, email) VALUES (?, ?)",
        List.of(new Object[] {1L, EMAIL}, new Object[] {2L, EMAIL}));
  }

  /** Changes an account whose row changed after it was read; Hibernate notices at commit. */
  @Transactional
  public void depositAfterChangeAtCommit() {
    var account = accounts.findById(ACCOUNT_ID).orElseThrow();
    jdbcTemplate.update(BUMP_VERSION, ACCOUNT_ID);
    account.deposit(1);
  }

  /** Changes an account whose row changed after it was read, and flushes it explicitly. */
  @Transactional
  public void depositAfterChangeWithFlush() {
    var account = accounts.findById(ACCOUNT_ID).orElseThrow();
    jdbcTemplate.update(BUMP_VERSION, ACCOUNT_ID);
    account.deposit(1);
    entityManager.flush();
  }

  /**
   * Counts the customers, in a transaction that needs a connection from the pool.
   *
   * @return how many customers there are
   */
  @Transactional(readOnly = true)
  public long countCustomers() {
    return customers.count();
  }

  /** Runs a statement that the database's own statement_timeout cancels. */
  @Transactional
  public void runPastDatabaseTimeout() {
    jdbcTemplate.execute("SET LOCAL statement_timeout = '100ms'");
    jdbcTemplate.queryForObject("SELECT pg_sleep(2)::text", String.class);
  }

  /** Locks an account that another connection holds, with a short lock_timeout. */
  @Transactional
  public void lockHeldAccount() {
    jdbcTemplate.execute("SET LOCAL lock_timeout = '200ms'");
    jdbcTemplate.queryForList("SELECT id FROM accounts WHERE id = ? FOR UPDATE", ACCOUNT_ID);
  }

  /**
   * Locks the other account, then the one another connection holds, which deadlocks once that
   * connection asks for the other account. PostgreSQL checks a waiting session for a deadlock once,
   * after its deadlock_timeout: this side's second leaves the other connection time to close the
   * cycle, and comes long before that connection's own check, so this side is the one it ends.
   */
  @Transactional
  public void lockInOppositeOrder() {
    jdbcTemplate.execute("SET LOCAL deadlock_timeout = '1s'");
    jdbcTemplate.update("UPDATE accounts SET balance = balance WHERE id = ?", OTHER_ACCOUNT_ID);
    jdbcTemplate.update("UPDATE accounts SET balance = balance WHERE id = ?", ACCOUNT_ID);
  }

  /**
   * Waits past the transaction's time limit before its first statement, which Spring then refuses
   * to run.
   */
  @Transactional(timeout = TRANSACTION_SECONDS)
  public void outliveTransaction() {
    // The wait is the slow work under test: the transaction must run out before the statement.
    await()
        .pollDelay(PAST_TRANSACTION_LIMIT)
        .atMost(PAST_TRANSACTION_LIMIT.multipliedBy(2))
        .until(() -> true);
    jdbcTemplate.queryForObject("SELECT 1", Integer.class);
  }
}
