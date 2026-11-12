package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.test.context.TestContext;

@DisplayName("The reset listener does nothing in a test without Stratum's mocks")
class MockClientsResetListenerTest {

  @Test
  @DisplayName("an application without MockClients is left alone")
  void noMockClientsBean() {
    var testContext = Mockito.mock(TestContext.class);
    Mockito.when(testContext.hasApplicationContext()).thenReturn(true);
    Mockito.when(testContext.getApplicationContext()).thenReturn(new StaticApplicationContext());

    assertThatNoException()
        .isThrownBy(() -> new MockClientsResetListener().afterTestMethod(testContext));
  }

  @Test
  @DisplayName("a test with no application is left alone")
  void noApplication() {
    var testContext = Mockito.mock(TestContext.class);

    assertThatNoException()
        .isThrownBy(() -> new MockClientsResetListener().afterTestMethod(testContext));
  }
}
