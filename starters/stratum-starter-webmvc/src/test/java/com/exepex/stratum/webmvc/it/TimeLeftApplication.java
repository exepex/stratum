package com.exepex.stratum.webmvc.it;

import static org.awaitility.Awaitility.await;

import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.ResponseTimeout;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.DeferredResult;

/**
 * A service that reads how long its requests have left: on the request's thread, in its {@code
 * Async} work and in its Spring MVC async handlers. Each endpoint answers the time left in
 * milliseconds, or {@value WebTestSupport#NO_TIME_LIMIT}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@EnableAsync
@Import({TimeLeftApplication.TimeLeftController.class, TimeLeftApplication.AsyncWork.class})
public class TimeLeftApplication {

  /** How long the endpoint that ignores the interrupt runs past its limit. */
  static final Duration RUNS_PAST_LIMIT = Duration.ofMillis(600);

  /** How long work waits for the test to let it go on. */
  static final Duration WAIT = Duration.ofSeconds(5);

  /** The service's async work, which reads the time left on the executor's thread. */
  @Component
  @RequiredArgsConstructor
  static class AsyncWork {

    /** How long the current request has left. */
    private final RequestTimeLeft requestTimeLeft;

    /** Lets the work that runs after the response go on. */
    private final CountDownLatch responseSent = new CountDownLatch(1);

    /** What the work that runs after the response saw. */
    private final CompletableFuture<String> seenAfterResponse = new CompletableFuture<>();

    /**
     * Reads the time left on the executor's thread.
     *
     * @return the time left there
     */
    @Async
    public CompletableFuture<String> timeLeftNow() {
      return CompletableFuture.completedFuture(
          WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft()));
    }

    /**
     * Waits until the request has been answered, then reads the time left.
     *
     * @throws InterruptedException if interrupted while waiting
     */
    @Async
    public void timeLeftAfterResponse() throws InterruptedException {
      responseSent.await(WAIT.toSeconds(), TimeUnit.SECONDS);
      seenAfterResponse.complete(WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft()));
    }

    /**
     * Lets the work that runs after the response go on, and returns what it saw.
     *
     * @return the future of what it saw
     */
    CompletableFuture<String> releaseAfterResponse() {
      responseSent.countDown();
      return seenAfterResponse;
    }
  }

  /** The endpoints. */
  @RestController
  @RequiredArgsConstructor
  static class TimeLeftController {

    /** How long the current request has left. */
    private final RequestTimeLeft requestTimeLeft;

    /** The service's async work. */
    private final AsyncWork asyncWork;

    /** What the handler that ran past its limit saw, once it was late. */
    @Getter(AccessLevel.PACKAGE)
    private final CompletableFuture<String> seenWhenLate = new CompletableFuture<>();

    /**
     * Reads the time left on the request's thread.
     *
     * @return the time left
     */
    @GetMapping("/time-left")
    String onRequestThread() {
      return WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft());
    }

    /**
     * Reads the time left under the method's own limit.
     *
     * @return the time left
     */
    @ResponseTimeout(20)
    @GetMapping("/time-left/own-limit")
    String ownLimit() {
      return WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft());
    }

    /**
     * Reads the time left on a route with no limit.
     *
     * @return {@value WebTestSupport#NO_TIME_LIMIT}
     */
    @GetMapping("/unlimited/time-left")
    String unlimited() {
      return WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft());
    }

    /**
     * Reads the time left in async work while the request waits for it.
     *
     * @return the time left there
     */
    @GetMapping("/time-left/async")
    String inAsyncWork() {
      return asyncWork.timeLeftNow().join();
    }

    /**
     * Starts async work that reads the time left after the response.
     *
     * @return {@code started}
     * @throws InterruptedException never; the work runs on the executor
     */
    @GetMapping("/time-left/async-after-response")
    String startAsyncWorkAfterResponse() throws InterruptedException {
      asyncWork.timeLeftAfterResponse();
      return "started";
    }

    /**
     * Returns a callable that waits until the request's limit has ended, which happens when this
     * handler returns, then reads the time left.
     *
     * @return the callable
     */
    @GetMapping("/time-left/callable")
    Callable<String> inCallable() {
      return () ->
          WebTestSupport.describeTimeLeft(
              await().atMost(WAIT).until(requestTimeLeft::timeLeft, Optional::isEmpty));
    }

    /**
     * Completes the answer on a thread of the service's own, which nothing carries the deadline to.
     *
     * @return the deferred answer
     */
    @GetMapping("/time-left/deferred")
    DeferredResult<String> inDeferredResult() {
      var answer = new DeferredResult<String>();
      Thread.ofPlatform()
          .start(
              () -> answer.setResult(WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft())));
      return answer;
    }

    /**
     * Ignores the interrupt, runs past the route's limit, and records the time left it then sees.
     *
     * @return never answered: the request gets the timeout status
     */
    @GetMapping("/late/time-left")
    String late() {
      runPastTheLimit();
      seenWhenLate.complete(WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft()));
      return "late";
    }

    /** Runs past the route's limit, ignoring the interrupt, and leaves the thread interrupted. */
    private void runPastTheLimit() {
      var answerAt = System.nanoTime() + RUNS_PAST_LIMIT.toNanos();
      var neverReleased = new CountDownLatch(1);
      var interrupted = false;
      while (System.nanoTime() < answerAt) {
        try {
          neverReleased.await(answerAt - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException _) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
