package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.support.AbstractTestExecutionListener;

/**
 * Clears the scripts and recorded calls of the mocked clients after each test method, as Spring
 * resets its own test mocks. Spring's test framework finds it in {@code META-INF/spring.factories};
 * a running service never loads it. It does nothing in a test without Stratum's clients.
 */
@StratumInternal
public class MockClientsResetListener extends AbstractTestExecutionListener {

  /**
   * Clears the mocks of the test's application, if it has one.
   *
   * @param testContext the test's context
   */
  @Override
  public void afterTestMethod(TestContext testContext) {
    if (testContext.hasApplicationContext()) {
      testContext
          .getApplicationContext()
          .getBeanProvider(MockClients.class)
          .ifAvailable(MockClients::reset);
    }
  }
}
