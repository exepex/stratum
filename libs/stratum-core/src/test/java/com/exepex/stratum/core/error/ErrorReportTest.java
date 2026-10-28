package com.exepex.stratum.core.error;

import static com.exepex.stratum.core.error.testsupport.ErrorTestData.LONGEST_ID_LENGTH;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.QUANTITY_PROBLEM;
import static com.exepex.stratum.core.error.testsupport.ErrorTestData.SKU;
import static com.exepex.stratum.test.LoggingContextIds.CORRELATION_ID;
import static com.exepex.stratum.test.LoggingContextIds.CORRELATION_ID_KEY;
import static com.exepex.stratum.test.LoggingContextIds.SPAN_ID;
import static com.exepex.stratum.test.LoggingContextIds.TRACE_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.example.orders.ImpostorErrorCode;
import com.example.orders.OrderErrorCode;
import com.exepex.stratum.test.LoggingContextIds;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.jspecify.annotations.Nullable;
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
    @DisplayName("without a detail, its message is the code's description")
    void takesSeverityAndDescription() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK);
      var external = report.getExternal();

      assertThat(external.code()).isEqualTo("ORD-0001");
      assertThat(external.severity()).isEqualTo(Severity.ERROR);
      assertThat(external.message()).isEqualTo("Not enough stock");
      assertThat(report.getCodeAndMessage()).isEqualTo("ORD-0001: Not enough stock");
      assertThat(external.details()).isEmpty();
      assertThat(report.getCause()).isNull();
    }

    @Test
    @DisplayName("with a detail, its message is the description, a colon and the detail")
    void appendsDetail() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getCodeAndMessage()).isEqualTo("ORD-0001: Not enough stock: SKU-1");
    }

    @Test
    @DisplayName("a warning code gives the severity WARNING")
    void warningCodeGivesWarningSeverity() {
      var report = ErrorReport.of(OrderErrorCode.DELAYED, "3 days");

      assertThat(report.getExternal().severity()).isEqualTo(Severity.WARNING);
      assertThat(report.getExternal().message()).isEqualTo("The order will ship late: 3 days");
    }

    @Test
    @DisplayName("Stratum's own code with the SX- prefix is accepted")
    void acceptsStratumCodeDeclaredByStratum() {
      var report = ErrorReport.of(CoreErrorCode.CUSTOMIZER_BROKEN, "Greeter");

      assertThat(report.getExternal().code()).isEqualTo("SX-CORE-1002");
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
    @DisplayName("line breaks and other control characters in the detail become spaces")
    void replacesControlCharactersInDetail() {
      var report =
          ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU + "\r\n2026-10-28 INFO forged\t");

      assertThat(report.getExternal().message())
          .isEqualTo("Not enough stock: SKU-1  2026-10-28 INFO forged ");
    }
  }

  @Nested
  @DisplayName("when it records the trace, span and correlation IDs")
  class WithIds {

    @Test
    @DisplayName("it takes them from the logging context")
    void readsIdsFromLoggingContext() {
      LoggingContextIds.putInLoggingContext();

      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getExternal().traceId()).isEqualTo(TRACE_ID);
      assertThat(report.getSpanId()).isEqualTo(SPAN_ID);
      assertThat(report.getExternal().correlationId()).isEqualTo(CORRELATION_ID);
    }

    @Test
    @DisplayName("without a logging context every ID is null")
    void leavesIdsNullWithoutContext() {
      var report = ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU);

      assertThat(report.getExternal().traceId()).isNull();
      assertThat(report.getSpanId()).isNull();
      assertThat(report.getExternal().correlationId()).isNull();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"c0a8012e-7f3d-4a8e-9a43-2f9b6f1c2d10", "order_42.step:3", "a"})
    @DisplayName("an ID of letters, digits, '-', '_', '.' and ':' is kept")
    void keepsUsableId(String id) {
      MDC.put(CORRELATION_ID_KEY, id);

      assertThat(correlationIdOfNewReport()).isEqualTo(id);
    }

    @ParameterizedTest(name = "\"{0}\"")
    @ValueSource(strings = {"", "order 42", "id\nforged log line", "id\r", "<script>", "ü"})
    @DisplayName("an empty ID, or one with any other character, is left out")
    void leavesOutUnusableId(String id) {
      MDC.put(CORRELATION_ID_KEY, id);

      assertThat(correlationIdOfNewReport()).isNull();
    }

    @Test
    @DisplayName("an ID of 128 characters is kept, and one of 129 is left out")
    void limitsIdLength() {
      var longest = "a".repeat(LONGEST_ID_LENGTH);
      MDC.put(CORRELATION_ID_KEY, longest);
      assertThat(correlationIdOfNewReport()).isEqualTo(longest);

      MDC.put(CORRELATION_ID_KEY, longest + "a");
      assertThat(correlationIdOfNewReport()).isNull();
    }

    /**
     * Makes a report from the current logging context.
     *
     * @return the correlation ID the report kept, or {@code null}
     */
    private @Nullable String correlationIdOfNewReport() {
      return ErrorReport.of(OrderErrorCode.OUT_OF_STOCK).getExternal().correlationId();
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

      assertThat(report.getExternal().details()).containsExactly(QUANTITY_PROBLEM);
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
  class External {

    @Test
    @DisplayName("it keeps the code, severity, message, details, trace and correlation IDs")
    void keepsWhatCallersMaySee() {
      LoggingContextIds.putInLoggingContext();

      var external =
          ErrorReport.of(OrderErrorCode.OUT_OF_STOCK, SKU)
              .withDetails(List.of(QUANTITY_PROBLEM))
              .withCause(new IllegalStateException("stock service down"))
              .getExternal();

      assertThat(external)
          .isEqualTo(
              new ExternalError(
                  "ORD-0001",
                  Severity.ERROR,
                  "Not enough stock: SKU-1",
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
