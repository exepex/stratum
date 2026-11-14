package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.AbstractBeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.support.GenericApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

@DisplayName("NamedPoolConnections gives a named pool its own database as beans of no default")
class NamedPoolConnectionsTest {

  /** The container's JDBC URL. */
  private static final String URL = "jdbc:postgresql://localhost:5432/batch";

  /** The container bean the initializer registers for the pool {@code batch}. */
  private static final String CONTAINER = "batchPostgresContainer";

  /** The connection details bean the initializer registers for the pool {@code batch}. */
  private static final String DETAILS = "batchJdbcConnectionDetails";

  /** Returns a container stand-in that reports its connection and whether it runs. */
  private PostgreSQLContainer standIn(boolean running) {
    var container = mock(PostgreSQLContainer.class);
    when(container.isRunning()).thenReturn(running);
    when(container.getJdbcUrl()).thenReturn(URL);
    when(container.getUsername()).thenReturn("app");
    when(container.getPassword()).thenReturn("test");
    return container;
  }

  /**
   * Returns a context with the initializer applied, not refreshed, whose container bean is replaced
   * by a stand-in once registered, so no Docker is needed.
   */
  private GenericApplicationContext contextWith(PostgreSQLContainer container) {
    var context = new GenericApplicationContext();
    NamedPoolConnections.postgres("batch").initialize(context);
    context.addBeanFactoryPostProcessor(
        (BeanFactoryPostProcessor) beanFactory -> replaceContainer(beanFactory, container));
    return context;
  }

  /** Replaces the container bean the initializer registered with a stand-in. */
  private void replaceContainer(
      ConfigurableListableBeanFactory beanFactory, PostgreSQLContainer container) {
    var registry = (BeanDefinitionRegistry) beanFactory;
    registry.removeBeanDefinition(CONTAINER);
    registry.registerBeanDefinition(
        CONTAINER, new RootBeanDefinition(PostgreSQLContainer.class, () -> container));
  }

  @Test
  @DisplayName("nothing is registered until Spring runs the registrars, after the configuration")
  void registersLate() {
    try (var context = new GenericApplicationContext()) {
      NamedPoolConnections.postgres("batch").initialize(context);

      assertThat(context.containsBeanDefinition(DETAILS)).isFalse();
      assertThat(context.containsBeanDefinition(CONTAINER)).isFalse();
    }
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {CONTAINER, DETAILS})
  @DisplayName("then the container and the connection details are no default candidates")
  void registersNonDefaultBeans(String beanName) {
    try (var context = new GenericApplicationContext()) {
      NamedPoolConnections.postgres("batch").initialize(context);
      context.addBeanFactoryPostProcessor(
          (BeanFactoryPostProcessor)
              beanFactory ->
                  assertThat(
                          ((AbstractBeanDefinition) beanFactory.getBeanDefinition(beanName))
                              .isDefaultCandidate())
                      .isFalse());
      context.addBeanFactoryPostProcessor(
          (BeanFactoryPostProcessor) beanFactory -> replaceContainer(beanFactory, standIn(true)));
      context.refresh();

      assertThat(context.containsBeanDefinition(beanName)).isTrue();
    }
  }

  @Test
  @DisplayName("the connection details report the container's URL, user name and password")
  void reportsContainer() {
    try (var context = contextWith(standIn(true))) {
      context.refresh();
      var details = context.getBean(DETAILS, JdbcConnectionDetails.class);

      assertThat(details.getJdbcUrl()).isEqualTo(URL);
      assertThat(details.getUsername()).isEqualTo("app");
      assertThat(details.getPassword()).isEqualTo("test");
    }
  }

  @Test
  @DisplayName("a running container is not started again, and one not running is started")
  void startsOnlyStoppedContainer() {
    try (var running = standIn(true);
        var stopped = standIn(false)) {
      refresh(running);
      refresh(stopped);

      verify(running, never()).start();
      verify(stopped).start();
    }
  }

  /** Starts and stops a context whose named pool connects to a container. */
  private void refresh(PostgreSQLContainer container) {
    try (var context = contextWith(container)) {
      context.refresh();
      context.getBean(DETAILS);
    }
  }
}
