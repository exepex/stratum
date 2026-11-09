package com.exepex.stratum.oauth2client.it;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.TestPropertySource;
import org.springframework.util.ClassUtils;

@SpringBootTest(
    classes = ClientTokenStarterIntegrationTest.ClientApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(locations = "classpath:invoices-registration.properties")
@DisplayName("The OAuth2 client starter gives the registrations and token store, and no lock-down")
class ClientTokenStarterIntegrationTest {

  @Autowired private ApplicationContext context;

  @Test
  @DisplayName("the registrations and the store of fetched tokens are beans")
  void registrationsAndTokenStore() {
    assertThat(context.getBean(ClientRegistrationRepository.class).findByRegistrationId("invoices"))
        .isNotNull();
    assertThat(context.getBeansOfType(OAuth2AuthorizedClientService.class)).hasSize(1);
  }

  @Test
  @DisplayName("Spring Boot's security set-up is absent, so no generated password or user exists")
  void noLockDown() {
    assertThat(
            ClassUtils.isPresent(
                "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
                context.getClassLoader()))
        .isFalse();
    assertThat(context.getBeansOfType(UserDetailsService.class)).isEmpty();
  }

  /** The smallest application that adds the starter, as a service does. */
  @SpringBootConfiguration
  @EnableAutoConfiguration
  static class ClientApplication {}
}
