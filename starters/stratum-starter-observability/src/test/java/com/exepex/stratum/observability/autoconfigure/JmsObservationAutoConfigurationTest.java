package com.exepex.stratum.observability.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.observability.JmsBeanObserver;
import com.exepex.stratum.observability.JmsListenerContainerObserver;
import com.exepex.stratum.observability.ObservabilityConstants;
import com.exepex.stratum.observability.testsupport.ObservabilityTestData;
import io.micrometer.observation.ObservationRegistry;
import jakarta.jms.ConnectionFactory;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.validation.autoconfigure.ValidationAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.jms.listener.MessageListenerContainer;

@DisplayName("ObservabilityAutoConfiguration observes the JMS beans the service sets up itself")
class JmsObservationAutoConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      ObservabilityTestData.contextRunner().withUserConfiguration(ApplicationRegistry.class);

  @Nested
  @DisplayName("with the module on")
  class ModuleOn {

    @Test
    @DisplayName("the service's own JmsTemplate bean gets the registry")
    void serviceTemplateGetsRegistry() {
      contextRunner
          .withUserConfiguration(ServiceSender.class)
          .run(context -> assertServiceTemplateObserved(context));
    }

    @Test
    @DisplayName(
        "a @JmsListener container gets the registry before it starts, even when its phase is"
            + " early")
    void listenerContainerHasRegistryWhenItStarts() {
      contextRunner
          .withBean(EarlyListenerRegistry.class)
          .run(
              context ->
                  assertThat(context.getBean(EarlyListenerRegistry.class).container.registryAtStart)
                      .isSameAs(context.getBean(ObservationRegistry.class)));
    }

    @Test
    @DisplayName("without JMS listeners set up, the application still starts")
    void noListenerRegistryStillStarts() {
      contextRunner.run(
          context ->
              assertThat(context)
                  .hasNotFailed()
                  .doesNotHaveBean(JmsListenerEndpointRegistry.class)
                  .getBean(JmsListenerContainerObserver.class)
                  .extracting(JmsListenerContainerObserver::isRunning)
                  .isEqualTo(true));
    }
  }

  @Nested
  @DisplayName("with the module off or JMS missing")
  class Off {

    @Test
    @DisplayName("with the module off, no JMS bean is observed")
    void moduleOffObservesNothing() {
      contextRunner
          .withPropertyValues(ObservabilityConstants.ENABLED + "=false")
          .withUserConfiguration(ServiceSender.class)
          .run(context -> assertServiceTemplateNotObserved(context));
    }

    @Test
    @DisplayName("without JMS on the classpath, neither observer is created")
    void withoutJmsCreatesNoObservers() {
      contextRunner
          .withClassLoader(new FilteredClassLoader(JmsTemplate.class))
          .run(context -> assertNoJmsObservers(context));
    }
  }

  @Nested
  @DisplayName("when the service or a company framework has its own observers")
  class OwnObservers {

    @Test
    @DisplayName("the service's own observers replace Stratum's")
    void serviceObserversWin() {
      contextRunner
          .withUserConfiguration(ServiceObservers.class)
          .run(context -> assertUsesServiceObservers(context));
    }

    @Test
    @DisplayName("a company framework's auto-configuration with no order wins over Stratum's")
    void companyObserversWin() {
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  ValidationAutoConfiguration.class,
                  ObservabilityAutoConfiguration.class,
                  CompanyObserversAutoConfiguration.class))
          .run(context -> assertUsesServiceObservers(context));
    }
  }

  /** Checks that both observers exist and the service's sender has the application's registry. */
  private void assertServiceTemplateObserved(AssertableApplicationContext context) {
    assertThat(context)
        .hasSingleBean(JmsBeanObserver.class)
        .hasSingleBean(JmsListenerContainerObserver.class);
    assertThat(context.getBean(JmsTemplate.class).getObservationRegistry())
        .isSameAs(context.getBean(ObservationRegistry.class));
  }

  /** Checks that neither observer exists and the service's sender has no registry. */
  private void assertServiceTemplateNotObserved(AssertableApplicationContext context) {
    assertNoJmsObservers(context);
    assertThat(context.getBean(JmsTemplate.class).getObservationRegistry()).isNull();
  }

  /** Checks that the context holds neither of Stratum's JMS observers. */
  private void assertNoJmsObservers(AssertableApplicationContext context) {
    assertThat(context)
        .doesNotHaveBean(JmsBeanObserver.class)
        .doesNotHaveBean(JmsListenerContainerObserver.class);
  }

  /** Checks that the context holds the two observers of {@code ServiceObservers}, and no other. */
  private void assertUsesServiceObservers(AssertableApplicationContext context) {
    assertThat(context)
        .hasSingleBean(JmsBeanObserver.class)
        .hasSingleBean(JmsListenerContainerObserver.class);
    assertThat(context.getBean(JmsBeanObserver.class)).isSameAs(ServiceObservers.BEAN_OBSERVER);
    assertThat(context.getBean(JmsListenerContainerObserver.class))
        .isSameAs(ServiceObservers.LISTENER_OBSERVER);
  }

  /** The application's observation registry, which Spring Boot would create. */
  @Configuration(proxyBeanMethods = false)
  static class ApplicationRegistry {

    /**
     * Returns the registry.
     *
     * @return the registry
     */
    @Bean
    ObservationRegistry observationRegistry() {
      return ObservationRegistry.create();
    }
  }

  /** A service that declares its own sender. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceSender {

    /**
     * Returns the service's sender.
     *
     * @return the sender
     */
    @Bean
    JmsTemplate invoiceSender() {
      return new JmsTemplate(mock(ConnectionFactory.class));
    }
  }

  /** A listener container that only records the registry it has when it is started. */
  static class RecordingContainer extends DefaultMessageListenerContainer {

    /** The registry the container had when it was started. */
    @Nullable ObservationRegistry registryAtStart;

    /** Records the registry instead of connecting to a broker. */
    @Override
    public void start() {
      registryAtStart = getObservationRegistry();
    }

    /** Nothing to release: the container never connected. */
    @Override
    public void destroy() {
      // Never initialized or started for real.
    }
  }

  /**
   * The registry of {@code @JmsListener} containers, holding one container in an early phase, as a
   * listener factory with {@code setPhase(0)} gives it.
   */
  static class EarlyListenerRegistry extends JmsListenerEndpointRegistry {

    /** The registry's one container. */
    final RecordingContainer container = new RecordingContainer();

    /**
     * Returns the one container.
     *
     * @return the containers
     */
    @Override
    public Collection<MessageListenerContainer> getListenerContainers() {
      return List.of(container);
    }

    /**
     * Returns the early phase the factory gave the container.
     *
     * @return 0
     */
    @Override
    public int getPhase() {
      return 0;
    }
  }

  /** The service's own observers. */
  @Configuration(proxyBeanMethods = false)
  static class ServiceObservers {

    /** The service's bean observer. */
    static final JmsBeanObserver BEAN_OBSERVER =
        new JmsBeanObserver(
            new StaticListableBeanFactory().getBeanProvider(ObservationRegistry.class));

    /** The service's listener observer. */
    static final JmsListenerContainerObserver LISTENER_OBSERVER =
        new JmsListenerContainerObserver(
            new StaticListableBeanFactory().getBeanProvider(JmsListenerEndpointRegistry.class),
            BEAN_OBSERVER);

    /**
     * Returns the service's bean observer. Static, because Spring creates bean post-processors
     * before the other beans.
     *
     * @return the observer
     */
    @Bean
    static JmsBeanObserver serviceJmsBeanObserver() {
      return BEAN_OBSERVER;
    }

    /**
     * Returns the service's listener observer.
     *
     * @return the observer
     */
    @Bean
    JmsListenerContainerObserver serviceJmsListenerContainerObserver() {
      return LISTENER_OBSERVER;
    }
  }

  /** A company framework's auto-configuration that declares no order, with the same beans. */
  @AutoConfiguration
  static class CompanyObserversAutoConfiguration extends ServiceObservers {}
}
