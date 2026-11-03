package com.exepex.stratum.logging.volume;

import ch.qos.logback.classic.LoggerContext;
import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.logging.LoggingProperties.Volume;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Adds the volume filter to Logback while the application runs, when the limits are on. Logback has
 * one logging context per JVM, so it holds one such filter: the one of the application that started
 * last. Stopping removes only this application's own filter.
 */
@StratumInternal
@RequiredArgsConstructor
public class LogVolumeFilterRegistration implements SmartLifecycle {

  /** The filter to add. */
  private final LogVolumeFilter filter;

  /** The settings that say whether the limits are on. */
  private final Volume settings;

  /** Whether this registration is running. */
  private final AtomicBoolean running = new AtomicBoolean();

  /** Replaces any earlier volume filter with this application's, when the limits are on. */
  @Override
  public void start() {
    if (settings.enabled()) {
      logbackContext()
          .ifPresent(
              context -> {
                var filters = context.getTurboFilterList();
                filters.removeIf(LogVolumeFilter.class::isInstance);
                filter.setContext(context);
                filter.start();
                filters.add(filter);
              });
    }
    running.set(true);
  }

  /** Removes this application's filter, and no other. */
  @Override
  public void stop() {
    logbackContext().ifPresent(context -> context.getTurboFilterList().remove(filter));
    filter.stop();
    running.set(false);
  }

  /**
   * Returns whether the registration is running.
   *
   * @return whether it is running
   */
  @Override
  public boolean isRunning() {
    return running.get();
  }

  /** Returns Logback's logging context, when Logback is the service's logging backend. */
  private Optional<LoggerContext> logbackContext() {
    return LoggerFactory.getILoggerFactory() instanceof LoggerContext context
        ? Optional.of(context)
        : Optional.empty();
  }
}
