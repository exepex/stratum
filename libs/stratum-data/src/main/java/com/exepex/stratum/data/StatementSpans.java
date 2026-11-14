package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import java.sql.Connection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Gives a statement's execute a client span, when the request that runs it is traced and sampled.
 * Its types name no tracing library, so the boundary works without one; {@link #NONE} gives no
 * spans. An implementation never throws into the JDBC call.
 */
@StratumInternal
public interface StatementSpans {

  /** What {@link #start} returns when it starts no span. */
  Object NO_SPAN = new Object();

  /** Gives no spans: the boundary of a service without tracing. */
  StatementSpans NONE = new StatementSpans() {};

  /**
   * Starts the span of an execute, when the current trace is sampled.
   *
   * @param sql the statement's SQL, read only for its first keyword; empty when unknown
   * @param batch the SQL added to a plain batch, read only when the trace is sampled; empty for
   *     none
   * @param connection the driver's connection, for the database's name and product
   * @return the started span, or {@link #NO_SPAN} when the trace is not sampled or there is none
   */
  default Object start(String sql, List<String> batch, @Nullable Connection connection) {
    return NO_SPAN;
  }

  /**
   * Ends a span this hook started.
   *
   * @param span the span {@link #start} returned, never {@link #NO_SPAN}
   * @param thrown what the execute threw, already clean, or {@code null}
   */
  default void end(Object span, @Nullable Throwable thrown) {
    // NONE starts no span, so it has none to end.
  }
}
