package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the data module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * {@code SX-DATA-1001} and {@code SX-DATA-1002} stop the application at startup; {@code
 * SX-DATA-1003} fails one statement.
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
   * HikariCP would turn its leak alarm off, because the threshold is under 2 seconds or above the
   * connections' {@code max-lifetime}. The detail names the keys, never their values.
   */
  LEAK_ALARM_OFF(
      "SX-DATA-1002",
      Severity.ERROR,
      "HikariCP would turn its connection leak alarm off",
      "Set spring.datasource.hikari.leak-detection-threshold to at least 2000 and at most"
          + " spring.datasource.hikari.max-lifetime, or raise max-lifetime above it, or set the"
          + " threshold to 0 to turn the alarm off."),

  /**
   * Stratum cancelled a SQL statement that ran past its time limit. The answer carries no detail;
   * the exception's message names the limit, what set it, the run time and the data source bean.
   */
  STATEMENT_TIMED_OUT(
      "SX-DATA-1003",
      Severity.ERROR,
      "A SQL statement ran past its time limit and was cancelled",
      "Make the statement faster, or give it more time: stratum.data.query-timeout, the time limit"
          + " the code sets on the statement, or the transaction's timeout.");

  /** The code, such as {@code SX-DATA-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts. */
  private final String fix;
}
