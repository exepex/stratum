package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.TokenDownstream;
import com.exepex.stratum.restclient.testsupport.TokenDownstream.TokenAnswer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;

@DisplayName("A client's token pool")
class ClientConnectionPoolsTest {

  /** A client timeout far above the call's time left. */
  private static final Duration LONG_TIMEOUT = Duration.ofSeconds(30);

  /** Pools with no SSL bundles at all: reading one fails, so a pool that asks for one is caught. */
  private final ClientConnectionPools pools =
      new ClientConnectionPools(
          HttpClientProperties.builder().build().defaults(),
          new StaticListableBeanFactory().getBeanProvider(SslBundles.class));

  /** Closes the pools of the test. */
  @AfterEach
  void closePools() {
    pools.destroy();
  }

  @Nested
  @DisplayName("never uses the client's SSL bundle")
  class Bundle {

    @Test
    @DisplayName("the client's own pool reads its bundle, while its token pool does not")
    void tokenPoolIgnoresBundle() {
      var settings =
          HttpClientProperties.builder()
              .build()
              .clientSettings(RestClientTestData.INVENTORY)
              .toBuilder()
              .sslBundle("bank")
              .build();

      assertThatCode(() -> pools.tokenRequestFactory(RestClientTestData.INVENTORY, settings))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> pools.requestFactory(RestClientTestData.INVENTORY, settings))
          .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
  }

  @Nested
  @DisplayName("ends a token request by the call's time left")
  class TimeLeft {

    /** An identity provider that does not answer. */
    @AutoClose private final TokenDownstream identityProvider = new TokenDownstream();

    @Test
    @DisplayName("even while the call is a streamed download, which keeps its own answer timeout")
    void tokenRequestEndsDuringDownload() {
      identityProvider.tokenAnswer(TokenAnswer.HOLD);
      var settings =
          HttpClientProperties.builder()
              .build()
              .clientSettings(RestClientTestData.INVENTORY)
              .toBuilder()
              .timeout(LONG_TIMEOUT)
              .build();
      var factory = pools.tokenRequestFactory(RestClientTestData.INVENTORY, settings);
      var started = System.nanoTime();

      assertThatThrownBy(
              () ->
                  AttemptTimeLeft.runWithin(
                      started + RestClientTestData.SHORT.toNanos(),
                      true,
                      () -> askForToken(factory)))
          .hasRootCauseInstanceOf(SocketTimeoutException.class);
      assertThat(Duration.ofNanos(System.nanoTime() - started))
          .isLessThan(RestClientTestData.FAILS_WITHIN);
    }

    /** Sends one token request through the factory. */
    private int askForToken(ClientHttpRequestFactory factory) {
      try (var answer =
          factory
              .createRequest(
                  URI.create(identityProvider.url() + TokenDownstream.TOKEN), HttpMethod.POST)
              .execute()) {
        return answer.getStatusCode().value();
      } catch (IOException failed) {
        throw new UncheckedIOException(failed);
      }
    }
  }
}
