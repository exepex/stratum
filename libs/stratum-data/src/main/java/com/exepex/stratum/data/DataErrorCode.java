package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the data module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * {@code SX-DATA-1003} fails one statement and {@code SX-DATA-1008} one repository call or
 * statement; every other code stops the application at startup.
 */
@StratumInternal
@Getter
@RequiredArgsConstructor
public enum DataErrorCode implements ErrorCode {

  /**
   * Hibernate found that an entity does not match the database schema. The detail is Hibernate's
   * message, which names the table and the column, never a value.
   */
  SCHEMA_MISMATCH(
      "SX-DATA-1001",
      Severity.ERROR,
      "An entity does not match the database schema",
      "Fix the entity mapping or add the missing migration, or, to stop Hibernate checking the"
          + " schema, set spring.jpa.hibernate.ddl-auto=none."),

  /**
   * HikariCP would turn a pool's leak alarm off, because the threshold is under 2 seconds or above
   * the connections' {@code max-lifetime}. The detail names the pool's keys, never their values.
   */
  LEAK_ALARM_OFF(
      "SX-DATA-1002",
      Severity.ERROR,
      "HikariCP would turn its connection leak alarm off",
      "Set the pool's leak-detection-threshold to at least 2000 and at most its max-lifetime, or"
          + " raise max-lifetime above it, or set the threshold to 0 to turn the alarm off: under"
          + " spring.datasource.hikari for the main pool, or stratum.data.sources.<name>.hikari for"
          + " the named pool the message names."),

  /**
   * Stratum cancelled a SQL statement that ran past its time limit. The answer carries no detail;
   * the exception's message names the limit, what set it, the run time and the pool.
   */
  STATEMENT_TIMED_OUT(
      "SX-DATA-1003",
      Severity.ERROR,
      "A SQL statement ran past its time limit and was cancelled",
      "Make the statement faster, or give it more time: stratum.data.query-timeout, the time limit"
          + " the code sets on the statement, or the transaction's timeout."),

  /**
   * A named pool's name or key breaks its rules, a pool has no connection, or two pools share one
   * name. The detail names the property key or the pool, never a value.
   */
  POOL_SETTING_INVALID(
      "SX-DATA-1004",
      Severity.ERROR,
      "A named pool's name or setting is not valid",
      "Give each pool under stratum.data.sources a name of lower-case letters and digits that"
          + " starts with a letter, is at most 31 characters and is not main; spell each key as"
          + " the data module's page lists it; give each pool a url or a connection details bean;"
          + " and give no two pools the same pool name."),

  /**
   * The pools together may open more connections than {@code stratum.data.max-connections}. The
   * detail names the key, the total and each pool's maximum.
   */
  TOO_MANY_CONNECTIONS(
      "SX-DATA-1005",
      Severity.ERROR,
      "The pools together may open more connections than the cap",
      "Lower a pool's maximum-pool-size, or raise stratum.data.max-connections to at least the"
          + " total the message names."),

  /**
   * A service's bean has a name Stratum keeps for a named pool and is a default candidate, which
   * would take the place of Spring Boot's own bean. The detail names the bean.
   */
  POOL_BEAN_DEFAULT_CANDIDATE(
      "SX-DATA-1006",
      Severity.ERROR,
      "A bean with a named pool's bean name would replace Spring Boot's own",
      "Declare the bean with @Bean(defaultCandidate = false), so that unqualified injection keeps"
          + " Spring Boot's own bean, or rename it."),

  /**
   * A mapping of repositories to named pools is not valid, or the routing it needs cannot be built.
   * The detail names the key, package, repository or pool, never a value of the connection.
   */
  ROUTING_INVALID(
      "SX-DATA-1007",
      Severity.ERROR,
      "A mapping of repositories to a named pool is not valid",
      "Give each packages entry a Java package name with no wildcard and map it to one pool only,"
          + " map only packages that hold JPA repositories, name only existing pools in"
          + " @UseDataSource, keep every mapped pool on main's database product, and use Spring"
          + " Boot's dataSource and no other primary DataSource, or drop the mapping."),

  /**
   * A transaction already on one pool reached a repository or a statement of another pool. The
   * detail names the pools only, never a class, since a web answer can carry it.
   */
  POOL_MIXED(
      "SX-DATA-1008",
      Severity.ERROR,
      "One transaction reached two pools",
      "Keep each transaction on one pool: call the other pool's repositories in their own"
          + " transaction, and use @Transactional with the JPA transaction manager, not a named"
          + " pool's transaction manager, around mapped repositories."),

  /**
   * A servlet web application keeps open-in-view on while repositories are mapped to named pools,
   * so one request's connection would serve every pool.
   */
  OPEN_IN_VIEW_WITH_ROUTING(
      "SX-DATA-1009",
      Severity.ERROR,
      "open-in-view is on while repositories are mapped to named pools",
      "Set spring.jpa.open-in-view=false.");

  /** The code, such as {@code SX-DATA-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts. */
  private final String fix;
}
