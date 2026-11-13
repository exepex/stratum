package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.properties.BackwardCompatibleMode;
import com.exepex.stratum.core.request.RequestTimeLeft;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import org.springframework.util.function.SingletonSupplier;

/**
 * Builds the statement limits of each data source bean, with the module's settings and one sweeper
 * thread for the whole application, which it starts with the first limits and stops with the
 * application.
 */
@StratumInternal
public class StatementLimitsFactory implements AutoCloseable {

  /** A Micrometer class, whose presence turns on the timeout counter. */
  private static final String METER_REGISTRY = "io.micrometer.core.instrument.MeterRegistry";

  /** How long a statement may run when nothing sets a limit, outside backward-compatible mode. */
  private static final Duration STANDARD_QUERY_TIMEOUT = Duration.ofSeconds(30);

  /** How much earlier than the web request's limit statements and borrows end, by default. */
  private static final Duration STANDARD_DEADLINE_MARGIN = Duration.ofMillis(150);

  /** The longest limit kept, in milliseconds; a longer one is cut to it. */
  private static final Duration LONGEST_LIMIT = Duration.ofMillis(StatementSlot.MAX_DEADLINE);

  /** The application's environment, with the settings and the mode. */
  private final Environment environment;

  /** Finds the data source beans and the meter registry. */
  private final BeanFactory beanFactory;

  /** The class loader that shows whether Micrometer is present. */
  private final ClassLoader classLoader;

  /** The module's settings, read once. */
  private final SingletonSupplier<DataProperties> settings;

  /** Guards the start and the stop of the sweeper. */
  private final Lock sweeperLock = new ReentrantLock();

  /** The one sweeper, started with the first limits; {@code null} until then. */
  private @Nullable StatementSweeper startedSweeper;

  /**
   * Creates the factory; nothing starts until the first data source is wrapped.
   *
   * @param environment the application's environment
   * @param beanFactory the bean factory, which finds the data sources and the meter registry
   * @param classLoader the application's class loader
   */
  public StatementLimitsFactory(
      Environment environment, BeanFactory beanFactory, ClassLoader classLoader) {
    this.environment = environment;
    this.beanFactory = beanFactory;
    this.classLoader = classLoader;
    this.settings = SingletonSupplier.of(() -> DataProperties.resolve(environment));
  }

  /**
   * Returns the statement limits of one data source bean.
   *
   * @param beanName the data source bean's name
   * @return its limits
   * @throws com.exepex.stratum.core.error.StratumException {@code SX-CORE-1003} if a setting breaks
   *     its rules
   */
  public StatementLimits forPool(String beanName) {
    boolean backwardCompatible = BackwardCompatibleMode.isOn(environment);
    var queryTimeout =
        Optional.ofNullable(settings.obtain().queryTimeout())
            .orElse(backwardCompatible ? Duration.ZERO : STANDARD_QUERY_TIMEOUT);
    return StatementLimits.builder()
        .pool(beanName)
        .outermost(SingletonSupplier.of(() -> beanFactory.getBean(beanName, DataSource.class)))
        .poolMillis(
            queryTimeout.compareTo(LONGEST_LIMIT) > 0
                ? StatementSlot.MAX_DEADLINE
                : queryTimeout.toMillis())
        .backwardCompatible(backwardCompatible)
        .sweeper(sweeper())
        .counter(
            ClassUtils.isPresent(METER_REGISTRY, classLoader)
                ? MeterTimeoutCounter.of(beanFactory)
                : TimeoutCounter.NONE)
        .wallClock(Clock.systemUTC())
        .requestTimeLeft(
            SingletonSupplier.of(
                () ->
                    Optional.ofNullable(
                        beanFactory.getBeanProvider(RequestTimeLeft.class).getIfAvailable())))
        .marginMillis(
            Optional.ofNullable(settings.obtain().deadlineMargin())
                .orElse(STANDARD_DEADLINE_MARGIN)
                .toMillis())
        .build();
  }

  /** Stops the sweeper thread, if it was started. */
  @Override
  public void close() {
    sweeperLock.lock();
    try {
      if (startedSweeper != null) {
        startedSweeper.close();
      }
    } finally {
      sweeperLock.unlock();
    }
  }

  /** Returns the one sweeper, started on first use. */
  private StatementSweeper sweeper() {
    sweeperLock.lock();
    try {
      if (startedSweeper == null) {
        startedSweeper = StatementSweeper.start();
      }
      return startedSweeper;
    } finally {
      sweeperLock.unlock();
    }
  }
}
