package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.api.StratumInternal;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.sdk.trace.data.SpanData;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import lombok.experimental.UtilityClass;

/**
 * The checks the data module's tests make on the span of each SQL statement: a client span named
 * after its operation, carrying the database system, its name and the operation, and no SQL text,
 * value or table.
 */
@StratumInternal
@UtilityClass
public class QuerySpanAssertions {

  /** The attribute of the database system, such as {@code postgresql}. */
  public static final AttributeKey<String> SYSTEM = AttributeKey.stringKey("db.system.name");

  /** The attribute of the database's name. */
  public static final AttributeKey<String> NAMESPACE = AttributeKey.stringKey("db.namespace");

  /** The attribute of the statement's operation, such as {@code SELECT}. */
  public static final AttributeKey<String> OPERATION = AttributeKey.stringKey("db.operation.name");

  /** Every attribute a statement span may carry. */
  private static final Set<String> ALLOWED =
      Set.of(SYSTEM.getKey(), NAMESPACE.getKey(), OPERATION.getKey());

  /**
   * Returns the statement spans among the spans that ended: the client spans.
   *
   * @param spans the spans that ended
   * @return the statement spans, in order
   */
  public static List<SpanData> statementSpans(Collection<SpanData> spans) {
    return spans.stream().filter(span -> span.getKind() == SpanKind.CLIENT).toList();
  }

  /**
   * Checks that a statement span is a client span named after its operation, with only the allowed
   * attributes, and no trace of a value the statement held.
   *
   * @param span the statement's span
   * @param operation the operation it must carry, such as {@code SELECT}
   * @param literal a value the statement held, which no part of the span may carry
   */
  public static void assertStatementSpan(SpanData span, String operation, String literal) {
    assertThat(span.getKind()).isEqualTo(SpanKind.CLIENT);
    assertThat(span.getName()).isEqualTo(operation);
    assertThat(span.getAttributes().get(OPERATION)).isEqualTo(operation);
    assertThat(span.getAttributes().asMap().keySet())
        .map(AttributeKey::getKey)
        .allMatch(ALLOWED::contains);
    assertThat(span.getAttributes().toString()).doesNotContain(literal);
    assertThat(span.getEvents().toString()).doesNotContain(literal);
  }
}
