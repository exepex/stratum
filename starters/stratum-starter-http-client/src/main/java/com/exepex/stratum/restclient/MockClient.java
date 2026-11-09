package com.exepex.stratum.restclient;

import java.util.List;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;

/**
 * The mock of one client set to {@code mock: true}, from {@link MockClients#of}. A scripted answer
 * wins over the file {@code stratum-mocks/<client>/<method>.json}.
 *
 * @param <T> the interface the mock was asked for
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class MockClient<T> {

  /** The client's name. */
  private final String client;

  /** The answers and calls of every mocked client. */
  private final MockAnswers answers;

  /**
   * Starts the script of one interface method's answer.
   *
   * @param method the method's name, such as {@code createPayment}
   * @return the script, finished by {@code respond} or {@code fail}
   */
  public MockScript on(String method) {
    return new MockScript(client, method, answers);
  }

  /**
   * Returns the calls of one interface method received since this mock was asked for, oldest first.
   *
   * @param method the method's name
   * @return the calls
   */
  public List<RecordedCall> calls(String method) {
    return answers.calls(client, method);
  }
}
