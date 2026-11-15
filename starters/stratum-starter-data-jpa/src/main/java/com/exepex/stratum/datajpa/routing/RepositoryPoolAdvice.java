package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.routing.PoolRouting;
import jakarta.persistence.EntityManagerFactory;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttributeSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Runs each call of one JPA repository on its pool, outside Spring Data's transaction advice. A
 * mapped repository's call wants its pool while it runs; in a transaction, it puts the transaction
 * on that pool, so the commit-time flush runs there too. An unmapped repository's call keeps the
 * pool the transaction is on, and puts a transaction on no pool yet on {@code main}. A method that
 * runs in a transaction of its own, such as one with {@code REQUIRES_NEW}, leaves the caller's
 * transaction alone: its own transaction gets the pool.
 */
@StratumInternal
@RequiredArgsConstructor
public final class RepositoryPoolAdvice implements MethodInterceptor {

  /** The pool each call and transaction wants. */
  private final PoolRouting routing;

  /** The ways a method runs outside the caller's transaction, which it then leaves alone. */
  private static final Set<Integer> OWN_TRANSACTION =
      Set.of(
          TransactionDefinition.PROPAGATION_REQUIRES_NEW,
          TransactionDefinition.PROPAGATION_NOT_SUPPORTED,
          TransactionDefinition.PROPAGATION_NEVER);

  /** The repository interface. */
  private final Class<?> repository;

  /** The repository's pool, or {@code null} if it is not mapped. */
  private final @Nullable String pool;

  /** The entity manager factory, whose transactions bind it as a resource. */
  private final Supplier<EntityManagerFactory> entityManagerFactory;

  /**
   * Reads each method's {@code @Transactional}, to see whether it joins the caller's transaction.
   */
  private final TransactionAttributeSource transactionAttributes;

  /**
   * Runs the call on the repository's pool.
   *
   * @param invocation the repository call
   * @return the call's result
   * @throws StratumException {@code SX-DATA-1008} if the transaction is on another pool, or is not
   *     a JPA transaction
   * @throws Throwable whatever the call throws
   */
  @Override
  public @Nullable Object invoke(MethodInvocation invocation) throws Throwable {
    var joins = joinsCallerTransaction(invocation);
    if (pool == null) {
      if (joins) {
        routing.pinTransaction(routing.currentPool(), repository.getName());
      }
      return invocation.proceed();
    }
    if (joins) {
      checkJpaTransaction(pool);
      routing.pinTransaction(pool, repository.getName());
    }
    var previous = routing.enter(pool);
    try {
      return invocation.proceed();
    } finally {
      routing.exit(previous);
    }
  }

  /** Returns whether the method runs in the caller's transaction, as it does by default. */
  private boolean joinsCallerTransaction(MethodInvocation invocation) {
    var attribute =
        transactionAttributes.getTransactionAttribute(invocation.getMethod(), repository);
    return attribute == null || !OWN_TRANSACTION.contains(attribute.getPropagationBehavior());
  }

  /**
   * Fails a mapped call inside a transaction of another manager, such as a named pool's own, whose
   * connection JPA would not use.
   */
  private void checkJpaTransaction(String wanted) {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.hasResource(entityManagerFactory.get())) {
      throw new StratumException(
          DataErrorCode.POOL_MIXED,
          "the repository %s of pool %s was called in a transaction that is not JPA's"
              .formatted(repository.getName(), wanted));
    }
  }
}
