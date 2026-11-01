package com.exepex.stratum.webmvc.it;

import com.exepex.stratum.core.error.StratumException;
import com.exepex.stratum.test.OrderErrorCode;
import com.exepex.stratum.web.ResponseTimeout;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A service whose endpoints block, as a slow database or downstream call does. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(SlowApplication.SlowController.class)
public class SlowApplication {

  /** The endpoints. */
  @RestController
  static class SlowController {

    /** How long the endpoint that is given more time blocks. */
    private static final long GIVEN_MORE_TIME_BLOCKS_MILLIS = 1500;

    /** How long the endpoint that ignores the interrupt runs. */
    private static final long IGNORES_INTERRUPT_MILLIS = 1500;

    /**
     * Ignores the interrupt and answers after the route's limit, as a socket read on a platform
     * thread does. Like most code, it leaves the thread marked as interrupted.
     *
     * @return {@code late}
     */
    @GetMapping("/slow/ignores-interrupt")
    String ignoresInterrupt() {
      runPastTheLimit();
      return "late";
    }

    /**
     * Ignores the interrupt, runs past the route's limit and then fails with a code the service
     * maps, as a slow call followed by a business check does.
     *
     * @return never
     */
    @GetMapping("/slow/ignores-interrupt-then-fails")
    String ignoresInterruptThenFails() {
      runPastTheLimit();
      throw new StratumException(OrderErrorCode.DUPLICATE_ORDER, "order 42");
    }

    /** Runs past the route's limit, ignoring the interrupt, and leaves the thread interrupted. */
    private void runPastTheLimit() {
      var answerAt = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IGNORES_INTERRUPT_MILLIS);
      var neverReleased = new CountDownLatch(1);
      var interrupted = false;
      while (System.nanoTime() < answerAt) {
        try {
          neverReleased.await(answerAt - System.nanoTime(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException ignored) {
          interrupted = true;
        }
      }
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    /**
     * Blocks until it is interrupted, as a call with no timeout of its own does.
     *
     * @return never
     * @throws InterruptedException once interrupted
     */
    @GetMapping("/slow/blocked")
    String blocked() throws InterruptedException {
      new CountDownLatch(1).await();
      return "never";
    }

    /**
     * Blocks for longer than the route's limit, within its own.
     *
     * @return {@code done}
     * @throws InterruptedException if interrupted
     */
    @ResponseTimeout(5)
    @GetMapping("/slow/given-more-time")
    String givenMoreTime() throws InterruptedException {
      new CountDownLatch(1).await(GIVEN_MORE_TIME_BLOCKS_MILLIS, TimeUnit.MILLISECONDS);
      return "done";
    }

    /**
     * Blocks until interrupted, with a limit shorter than the default.
     *
     * @return never
     * @throws InterruptedException once interrupted
     */
    @ResponseTimeout(value = 200, unit = TimeUnit.MILLISECONDS)
    @GetMapping("/orders/own-limit")
    String ownLimit() throws InterruptedException {
      new CountDownLatch(1).await();
      return "never";
    }
  }
}
