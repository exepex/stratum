package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.SslBundles;

@DisplayName("A client's token pool")
class ClientConnectionPoolsTest {

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
}
