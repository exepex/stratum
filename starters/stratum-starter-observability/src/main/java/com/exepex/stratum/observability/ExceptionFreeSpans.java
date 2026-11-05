package com.exepex.stratum.observability;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.micrometer.tracing.opentelemetry.autoconfigure.SpanExporters;

/**
 * Keeps exception data out of every span the service sends, by wrapping each exporter that Spring
 * Boot's span processor uses, the service's own exporter beans included.
 */
@StratumInternal
public class ExceptionFreeSpans implements BeanPostProcessor {

  /**
   * Wraps each exporter of a {@code SpanExporters} bean so that it sends no exception data; every
   * other bean is returned unchanged.
   *
   * @param bean the bean
   * @param beanName the bean's name
   * @return the wrapped exporters, or the bean itself
   */
  @Override
  public Object postProcessAfterInitialization(Object bean, String beanName) {
    return bean instanceof SpanExporters exporters
        ? SpanExporters.of(exporters.list().stream().map(ExceptionFreeSpanExporter::new).toList())
        : bean;
  }
}
