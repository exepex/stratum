package com.exepex.stratum.logging.testsupport;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import com.exepex.stratum.logging.LoggingProperties;
import com.exepex.stratum.logging.LoggingProperties.Volume;
import com.exepex.stratum.logging.volume.LogVolumeFilter;
import java.util.List;
import lombok.experimental.UtilityClass;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

/** Volume settings, logger names and trace IDs shared by the volume tests. */
@UtilityClass
public class LogVolumeTestData {

  /** The logger most tests write to. */
  public static final String ORDERS = "com.acme.orders.OrderService";

  /** The logger Spring Security's loggers sit below, itself never limited by default. */
  public static final String SECURITY_ROOT = "org.springframework.security";

  /** A second logger, whose line makes a summary due without being dropped itself. */
  public static final String OTHER = "com.acme.Other";

  /** A logger of Spring Security, which is never limited by default. */
  public static final String SECURITY = "org.springframework.security.web.FilterChainProxy";

  /** A logger whose name only starts like Spring Security's, so it is limited. */
  public static final String SECURITY_LOOKALIKE = "org.springframework.securityx.Audit";

  /** A small limit, so that a test writes only a few lines to reach it. */
  public static final int SMALL_LIMIT = 3;

  /** A W3C trace ID that one-in-two sampling keeps. */
  public static final String KEPT_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4737";

  /** A W3C trace ID that one-in-two sampling drops. */
  public static final String DROPPED_TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /**
   * Enough untraced lines that a one-in-two draw keeps some and drops some: it keeps all or none of
   * them about once in 2^199 runs.
   */
  public static final int UNTRACED_LINES = 200;

  /** One in how many lines sampling keeps when a test wants nearly every line dropped. */
  public static final int RARELY = 1_000_000;

  /**
   * Returns the default volume settings, as bound from empty configuration, for a test to change.
   *
   * @return a builder holding the defaults
   */
  public static Volume.VolumeBuilder defaults() {
    return LoggingProperties.resolve(new MockEnvironment()).volume().toBuilder();
  }

  /**
   * Returns the volume filters in the JVM's Logback context, which Spring Boot also uses.
   *
   * @return the filters, in Logback's order
   */
  public static List<TurboFilter> volumeFiltersInLogback() {
    return jvmLogbackContext().getTurboFilterList().stream()
        .filter(LogVolumeFilter.class::isInstance)
        .toList();
  }

  /**
   * Returns the JVM's Logback context.
   *
   * @return the context
   */
  public static LoggerContext jvmLogbackContext() {
    return (LoggerContext) LoggerFactory.getILoggerFactory();
  }
}
