package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The codes of the data module, each listed with its fix in {@code docs/guides/error-codes.md}.
 * Every error here stops the application at startup.
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
          + " threshold to 0 to turn the alarm off.");

  /** The code, such as {@code SX-DATA-1001}. */
  private final String code;

  /** Whether the operation failed or only needs attention. */
  private final Severity severity;

  /** What went wrong, in fixed text. */
  private final String description;

  /** How to fix it, as the Fix column of the error codes page starts. */
  private final String fix;
}
