package com.exepex.stratum.data.testsupport;

import static org.awaitility.Awaitility.await;

import jakarta.persistence.EntityManagerFactory;
import java.sql.SQLException;
import lombok.experimental.UtilityClass;
import org.hibernate.JDBCException;
import org.hibernate.engine.spi.SessionFactoryImplementor;

/**
 * The checks only the data JPA starter's tests share: Hibernate's view of a database error, and the
 * wait for the TLS check. The checks every data module shares are in {@code
 * DatabaseFailureAssertions}.
 */
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
}
