package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.Serial;
import java.sql.SQLTimeoutException;
import org.jspecify.annotations.Nullable;

/**
 * A statement Stratum cancelled because it ran past its time limit, with SQLState {@code 57014}, as
 * PostgreSQL reports a cancelled statement. Spring and Hibernate turn it into their {@code
 * QueryTimeoutException}. Its message holds {@code SX-DATA-1003}, the limit, what set it, the run
 * time and the data source bean, never SQL or a value. With {@code
 * stratum.backward-compatible=true} its cause is the driver's clean error, so the service's error
 * answers stay as they were; otherwise its cause is a {@code StratumException} with {@code
 * SX-DATA-1003} and no detail, which the web starters answer with status 503 by default.
 */
@StratumInternal
public class StatementTimeoutException extends SQLTimeoutException {

  @Serial private static final long serialVersionUID = 1L;

  /** The SQLState of a cancelled statement. */
  static final String CANCELLED = "57014";

  /**
   * Creates the exception.
   *
   * @param message the code, the limit, what set it, the run time and the data source bean; never
   *     SQL or a value
   * @param cause the driver's clean error, or the {@code StratumException} that carries the code;
   *     {@code null} in compatible mode when the statement never reached the driver
   */
  public StatementTimeoutException(String message, @Nullable Throwable cause) {
    super(message, CANCELLED, cause);
  }
}
