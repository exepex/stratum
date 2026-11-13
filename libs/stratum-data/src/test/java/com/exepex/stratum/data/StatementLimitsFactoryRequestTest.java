package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.data.testsupport.NoMarginSettings;
import com.exepex.stratum.test.StarterTestSupport;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("StatementLimitsFactory gives each data source the web request's deadline")
class StatementLimitsFactoryRequestTest {

  /** A time left the default margin of 150 ms uses up, but no margin leaves. */
  private static final Duration UNDER_MARGIN = Duration.ofMillis(100);

  /** A time left the default margin leaves time in. */
  private static final Duration OVER_MARGIN = Duration.ofMillis(200);

  /** The web request's time left, as the web module's bean would read it. */
  private Optional<Duration> left = Optional.empty();

  /** Finds the web module's bean, as many times as it is asked. */
  @SuppressWarnings("unchecked")
  private final ObjectProvider<RequestTimeLeft> requestTimeLeft = mock(ObjectProvider.class);

  /** Finds no meter registry, so nothing is counted. */
  @SuppressWarnings("unchecked")
  private final ObjectProvider<MeterRegistry> meterRegistries = mock(ObjectProvider.class);

  /** The application's bean factory, with no meter registry. */
  private final BeanFactory beanFactory = mock(BeanFactory.class);

  /** Gives the bean factory the web module's bean, or none. */
  private void withBean(boolean present) {
    when(beanFactory.getBeanProvider(MeterRegistry.class)).thenReturn(meterRegistries);
    when(beanFactory.getBeanProvider(RequestTimeLeft.class)).thenReturn(requestTimeLeft);
    when(requestTimeLeft.getIfAvailable()).thenReturn(present ? () -> left : null);
  }

  /** Checks three borrows, before and after the pool, as three statements' borrows do. */
  private void borrowThreeTimes(StatementLimits limits) throws SQLException {
    limits.checkBeforeBorrow();
    limits.checkAfterBorrow();
    limits.checkBeforeBorrow();
  }

  /** Returns what a borrow with the given time left throws, or {@code null}. */
  private @Nullable Throwable borrowWith(ConfigurableEnvironment environment, Duration timeLeft) {
    left = Optional.of(timeLeft);
    try (var factory =
        new StatementLimitsFactory(
            environment, beanFactory, Thread.currentThread().getContextClassLoader())) {
      var limits = factory.forPool("ordersDataSource");
      return catchThrowable(() -> limits.checkBeforeBorrow());
    }
  }

  @Nested
  @DisplayName("with the web module's bean")
  class WithBean {

    @Test
    @DisplayName("the margin is 150 ms by default")
    void defaultMargin() {
      withBean(true);

      assertThat(borrowWith(new MockEnvironment(), UNDER_MARGIN))
          .isInstanceOf(StatementTimeoutException.class);
      assertThat(borrowWith(new MockEnvironment(), OVER_MARGIN)).isNull();
    }

    @Test
    @DisplayName("the margin is set in application.properties")
    void marginFromProperties() {
      withBean(true);
      var environment = new MockEnvironment().withProperty(DataConstants.DEADLINE_MARGIN, "0");

      assertThat(borrowWith(environment, UNDER_MARGIN)).isNull();
    }

    @Test
    @DisplayName("the margin is set in application.yml")
    void marginFromYaml() throws IOException {
      withBean(true);
      var environment = new MockEnvironment();
      new YamlPropertySourceLoader()
          .load("deadline-margin", new ClassPathResource("deadline-margin.yml"))
          .forEach(environment.getPropertySources()::addFirst);

      assertThat(borrowWith(environment, UNDER_MARGIN)).isNull();
    }

    @Test
    @DisplayName("the margin is set in Java, which wins over the files")
    void marginFromJava(@TempDir Path folder) throws IOException {
      withBean(true);
      var environment = new MockEnvironment().withProperty(DataConstants.DEADLINE_MARGIN, "1s");

      var failure =
          StarterTestSupport.withCustomizers(
              folder,
              DataPropertiesCustomizer.class,
              List.of(NoMarginSettings.class),
              () -> borrowWith(environment, UNDER_MARGIN));

      assertThat(failure).isNull();
    }

    @Test
    @DisplayName("a negative margin stops startup with SX-CORE-1003")
    void negativeMargin() {
      withBean(true);
      var environment = new MockEnvironment().withProperty(DataConstants.DEADLINE_MARGIN, "-1ms");

      var failure =
          catchThrowableOfType(StratumException.class, () -> borrowWith(environment, OVER_MARGIN));

      assertThat(failure.getErrorCode()).isEqualTo(CoreErrorCode.INVALID_SETTINGS);
    }

    @Test
    @DisplayName("the bean is looked up once, not on every statement or borrow")
    void lookedUpOnce() {
      withBean(true);
      left = Optional.of(OVER_MARGIN);
      try (var factory =
          new StatementLimitsFactory(
              new MockEnvironment(), beanFactory, Thread.currentThread().getContextClassLoader())) {
        var limits = factory.forPool("ordersDataSource");
        assertThatCode(() -> borrowThreeTimes(limits)).doesNotThrowAnyException();
      }

      verify(requestTimeLeft, times(1)).getIfAvailable();
    }
  }

  @Test
  @DisplayName("without the web module's bean there is no request limit")
  void noBean() {
    withBean(false);

    assertThat(borrowWith(new MockEnvironment(), Duration.ofMillis(-1))).isNull();
  }
}
