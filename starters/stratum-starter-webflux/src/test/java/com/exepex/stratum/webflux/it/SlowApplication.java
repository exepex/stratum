package com.exepex.stratum.webflux.it;

import com.exepex.stratum.web.ResponseTimeout;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** A reactive service whose endpoints wait, as a slow database or downstream call does. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(SlowApplication.SlowController.class)
public class SlowApplication {

  /** The endpoints. */
  @RestController
  static class SlowController {

    /** How long the endpoint that is given more time waits. */
    private static final Duration GIVEN_MORE_TIME_WAIT = Duration.ofMillis(1500);

    /** How often the stream sends a line. */
    private static final Duration STREAM_INTERVAL = Duration.ofMillis(500);

    /** How many lines the stream sends, which takes longer than the route's limit. */
    private static final int STREAM_LINES = 4;

    /** How long the service's own timeout waits. */
    private static final Duration SERVICE_TIMEOUT = Duration.ofMillis(100);

    /**
     * Never answers, as a call with no timeout of its own.
     *
     * @return never completes
     */
    @GetMapping("/slow/blocked")
    Mono<String> blocked() {
      return Mono.never();
    }

    /**
     * Waits for longer than the route's limit, within its own.
     *
     * @return {@code done}
     */
    @ResponseTimeout(5)
    @GetMapping("/slow/given-more-time")
    Mono<String> givenMoreTime() {
      return Mono.delay(GIVEN_MORE_TIME_WAIT).thenReturn("done");
    }

    /**
     * Never answers, with a limit shorter than the default.
     *
     * @return never completes
     */
    @ResponseTimeout(value = 200, unit = TimeUnit.MILLISECONDS)
    @GetMapping("/orders/own-limit")
    Mono<String> ownLimit() {
      return Mono.never();
    }

    /**
     * Streams lines for longer than the route's limit, starting at once.
     *
     * @return the lines
     */
    @GetMapping(path = "/slow/lines", produces = MediaType.TEXT_PLAIN_VALUE)
    Flux<String> stream() {
      return Flux.interval(Duration.ZERO, STREAM_INTERVAL)
          .take(STREAM_LINES)
          .map(line -> "line " + line + "\n");
    }

    /**
     * Fails with the service's own timeout, long before the default limit.
     *
     * @return fails with a {@link java.util.concurrent.TimeoutException}
     */
    @GetMapping("/orders/own-timeout")
    Mono<String> ownTimeout() {
      return Mono.<String>never().timeout(SERVICE_TIMEOUT);
    }
  }
}
