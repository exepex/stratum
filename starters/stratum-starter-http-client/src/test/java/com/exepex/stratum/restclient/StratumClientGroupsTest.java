package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.ClientSettings;
import com.exepex.stratum.httpclient.HttpClientProperties;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.InventoryClient;
import com.exepex.stratum.restclient.testsupport.generated.PartnerOrdersApi;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.web.service.registry.HttpServiceGroup;

@DisplayName("A group is a Stratum client's by a marked interface or one its settings name")
class StratumClientGroupsTest {

  /** The set-up under test: the partner client names the generated interface. */
  private final StratumClientGroups groups =
      new StratumClientGroups(
          HttpClientProperties.builder()
              .defaults(ClientSettings.builder().build())
              .clients(
                  Map.of(
                      RestClientTestData.PARTNER,
                      ClientSettings.builder()
                          .interfaces(List.of(PartnerOrdersApi.class.getName()))
                          .build()))
              .build(),
          Mockito.mock(StratumClientBuilders.class),
          new StaticListableBeanFactory().getBeanProvider(ClientExchangeDecorator.class),
          Mockito.mock(MockAnswers.class));

  @Test
  @DisplayName("a group with a @StratumClient interface is Stratum's")
  void markedInterface() {
    assertThat(groups.isStratumClient(group(RestClientTestData.INVENTORY, InventoryClient.class)))
        .isTrue();
  }

  @Test
  @DisplayName("a group with an interface its client's settings name is Stratum's")
  void namedInterface() {
    assertThat(groups.isStratumClient(group(RestClientTestData.PARTNER, PartnerOrdersApi.class)))
        .isTrue();
  }

  @Test
  @DisplayName("a service's own group with a client's name but none of its interfaces is not")
  void nameAloneDoesNotCount() {
    assertThat(groups.isStratumClient(group(RestClientTestData.PARTNER, Runnable.class))).isFalse();
  }

  @Test
  @DisplayName("an interface named for one client does not make another client's group Stratum's")
  void namedForAnotherClient() {
    assertThat(groups.isStratumClient(group(RestClientTestData.PRICING, PartnerOrdersApi.class)))
        .isFalse();
  }

  /** Returns a group of the given name with one interface. */
  private HttpServiceGroup group(String name, Class<?> serviceType) {
    return new ServiceGroup(name, Set.of(serviceType), HttpServiceGroup.ClientType.REST_CLIENT);
  }

  /**
   * A group as Spring registers it.
   *
   * @param name the group's name
   * @param httpServiceTypes the group's interfaces
   * @param clientType the kind of client the group uses
   */
  private record ServiceGroup(
      String name, Set<Class<?>> httpServiceTypes, HttpServiceGroup.ClientType clientType)
      implements HttpServiceGroup {}
}
