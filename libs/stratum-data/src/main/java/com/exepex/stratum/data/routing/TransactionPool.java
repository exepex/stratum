package com.exepex.stratum.data.routing;

import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The pool a transaction is on, bound as a transaction resource. It follows the transaction: it is
 * set aside while a {@code REQUIRES_NEW} transaction runs, and removed when the transaction ends,
 * committed or rolled back.
 *
 * @param key the key it is bound under: the routing's own instance
 * @param pool the pool's name
 */
record TransactionPool(Object key, String pool) implements TransactionSynchronization {

  @Override
  public void suspend() {
    TransactionSynchronizationManager.unbindResourceIfPossible(key);
  }

  @Override
  public void resume() {
    TransactionSynchronizationManager.bindResource(key, this);
  }

  @Override
  public void afterCompletion(int status) {
    TransactionSynchronizationManager.unbindResourceIfPossible(key);
  }
}
