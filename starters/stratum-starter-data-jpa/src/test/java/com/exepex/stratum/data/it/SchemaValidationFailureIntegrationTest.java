package com.exepex.stratum.data.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.data.DataErrorCode;
import com.exepex.stratum.data.it.mismatch.MismatchApplication;
import com.exepex.stratum.test.PostgresContainerConfiguration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
@DisplayName("A service whose entity does not match the PostgreSQL schema stops with SX-DATA-1001")
class SchemaValidationFailureIntegrationTest {

  @Test
  @DisplayName("Spring Boot's failure report names the code, the column and both ways out")
  void reportsMissingColumn(CapturedOutput output) {
    var application =
        new SpringApplicationBuilder(
                MismatchApplication.class, PostgresContainerConfiguration.class)
            .web(WebApplicationType.NONE)
            .properties("spring.sql.init.mode=always");

    assertThatThrownBy(application::run).isInstanceOf(RuntimeException.class);

    assertThat(output.getOut())
        .contains(DataErrorCode.SCHEMA_MISMATCH.getCode())
        .contains("missing column [customer_id] in table [orders]")
        .contains(DataErrorCode.SCHEMA_MISMATCH.getFix());
  }
}
