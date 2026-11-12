package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("The circuit breaker ignores a call that was never sent, and only that")
class NeverSentRuleTest {

  @Test
  @DisplayName("a body above interceptor-body-limit (SX-HTTPC-0005) was never sent")
  void bodyTooLargeNeverSent() {
    var failure = new StratumException(HttpClientErrorCode.INTERCEPTOR_BODY_TOO_LARGE, "");

    assertThat(NeverSentFailures.isNeverSent(failure)).isTrue();
  }

  @Test
  @DisplayName("a call with no caller token to relay (SX-HTTPC-0004) was never sent")
  void callerTokenMissingNeverSent() {
    var failure = new StratumException(HttpClientErrorCode.CALLER_TOKEN_MISSING);

    assertThat(NeverSentFailures.isNeverSent(failure)).isTrue();
  }

  @Test
  @DisplayName("SX-HTTPC-0002 on its own, as after a 401's failed renewal, is not ignored")
  void unavailableNotIgnored() {
    var failure = new StratumException(HttpClientErrorCode.DOWNSTREAM_UNAVAILABLE);

    assertThat(NeverSentFailures.isNeverSent(failure)).isFalse();
  }

  @Test
  @DisplayName("a failure of the other service, such as a dropped connection, is not ignored")
  void downstreamFailureNotIgnored() {
    assertThat(NeverSentFailures.isNeverSent(new ResourceAccessException("connection reset")))
        .isFalse();
  }
}
