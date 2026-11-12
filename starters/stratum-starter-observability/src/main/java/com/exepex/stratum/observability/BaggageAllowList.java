package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import io.opentelemetry.context.propagation.ContextPropagators;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;

/**
 * Makes every call and message the service sends or reads carry only the allowed baggage entries,
 * by wrapping the propagators that Spring Boot's tracing and OpenTelemetry use, the service's own
 * included.
 */
@StratumInternal
@RequiredArgsConstructor
public class BaggageAllowList implements BeanPostProcessor {

  /** The baggage names that are kept, matched exactly. */
  private final Set<String> allowedNames;

  /**
   * Returns the allow-list of the service's settings: {@code stratum.observability.baggage.allowed}
   * and Spring Boot's {@code management.tracing.baggage.remote-fields}.
   *
   * @param environment the application's environment
   * @return the allow-list
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public static BaggageAllowList from(Environment environment) {
    var allowed = ObservabilityProperties.resolve(environment).baggage().allowed();
    var remoteFields =
        Binder.get(environment)
            .bind(ObservabilityConstants.BAGGAGE_REMOTE_FIELDS, Bindable.listOf(String.class))
            .orElseGet(List::of);
    return new BaggageAllowList(
        Stream.concat(allowed.stream(), remoteFields.stream())
            .collect(Collectors.toUnmodifiableSet()));
  }

  /**
   * Wraps a {@code ContextPropagators} bean so that it keeps only the allowed baggage entries;
   * every other bean is returned unchanged.
   *
   * @param bean the bean
   * @param beanName the bean's name
   * @return the wrapped propagators, or the bean itself
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    return bean instanceof ContextPropagators propagators
        ? ContextPropagators.create(
            new AllowedBaggagePropagator(propagators.getTextMapPropagator(), allowedNames))
        : bean;
  }
}
