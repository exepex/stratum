package com.exepex.stratum.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.LogLineCapture;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.codec.ByteArrayCodec;
import io.lettuce.core.output.StatusOutput;
import io.lettuce.core.protocol.Command;
import io.lettuce.core.protocol.CommandArgs;
import io.lettuce.core.protocol.CommandType;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;

@DisplayName("CacheCommandTimeouts gives each cache's Redis commands their own timeout")
class CacheCommandTimeoutsTest {

  /** The global {@code spring.data.redis.timeout}. */
  private static final Duration GLOBAL = Duration.ofSeconds(1);

  /** The timeout of the slow cache, above the global one. */
  private static final Duration REPORTS_TIMEOUT = Duration.ofSeconds(3);

  /** The timeout of the fast cache, below the global one. */
  private static final Duration QUOTES_TIMEOUT = Duration.ofMillis(200);

  /** A cache timeout shorter than the 1 ms Lettuce counts in, in ns. */
  private static final long SUB_MILLISECOND_NANOS = 500_000;

  /** The cache with a timeout above the global one. */
  private static final String REPORTS = "reports";

  /** A key of the reports cache, as Spring Data writes it. */
  private static final String REPORTS_KEY = "orders:reports::42";

  /** The key prefix of the service. */
  private static final String PREFIX = "orders:";

  /** Two caches with their own timeout, one above and one below the global one. */
  private static final Map<String, Duration> CACHE_TIMEOUTS =
      Map.of(REPORTS, REPORTS_TIMEOUT, "quotes", QUOTES_TIMEOUT);

  private final CacheCommandTimeouts timeouts = timeoutsFor(CACHE_TIMEOUTS);

  /** Returns the timeouts for the given caches, with the global value and the prefix above. */
  private CacheCommandTimeouts timeoutsFor(Map<String, Duration> cacheTimeouts) {
    return timeoutsFor(GLOBAL, cacheTimeouts);
  }

  /** Returns the timeouts for the given global value and caches, with the prefix above. */
  private CacheCommandTimeouts timeoutsFor(Duration global, Map<String, Duration> cacheTimeouts) {
    return new CacheCommandTimeouts(
        CacheTimeoutSettings.builder()
            .global(global)
            .cacheTimeouts(cacheTimeouts)
            .keyPrefix(PREFIX)
            .build());
  }

  /** Returns a GET of the key, as Spring Data sends it. */
  private Command<byte[], byte[], String> get(String key) {
    return new Command<>(
        CommandType.GET,
        new StatusOutput<>(ByteArrayCodec.INSTANCE),
        new CommandArgs<>(ByteArrayCodec.INSTANCE).addKey(key.getBytes(StandardCharsets.UTF_8)));
  }

  @Nested
  @DisplayName("for each command")
  class PerCommand {

    @Test
    @DisplayName("a command of a cache with its own timeout gets that timeout, higher or lower")
    void cacheCommandGetsOwnTimeout() {
      assertThat(timeouts.getTimeout(get(REPORTS_KEY))).isEqualTo(REPORTS_TIMEOUT.toMillis());
      assertThat(timeouts.getTimeout(get("orders:quotes::42")))
          .isEqualTo(QUOTES_TIMEOUT.toMillis());
    }

    @Test
    @DisplayName("a cache timeout below 1 ms gets 1 ms, never 0, which would wait forever")
    void belowOneMillisecondGetsOne() {
      var subMillisecond = timeoutsFor(Map.of(REPORTS, Duration.ofNanos(SUB_MILLISECOND_NANOS)));

      assertThat(subMillisecond.getTimeout(get(REPORTS_KEY))).isOne();
    }

    @Test
    @DisplayName("a global timeout below 1 ms gets 1 ms for every other command, never 0")
    void globalBelowOneMillisecondGetsOne() {
      var subMillisecondGlobal =
          timeoutsFor(Duration.ofNanos(SUB_MILLISECOND_NANOS), CACHE_TIMEOUTS);

      assertThat(subMillisecondGlobal.getTimeout(get("session-42"))).isOne();
    }

    @Test
    @DisplayName(
        "a global timeout of 0 stays no timeout for other commands, as Lettuce reads it, while a"
            + " cache keeps its own value")
    void zeroGlobalStaysNoTimeout() {
      var noGlobalTimeout = timeoutsFor(Duration.ZERO, CACHE_TIMEOUTS);

      assertThat(noGlobalTimeout.getTimeout(get("session-42"))).isZero();
      assertThat(noGlobalTimeout.getTimeout(get(REPORTS_KEY)))
          .isEqualTo(REPORTS_TIMEOUT.toMillis());
    }

