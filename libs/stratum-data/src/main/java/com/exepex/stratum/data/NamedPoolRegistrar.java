package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.StratumException;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;
import java.util.function.Supplier;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.BeanFactoryAware;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

/**
 * Registers each named pool's beans: {@code <name>JdbcConnectionDetails} from its {@code url} keys,
 * {@code <name>DataSource} and {@code <name>TransactionManager}. None of them is a default
 * candidate, so a plain {@code DataSource} or {@code @Transactional} keeps Spring Boot's main pool
 * and a named pool is reached by its qualifier only. A service's own bean of one of those names is
 * kept, and its {@code <name>JdbcConnectionDetails}, if not a default candidate, adds the pool
 * without any key.
 *
 * <p>It runs once every configuration class is read, so Spring Boot has already decided to create
 * its main pool, and the service's own beans are known.
 */
@StratumInternal
public final class NamedPoolRegistrar
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanFactoryAware {

  /** Checks the pools' names and keys. */
  private final PoolKeyCheck keyCheck = new PoolKeyCheck();

  /** Checks the pools' leak alarm thresholds. */
  private final LeakAlarmCheck leakAlarmCheck = new LeakAlarmCheck();

  /** Finds the pools' names. */
  private final NamedPoolNames poolNames = new NamedPoolNames();

  /** The application's environment, with the pools' keys. */
  @Setter private @Nullable Environment environment;

  /** The bean factory, which the pools' beans are built from. */
  @Setter private @Nullable BeanFactory beanFactory;

  /**
   * Checks the pools' names and keys and registers each pool's beans.
   *
   * @param registry the application's bean definitions
   * @throws StratumException {@code SX-DATA-1004} if a name or key breaks its rules or a pool has
   *     no connection, {@code SX-DATA-1002} if HikariCP would turn a pool's leak alarm off, {@code
   *     SX-DATA-1006} if a service's bean of a pool's bean name is a default candidate, or {@code
   *     SX-CORE-1003} if a setting breaks its rules
   */
  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    var env = Objects.requireNonNull(environment, "the environment is not set yet");
    keyCheck.checkKeys(env);
    var settings = DataProperties.resolve(env);
    keyCheck.checkSettings(settings);
    leakAlarmCheck.checkNamedPools(env, settings);
    var beans =
        new NamedPoolBeans(env, Objects.requireNonNull(beanFactory, "the bean factory is not set"));
    poolNames
        .names(registry, settings)
        .forEach(name -> register(registry, beans, name, settings.sources().get(name)));
  }

  /** Registers the beans of one pool that the service does not define itself. */
  private void register(
      BeanDefinitionRegistry registry,
      NamedPoolBeans beans,
      String name,
      @Nullable PoolSettings found) {
    var settings = found == null ? PoolSettings.builder().build() : found;
    var details = name + DataConstants.DETAILS_SUFFIX;
    var dataSource = name + DataConstants.DATA_SOURCE_SUFFIX;
    var manager = name + DataConstants.TRANSACTION_MANAGER_SUFFIX;
    rejectDefaultCandidates(registry, details, dataSource, manager);
    var url = settings.url();
    if (!registry.containsBeanDefinition(details)) {
      if (url == null) {
        throw new StratumException(
            DataErrorCode.POOL_SETTING_INVALID,
            "%s.%s.url is not set, and there is no bean %s"
                .formatted(DataConstants.SOURCES, name, details));
      }
      define(
          registry,
          details,
          JdbcConnectionDetails.class,
          () -> new PoolConnectionDetails(url, settings.username(), settings.password()));
    }
    define(registry, dataSource, HikariDataSource.class, () -> beans.dataSource(name, settings));
    define(
        registry,
        manager,
        DataSourceTransactionManager.class,
        () -> beans.transactionManager(name, settings));
  }

  /**
   * Fails when the service defines a bean of one of the pool's bean names as a default candidate.
   */
  private void rejectDefaultCandidates(BeanDefinitionRegistry registry, String... beanNames) {
    // A loop, not a stream: it throws at the first bean that breaks the rule, which a stream could
    // do only through a helper that exists just to throw.
    for (var beanName : beanNames) {
      if (registry.containsBeanDefinition(beanName)
          && poolNames.isDefaultCandidate(registry, beanName)) {
        throw new StratumException(
            DataErrorCode.POOL_BEAN_DEFAULT_CANDIDATE,
            "the bean %s is a default candidate".formatted(beanName));
      }
    }
  }

  /** Registers a bean that is no default candidate, unless the service defines one of that name. */
  private <T> void define(
      BeanDefinitionRegistry registry, String beanName, Class<T> type, Supplier<T> supplier) {
    if (registry.containsBeanDefinition(beanName)) {
      return;
    }
    var definition = new RootBeanDefinition(type, supplier);
    definition.setDefaultCandidate(false);
    definition.setFallback(true);
    definition.setDestroyMethodName(AbstractBeanDefinition.INFER_METHOD);
    registry.registerBeanDefinition(beanName, definition);
  }
}
