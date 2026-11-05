package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.jms.config.JmsListenerEndpointRegistry;
import org.springframework.jms.listener.AbstractMessageListenerContainer;

/**
 * Times and traces the {@code @JmsListener} methods whose listener container factory the service
 * built itself, such as one per broker. Spring creates their containers outside the bean factory,
 * so {@link JmsBeanObserver} never sees them; this gives them the observation registry before any
 * listener starts.
 */
@StratumInternal
@RequiredArgsConstructor
public class JmsListenerContainerObserver implements SmartLifecycle {

  /**
   * The registry of the {@code @JmsListener} containers, which exists only when Spring Boot set up
   * JMS listeners.
   */
  private final ObjectProvider<JmsListenerEndpointRegistry> endpointRegistry;

  /** Gives each container the observation registry when it has none. */
  private final JmsBeanObserver jmsBeanObserver;

  /** Whether this observer is running. */
  private final AtomicBoolean running = new AtomicBoolean();

  /** Gives the observation registry to every {@code @JmsListener} container that has none. */
  @Override
  public void start() {
    var registry = endpointRegistry.getIfAvailable();
    if (registry != null) {
      registry.getListenerContainers().stream()
          .filter(AbstractMessageListenerContainer.class::isInstance)
          .map(AbstractMessageListenerContainer.class::cast)
          .forEach(jmsBeanObserver::observe);
    }
    running.set(true);
  }

  /** Marks the observer stopped; the containers keep their registry. */
  @Override
  public void stop() {
    running.set(false);
  }

  /**
   * Returns whether the observer is running.
   *
   * @return whether it is running
   */
  @Override
  public boolean isRunning() {
    return running.get();
  }

  /**
   * Returns the earliest phase, so the containers have the registry before any of them starts.
   *
   * @return {@code Integer.MIN_VALUE}
   */
  @Override
  public int getPhase() {
    return Integer.MIN_VALUE;
  }
}
