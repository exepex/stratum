package com.exepex.stratum.data.testsupport;

import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.mock.env.MockEnvironment;

/** The names, keys and secrets the tests of named pools share. */
@UtilityClass
public class NamedPoolTestData {

  /** The name of the named pool most tests add. */
  public static final String BATCH = "batch";

  /** The data source bean of the pool {@link #BATCH}. */
  public static final String BATCH_DATA_SOURCE = "batchDataSource";

  /** The connection details bean of the pool {@link #BATCH}. */
  public static final String BATCH_DETAILS = "batchJdbcConnectionDetails";

  /** The transaction manager bean of the pool {@link #BATCH}. */
  public static final String BATCH_MANAGER = "batchTransactionManager";

  /** The first part of the pool {@link #BATCH}'s keys. */
  public static final String BATCH_KEYS = "stratum.data.sources.batch.";

  /** A password no log line, message or {@code toString} may carry. */
  public static final String SECRET = "s3cr3t";

  /** A JDBC URL of a database nothing listens on, which a cold pool never connects to. */
  public static final String URL = "jdbc:postgresql://localhost:1/orders";

  /** A URL that carries the password, as PostgreSQL's driver accepts it. */
  public static final String URL_WITH_PASSWORD = URL + "?user=app&password=" + SECRET;

  /** A URL that carries the password, as Oracle's driver accepts it. */
  public static final String ORACLE_URL_WITH_PASSWORD =
      "jdbc:oracle:thin:scott/" + SECRET + "@localhost:1/orders";

  /**
   * Returns an environment with the given operating system variables, which Spring Boot maps to
   * keys as it does in a real service.
   *
   * @param variables each variable's name and value
   * @return the environment
   */
  public static MockEnvironment withVariables(Map<String, Object> variables) {
    var environment = new MockEnvironment();
    environment
        .getPropertySources()
        .addFirst(
            new SystemEnvironmentPropertySource(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME, variables));
    return environment;
  }
}
