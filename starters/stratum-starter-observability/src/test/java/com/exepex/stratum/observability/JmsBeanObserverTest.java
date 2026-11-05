package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.test.LogLineCapture;
import io.micrometer.observation.ObservationRegistry;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;
import org.springframework.scheduling.support.TaskUtils;

@DisplayName(
    "JmsBeanObserver gives the application's observation registry to JMS beans that have none")
class JmsBeanObserverTest {

  /** The bean name passed to the post-processor; it plays no part. */
  private static final String BEAN_NAME = "invoiceSender";

  /** The application's registry. */
  private final ObservationRegistry applicationRegistry = ObservationRegistry.create();

  /** Returns an observer of an application with these observation registries. */
  private JmsBeanObserver observerWith(Map<String, ObservationRegistry> registries) {
    return new JmsBeanObserver(
        new StaticListableBeanFactory(Map.copyOf(registries))
            .getBeanProvider(ObservationRegistry.class));
  }

  /** Returns an observer of an application with one observation registry. */
  private JmsBeanObserver observer() {
    return observerWith(Map.of("observationRegistry", applicationRegistry));
  }

  @Nested
  @DisplayName("a JmsTemplate bean")
  class Sender {

    @Test
    @DisplayName("with no registry gets the application's")
    void getsRegistry() {
      var template = new JmsTemplate();

      observer().postProcessAfterInitialization(template, BEAN_NAME);

      assertThat(template.getObservationRegistry()).isSameAs(applicationRegistry);
    }

    @Test
    @DisplayName("keeps the registry the service set, such as NOOP")
    void keepsServiceRegistry() {
      var template = new JmsTemplate();
      template.setObservationRegistry(ObservationRegistry.NOOP);

      observer().postProcessAfterInitialization(template, BEAN_NAME);

      assertThat(template.getObservationRegistry()).isSameAs(ObservationRegistry.NOOP);
    }
  }

  @Nested
  @DisplayName("a listener container bean")
  class ListenerContainer {

    @Test
    @DisplayName("with no registry gets the application's")
    void getsRegistry() {
      var container = new DefaultMessageListenerContainer();

      observer().postProcessAfterInitialization(container, BEAN_NAME);

      assertThat(container.getObservationRegistry()).isSameAs(applicationRegistry);
    }

    @Test
    @DisplayName("keeps the registry the service set, such as NOOP")
    void keepsServiceRegistry() {
      var container = new DefaultMessageListenerContainer();
      container.setObservationRegistry(ObservationRegistry.NOOP);

      observer().postProcessAfterInitialization(container, BEAN_NAME);

      assertThat(container.getObservationRegistry()).isSameAs(ObservationRegistry.NOOP);
    }

    @Test
    @DisplayName(
        "with no error handler gets one that logs Spring's warning under the container's logger")
    void getsErrorHandler() {
      var container = new DefaultMessageListenerContainer();
      var containerLines = new LogLineCapture(DefaultMessageListenerContainer.class.getName());
      observer().postProcessAfterInitialization(container, BEAN_NAME);

      containerLines.start();
      try {
        container.getErrorHandler().handleError(new IllegalStateException("listener failed"));
      } finally {
        containerLines.stop();
      }

      assertThat(container.getErrorHandler()).isInstanceOf(ObservedJmsErrorHandler.class);
      assertThat(containerLines.lines())
          .singleElement()
          .extracting(ILoggingEvent::getFormattedMessage)
          .isEqualTo(ObservedJmsErrorHandler.LISTENER_FAILED);
    }

    @Test
    @DisplayName("keeps the error handler the service set")
    void keepsServiceErrorHandler() {
      var container = new DefaultMessageListenerContainer();
      var serviceHandler = TaskUtils.LOG_AND_SUPPRESS_ERROR_HANDLER;
      container.setErrorHandler(serviceHandler);

      observer().postProcessAfterInitialization(container, BEAN_NAME);

      assertThat(container.getErrorHandler()).isSameAs(serviceHandler);
    }
  }

  @Nested
  @DisplayName("without one application registry")
  class NoUniqueRegistry {

    @Test
    @DisplayName("with none, the bean is left without a registry")
    void noRegistry() {
      var template = new JmsTemplate();

      observerWith(Map.of()).postProcessAfterInitialization(template, BEAN_NAME);

      assertThat(template.getObservationRegistry()).isNull();
    }

    @Test
    @DisplayName("with two, the bean is left without a registry and startup goes on")
    void twoRegistries() {
      var container = new DefaultMessageListenerContainer();

      observerWith(
              Map.of(
                  "observationRegistry",
                  applicationRegistry,
                  "auditObservationRegistry",
                  ObservationRegistry.create()))
          .postProcessAfterInitialization(container, BEAN_NAME);

      assertThat(container.getObservationRegistry()).isNull();
      assertThat(container.getErrorHandler()).isNull();
    }
  }

  @Test
  @DisplayName("any other bean is returned as it is")
  void otherBeanUnchanged() {
    var bean = new Object();

    assertThat(observer().postProcessAfterInitialization(bean, BEAN_NAME)).isSameAs(bean);
  }
}
