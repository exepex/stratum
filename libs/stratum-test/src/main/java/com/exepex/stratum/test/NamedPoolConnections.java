package com.exepex.stratum.test;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Gives a named pool of {@code stratum.data.sources} its own PostgreSQL database in Docker for a
 * test. Spring Boot's {@code @ServiceConnection} connects only its main pool, so this initializer
 * adds a container of the same pinned image as {@link PostgresContainerConfiguration}, with a
 * database named after the pool, and registers {@code <name>JdbcConnectionDetails} from it as a
 * bean that is no default candidate: Boot's main pool keeps its own connection, and Stratum builds
 * the pool {@code <name>} from it. The container lives as long as the application context. Under
 * {@code @SpringBootTest}, call it from an initializer class named in
 * {@code @ContextConfiguration(initializers = ...)}; otherwise add it with {@code
 * SpringApplicationBuilder.initializers(...)} or {@code
 * ApplicationContextRunner.withInitializer(...)}.
 *
 * <p>It registers the beans once every configuration class is read, after Boot has given a
 * {@code @ServiceConnection} container its own connection details: Boot gives none while any {@code
 * JdbcConnectionDetails} bean exists, so a {@code @Bean} of the pool's details would leave the main
 * pool without its container.
 */
@UtilityClass
public class NamedPoolConnections {

  /** The end of a named pool's connection details bean name, after the pool's name. */
  private static final String DETAILS_SUFFIX = "JdbcConnectionDetails";

  /** The end of the name of the bean that registers a pool's beans, after the pool's name. */
  private static final String REGISTRAR_SUFFIX = "PoolConnectionsRegistrar";

  /** The end of a named pool's container bean name, after the pool's name. */
  private static final String CONTAINER_SUFFIX = "PostgresContainer";

  /**
   * Returns the initializer that gives a named pool its own PostgreSQL database.
   *
   * @param pool the pool's name, as under {@code stratum.data.sources}
   * @return the initializer
   */
  public static ApplicationContextInitializer<ConfigurableApplicationContext> postgres(
      String pool) {
    return context -> addRegistrar((BeanDefinitionRegistry) context.getBeanFactory(), pool);
  }

  /** Adds the registrar of the pool's beans, which Spring runs after the configuration classes. */
  private static void addRegistrar(BeanDefinitionRegistry registry, String pool) {
    registry.registerBeanDefinition(
        pool + REGISTRAR_SUFFIX,
        new RootBeanDefinition(PoolBeansRegistrar.class, () -> new PoolBeansRegistrar(pool)));
  }

  /** A container's connection. A test database's password is no secret. */
  @Getter
  @RequiredArgsConstructor
  private static final class ContainerConnection implements JdbcConnectionDetails {

    /** The container's JDBC URL. */
    private final String jdbcUrl;

    /** Its user name. */
    private final String username;

    /** Its password. */
    private final String password;
  }

  /**
   * Registers one pool's container and connection details. It is ordered, so Spring runs it after
   * the configuration classes and before Stratum's own registrar of the pools, which is not.
   *
   * @param pool the pool's name
   */
  private record PoolBeansRegistrar(String pool)
      implements BeanDefinitionRegistryPostProcessor, Ordered {

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
      register(registry);
    }

    /** Registers the pool's container and its connection details, neither a default candidate. */
    private void register(BeanDefinitionRegistry registry) {
      var containerName = pool + CONTAINER_SUFFIX;
      var container =
          new RootBeanDefinition(
              PostgreSQLContainer.class,
              () -> new PostgreSQLContainer(ContainerImages.POSTGRES_IMAGE).withDatabaseName(pool));
      container.setDefaultCandidate(false);
      container.setDestroyMethodName(AbstractBeanDefinition.INFER_METHOD);
      registry.registerBeanDefinition(containerName, container);
      var beanFactory = (BeanFactory) registry;
      var details =
          new RootBeanDefinition(
              JdbcConnectionDetails.class,
              () -> detailsOf(beanFactory.getBean(containerName, PostgreSQLContainer.class)));
      details.setDefaultCandidate(false);
      registry.registerBeanDefinition(pool + DETAILS_SUFFIX, details);
    }

    /** Returns the connection of a container, which it starts first if it has not. */
    private JdbcConnectionDetails detailsOf(PostgreSQLContainer container) {
      if (!container.isRunning()) {
        container.start();
      }
      return new ContainerConnection(
          container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    public int getOrder() {
      return Ordered.LOWEST_PRECEDENCE;
    }
  }
}
