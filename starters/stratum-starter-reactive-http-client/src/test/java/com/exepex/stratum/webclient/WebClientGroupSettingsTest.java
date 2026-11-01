package com.exepex.stratum.webclient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.http.client.autoconfigure.HttpClientProperties;
import org.springframework.boot.http.client.autoconfigure.HttpClientSettingsPropertyMapper;
import org.springframework.boot.http.client.autoconfigure.service.HttpServiceClientProperties;
import org.springframework.boot.http.client.reactive.ClientHttpConnectorBuilder;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.service.registry.HttpServiceGroup;
import org.springframework.web.service.registry.HttpServiceGroupConfigurer;

@DisplayName("WebClientGroupSettings rebuilds a group's connector only when the group sets its own")
class WebClientGroupSettingsTest {

  /** The group the tests configure. */
  private static final String INVENTORY = "inventory";

  /** The inventory group's own keys, which each test fills in. */
  private final HttpClientProperties inventoryProperties = new HttpClientProperties();

  /** The builder of the inventory group's {@code WebClient}. */
  private final WebClient.Builder inventoryWebClient = mock(WebClient.Builder.class);

  @Test
  @DisplayName("a group with its own read timeout gets a connector built from it")
  void ownTimeoutRebuildsConnector() {
    inventoryProperties.setReadTimeout(Duration.ofSeconds(1));

    configureInventory();

    verify(inventoryWebClient).clientConnector(any());
  }

  @Test
  @DisplayName("a group with only an address keeps its connector, a service's own included")
  void addressOnlyKeepsConnector() {
    inventoryProperties.setBaseUrl("https://inventory.internal");

    configureInventory();

    verify(inventoryWebClient, never()).clientConnector(any());
  }

  /** Runs the group settings on the inventory group alone. */
  @SuppressWarnings("unchecked")
  private void configureInventory() {
    var groupProperties = mock(HttpServiceClientProperties.class);
    when(groupProperties.get(INVENTORY)).thenReturn(inventoryProperties);
    var inventoryGroup = mock(HttpServiceGroup.class);
    when(inventoryGroup.name()).thenReturn(INVENTORY);
    HttpServiceGroupConfigurer.Groups<WebClient.Builder> groups =
        mock(HttpServiceGroupConfigurer.Groups.class);
    doAnswer(
            call -> {
              call.<HttpServiceGroupConfigurer.ClientCallback<WebClient.Builder>>getArgument(0)
                  .withClient(inventoryGroup, inventoryWebClient);
              return null;
            })
        .when(groups)
        .forEachClient(any(HttpServiceGroupConfigurer.ClientCallback.class));

    new WebClientGroupSettings(
            groupProperties,
            new HttpClientSettingsPropertyMapper(null, null),
            ClientHttpConnectorBuilder.reactor())
        .configureGroups(groups);
  }
}
