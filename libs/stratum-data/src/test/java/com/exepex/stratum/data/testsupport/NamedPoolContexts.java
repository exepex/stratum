package com.exepex.stratum.data.testsupport;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import javax.sql.DataSource;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.transaction.autoconfigure.TransactionManagerCustomizationAutoConfiguration;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The application the tests of named pools start: Spring Boot's own pool and transaction manager,
 * as a service gets them, and Stratum's data configuration, with the main pool and the pool {@code
 * batch} pointed at a database nothing listens on. No pool connects unless a test borrows.
 */
@UtilityClass
public class NamedPoolContexts {

  /**
   * Returns the application with the main pool and the pool {@code batch}.
   *
   * @return the context runner
   */
  public static ApplicationContextRunner withBatchPool() {
    return withMainPool()
        .withPropertyValues(NamedPoolTestData.BATCH_KEYS + "url=" + NamedPoolTestData.URL);
  }

  /**
   * Returns the application with only the main pool.
   *
   * @return the context runner
   */
  public static ApplicationContextRunner withMainPool() {
    return new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration.class,
                DataSourceTransactionManagerAutoConfiguration.class,
                TransactionManagerCustomizationAutoConfiguration.class,
                DataStarterStandIns.JdbcBoundaryStandIn.class))
        .withPropertyValues("spring.datasource.url=" + NamedPoolTestData.URL);
  }

  /**
   * Returns the HikariCP pool behind a data source bean, through Stratum's boundary.
   *
   * @param context the started application
   * @param beanName the data source bean's name
   * @return the pool
   * @throws SQLException never, since the boundary unwraps to the pool
   */
  public static HikariDataSource poolOf(AssertableApplicationContext context, String beanName)
      throws SQLException {
    return context.getBean(beanName, DataSource.class).unwrap(HikariDataSource.class);
  }

  /**
   * A service bean that injects a data source and a transaction manager without a qualifier, and
   * the pool {@code batch}'s with one, as a service's own code does.
   *
   * @param dataSource the data source without a qualifier
   * @param batch the pool {@code batch}
   * @param manager the transaction manager without a qualifier, which {@code @Transactional} uses
   * @param batchManager the pool {@code batch}'s transaction manager
   */
  public record Injected(
      DataSource dataSource,
      @Qualifier(NamedPoolTestData.BATCH_DATA_SOURCE) DataSource batch,
      PlatformTransactionManager manager,
      @Qualifier(NamedPoolTestData.BATCH_MANAGER) PlatformTransactionManager batchManager) {}
}
