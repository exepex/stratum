package com.exepex.stratum.oauth2client.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

@SpringBootTest(
    classes = ClientTokenWebIntegrationTest.WebApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(locations = "classpath:invoices-registration.properties")
@DisplayName("A servlet service with the OAuth2 client starter and a registration")
class ClientTokenWebIntegrationTest {

  /** What the service's endpoint answers. */
  private static final String ANSWER = "stock";

  @LocalServerPort private int port;

  @Autowired private ApplicationContext context;

  @Nested
  @DisplayName("starts with no login chain")
  class NoLoginChain {

    @Test
    @DisplayName("its endpoint answers 200 without a sign-in, and the token store is a bean")
    void endpointAnswersWithoutSignIn() {
      var answer =
          RestClient.create("http://127.0.0.1:" + port)
              .get()
              .uri("/stock")
              .retrieve()
              .toEntity(String.class);

      assertThat(answer.getStatusCode()).isEqualTo(HttpStatus.OK);
      assertThat(answer.getBody()).isEqualTo(ANSWER);
      assertThat(context.getBeansOfType(SecurityFilterChain.class)).isEmpty();
      assertThat(context.getBeansOfType(OAuth2AuthorizedClientService.class)).hasSize(1);
    }
  }

  /** The smallest servlet service that adds the starter, with one endpoint. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  @RestController
  static class WebApplication {

    /**
     * Answers the endpoint of the test.
     *
     * @return the answer
     */
    @GetMapping("/stock")
    String stock() {
      return ANSWER;
    }
  }
}
