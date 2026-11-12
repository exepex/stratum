package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.exepex.stratum.httpclient.HttpClientConstants;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.support.RestClientAdapter;
import org.springframework.web.service.invoker.HttpRequestValues;

@DisplayName("AroundCallExchangeAdapter lets a missing answer through only for an Optional method")
class AroundCallExchangeAdapterTest {

  /** The body type of the calls. */
  private static final ParameterizedTypeReference<String> TEXT =
      new ParameterizedTypeReference<>() {};

  /** An adapter whose every call ends with no answer, as a 404 of an Optional method does. */
  private final AroundCallExchangeAdapter noAnswer =
      new AroundCallExchangeAdapter(RestClientAdapter.create(RestClient.create())) {
        @Override
        protected <T> @Nullable T around(
            HttpRequestValues values, boolean streamedDownload, Supplier<@Nullable T> call) {
          return null;
        }
      };

  @Test
  @DisplayName("a call whose method returns Optional gets no answer, which Spring makes empty")
  void optionalCallGetsNothing() {
    var values = call().addAttribute(HttpClientConstants.OPTIONAL_ANSWER, Boolean.TRUE).build();

    assertThat(noAnswer.exchangeForEntity(values, TEXT)).isNull();
    assertThat(noAnswer.exchangeForBodilessEntity(values)).isNull();
    assertThat(noAnswer.exchangeForHeaders(values)).isNull();
  }

  @Test
  @DisplayName("any other call with no answer fails fast")
  void otherCallFailsFast() {
    var values = call().build();

    assertThatNullPointerException().isThrownBy(() -> noAnswer.exchangeForEntity(values, TEXT));
    assertThatNullPointerException().isThrownBy(() -> noAnswer.exchangeForBodilessEntity(values));
    assertThatNullPointerException().isThrownBy(() -> noAnswer.exchangeForHeaders(values));
  }

  /** Returns a GET call, to be marked or not. */
  private HttpRequestValues.Builder call() {
    return HttpRequestValues.builder().setHttpMethod(HttpMethod.GET).setUriTemplate("/orders");
  }
}
