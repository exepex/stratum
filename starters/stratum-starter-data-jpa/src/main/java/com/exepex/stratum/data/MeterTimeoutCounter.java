package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Counts the cancelled statements in the counter {@code stratum.data.timeouts}, tagged with {@code
 * pool}, the data source bean, and {@code cause}: {@code transaction}, {@code query-timeout} or
 * {@code caller}. The registry is looked up at the first count, since the boundary is set up before
 * any meter registry exists.
 */
@StratumInternal
@RequiredArgsConstructor
public class MeterTimeoutCounter implements TimeoutCounter {

  /** The meter registries of the application; none when metrics are off. */
  private final ObjectProvider<MeterRegistry> registries;

  /**
   * Returns the counter of an application, which finds its meter registry at the first count. Only
   * called when Micrometer is present, so no other class refers to it.
   *
   * @param beanFactory the bean factory, which finds the registry
   * @return the counter
   */
  static TimeoutCounter of(BeanFactory beanFactory) {
    return new MeterTimeoutCounter(beanFactory.getBeanProvider(MeterRegistry.class));
  }

  /**
   * Counts one cancelled statement, when the application has a meter registry.
   *
   * @param pool the data source bean's name
   * @param source what set the limit
   */
  @Override
  public void count(String pool, LimitSource source) {
    registries.ifAvailable(
        registry ->
            registry
                .counter(DataConstants.TIMEOUTS_METER, "pool", pool, "cause", source.getTag())
                .increment());
  }
}
