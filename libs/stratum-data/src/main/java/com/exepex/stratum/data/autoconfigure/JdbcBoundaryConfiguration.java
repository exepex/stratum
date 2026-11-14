package com.exepex.stratum.data.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.properties.StratumCustomizers;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DatabaseTlsCheck;
import com.exepex.stratum.data.JdbcBoundaryPostProcessor;
import com.exepex.stratum.data.MainPoolNamePostProcessor;
import com.exepex.stratum.data.NamedPoolRegistrar;
import com.exepex.stratum.data.PoolCheck;
import com.exepex.stratum.data.PostgresLoginTimeoutPostProcessor;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;
import org.springframework.core.env.Environment;

/**
 * Creates the JDBC boundary around every data source bean, with the time limits of their
 * statements, the login limit of every PostgreSQL pool, the check of their connections' encryption,
 * the named pools of {@code stratum.data.sources} and the check of all pools, unless {@code
 * stratum.data.enabled=false}. Each data starter's auto-configuration imports it.
 */
@StratumInternal
@Configuration(proxyBeanMethods = false)
@ConditionalOnStratumModule(DataConstants.MODULE)
public final class JdbcBoundaryConfiguration {

  /**
   * Stops startup with {@code SX-CORE-1004} when the service declares a {@code
   * DataPropertiesCustomizer} as a bean, which would be silently ignored.
   *
   * @param beanFactory the service's beans
   */
  JdbcBoundaryConfiguration(ListableBeanFactory beanFactory) {
    StratumCustomizers.rejectBeans(beanFactory);
  }

  /**
   * Wraps every data source bean. A service replaces it with a bean of the same name, for example
   * to skip one of its data sources. Static, because Spring creates bean post-processors before the
   * other beans.
   *
   * @return the post-processor, which wraps every data source bean
   */
  @Bean(DataConstants.JDBC_BOUNDARY)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.JDBC_BOUNDARY)
  public static JdbcBoundaryPostProcessor stratumJdbcBoundary() {
    return new JdbcBoundaryPostProcessor(beanName -> true);
  }

  /**
   * Gives every HikariCP pool bean that connects to PostgreSQL a login limit from its own {@code
   * connection-timeout}. A service replaces it with a bean of the same name. Static, because Spring
   * creates bean post-processors before the other beans.
   *
   * @return the post-processor, which sets the driver's {@code loginTimeout}
   */
  @Bean(DataConstants.LOGIN_TIMEOUT)
  @Fallback
  @ConditionalOnClass(name = {"org.postgresql.Driver", "com.zaxxer.hikari.HikariDataSource"})
  @ConditionalOnMissingBean(name = DataConstants.LOGIN_TIMEOUT)
  public static PostgresLoginTimeoutPostProcessor stratumPostgresLoginTimeout() {
    return new PostgresLoginTimeoutPostProcessor();
  }

  /**
   * Checks, once the application is ready, whether each data source's connection is encrypted. A
   * service replaces it with a bean of the same name.
   *
   * @param context the application whose data sources are checked
   * @return the check
   */
  @Bean(DataConstants.TLS_CHECK)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.TLS_CHECK)
  public DatabaseTlsCheck stratumDatabaseTlsCheck(ConfigurableApplicationContext context) {
    return DatabaseTlsCheck.forClassPath(context);
  }

  /**
   * Registers the beans of each named pool of {@code stratum.data.sources}. A service replaces it
   * with a bean of the same name. Static, because Spring creates registry post-processors before
   * the other beans.
   *
   * @return the registrar
   */
  @Bean(DataConstants.NAMED_POOLS)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.NAMED_POOLS)
  public static NamedPoolRegistrar stratumNamedPools() {
    return new NamedPoolRegistrar();
  }

  /**
   * Names Spring Boot's HikariCP pool {@code main} when the service gave it no name. A service
   * replaces it with a bean of the same name. Static, because Spring creates bean post-processors
   * before the other beans.
   *
   * @return the post-processor
   */
  @Bean(DataConstants.MAIN_POOL_NAME)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.MAIN_POOL_NAME)
  public static MainPoolNamePostProcessor stratumMainPoolName() {
    return new MainPoolNamePostProcessor();
  }

  /**
   * Checks every pool once all beans exist: no two share a name, and together they stay within
   * {@code stratum.data.max-connections}. A service replaces it with a bean of the same name.
   *
   * @param beanFactory the service's beans
   * @param environment the application's environment
   * @return the check
   */
  @Bean(DataConstants.POOL_CHECK)
  @Fallback
  @ConditionalOnMissingBean(name = DataConstants.POOL_CHECK)
  public PoolCheck stratumPoolCheck(ListableBeanFactory beanFactory, Environment environment) {
    return new PoolCheck(beanFactory, environment);
  }
}
