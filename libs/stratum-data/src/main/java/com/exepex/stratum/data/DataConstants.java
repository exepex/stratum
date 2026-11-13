package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;

/** The property keys and names the data module uses (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class DataConstants {

  /** The module's name, as its switch {@code stratum.data.enabled} spells it. */
  public static final String MODULE = "data";

  /** The first part of every data property. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The module's on/off property. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The part of the error codes that names this module, as in {@code SX-DATA-1001}. */
  public static final String ERROR_CODE_MODULE = "DATA";

  /** How long a caller waits for a free connection from the pool, in milliseconds. */
  public static final String CONNECTION_TIMEOUT = "spring.datasource.hikari.connection-timeout";

  /** How long a connection may be held before HikariCP logs a leak warning, in milliseconds. */
  public static final String LEAK_DETECTION_THRESHOLD =
      "spring.datasource.hikari.leak-detection-threshold";

  /** How long HikariCP keeps a connection before it closes and replaces it, in milliseconds. */
  public static final String MAX_LIFETIME = "spring.datasource.hikari.max-lifetime";

  /** The time limit of every transaction that sets none of its own. */
  public static final String TRANSACTION_TIMEOUT = "spring.transaction.default-timeout";

  /** A class of Spring Batch, whose presence means the service runs batch jobs. */
  public static final String SPRING_BATCH_JOB = "org.springframework.batch.core.job.Job";

  /** The property that limits how long one statement may run. */
  public static final String QUERY_TIMEOUT = PREFIX + ".query-timeout";

  /** The counter of the statements Stratum cancelled because they ran past their limit. */
  public static final String TIMEOUTS_METER = "stratum.data.timeouts";

  /** The name of the bean that wraps every data source in the JDBC boundary. */
  public static final String JDBC_BOUNDARY = "stratumJdbcBoundary";

  /** The name of the bean that checks whether each data source's connection is encrypted. */
  public static final String TLS_CHECK = "stratumDatabaseTlsCheck";

  /** The name of the bean that gives each PostgreSQL pool bean a login limit. */
  public static final String LOGIN_TIMEOUT = "stratumPostgresLoginTimeout";

  /** The name of the bean that wraps the connection Spring Boot builds for Flyway. */
  public static final String FLYWAY_BOUNDARY = "stratumFlywayBoundary";

  /** The name of the bean that wraps the connection Spring Boot builds for Liquibase. */
  public static final String LIQUIBASE_BOUNDARY = "stratumLiquibaseBoundary";
}
