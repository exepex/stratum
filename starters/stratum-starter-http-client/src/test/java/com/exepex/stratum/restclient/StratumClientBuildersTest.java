package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.GlobalClientInterceptor;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

@DisplayName(
    "Every client's calls go through the steps of copied headers, interceptors and trace headers")
class StratumClientBuildersTest {

  /** A mocked client's transport, whose factory the steps wrap or not. */
  private final ClientTransport transport =
      new ClientTransport.Mocked(RestClientTestData.answersWithoutMapper());

  /** The settings of a client with nothing to copy. */
  private final ClientSettings plain =
      HttpClientProperties.builder().build().clientSettings(RestClientTestData.INVENTORY);

  @Test
  @DisplayName(
      "a client with no copied header and no interceptor still gets the steps, for its trace"
          + " headers")
  void stepsWithoutCopyOrInterceptor() {
    var factory =
        buildersWith(new StaticListableBeanFactory())
            .requestFactory(RestClientTestData.INVENTORY, plain, transport);

    assertThat(factory).isInstanceOf(OutgoingStepsRequestFactory.class);
  }

  @Test
  @DisplayName("a client with a copied header gets the steps around its transport's factory")
  void stepsWithCopiedHeader() {
    var copying = plain.toBuilder().copyHeaders(List.of("X-Tenant")).build();

    var factory =
        buildersWith(new StaticListableBeanFactory())
            .requestFactory(RestClientTestData.INVENTORY, copying, transport);

    assertThat(factory).isInstanceOf(OutgoingStepsRequestFactory.class);
  }

  @Test
  @DisplayName("a client with a global interceptor gets the steps around its transport's factory")
  void stepsWithInterceptor() {
    var beans = new StaticListableBeanFactory();
    beans.addBean("audit", (GlobalClientInterceptor) request -> request.setHeader("X-A", "a"));

    var factory =
        buildersWith(beans).requestFactory(RestClientTestData.INVENTORY, plain, transport);

    assertThat(factory).isInstanceOf(OutgoingStepsRequestFactory.class);
  }

  /** Returns the set-up with the interceptors the beans hold. */
  private StratumClientBuilders buildersWith(StaticListableBeanFactory beans) {
    return new StratumClientBuilders(
        HttpClientProperties.builder().build(),
        Mockito.mock(ClientConnectionPools.class),
        new MockEnvironment(),
        beans.getBeanProvider(StratumClientCustomizer.class),
        beans.getBeanProvider(TokenInitializers.class),
        RestClientTestData.answersWithoutMapper(),
        Mockito.mock(ClientResilience.class),
        new ClientInterceptors(Map.of(), beans));
  }
}
