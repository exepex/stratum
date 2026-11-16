package com.exepex.stratum.data.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The pool the current call wants: the repository's own pool while it runs, else the pool the
 * transaction is on, else {@code main}. A transaction is put on the pool of its first repository
 * call or statement and stays on it, so its commit-time flush runs on that pool too. One instance
 * serves the routing data source and every repository.
 */
@StratumInternal
public final class PoolRouting {

  /** The pool of the repository call running on each thread, if any. */
  private final ThreadLocal<@Nullable String> callPool = new ThreadLocal<>();

  /**
   * Returns the pool the current call wants: the repository's, else the transaction's, else main.
   *
   * @return the pool's name
   */
  public String currentPool() {
    var pool = callPool.get();
    if (pool != null) {
      return pool;
    }
    var transactionPool = transactionPool();
    return transactionPool == null ? DataConstants.MAIN_POOL : transactionPool;
  }

  /**
   * Returns the pool the current transaction is on, or none outside a transaction or before its
   * first repository call or statement.
   *
   * @return the pool's name, or {@code null}
   */
  public @Nullable String transactionPool() {
    return TransactionSynchronizationManager.getResource(this) instanceof TransactionPool pinned
        ? pinned.pool()
        : null;
  }

  /**
   * Starts a repository call on its pool, and returns the pool of the call it runs inside, which
   * {@link #exit} restores.
   *
   * @param pool the repository's pool
   * @return the enclosing call's pool, or {@code null}
   */
  public @Nullable String enter(String pool) {
    var previous = callPool.get();
    callPool.set(pool);
    return previous;
  }

  /**
   * Ends a repository call and restores the enclosing call's pool.
   *
   * @param previous the pool {@link #enter} returned
   */
  public void exit(@Nullable String previous) {
    if (previous == null) {
      callPool.remove();
    } else {
      callPool.set(previous);
    }
  }

  /**
   * Puts the current transaction on a pool, unless it is on that pool already. Outside a
   * transaction, it does nothing.
   *
   * @param pool the pool of the repository being called
   * @throws StratumException {@code SX-DATA-1008} if the transaction is on another pool
   */
  public void pinTransaction(String pool) {
    if (!inTransaction()) {
      return;
    }
    var pinned = transactionPool();
    if (pinned == null) {
      bind(pool);
    } else if (!pinned.equals(pool)) {
      throw mixed(pinned, "a repository of pool " + pool);
    }
  }

  /**
   * Fails a statement whose call wants another pool than the one its connection is on. Otherwise,
   * in a transaction on no pool yet, it puts the transaction on the connection's pool, so a later
   * repository call of another pool fails before it can return an entity loaded from this one.
   *
   * @param connectionPool the pool the connection was opened on
   * @throws StratumException {@code SX-DATA-1008} if the current call wants another pool
   */
  void checkStatement(String connectionPool) {
    var wanted = currentPool();
    if (!wanted.equals(connectionPool)) {
      throw mixed(connectionPool, "a statement of pool " + wanted);
    }
    if (inTransaction() && transactionPool() == null) {
      bind(connectionPool);
    }
  }

  /** Returns whether a transaction with synchronization is active on this thread. */
  private boolean inTransaction() {
    return TransactionSynchronizationManager.isActualTransactionActive()
        && TransactionSynchronizationManager.isSynchronizationActive();
  }

  /** Puts the current transaction on a pool until it completes. */
  private void bind(String pool) {
    var transactionPool = new TransactionPool(this, pool);
    TransactionSynchronizationManager.bindResource(this, transactionPool);
    TransactionSynchronizationManager.registerSynchronization(transactionPool);
  }

  /** Returns the failure of a call that reaches another pool than its transaction's. */
  private StratumException mixed(String pool, String what) {
    return new StratumException(
        DataErrorCode.POOL_MIXED, "a transaction on pool %s reached %s".formatted(pool, what));
  }
}
