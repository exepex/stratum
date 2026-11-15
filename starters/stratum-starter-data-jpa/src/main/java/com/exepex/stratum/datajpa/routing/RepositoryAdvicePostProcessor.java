package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.routing.PoolRouting;
import jakarta.persistence.EntityManagerFactory;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactoryBean;
import org.springframework.data.repository.core.RepositoryInformation;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAttributeSource;

/**
 * Adds the pool advice to every JPA repository, before Spring Data builds it, as its first advice,
 * so that it runs outside the repository's transaction.
 */
@StratumInternal
@RequiredArgsConstructor
public final class RepositoryAdvicePostProcessor implements BeanPostProcessor {

  /** The pool each call and transaction wants. */
  private final PoolRouting routing;

  /** Each mapped repository's pool. */
  private final RepositoryPools pools;

  /** The entity manager factory, found on first use. */
  private final Supplier<EntityManagerFactory> entityManagerFactory;

  /** Reads each repository method's {@code @Transactional}, shared by every advice. */
  private final TransactionAttributeSource transactionAttributes =
      new AnnotationTransactionAttributeSource();

  /**
   * Asks a JPA repository's factory bean to add the advice to the repository it builds.
   *
   * @param bean the bean
   * @param beanName the bean's name
   * @return the same bean
   */
  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof JpaRepositoryFactoryBean<?, ?, ?> factoryBean) {
      factoryBean.addRepositoryFactoryCustomizer(
          factory -> factory.addRepositoryProxyPostProcessor(this::addAdvice));
    }
    return bean;
  }

  /** Adds the advice of one repository, first. */
  private void addAdvice(ProxyFactory proxy, RepositoryInformation information) {
    var repository = information.getRepositoryInterface();
    proxy.addAdvice(
        0,
        new RepositoryPoolAdvice(
            routing,
            repository,
            pools.poolOf(repository),
            entityManagerFactory,
            transactionAttributes));
  }
}
