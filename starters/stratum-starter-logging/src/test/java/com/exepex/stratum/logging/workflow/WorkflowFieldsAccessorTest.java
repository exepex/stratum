package com.exepex.stratum.logging.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.util.LogbackMDCAdapter;
import com.exepex.stratum.core.error.LoggingContextConstants;
import com.exepex.stratum.logging.LoggingConstants;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

@DisplayName("WorkflowFieldsAccessor writes the workflow fields only when they change")
class WorkflowFieldsAccessorTest {

  /** The trace ID tracing put in the MDC. */
  private static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";

  /** The workflow the tests run in. */
  private static final String ORDER_CHECKOUT = "ORDER_CHECKOUT";

  private final WorkflowFieldsAccessor accessor = new WorkflowFieldsAccessor();

  /** Clears the logging context, so no test sees fields left by another. */
  @AfterEach
  void clearLoggingContext() {
    MDC.clear();
  }

  @Test
  @DisplayName("setting the carried fields puts the new ones, removes the unset ones")
  void setsCarriedFieldsAndRemovesUnsetOnes() {
    MDC.put(LoggingConstants.WORKFLOW_ID, "wf-41");
    MDC.put(LoggingContextConstants.TRACE_ID, TRACE_ID);
    var carriedFields = new HashMap<String, String>();
    carriedFields.put(LoggingConstants.WORKFLOW, ORDER_CHECKOUT);
    carriedFields.put(LoggingConstants.WORKFLOW_ID, null);

    accessor.setValue(carriedFields);

    assertThat(MDC.getCopyOfContextMap())
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                LoggingConstants.WORKFLOW, ORDER_CHECKOUT,
                LoggingContextConstants.TRACE_ID, TRACE_ID));
  }

  @Test
  @DisplayName(
      "unchanged fields, and clearing when no workflow field is set, leave the MDC untouched, so"
          + " Logback keeps its cached copy for log lines")
  void leavesLoggingContextUntouchedWhenNothingChanges() {
    MDC.put(LoggingContextConstants.TRACE_ID, TRACE_ID);
    var logbackMdc = (LogbackMDCAdapter) MDC.getMDCAdapter();
    var cachedCopyBefore = logbackMdc.getPropertyMap();

    accessor.setValue(accessor.currentFields());
    accessor.setValue();

    assertThat(logbackMdc.getPropertyMap()).isSameAs(cachedCopyBefore);
  }
}
