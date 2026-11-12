package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.ResourceAccessException;

@DisplayName("An attempt's text leaves out the other service's data")
class ClientAttemptTest {

  @Test
  @DisplayName("the result and the failure are not in its text; the method is")
  void toStringHidesResultAndFailure() {
    var attempt =
        ClientAttempt.builder()
            .method(HttpMethod.GET)
            .result(RestClientTestData.SECRET)
            .failure(new ResourceAccessException(RestClientTestData.SECRET))
            .timeLeft(Duration.ZERO)
            .nextWait(Duration.ZERO)
            .build();

    assertThat(attempt.toString())
        .doesNotContain(RestClientTestData.SECRET)
        .contains(HttpMethod.GET.name());
  }
}
