package com.exepex.stratum.datajpa;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.BeanCreationException;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("SchemaValidationFailureAnalyzer reports a failed schema check as SX-DATA-1001")
class SchemaValidationFailureAnalyzerTest {

  private static final String HIBERNATE_MESSAGE =
      "Schema validation: missing column [customer_id] in table [orders]";

  /** A failed schema check as Spring reports it: inside the failure to create Hibernate. */
  private static final BeanCreationException FAILED_SCHEMA_CHECK =
      new BeanCreationException(
          "entityManagerFactory", "Init failed", new SchemaManagementException(HIBERNATE_MESSAGE));

  @Test
  @DisplayName("it names the code, Hibernate's message and both ways out")
  void reportsCodeMessageAndFix() {
    var analysis =
        new SchemaValidationFailureAnalyzer(new MockEnvironment()).analyze(FAILED_SCHEMA_CHECK);

    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription())
        .startsWith(DataErrorCode.SCHEMA_MISMATCH.getCode())
        .contains(HIBERNATE_MESSAGE);
    assertThat(analysis.getAction()).startsWith(DataErrorCode.SCHEMA_MISMATCH.getFix());
  }

  @Test
  @DisplayName("it leaves any other failure to Spring Boot")
  void ignoresOtherFailures() {
    var otherFailure = new BeanCreationException("entityManagerFactory", "Init failed");

    assertThat(new SchemaValidationFailureAnalyzer(new MockEnvironment()).analyze(otherFailure))
        .isNull();
  }

  @Test
  @DisplayName("with the module off, it reports nothing")
  void silentWithModuleOff() {
    var environment = new MockEnvironment().withProperty(DataConstants.ENABLED, "false");

    assertThat(new SchemaValidationFailureAnalyzer(environment).analyze(FAILED_SCHEMA_CHECK))
        .isNull();
  }
}
