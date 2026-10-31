package com.exepex.stratum.webmvc.it;

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
