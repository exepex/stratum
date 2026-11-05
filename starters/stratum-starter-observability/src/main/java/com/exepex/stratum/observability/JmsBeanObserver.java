package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.micrometer.observation.ObservationRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.AbstractMessageListenerContainer;

/**
 * Times and traces the JMS senders and listeners the service declares as beans, which Spring Boot
 * leaves unobserved: each {@code JmsTemplate} and listener container bean that has no observation
 * registry gets the application's, and each listener container without an error handler records
 * failed messages as errors. A registry or error handler the service set itself, such as {@code
 * ObservationRegistry.NOOP}, is kept.
 */
@StratumInternal
@RequiredArgsConstructor
public class JmsBeanObserver implements BeanPostProcessor {

  /**
   * The application's observation registry, read only when a JMS bean is found, so that it is
   * created after Spring Boot has given it its handlers.
   */
  private final ObjectProvider<ObservationRegistry> observationRegistry;

  /**
   * Gives the registry to a JMS sender or listener container bean that has none.
   *
   * @param bean the bean
   * @param beanName its name
   * @return the same bean
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    switch (bean) {
      case JmsTemplate template -> observe(template);
      case AbstractMessageListenerContainer container -> observe(container);
      default -> {
        // Not a JMS sender or listener: nothing to observe.
      }
    }
    return bean;
  }

  /** Gives the registry to a sender that has none. */
  private void observe(JmsTemplate template) {
    if (template.getObservationRegistry() == null) {
      applicationRegistry().ifPresent(template::setObservationRegistry);
    }
  }

  /**
   * Gives the registry to a listener container that has none, and, when it then has a registry and
   * no error handler, a handler that records each failed message as an error.
   *
   * @param container the listener container
   */
  public void observe(AbstractMessageListenerContainer container) {
    if (container.getObservationRegistry() == null) {
      applicationRegistry().ifPresent(container::setObservationRegistry);
    }
    var containerRegistry = container.getObservationRegistry();
    if (containerRegistry != null && container.getErrorHandler() == null) {
      container.setErrorHandler(
          new ObservedJmsErrorHandler(
              containerRegistry, LoggerFactory.getLogger(container.getClass())));
    }
  }

  /** Returns the application's registry, or nothing when there is none or more than one. */
  private Optional<ObservationRegistry> applicationRegistry() {
    return Optional.ofNullable(observationRegistry.getIfUnique());
  }
}
