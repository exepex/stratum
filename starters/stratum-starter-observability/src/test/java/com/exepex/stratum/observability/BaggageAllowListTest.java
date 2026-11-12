package com.exepex.stratum.observability;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.logging.LoggingContextConstants;
import com.exepex.stratum.observability.testsupport.BaggageTestData;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("BaggageAllowList keeps the baggage names of the service's settings")
class BaggageAllowListTest {

  /** A caller's correlation ID and remote field, a secret and a casing variant. */
  private static final String CALLER_BAGGAGE_WITH_REMOTE_FIELD =
      BaggageTestData.CALLER_BAGGAGE + "," + BaggageTestData.REMOTE_FIELD + "=t-1";

  @Test
  @DisplayName("with nothing set, only the correlation ID is kept")
  void keepsCorrelationIdByDefault() {
    var allowList = BaggageAllowList.from(new MockEnvironment());

    assertThat(BaggageTestData.namesKeptBy(allowList, CALLER_BAGGAGE_WITH_REMOTE_FIELD))
        .containsExactly(LoggingContextConstants.CORRELATION_ID);
  }

  @Test
  @DisplayName("Spring Boot's remote fields are kept as well as the allowed names")
  void keepsRemoteFields() {
    var environment =
        new MockEnvironment()
            .withProperty(
                ObservabilityConstants.BAGGAGE_REMOTE_FIELDS, BaggageTestData.REMOTE_FIELD);

    assertThat(
            BaggageTestData.namesKeptBy(
                BaggageAllowList.from(environment), CALLER_BAGGAGE_WITH_REMOTE_FIELD))
        .containsExactlyInAnyOrder(
            LoggingContextConstants.CORRELATION_ID, BaggageTestData.REMOTE_FIELD);
  }

  @Test
  @DisplayName("an allowed list set by the service replaces the default")
  void allowedListReplacesDefault() {
    var environment =
        new MockEnvironment()
            .withProperty(ObservabilityConstants.PREFIX + ".baggage.allowed", "evil");

    assertThat(
            BaggageTestData.namesKeptBy(
                BaggageAllowList.from(environment), BaggageTestData.CALLER_BAGGAGE))
        .containsExactly("evil");
  }

  @Test
  @DisplayName("a bean that holds no propagators is returned unchanged")
  void leavesOtherBeansUnchanged() {
    var otherBean = new Object();

    assertThat(
            BaggageAllowList.from(new MockEnvironment())
                .postProcessAfterInitialization(otherBean, "otherBean"))
        .isSameAs(otherBean);
  }
}
