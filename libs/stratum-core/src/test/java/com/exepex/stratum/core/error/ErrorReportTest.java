package com.exepex.stratum.core.error;

import static com.exepex.stratum.core.error.testsupport.ErrorTestData.CORRELATION_ID;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.CORRELATION_ID_KEY;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.LONGEST_ID_LENGTH;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.QUANTITY_PROBLEM;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SKU;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SPAN_ID;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.orders.ImpostorErrorCode;
import com.example.orders.OrderErrorCode;
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
import org.junit.jupiter.params.provider.ValueSource;
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
    @DisplayName("it takes the code's severity and fills in the description")
    void takesSeverityAndFillsDescription() {
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
      var report = ErrorReport.of(OrderErrorCode.DELAYED, "3 days");

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
    @DisplayName("an argument is inserted as text and never read as a pattern")
    void insertsArgumentsAsText() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, "{} %s %n");

      assertThat(report.getMessage()).isEqualTo("Not enough stock for {} %s %n.");
    }

    @Test
    @DisplayName("a null argument is written as null")
    void writesNullArgument() {
      assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, (Object) null).getMessage())
          .isEqualTo("Not enough stock for null.");
    }
  }

  @Nested
  @DisplayName("when it records the trace, span and correlation IDs")
  class WithIds {

    @Test
    @DisplayName("it takes them from the logging context")
    void readsIdsFromLoggingContext() {
      ErrorTestData.putIdsInLoggingContext();

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getTraceId()).isEqualTo(TRACE_ID);
      assertThat(report.getSpanId()).isEqualTo(SPAN_ID);
      assertThat(report.getCorrelationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    @DisplayName("without a logging context every ID is null")
    void leavesIdsNullWithoutContext() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getTraceId()).isNull();
      assertThat(report.getSpanId()).isNull();
      assertThat(report.getCorrelationId()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"c0a8012e-7f3d-4a8e-9a43-2f9b6f1c2d10", "order_42.step:3", "a"})
    @DisplayName("an ID of letters, digits, '-', '_', '.' and ':' is kept")
    void keepsUsableId(String id) {
      MDC.put(CORRELATION_ID_KEY, id);

      assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).getCorrelationId()).isEqualTo(id);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "order 42", "id\nforged log line", "id\r", "<script>", "ü"})
    @DisplayName("an empty ID, or one with any other character, is left out")
    void leavesOutUnusableId(String id) {
      MDC.put(CORRELATION_ID_KEY, id);

      assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).getCorrelationId()).isNull();
    }

    @Test
    @DisplayName("an ID of 128 characters is kept, and one of 129 is left out")
    void limitsIdLength() {
      var longest = "a".repeat(LONGEST_ID_LENGTH);
      MDC.put(CORRELATION_ID_KEY, longest);
      assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).getCorrelationId())
          .isEqualTo(longest);

      MDC.put(CORRELATION_ID_KEY, longest + "a");
      assertThat(ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).getCorrelationId()).isNull();
    }
  }

  @Nested
  @DisplayName("when it has field details and a cause")
  class WithDetailsAndCause {

    @Test
    @DisplayName("it keeps a copy of the details that later changes to the list do not reach")
    void copiesDetails() {
      var details = new ArrayList<>(List.of(QUANTITY_PROBLEM));

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).withDetails(details);
      details.clear();

      assertThat(report.getDetails()).containsExactly(QUANTITY_PROBLEM);
    }

    @Test
    @DisplayName("it keeps the cause, with its stack trace, for the logs")
    void keepsCause() {
      var cause = new IllegalStateException("stock service down");

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU).withCause(cause);

      assertThat(report.getCause()).isSameAs(cause);
    }
  }

  @Nested
  @DisplayName("when it is shown outside the application")
  class ToExternal {

    @Test
    @DisplayName("it keeps the code, severity, message, details, trace and correlation IDs")
    void keepsWhatCallersMaySee() {
      ErrorTestData.putIdsInLoggingContext();

      var external =
          ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU)
              .withDetails(List.of(QUANTITY_PROBLEM))
              .withCause(new IllegalStateException("internal QUANTITY_PROBLEM"))
              .toExternal();

      assertThat(external)
          .isEqualTo(
              new ExternalError(
                  "ORD-0001",
                  Severity.ERROR,
                  "Not enough stock for SKU-1.",
                  List.of(QUANTITY_PROBLEM),
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

    @Test
    @DisplayName("made directly, it keeps a copy of the details")
    void externalErrorCopiesDetails() {
      var details = new ArrayList<>(List.of(QUANTITY_PROBLEM));

      var external =
          new ExternalError("ORD-0001", Severity.ERROR, "Not enough stock.", details, null, null);
      details.clear();

      assertThat(external.details()).containsExactly(QUANTITY_PROBLEM);
    }
  }
}
