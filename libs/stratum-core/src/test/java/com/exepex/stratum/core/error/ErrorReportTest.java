package com.exepex.stratum.core.error;

import static com.exepex.stratum.core.error.testsupport.ErrorTestData.CORRELATION_ID;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SKU;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SPAN_ID;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.orders.ImpostorErrorCode;
import com.example.orders.OrderErrorCode;
import com.example.orders.OrderWarningCode;
import com.exepex.stratum.core.error.testsupport.ErrorTestData;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.MDC;

@DisplayName("ErrorReport records an error or warning for the logs")
class ErrorReportTest {

  /** Leaves the logging context empty for the next test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Nested
  @DisplayName("when it is made from a code")
  class FromCode {

    @Test
    @DisplayName("an error code gives the severity ERROR and fills in the description")
    void errorCodeGivesErrorSeverity() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getCode()).isEqualTo("ORD-0001");
      assertThat(report.getSeverity()).isEqualTo(Severity.ERROR);
      assertThat(report.getMessage()).isEqualTo("Not enough stock for SKU-1.");
      assertThat(report.getCodeAndMessage()).isEqualTo("ORD-0001: Not enough stock for SKU-1.");
      assertThat(report.getDetails()).isEmpty();
      assertThat(report.getCause()).isNull();
    }

    @Test
    @DisplayName("a warning code gives the severity WARNING")
    void warningCodeGivesWarningSeverity() {
      var report = ErrorReport.of(OrderWarningCode.DELAYED, "3 days");

      assertThat(report.getSeverity()).isEqualTo(Severity.WARNING);
      assertThat(report.getMessage()).isEqualTo("The order will ship 3 days late.");
    }

    @Test
    @DisplayName("Stratum's own code with the SX- prefix is accepted")
    void acceptsStratumCodeDeclaredByStratum() {
      var report = ErrorReport.of(CoreErrorCode.CUSTOMIZER_RETURNED_NULL, "Greeter");

      assertThat(report.getCode()).isEqualTo("SX-CORE-1002");
    }

    @ParameterizedTest(name = "\"{0}\"")
    @EnumSource(ImpostorErrorCode.class)
    @DisplayName("a service's code with Stratum's SX- prefix, in any case or spacing, is rejected")
    void rejectsStratumPrefixDeclaredOutsideStratum(ImpostorErrorCode impostor) {
      assertThatIllegalArgumentException()
          .isThrownBy(() -> ErrorReport.of(impostor))
          .withMessage(
              ImpostorErrorCode.class.getName()
                  + " declares "
                  + impostor.getCode()
                  + ", but the prefix SX- is reserved for Stratum."
                  + " Give the service's codes their own prefix.");
    }

    @Test
    @DisplayName("line breaks and other control characters in an argument become spaces")
    void replacesControlCharactersInArguments() {
      var report =
          ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU + "\r\n2026-10-28 INFO forged\t");

      assertThat(report.getMessage())
          .isEqualTo("Not enough stock for SKU-1  2026-10-28 INFO forged .");
    }

    @Test
    @DisplayName("a null argument is written as null")
    void writesNullArgument() {
      assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, (Object) null).getMessage())
          .isEqualTo("Not enough stock for null.");
    }
  }

  @Nested
  @DisplayName("when it records the diagnostic IDs")
  class WithIds {

    @Test
    @DisplayName("it takes the trace, span and correlation IDs from the logging context")
    void readsIdsFromLoggingContext() {
      ErrorTestData.putIdsInLoggingContext();

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getIds()).isEqualTo(new DiagnosticIds(TRACE_ID, SPAN_ID, CORRELATION_ID));
    }

    @Test
    @DisplayName("without a logging context every ID is null")
    void leavesIdsNullWithoutContext() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getIds()).isEqualTo(new DiagnosticIds(null, null, null));
    }
  }

  @Nested
  @DisplayName("when it has field details and a cause")
  class WithDetailsAndCause {

    @Test
    @DisplayName("it keeps a copy of the details that later changes to the list do not reach")
    void copiesDetails() {
      var details = new ArrayList<>(List.of(new ErrorDetail("quantity", "must be positive")));

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).withDetails(details);
      details.clear();

      assertThat(report.getDetails())
          .containsExactly(new ErrorDetail("quantity", "must be positive"));
    }

    @Test
    @DisplayName("it keeps the cause, with its stack trace, for the logs")
    void keepsCause() {
      var cause = new IllegalStateException("stock service down");

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).withCause(cause);

      assertThat(report.getCause()).isSameAs(cause);
    }

    @Test
    @DisplayName("an argument is inserted as text and never read as a pattern")
    void insertsArgumentsAsText() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, "{} %s %n");

      assertThat(report.getMessage()).isEqualTo("Not enough stock for {} %s %n.");
    }
  }

  @Nested
  @DisplayName("when it is shown outside the application")
  class ToExternal {

    @Test
    @DisplayName("it keeps the code, severity, message, details, trace and correlation IDs")
    void keepsWhatCallersMaySee() {
      ErrorTestData.putIdsInLoggingContext();
      var detail = new ErrorDetail("quantity", "must be positive");

      var external =
          ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU)
              .withDetails(List.of(detail))
              .withCause(new IllegalStateException("internal detail"))
              .toExternal();

      assertThat(external)
          .isEqualTo(
              new ExternalError(
                  "ORD-0001",
                  Severity.ERROR,
                  "Not enough stock for SKU-1.",
                  List.of(detail),
                  TRACE_ID,
                  CORRELATION_ID));
    }

    @Test
    @DisplayName("it has no field for the span ID, the cause or the stack trace")
    void hasNoInternalFields() {
      var components =
          Arrays.stream(ExternalError.class.getRecordComponents())
              .map(RecordComponent::getName)
              .toList();

      assertThat(components)
          .containsExactly("code", "severity", "message", "details", "traceId", "correlationId");
    }
  }
}
