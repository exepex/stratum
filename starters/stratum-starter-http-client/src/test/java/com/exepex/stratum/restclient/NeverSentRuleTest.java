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

    assertThat(RetryRules.isNeverSent(failure)).isTrue();
  }

  @Test
  @DisplayName("another code, such as a missing caller token (SX-HTTPC-0004), is not ignored")
  void otherCodeNotIgnored() {
    var failure = new StratumException(HttpClientErrorCode.CALLER_TOKEN_MISSING, "");

    assertThat(RetryRules.isNeverSent(failure)).isFalse();
  }

  @Test
  @DisplayName("a failure of the other service, such as a dropped connection, is not ignored")
  void downstreamFailureNotIgnored() {
    assertThat(RetryRules.isNeverSent(new ResourceAccessException("connection reset"))).isFalse();
  }
}
