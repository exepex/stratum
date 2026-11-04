package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import liquibase.integration.spring.SpringLiquibase;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;

/**
 * Gives the connection that Spring Boot builds for Liquibase the clean errors of the JDBC boundary,
 * before Liquibase runs its changes. Spring Boot builds it from Liquibase's own {@code
 * spring.liquibase.url} or {@code user}, or from Liquibase's connection details, such as those of
 * {@code @ServiceConnection} or Docker Compose. Liquibase's use of a data source bean needs nothing
 * more: that bean already has its boundary.
 */
@StratumInternal
@RequiredArgsConstructor
public class LiquibaseBoundaryPostProcessor implements BeanPostProcessor {

  /** The post-processor whose boundary wraps the data source bean; none means no wrapping. */
  private final ObjectProvider<JdbcBoundaryPostProcessor> boundary;

  /**
   * Replaces the connection of a Liquibase bean with its boundary, before the bean runs the changes
   * as it initialises.
   *
   * @param bean the bean, before its initialisation
   * @param beanName its name
   * @return the bean
   */
  @Override
  public Object postProcessBeforeInitialization(Object bean, String beanName) {
    if (bean instanceof SpringLiquibase liquibase) {
      boundary.ifUnique(postProcessor -> wrapConnection(liquibase, postProcessor));
    }
    return bean;
  }

  /** Replaces the Liquibase bean's connection with the one the post-processor returns. */
  private void wrapConnection(SpringLiquibase liquibase, JdbcBoundaryPostProcessor postProcessor) {
    liquibase.setDataSource(postProcessor.migrationDataSource(liquibase.getDataSource()));
  }
}
