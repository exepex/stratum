package com.exepex.stratum.data.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import jakarta.persistence.EntityManagerFactory;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.stream.Stream;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.JDBCException;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.boot.test.system.CapturedOutput;

/** The checks the data module's tests share on database errors. */
@Slf4j
@UtilityClass
public class DataAssertions {

  /** The thread of the TLS check, which borrows one connection shortly after startup. */
  private static final String TLS_CHECK_THREAD = "stratum-database-tls-check";

  /**
   * Waits until the TLS check has finished, so that a test that counts or exhausts the pool's
   * connections counts only its own.
   */
  public static void awaitTlsCheckDone() {
    await().until(DataAssertions::tlsCheckDone);
  }

  /** Returns whether no TLS check thread is running. */
  private static boolean tlsCheckDone() {
    return Thread.getAllStackTraces().keySet().stream()
        .noneMatch(thread -> TLS_CHECK_THREAD.equals(thread.getName()));
  }

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
   * Returns what Hibernate makes of a database error, as it does for its own calls.
   *
   * @param entityManagerFactory the service's Hibernate
   * @param failure the database error
   * @return Hibernate's exception
   */
  public static JDBCException hibernateTranslationOf(
      EntityManagerFactory entityManagerFactory, SQLException failure) {
    return entityManagerFactory
        .unwrap(SessionFactoryImplementor.class)
        .getJdbcServices()
        .getSqlExceptionHelper()
        .convert(failure, "probe");
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
   * Checks that a value appears in no message, no {@code toString()} and no printed stack trace of
   * a failure and of every failure in its chain: causes, suppressed and next exceptions.
   *
   * @param failure the failure the service sees
   * @param value the value that must not appear
   */
  public static void assertChainCarriesNo(Throwable failure, String value) {
    var printed = new StringWriter();
    failure.printStackTrace(new PrintWriter(printed));
    assertThat(printed.toString()).doesNotContain(value);
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
