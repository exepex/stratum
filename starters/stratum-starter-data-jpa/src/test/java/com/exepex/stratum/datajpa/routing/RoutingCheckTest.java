package com.exepex.stratum.datajpa.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.test.LogLineCapture;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;

@DisplayName("RoutingCheck keeps every mapped pool on main's database product and logs the mapping")
class RoutingCheckTest {

  private static final String BATCH = "batch";

  /** A package a pool maps. */
  private static final String ACME = "com.acme";

  private static final String POSTGRES_URL = "jdbc:postgresql://db:5432/orders";

  /** Captures the check's lines. */
  private final LogLineCapture lines = new LogLineCapture(RoutingCheck.class.getName());

  /** The check's logger, whose level the tests lower to see the DEBUG line. */
  private final Logger logger = (Logger) LoggerFactory.getLogger(RoutingCheck.class);

  private final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

  /** Starts capturing the check's lines, DEBUG included. */
  @BeforeEach
  void startCapture() {
    logger.setLevel(Level.DEBUG);
    lines.start();
  }

  /** Stops the capture and resets the level. */
  @AfterEach
  void stopCapture() {
    lines.stop();
    logger.setLevel(null);
  }

  /** Defines main's connection, a default candidate as Spring Boot's is. */
  private void mainOn(String url) {
    beanFactory.registerBeanDefinition(
        "jdbcConnectionDetails",
        new RootBeanDefinition(JdbcConnectionDetails.class, () -> on(url)));
  }

  /** Defines a named pool's connection, no default candidate, as Stratum's is. */
  private void poolOn(String pool, String url) {
    var definition = new RootBeanDefinition(JdbcConnectionDetails.class, () -> on(url));
    definition.setDefaultCandidate(false);
    beanFactory.registerBeanDefinition(pool + "JdbcConnectionDetails", definition);
  }

  /** Returns a connection to a URL. */
  private JdbcConnectionDetails on(String url) {
    var details = mock(JdbcConnectionDetails.class);
    when(details.getJdbcUrl()).thenReturn(url);
    return details;
  }

  /** Runs the check for pools mapped by packages and annotations. */
  private void check(Map<String, List<String>> packages, Map<String, List<String>> annotated) {
    new RoutingCheck(
            beanFactory,
            RepositoryPools.builder()
                .pools(Map.of())
                .packages(packages)
                .annotated(annotated)
                .build())
        .afterSingletonsInstantiated();
  }

  /** Returns the captured lines at a level. */
  private List<String> linesAt(Level level) {
    return lines.lines().stream()
        .filter(line -> level.equals(line.getLevel()))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  @Nested
  @DisplayName("the database product")
  class Product {

    /** Pairs of main's and a pool's URL whose products differ. */
    private static final List<Arguments> DIFFERENT_PRODUCTS =
        List.of(
            Arguments.of(POSTGRES_URL, "jdbc:mysql://db:3306/orders", "MYSQL"),
            Arguments.of(
                "jdbc:mysql://db:3306/orders", "jdbc:mariadb://db:3306/orders", "MARIADB"));

    /** URLs whose product a URL does not show. */
    private static final List<String> UNKNOWN_URLS =
        List.of(
            "jdbc:tc:postgresql:17:///orders",
            "jdbc:aws-wrapper:postgresql://db:5432/orders",
            "jdbc:unknowndb://db/orders");

    @Test
    @DisplayName("a pool on main's product passes")
    void sameProductPasses() {
      mainOn(POSTGRES_URL);
      poolOn(BATCH, "jdbc:postgresql://replica:5432/orders");

      assertThatCode(() -> check(Map.of(BATCH, List.of(ACME)), Map.of()))
          .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{2}")
    @FieldSource("DIFFERENT_PRODUCTS")
    @DisplayName("a pool on another product fails with SX-DATA-1007, naming products, not URLs")
    void otherProductFails(String mainUrl, String poolUrl, String poolProduct) {
      mainOn(mainUrl);
      poolOn(BATCH, poolUrl);

      var failure =
          catchThrowableOfType(
              StratumException.class, () -> check(Map.of(BATCH, List.of(ACME)), Map.of()));

      assertThat(failure.getErrorCode()).isEqualTo(DataErrorCode.ROUTING_INVALID);
      assertThat(failure).hasMessageContaining("the pool batch runs " + poolProduct);
      assertThat(failure.getMessage()).doesNotContain("jdbc:");
    }

    @ParameterizedTest(name = "{0}")
    @FieldSource("UNKNOWN_URLS")
    @DisplayName("a URL that does not show its product is skipped, with a DEBUG line only")
    void unknownSkipped(String poolUrl) {
      mainOn(POSTGRES_URL);
      poolOn(BATCH, poolUrl);

      assertThatCode(() -> check(Map.of(BATCH, List.of(ACME)), Map.of()))
          .doesNotThrowAnyException();
      assertThat(linesAt(Level.DEBUG)).singleElement().asString().contains("'batch'");
    }
  }

  @Nested
  @DisplayName("the startup line")
  class StartupLine {

    /** Five packages, two more than the line names. */
    private static final List<String> FIVE_PACKAGES =
        List.of("com.acme.p1", "com.acme.p2", "com.acme.p3", "com.acme.p4", "com.acme.p5");

    @Test
    @DisplayName("names the first three packages and counts the rest")
    void countsTheRest() {
      mainOn(POSTGRES_URL);
      poolOn(BATCH, POSTGRES_URL);

      check(Map.of(BATCH, FIVE_PACKAGES), Map.of());

      assertThat(linesAt(Level.INFO))
          .containsExactly(
              "Pool 'batch' serves repositories in 5 packages: "
                  + "com.acme.p1, com.acme.p2, com.acme.p3 and 2 more");
    }

    @Test
    @DisplayName("names one package and one annotated interface in the singular")
    void singular() {
      mainOn(POSTGRES_URL);
      poolOn(BATCH, POSTGRES_URL);

      check(Map.of(BATCH, List.of(ACME)), Map.of(BATCH, List.of("com.x.Jobs")));

      assertThat(linesAt(Level.INFO))
          .containsExactly(
              "Pool 'batch' serves repositories in 1 package: com.acme,"
                  + " and 1 annotated interface: com.x.Jobs");
    }

    @Test
    @DisplayName("names annotated interfaces alone when the pool maps no package")
    void annotatedOnly() {
      mainOn(POSTGRES_URL);
      poolOn(BATCH, POSTGRES_URL);

      check(Map.of(), Map.of(BATCH, List.of("com.x.Jobs", "com.x.Runs")));

      assertThat(linesAt(Level.INFO))
          .containsExactly(
              "Pool 'batch' serves repositories in 2 annotated interfaces: com.x.Jobs, com.x.Runs");
    }
  }
}