    @Test
    @DisplayName(
        "any other command gets the global timeout, never 0 or -1, which would wait forever")
    void otherCommandsGetGlobal() {
      var ping =
          new Command<>(
              CommandType.PING,
              new StatusOutput<>(ByteArrayCodec.INSTANCE),
              new CommandArgs<>(ByteArrayCodec.INSTANCE));

      assertThat(timeouts.getTimeout(get("orders:products::42"))).isEqualTo(GLOBAL.toMillis());
      assertThat(timeouts.getTimeout(get("session-42"))).isEqualTo(GLOBAL.toMillis());
      assertThat(timeouts.getTimeout(ping)).isEqualTo(GLOBAL.toMillis());
      assertThat(timeouts.getTimeout(null)).isEqualTo(GLOBAL.toMillis());
    }
  }

  @Nested
  @DisplayName("applied to Lettuce")
  class AppliedToLettuce {

    private final LogLineCapture infoLines =
        new LogLineCapture(CacheCommandTimeouts.class.getName());

    /** Captures the startup line. */
    @BeforeEach
    void startCapture() {
      infoLines.start();
    }

    /** Stops capturing. */
    @AfterEach
    void stopCapture() {
      infoLines.stop();
    }

    @Test
    @DisplayName(
        "the connection waits for the largest value, Lettuce asks for each command's timeout,"
            + " and one line says the largest value also bounds every connect handshake")
    void raisesSharedTimeout() {
      var configuration = LettuceClientConfiguration.builder().commandTimeout(GLOBAL);
      var options = ClientOptions.builder().timeoutOptions(TimeoutOptions.enabled());

      timeouts.customize(configuration);
      timeouts.customize(options);

      assertThat(configuration.build().getCommandTimeout()).isEqualTo(REPORTS_TIMEOUT);
      var timeoutOptions = options.build().getTimeoutOptions();
      assertThat(timeoutOptions.getSource()).isSameAs(timeouts);
      assertThat(timeoutOptions.isApplyConnectionTimeout()).isFalse();
      assertThat(infoLines.onlyLine().getFormattedMessage())
          .contains("raised to PT3S")
          .contains("connect handshake");
    }

    @Test
    @DisplayName(
        "with only lower values, the connection keeps the global timeout and nothing is logged")
    void keepsGlobalForLowerValues() {
      var lowerOnly = timeoutsFor(Map.of("quotes", QUOTES_TIMEOUT));
      var configuration = LettuceClientConfiguration.builder().commandTimeout(GLOBAL);

      lowerOnly.customize(configuration);

      assertThat(configuration.build().getCommandTimeout()).isEqualTo(GLOBAL);
      assertThat(infoLines.lines()).isEmpty();
    }

    @Test
    @DisplayName("with no cache value, Spring Boot's Lettuce settings stay exactly as they are")
    void changesNothingWithoutValues() {
      var none = timeoutsFor(Map.of());
      var bootOptions = TimeoutOptions.enabled();
      var configuration = LettuceClientConfiguration.builder().commandTimeout(GLOBAL);
      var options = ClientOptions.builder().timeoutOptions(bootOptions);

      none.customize(configuration);
      none.customize(options);

      assertThat(configuration.build().getCommandTimeout()).isEqualTo(GLOBAL);
      assertThat(options.build().getTimeoutOptions()).isSameAs(bootOptions);
      assertThat(infoLines.lines()).isEmpty();
    }
  }

  @Nested
  @DisplayName("checked at startup")
  class StartupCheck {

    /** The cap while the global timeout is lower. */
    private static final Duration CAP = Duration.ofSeconds(10);

    /** A global timeout above the cap, which lifts it. */
    private static final Duration HIGH_GLOBAL = Duration.ofSeconds(30);

    /** A cache value between the cap and {@link #HIGH_GLOBAL}. */
    private static final Duration ABOVE_CAP = Duration.ofSeconds(20);

    @Test
    @DisplayName(
        "a value above 10 seconds stops startup with SX-CORE-1003, naming the key, not the cache")
    void rejectsAboveCap() {
      var tooLong = Map.of(REPORTS, CAP.plusMillis(1));

      assertThatExceptionOfType(StratumException.class)
          .isThrownBy(() -> timeoutsFor(tooLong))
          .withMessageStartingWith("SX-CORE-1003")
          .withMessageContaining("stratum.cache.caches[*].command-timeout")
          .withMessageNotContaining(REPORTS);
    }

    @Test
    @DisplayName("10 seconds exactly is allowed")
    void allowsCap() {
      var atCap = timeoutsFor(Map.of(REPORTS, CAP));

      assertThat(atCap.getTimeout(get(REPORTS_KEY))).isEqualTo(CAP.toMillis());
    }

    @Test
    @DisplayName("a global timeout above 10 seconds lifts the cap to the global value")
    void globalLiftsCap() {
      var cacheTimeouts = Map.of(REPORTS, ABOVE_CAP);
      var lifted = timeoutsFor(HIGH_GLOBAL, cacheTimeouts);

      assertThat(lifted.getTimeout(get(REPORTS_KEY))).isEqualTo(ABOVE_CAP.toMillis());
    }
  }
}
