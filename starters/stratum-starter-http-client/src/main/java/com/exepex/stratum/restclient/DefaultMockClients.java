package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.SmartInitializingSingleton;

/**
 * Gives tests the mocks of the clients set to {@code mock: true}, and warns at every startup about
 * each mocked client, so that a mock never stays silent in a running service.
 */
@Slf4j
@StratumInternal
@RequiredArgsConstructor
public class DefaultMockClients implements MockClients, SmartInitializingSingleton {

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /** The answers and calls of every mocked client. */
  private final MockAnswers answers;

  @Override
  public <T> MockClient<T> of(Class<T> type) {
    var client =
        answers
            .clientOf(type)
            .orElseThrow(
                () ->
                    new IllegalArgumentException(
                        type.getName()
                            + " is not an interface of a client with mock: true; set"
                            + " stratum.http-client.clients.<name>.mock=true for its client"));
    answers.startRecording(client);
    return new MockClient<>(client, answers);
  }

  @Override
  public void reset() {
    answers.reset();
  }

  /** Warns once per mocked client, after startup. */
  @Override
  public void afterSingletonsInstantiated() {
    properties.clients().keySet().stream()
        .filter(client -> Boolean.TRUE.equals(properties.clientSettings(client).mock()))
        .sorted()
        .map(this::warning)
        .forEach(log::warn);
  }

  /** Returns the warning about one mocked client, on one line whatever its name holds. */
  private String warning(String client) {
    return ("HTTP client "
            + client
            + " answers from mocks and never calls its service (mock: true)")
        .replaceAll("[\\r\\n]", " ");
  }
}
