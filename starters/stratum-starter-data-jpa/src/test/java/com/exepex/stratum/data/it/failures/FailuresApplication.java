package com.exepex.stratum.data.it.failures;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A service with one endpoint for each way a request can fail because of the database. */
@SpringBootApplication
public class FailuresApplication {

  /** Stores two customers with the same email address through JPA. */
  public static final String DUPLICATE = "/failures/duplicate";

  /** Inserts two customers with the same email address in one JDBC batch. */
  public static final String DUPLICATE_IN_BATCH = "/failures/duplicate-batch";

  /** Changes an account changed meanwhile, noticed at commit. */
  public static final String STALE_AT_COMMIT = "/failures/stale-commit";

  /** Changes an account changed meanwhile, noticed by an explicit flush. */
  public static final String STALE_AT_FLUSH = "/failures/stale-flush";

  /** Needs a pooled connection. */
  public static final String POOLED = "/failures/pooled";

  /** Runs past the database's own statement_timeout. */
  public static final String DATABASE_TIMEOUT = "/failures/database-timeout";

  /** Waits for a lock past lock_timeout. */
  public static final String LOCK_TIMEOUT = "/failures/lock-timeout";

  /** Takes locks in the opposite order to another connection. */
  public static final String DEADLOCK = "/failures/deadlock";

  /** Outlives its transaction's time limit. */
  public static final String TRANSACTION_TIMEOUT = "/failures/transaction-timeout";

  /** Answers each endpoint by running the failing work. */
  @RestController
  @RequiredArgsConstructor
  static class FailuresController {

    /** What an endpoint answers if its work does not fail. */
    private static final String DONE = "done";

    /** The failing work. */
    private final FailingAccounts accounts;

    /**
     * Stores a duplicate customer.
     *
     * @return never returns normally
     */
    @GetMapping(DUPLICATE)
    String duplicate() {
      accounts.addDuplicateCustomer();
      return DONE;
    }

    /**
     * Inserts a duplicate customer in a batch.
     *
     * @return never returns normally
     */
    @GetMapping(DUPLICATE_IN_BATCH)
    String duplicateInBatch() {
      accounts.addDuplicateCustomersInBatch();
      return DONE;
    }

    /**
     * Changes an account changed meanwhile, at commit.
     *
     * @return never returns normally
     */
    @GetMapping(STALE_AT_COMMIT)
    String staleAtCommit() {
      accounts.depositAfterChangeAtCommit();
      return DONE;
    }

    /**
     * Changes an account changed meanwhile, with an explicit flush.
     *
     * @return never returns normally
     */
    @GetMapping(STALE_AT_FLUSH)
    String staleAtFlush() {
      accounts.depositAfterChangeWithFlush();
      return DONE;
    }

    /**
     * Counts customers with a pooled connection.
     *
     * @return the count, when a connection is free
     */
    @GetMapping(POOLED)
    String pooled() {
      return String.valueOf(accounts.countCustomers());
    }

    /**
     * Runs past the database's statement_timeout.
     *
     * @return never returns normally
     */
    @GetMapping(DATABASE_TIMEOUT)
    String databaseTimeout() {
      accounts.runPastDatabaseTimeout();
      return DONE;
    }

    /**
     * Waits for a lock past lock_timeout.
     *
     * @return never returns normally
     */
    @GetMapping(LOCK_TIMEOUT)
    String lockTimeout() {
      accounts.lockHeldAccount();
      return DONE;
    }

    /**
     * Takes locks in the opposite order to another connection.
     *
     * @return never returns normally
     */
    @GetMapping(DEADLOCK)
    String deadlock() {
      accounts.lockInOppositeOrder();
      return DONE;
    }

    /**
     * Outlives its transaction.
     *
     * @return never returns normally
     */
    @GetMapping(TRANSACTION_TIMEOUT)
    String transactionTimeout() {
      accounts.outliveTransaction();
      return DONE;
    }
  }
}
