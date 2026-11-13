package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.spi.ThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import com.exepex.stratum.core.api.StratumInternal;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.test.system.CapturedOutput;

/**
 * The checks that the tests of every data module share on database errors: that a personal value
 * the database put in an error reaches neither the log nor any failure the service sees.
 */
@StratumInternal
@Slf4j
@UtilityClass
public class DatabaseFailureAssertions {

  /** A personal value that a driver's message carries and no clean copy may show. */
  public static final String PERSONAL_VALUE = "ada.lovelace@example.com";

  /** The name of the constraint the test errors violate. */
  public static final String CONSTRAINT = "customers_email_key";

  /**
   * Logs a failure as a service would, then checks that a value appears neither in the log nor
   * anywhere in the failure's chain.
   *
   * @param failure the failure the service sees, such as Spring's translated exception
   * @param output the captured log
   * @param value the value that must not appear
   */
  public static void assertServiceSeesNo(Throwable failure, CapturedOutput output, String value) {
    log.error("The database call failed", failure);
    assertThat(output.getAll()).contains("The database call failed").doesNotContain(value);
    assertChainCarriesNo(failure, value);
  }

  /**
   * Returns the first {@code SQLException} in a failure's chain.
   *
   * @param failure the failure the service sees
   * @return the database error behind it
   */
  public static SQLException sqlExceptionIn(Throwable failure) {
    return chainOf(failure)
        .filter(SQLException.class::isInstance)
        .map(SQLException.class::cast)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no SQLException in " + failure));
  }

  /**
   * Checks that a value appears in no message, no {@code toString()} and no stack trace, as Logback
   * prints it, of a failure and of every failure in its chain: causes, suppressed and next
   * exceptions.
   *
   * @param failure the failure the service sees
   * @param value the value that must not appear
   */
  public static void assertChainCarriesNo(Throwable failure, String value) {
    assertThat(ThrowableProxyUtil.asString(new ThrowableProxy(failure))).doesNotContain(value);
    chainOf(failure).forEach(member -> assertCarriesNo(member, value));
  }

  /** Checks that a value appears neither in a failure's message nor in its {@code toString()}. */
  private static void assertCarriesNo(Throwable member, String value) {
    assertThat(member.toString()).doesNotContain(value);
    assertThat(String.valueOf(member.getMessage())).doesNotContain(value);
  }

  /**
   * Returns a failure and every failure in its chain, each once, the nearest first.
   *
   * @param failure the failure
   * @return the failures
   */
  public static Stream<Throwable> chainOf(Throwable failure) {
    Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
    var inOrder = new ArrayList<Throwable>();
    var waiting = new ArrayDeque<Throwable>();
    waiting.add(failure);
    // A loop, not a stream: each failure adds the failures it links to.
    while (!waiting.isEmpty()) {
      var member = waiting.poll();
      if (seen.add(member)) {
        inOrder.add(member);
        Stream.of(member.getSuppressed()).forEach(waiting::add);
        if (member.getCause() != null) {
          waiting.add(member.getCause());
        }
        if (member instanceof SQLException sqlFailure && sqlFailure.getNextException() != null) {
          waiting.add(sqlFailure.getNextException());
        }
      }
    }
    return inOrder.stream();
  }
}
