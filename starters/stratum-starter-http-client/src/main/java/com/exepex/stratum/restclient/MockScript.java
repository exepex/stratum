package com.exepex.stratum.restclient;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpStatusCode;

/**
 * The scripted answer of one method of a mocked client, from {@link MockClient#on}. It applies to
 * every later call of the method, until the test ends or {@link MockClients#reset()}.
 */
@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class MockScript {

  /** The client's name. */
  private final String client;

  /** The method's name. */
  private final String method;

  /** The answers and calls of every mocked client. */
  private final MockAnswers answers;

  /** How long the answer takes; none unless set. */
  private Duration answerTime = Duration.ZERO;

  /**
   * Makes the answer take this long. A delay longer than the call's time limit, the client's {@code
   * timeout} or the time its {@code total-timeout} leaves, fails the call as a read timeout would.
   *
   * @param time how long the answer takes
   * @return this script
   */
  public MockScript delay(Duration time) {
    answerTime = time;
    return this;
  }

  /**
   * Answers 200 with this value: a text as it is, any other value written as JSON, nothing for
   * {@code null}.
   *
   * @param value the answer's body
   */
  public void respond(@Nullable Object value) {
    answers.script(client, method, answers.ok(value).withDelay(answerTime));
  }

  /**
   * Answers with an error status and no body, which the client's status handlers turn into an
   * exception as for a real answer.
   *
   * @param status the status, such as {@code HttpStatus.NOT_FOUND}
   */
  public void fail(HttpStatusCode status) {
    fail(status, "");
  }

  /**
   * Answers with an error status and a JSON body.
   *
   * @param status the status, such as {@code HttpStatus.CONFLICT}
   * @param body the body, as JSON text
   */
  public void fail(HttpStatusCode status, String body) {
    answers.script(client, method, MockAnswer.json(status, body).withDelay(answerTime));
  }
}
