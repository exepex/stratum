package com.exepex.stratum.resilience.it;

import com.exepex.stratum.resilience.testsupport.HeldRequests;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/** Endpoints that hold, fail, run long or never answer, for the limit's edge cases. */
@RestController
@RequiredArgsConstructor
class HeldController {

  /** The path that holds its work, which fills a route with a limit of 1. */
  static final String HELD_PATH = "/held/sync";

  /** How long the slow endpoint blocks, far past any time limit of the tests. */
  private static final Duration BLOCK = Duration.ofSeconds(5);

  /** The work the endpoints hold. */
  private final HeldRequests held;

  /**
   * Holds on the request thread.
   *
   * @return the answer
   */
  @GetMapping({HELD_PATH, "/reports/held", "/release/held"})
  String holdOnRequestThread() {
    return held.hold();
  }

  /**
   * Answers at once.
   *
   * @return the answer
   */
  @GetMapping({"/held/quick", "/reports/{id}", "/release/quick"})
  String quick() {
    return HeldRequests.DONE;
  }

  /**
   * Blocks until interrupted, as a blocking call that honours interrupts does.
   *
   * @return the answer, if nothing interrupts it within 5 seconds
   * @throws InterruptedException when the time limit interrupts it
   */
  @GetMapping("/held/slow")
  String slow() throws InterruptedException {
    new CountDownLatch(1).await(BLOCK.toMillis(), TimeUnit.MILLISECONDS);
    return HeldRequests.DONE;
  }

  /**
   * Fails on the request thread.
   *
   * @return never
   */
  @GetMapping({"/held/fails", "/release/fails"})
  String fails() {
    throw new IllegalStateException("handler failed");
  }

  /**
   * Fails in a {@code Callable}.
   *
   * @return the failing work
   */
  @GetMapping("/release/callable-fails")
  Callable<String> callableFails() {
    return this::fails;
  }

  /**
   * Never sets its result, so the request runs out of Spring MVC's async time.
   *
   * @return the result that never comes
   */
  @GetMapping("/release/never")
  DeferredResult<String> never() {
    return new DeferredResult<>();
  }
}
