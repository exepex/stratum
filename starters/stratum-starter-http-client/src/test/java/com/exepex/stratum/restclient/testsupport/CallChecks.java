package com.exepex.stratum.restclient.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.IntSupplier;
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
   * Returns how many connections a check's call left the client to open: 0 when it gave its
   * connection back, 1 when it dropped it. A call that keeps its connection runs before, so that
   * the check's call finds one to reuse, and after, so that it shows whether that one is still
   * there. The client needs a pool of one, so that no other idle connection hides the drop.
   *
   * @param connections the server's count of connections opened so far
   * @param keepingCall a call that gives its connection back
   * @param check the check of the call under test
   * @return the number of connections opened after the check's call
   */
  public static int connectionsOpenedAfter(
      IntSupplier connections, Runnable keepingCall, Runnable check) {
    keepingCall.run();
    var before = connections.getAsInt();
    check.run();
    keepingCall.run();
    return connections.getAsInt() - before;
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
   * Checks that a call of a client's interface or bean ends with one of the HTTP client's codes.
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

  /**
   * Checks that a client's breaker is in the state given and has recorded neither a success nor a
   * failure, as after a call that was never sent.
   *
   * @param breaker the client's breaker
   * @param state the state it must be in
   */
  public static void assertNothingRecorded(CircuitBreaker breaker, CircuitBreaker.State state) {
    assertThat(breaker.getState()).isEqualTo(state);
    assertThat(breaker.getMetrics().getNumberOfSuccessfulCalls()).isZero();
    assertThat(breaker.getMetrics().getNumberOfFailedCalls()).isZero();
  }
}
