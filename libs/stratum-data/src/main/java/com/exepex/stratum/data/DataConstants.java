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

  /** The property that keeps statements and borrows ahead of the web request's own limit. */
  public static final String DEADLINE_MARGIN = PREFIX + ".deadline-margin";

  /**
   * The web module's map of error codes to HTTP statuses, where this module gives its own code a
   * default.
   */
  public static final String WEB_ERROR_STATUSES = "stratum.web.error.statuses";

  /** The HTTP status of a request whose statement Stratum cancelled: 503, Service Unavailable. */
  public static final int STATEMENT_TIMED_OUT_STATUS = 503;

  /** The counter of the statements Stratum cancelled and the borrows it failed, out of time. */
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

  /** A class of Micrometer Tracing's OpenTelemetry bridge, whose presence turns on query spans. */
  public static final String TRACER_BRIDGE = "io.micrometer.tracing.otel.bridge.OtelTracer";

  /** The span tag of the database system, such as {@code postgresql}. */
  public static final String SPAN_SYSTEM = "db.system.name";

  /** The span tag of the database's name. */
  public static final String SPAN_NAMESPACE = "db.namespace";

  /** The span tag of the operation the statement's SQL starts with, such as {@code SELECT}. */
  public static final String SPAN_OPERATION = "db.operation.name";

  /** The span tag of the pool the statement ran on: {@code main} or a named pool's name. */
  public static final String SPAN_POOL = "db.client.connection.pool.name";

  /** The first part of every named pool's properties, followed by the pool's name. */
  public static final String SOURCES = PREFIX + ".sources";

  /** The property that caps the connections of all pools together. */
  public static final String MAX_CONNECTIONS = PREFIX + ".max-connections";

  /** The name of the pool that {@code spring.datasource.*} configures. */
  public static final String MAIN_POOL = "main";

  /** The name of Spring Boot's own data source bean, the main pool. */
  public static final String MAIN_BEAN = "dataSource";

  /** The end of a named pool's data source bean name, after the pool's name. */
  public static final String DATA_SOURCE_SUFFIX = "DataSource";

  /** The end of a named pool's connection details bean name, after the pool's name. */
  public static final String DETAILS_SUFFIX = "JdbcConnectionDetails";

  /** The end of a named pool's transaction manager bean name, after the pool's name. */
  public static final String TRANSACTION_MANAGER_SUFFIX = "TransactionManager";

  /** HikariCP's pool, whose presence turns on the named pools and the pools' check. */
  public static final String HIKARI_POOL = "com.zaxxer.hikari.HikariDataSource";

  /** The key under which a named pool's HikariCP settings follow. */
  public static final String HIKARI_KEYS = "hikari";

  /** The name of the bean that registers the named pools' beans. */
  public static final String NAMED_POOLS = "stratumNamedPools";

  /** The name of the bean that names Spring Boot's pool {@code main}. */
  public static final String MAIN_POOL_NAME = "stratumMainPoolName";

  /** The name of the bean that checks the pools once every bean exists. */
  public static final String POOL_CHECK = "stratumPoolCheck";

  /** Whether Spring translates JDBC errors, which picks the kind of transaction manager. */
  public static final String EXCEPTION_TRANSLATION = "spring.dao.exceptiontranslation.enabled";

  /** The name of the bean that routes the mapped repositories' connections to their pools. */
  public static final String ROUTING_BEAN = "stratumRoutingDataSource";

  /** The key after a named pool's name that maps repository packages to the pool. */
  public static final String PACKAGES_KEY = "packages";

  /** Whether Spring Boot borrows a connection at transaction begin or at the first statement. */
  public static final String CONNECTION_FETCH = "spring.datasource.connection-fetch";
}
