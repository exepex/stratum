package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleSwitch;
import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.core.error.StratumStartupFailureAnalyzer;
import java.util.Objects;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;
import org.springframework.core.env.Environment;

/**
 * Reports a failed schema check as {@code SX-DATA-1001} in Spring Boot's "APPLICATION FAILED TO
 * START" report: Hibernate's message, which names the table and column, and the two ways out.
 * Spring Boot loads it from {@code META-INF/spring.factories}. It stays silent when {@code
 * stratum.data.enabled=false}.
 */
@StratumInternal
@RequiredArgsConstructor
public class SchemaValidationFailureAnalyzer implements FailureAnalyzer {

  /** The application's environment, which Spring Boot passes in and which holds the switch. */
  private final Environment environment;

  /** Writes the report the same way as for every other Stratum code. */
  private final StratumStartupFailureAnalyzer codedFailures = new StratumStartupFailureAnalyzer();

  /**
   * Describes the first failed schema check among the failure and its causes.
   *
   * @param failure what stopped startup
   * @return the report, or {@code null} when no schema check failed or the module is off
   */
  @Override
  public @Nullable FailureAnalysis analyze(Throwable failure) {
    if (!StratumModuleSwitch.isEnabled(environment, DataConstants.MODULE)) {
      return null;
    }
    return Stream.iterate(failure, Objects::nonNull, Throwable::getCause)
        .filter(SchemaManagementException.class::isInstance)
        .findFirst()
        .map(this::reportOf)
        .orElse(null);
  }

  /** Returns the report of a failed schema check, with Hibernate's message as the detail. */
  private @Nullable FailureAnalysis reportOf(Throwable schemaFailure) {
    var detail = Objects.requireNonNullElse(schemaFailure.getMessage(), "");
    return codedFailures.analyze(
        new StratumException(DataErrorCode.SCHEMA_MISMATCH, detail, schemaFailure));
  }
}
