package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.clients.ClientsApplication;
import java.util.Arrays;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

@DisplayName(
    "A service's own RestClient keeps Spring Boot's engine, Java's client by Stratum's default")
class ForeignClientIntegrationTest {

  @Test
  @DisplayName(
      "Spring Boot's RestClient builder sends with Java's client, not Stratum's Apache one")
  void plainBuilderKeepsJdkClient() {
    assertThat(requestFactoryOfServiceClients()).isInstanceOf(JdkClientHttpRequestFactory.class);
  }

  @Test
  @DisplayName("the service's own spring.http.clients.imperative.factory wins")
  void teamFactoryWins() {
    assertThat(
            requestFactoryOfServiceClients(
                RestClientTestData.setting(HttpClientConstants.BOOT_IMPERATIVE_FACTORY, "simple")))
        .isInstanceOf(SimpleClientHttpRequestFactory.class);
  }

  /**
   * Starts the application, with its environment post-processors, and returns the request factory
   * Spring Boot gives the service's own clients.
   */
  private ClientHttpRequestFactory requestFactoryOfServiceClients(String... settings) {
    var application = new SpringApplication(ClientsApplication.class);
    application.setWebApplicationType(WebApplicationType.NONE);
    var arguments =
        Stream.concat(
                Stream.of(
                    "--"
                        + RestClientTestData.INVENTORY_SETTINGS
                        + ".url="
                        + RestClientTestData.UNUSED_URL,
                    "--"
                        + RestClientTestData.PRICING_SETTINGS
                        + ".url="
                        + RestClientTestData.UNUSED_URL),
                Arrays.stream(settings).map(setting -> "--" + setting))
            .toArray(String[]::new);
    try (var context = application.run(arguments)) {
      return context.getBean(ClientHttpRequestFactoryBuilder.class).build();
    }
  }
}
