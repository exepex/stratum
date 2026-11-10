package com.exepex.stratum.restclient.testsupport.beans;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.restclient.observation.ObservationRestClientCustomizer;
import org.springframework.boot.restclient.observation.ObservationRestTemplateCustomizer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.client.observation.ClientRequestObservationContext;
import org.springframework.http.client.observation.DefaultClientRequestObservationConvention;

/**
 * Records each call of a {@code RestClient} or {@code RestTemplate} with the customizers Spring
 * Boot's observation set-up declares, here declared by hand, as the test has no Micrometer
 * auto-configuration. It counts the client calls that were recorded.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ObservedCalls {

  /** The client calls recorded so far. */
  private final AtomicInteger recordedCallCount = new AtomicInteger();

  /**
   * Returns how many client calls were recorded so far.
   *
   * @return the number
   */
  public int recordedCalls() {
    return recordedCallCount.get();
  }

  /**
   * Makes the registry that records each call.
   *
   * @return the registry
   */
  @Bean
  ObservationRegistry observationRegistry() {
    var registry = ObservationRegistry.create();
    registry.observationConfig().observationHandler(new CallCounter());
    return registry;
  }

  /**
   * Makes Spring Boot's customizer that records each call of a {@code RestClient}.
   *
   * @param registry the registry
   * @return the customizer
   */
  @Bean
  ObservationRestClientCustomizer observationRestClientCustomizer(ObservationRegistry registry) {
    return new ObservationRestClientCustomizer(
        registry, new DefaultClientRequestObservationConvention());
  }

  /**
   * Makes Spring Boot's customizer that records each call of a {@code RestTemplate}.
   *
   * @param registry the registry
   * @return the customizer
   */
  @Bean
  ObservationRestTemplateCustomizer observationRestTemplateCustomizer(
      ObservationRegistry registry) {
    return new ObservationRestTemplateCustomizer(
        registry, new DefaultClientRequestObservationConvention());
  }

  /** Counts each client call when it is recorded. */
  private final class CallCounter implements ObservationHandler<ClientRequestObservationContext> {

    @Override
    public void onStop(ClientRequestObservationContext context) {
      recordedCallCount.incrementAndGet();
    }

    @Override
    public boolean supportsContext(Observation.Context context) {
      return context instanceof ClientRequestObservationContext;
    }
  }
}
