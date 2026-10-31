package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("RequestFieldsAccessor keeps a request's fields in the logging context")
class RequestFieldsAccessorTest {

  /** The accessor under test. */
  private final RequestFieldsAccessor accessor = new RequestFieldsAccessor();

  /** The fields of a request that arrived. */
  private static final Map<String, String> ARRIVED =
      Map.of(LoggingContextConstants.HTTP_METHOD, "GET", LoggingContextConstants.HTTP_PATH, "/a");

  /** Leaves the thread's logging context empty for the next test. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("puts the fields and the route once it is known, and reads them back")
  void putsAndReadsFields() {
    accessor.setValue(new RequestFieldValues(ARRIVED, () -> "/a"));

    assertThat(MDC.get(LoggingContextConstants.HTTP_ROUTE)).isEqualTo("/a");
    assertThat(accessor.getValue().fields())
        .containsEntry(LoggingContextConstants.HTTP_METHOD, "GET")
        .containsEntry(LoggingContextConstants.HTTP_ROUTE, "/a");
  }

  @Test
  @DisplayName("removes a field of an earlier request that the carried fields do not hold")
  void removesStaleFields() {
    MDC.put(LoggingContextConstants.HTTP_QUERY, "earlier=1");

    accessor.setValue(new RequestFieldValues(ARRIVED, () -> null));

    assertThat(MDC.get(LoggingContextConstants.HTTP_QUERY)).isNull();
  }

  @Test
  @DisplayName("removes every request field, and leaves the service's own fields")
  void resetKeepsServiceFields() {
    MDC.put("customerId", "c-1");
    accessor.setValue(new RequestFieldValues(ARRIVED, () -> null));

    accessor.setValue();

    assertThat(MDC.getCopyOfContextMap()).containsOnlyKeys("customerId");
    assertThat(accessor.getValue()).isNull();
  }
}
