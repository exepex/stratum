package com.exepex.stratum.datajpa.routing;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataProperties;
import com.exepex.stratum.data.NamedPoolNames;
import com.exepex.stratum.data.routing.PoolRouting;
import com.exepex.stratum.data.routing.RoutingDataSource;
import com.exepex.stratum.datajpa.DataJpaConstants;
import jakarta.persistence.EntityManagerFactory;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import lombok.Setter;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanClassLoaderAware;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.context.EnvironmentAware;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.util.function.SingletonSupplier;

/**
 * Maps JPA repositories to named pools, from {@code stratum.data.sources.<name>.packages} and
 * {@code @UseDataSource}. With no mapping it does nothing. With one, it registers the primary
 * {@code stratumRoutingDataSource}, a lazy proxy over a data source that routes each statement to
 * the pool its call wants, the advice on every JPA repository, and the startup check.
 *
 * <p>It runs once every configuration class is read, so Spring Data's repository definitions and
 * Spring Boot's {@code dataSource} are known.
 */
@StratumInternal
public final class RoutingRegistrar
    implements BeanDefinitionRegistryPostProcessor, EnvironmentAware, BeanClassLoaderAware {

  /** Finds the JPA repository interfaces. */
  private final JpaRepositoryDefinitions repositories = new JpaRepositoryDefinitions();

  /** Finds the named pools. */
  private final NamedPoolNames poolNames = new NamedPoolNames();

  /** Checks that routing can be the only primary data source. */
  private final PrimaryDataSourceCheck primaryCheck = new PrimaryDataSourceCheck();

  /** The application's environment, with the mapping. */
  @Setter private @Nullable Environment environment;

  /** The application's class loader, which loads the repository interfaces. */
  @Setter private @Nullable ClassLoader beanClassLoader;

  /**
   * Maps the repositories and, if any is mapped, registers the routing beans.
   *
   * @param registry the application's bean definitions
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-DATA-1007} if the mapping
   *     breaks a rule or routing cannot be the only primary data source
   */
  @Override
  public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
    var env = Objects.requireNonNull(environment, "the environment is not set yet");
    var loader = Objects.requireNonNull(beanClassLoader, "the class loader is not set yet");
    var settings = DataProperties.resolve(env);
    var packages = new TreeMap<String, List<String>>();
    settings.sources().forEach((pool, poolSettings) -> packages.put(pool, poolSettings.packages()));
    var pools =
        new RepositoryMapping(packages, poolNames.names(registry, settings))
            .map(repositories.find(registry, loader));
    if (pools.isEmpty()) {
      return;
    }
    if (!(registry instanceof ConfigurableListableBeanFactory beanFactory)) {
      throw new IllegalStateException("the bean registry is not a bean factory");
    }
    primaryCheck.check(registry, beanFactory);
    register(registry, beanFactory, pools);
  }

  /** Registers the routing data source, the mapping, the advice and the check. */
  private void register(
      BeanDefinitionRegistry registry,
      ConfigurableListableBeanFactory beanFactory,
      RepositoryPools pools) {
    var routing = new PoolRouting();
    var routingBean =
        new RootBeanDefinition(
            DataSource.class, () -> routingDataSource(beanFactory, routing, pools));
    routingBean.setPrimary(true);
    registry.registerBeanDefinition(DataConstants.ROUTING_BEAN, routingBean);
    define(registry, DataJpaConstants.REPOSITORY_POOLS, RepositoryPools.class, () -> pools);
    Supplier<EntityManagerFactory> entityManagerFactory =
        SingletonSupplier.of(
            () ->
                beanFactory.getBean(
                    DataJpaConstants.ENTITY_MANAGER_FACTORY, EntityManagerFactory.class));
    define(
            registry,
            DataJpaConstants.REPOSITORY_ADVICE,
            RepositoryAdvicePostProcessor.class,
            () -> new RepositoryAdvicePostProcessor(routing, pools, entityManagerFactory))
        .setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
    define(
        registry,
        DataJpaConstants.ROUTING_CHECK,
        RoutingCheck.class,
        () -> new RoutingCheck(beanFactory, pools));
  }

  /**
   * Returns the routing data source: a lazy proxy over the router, whose targets are main's pool
   * behind its one boundary, which Spring Boot's own lazy proxy holds when it fetches lazily, and
   * each mapped pool's data source.
   */
  private DataSource routingDataSource(
      BeanFactory beanFactory, PoolRouting routing, RepositoryPools pools) {
    var main = beanFactory.getBean(DataConstants.MAIN_BEAN, DataSource.class);
    var mainTarget =
        main instanceof LazyConnectionDataSourceProxy lazy
            ? Objects.requireNonNull(lazy.getTargetDataSource(), "the lazy proxy has no target")
            : main;
    Map<String, DataSource> targets =
        pools.targets().stream()
            .collect(
                Collectors.toMap(
                    Function.identity(),
                    pool ->
                        beanFactory.getBean(
                            pool + DataConstants.DATA_SOURCE_SUFFIX, DataSource.class)));
    return new LazyConnectionDataSourceProxy(new RoutingDataSource(routing, targets, mainTarget));
  }

  /** Registers a bean that is no default candidate and returns its definition. */
  private <T> RootBeanDefinition define(
      BeanDefinitionRegistry registry, String beanName, Class<T> type, Supplier<T> supplier) {
    var definition = new RootBeanDefinition(type, supplier);
    definition.setDefaultCandidate(false);
    registry.registerBeanDefinition(beanName, definition);
    return definition;
  }
}
