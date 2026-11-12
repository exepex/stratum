package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RecordedMeters;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.ClientHttpRequestFactory;

@DisplayName("A client's pools, and its token pool")
class ClientConnectionPoolsTest {

  /** Where an attempt of the inventory client goes. */
  private static final URI STOCK = URI.create("http://inventory:8080/stock/42");

  /** The time an attempt of these tests has. */
  private static final Duration ATTEMPT_TIME = Duration.ofSeconds(1);

  /** The meters the pools are shown in. */
  private final RecordedMeters meters = new RecordedMeters();

  /** Pools with no SSL bundles at all: reading one fails, so a pool that asks for one is caught. */
  private final ClientConnectionPools pools =
      new ClientConnectionPools(
          HttpClientProperties.builder().build().defaults(),
          new StaticListableBeanFactory().getBeanProvider(SslBundles.class),
          meters.provider());

  /** The inventory client's settings, with Stratum's defaults. */
  private final ClientSettings inventory =
      HttpClientProperties.builder().build().clientSettings(RestClientTestData.INVENTORY);

  /** Closes the pools of the test. */
  @AfterEach
  void closePools() {
    pools.destroy();
  }

  /** Returns the address an attempt noted when it made a request through the factory. */
  private @Nullable URI notedBy(ClientHttpRequestFactory requestFactory) {
    var sentTo = new AtomicReference<@Nullable URI>();
    AttemptTimeLeft.runWithin(
        System.nanoTime() + ATTEMPT_TIME.toNanos(),
        false,
        sentTo,
        () -> createRequest(requestFactory));
    return sentTo.get();
  }

  /** Makes a request to the stock address, which opens no connection. */
  private Object createRequest(ClientHttpRequestFactory requestFactory) {
    try {
      return requestFactory.createRequest(STOCK, HttpMethod.GET);
    } catch (IOException failure) {
      throw new IllegalStateException(failure);
    }
  }

  @Nested
  @DisplayName("never uses the client's SSL bundle")
  class Bundle {

    @Test
    @DisplayName("the client's own pool reads its bundle, while its token pool does not")
    void tokenPoolIgnoresBundle() {
      var settings = inventory.toBuilder().sslBundle("bank").build();

      assertThatCode(() -> pools.tokenRequestFactory(RestClientTestData.INVENTORY, settings))
          .doesNotThrowAnyException();
      assertThatThrownBy(() -> pools.requestFactory(RestClientTestData.INVENTORY, settings))
          .isInstanceOf(NoSuchBeanDefinitionException.class);
    }
  }

  @Nested
  @DisplayName("in the call log and meters")
  class Observed {

    @Test
    @DisplayName("an attempt through the client's pool notes its address; a token fetch does not")
    void onlyClientPoolNotesAddress() {
      assertThat(notedBy(pools.requestFactory(RestClientTestData.INVENTORY, inventory)))
          .isEqualTo(STOCK);
      assertThat(notedBy(pools.tokenRequestFactory(RestClientTestData.INVENTORY, inventory)))
          .isNull();
    }

    @Test
    @DisplayName(
        "the client's pool and the shared one are shown in the meters, a token pool is not")
    void tokenPoolNotMetered() {
      pools.requestFactory(RestClientTestData.INVENTORY, inventory);
      pools.tokenRequestFactory(RestClientTestData.INVENTORY, inventory);
      pools.requestFactory(
          RestClientTestData.PRICING, inventory.toBuilder().sharedPool(true).build());

      assertThat(meters.getPools())
          .containsExactly(RestClientTestData.INVENTORY, HttpClientConstants.SHARED_POOL_TAG);
    }
  }
}
