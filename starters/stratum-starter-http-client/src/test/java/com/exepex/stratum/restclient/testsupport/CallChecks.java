package com.exepex.stratum.restclient.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import lombok.experimental.UtilityClass;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.assertj.core.api.ThrowableAssertAlternative;
import org.springframework.web.client.ResourceAccessException;

/** Checks of calls that fail, or that hold a connection, shared by the integration tests. */
@UtilityClass
public class CallChecks {

  /**
   * Starts a call to {@link ScriptedDownstream#HELD} and returns once the downstream holds it, so
   * that it keeps one connection of its pool busy. Each call runs on a thread of its own, so that
   * any number of them can be held at once.
   *
   * @param heldCall the client's call to the held path
   * @param downstream the server that holds it
   * @return the call, which answers after {@link ScriptedDownstream#release()}
   */
  public static CompletableFuture<String> holdOneConnection(
      Supplier<String> heldCall, ScriptedDownstream downstream) {
    var heldRequestsBefore = downstream.heldRequests();
    var call = CompletableFuture.supplyAsync(heldCall, Thread::startVirtualThread);
    await()
        .atMost(RestClientTestData.WAIT)
        .until(() -> downstream.heldRequests() > heldRequestsBefore);
    return call;
  }

  /**
   * Checks that a call fails, with the given root cause, before {@link
   * RestClientTestData#FAILS_WITHIN}, which is far below every default, so the short setting under
   * test is what failed it. A client's interface throws a code for it, any other client Spring's
   * own exception.
   *
   * @param call the call
   * @param rootCause the type of the failure's root cause
   */
  public static void assertFailsFast(ThrowingCallable call, Class<? extends Throwable> rootCause) {
    var startedAt = System.nanoTime();

    assertThatThrownBy(call)
        .isInstanceOfAny(StratumException.class, ResourceAccessException.class)
        .hasRootCauseInstanceOf(rootCause);
    assertThat(Duration.ofNanos(System.nanoTime() - startedAt))
        .isLessThan(RestClientTestData.FAILS_WITHIN);
  }

  /**
   * Checks that a call of a client's interface ends with one of the HTTP client's codes.
   *
   * @param call the call
   * @param code the code it must end with
   * @return the check of the exception, for more checks such as its root cause
   */
  public static ThrowableAssertAlternative<StratumException> assertEndsWith(
      ThrowingCallable call, HttpClientErrorCode code) {
    return assertThatExceptionOfType(StratumException.class)
        .isThrownBy(call)
        .satisfies(failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
  }
}
