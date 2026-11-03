package com.exepex.stratum.logging;

import com.exepex.stratum.logging.mdc.ReactiveMdcFields;
import com.exepex.stratum.logging.workflow.ReactiveWorkflows;
import com.exepex.stratum.logging.workflow.Workflow;
import com.exepex.stratum.logging.workflow.WorkflowAspect;
import com.exepex.stratum.logging.workflow.Workflows;
import com.exepex.stratum.test.StarterTestSupport;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("The logging starter follows Stratum's architecture rules")
class LoggingArchTest {

  /**
   * The logging starter's public types that services may use; every other one carries the label.
   */
  private static final Set<Class<?>> API_TYPES =
      Set.of(
          LoggingProperties.class,
          LoggingPropertiesCustomizer.class,
          ReactiveMdcFields.class,
          ReactiveWorkflows.class,
          Workflow.class,
          WorkflowAspect.class,
          Workflows.class);

  @Test
  @DisplayName(
      "its autoconfigure package only declares beans, it uses no other starter and has no cycles")
  void followsStarterArchitecture() {
    StarterTestSupport.checkStarterArchitecture(LoggingConstants.class, API_TYPES);
  }
}
