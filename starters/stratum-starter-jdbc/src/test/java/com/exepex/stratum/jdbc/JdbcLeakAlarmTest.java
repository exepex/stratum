package com.exepex.stratum.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.DataErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Configuration;

@DisplayName("A plain JDBC service gets the leak alarm check of stratum-data")
class JdbcLeakAlarmTest {

  @Test
  @DisplayName("a leak alarm threshold HikariCP would turn off stops startup with SX-DATA-1002")
  void stopsStartupForLeakAlarmOff() {
    var failure =
        catchThrowable(
            () ->
                new SpringApplicationBuilder(EmptyService.class)
                    .web(WebApplicationType.NONE)
                    .properties(DataConstants.LEAK_DETECTION_THRESHOLD + "=1000")
                    .run()
                    .close());

    assertThat(failure)
        .isInstanceOfSatisfying(
            StratumException.class,
            exception ->
                assertThat(exception.getErrorCode()).isEqualTo(DataErrorCode.LEAK_ALARM_OFF));
  }

  /** A service with no beans, which startup never reaches. */
  @Configuration(proxyBeanMethods = false)
  static class EmptyService {}
}
