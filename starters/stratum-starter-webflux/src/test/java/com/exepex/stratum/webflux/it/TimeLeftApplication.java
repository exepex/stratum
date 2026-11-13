package com.exepex.stratum.webflux.it;

import com.exepex.stratum.core.request.RequestTimeLeft;
import com.exepex.stratum.test.WebTestSupport;
import com.exepex.stratum.web.ResponseTimeout;
import com.exepex.stratum.web.WebConstants;
import java.util.concurrent.CompletableFuture;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.ContextView;

/**
 * A reactive service that reads how long its requests have left, on the threads Reactor runs their
 * work on. Each endpoint answers the time left in milliseconds, or {@value
 * WebTestSupport#NO_TIME_LIMIT}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(TimeLeftApplication.TimeLeftController.class)
public class TimeLeftApplication {

  /** The endpoints. */
  @RestController
  @RequiredArgsConstructor
  static class TimeLeftController {

    /** How long the current request has left. */
    private final RequestTimeLeft requestTimeLeft;

    /** The deadline of the request that kept it, to read after the response. */
    @Getter(AccessLevel.PACKAGE)
    private final CompletableFuture<RequestTimeLeft> keptDeadline = new CompletableFuture<>();

    /**
     * Reads the time left where the handler's work runs.
     *
     * @return the time left
     */
    @GetMapping("/time-left")
    Mono<String> inHandler() {
      return Mono.fromSupplier(this::timeLeftNow);
    }

    /**
     * Reads the time left in a blocking call moved to the bounded elastic scheduler, as a JDBC call
     * is.
     *
     * @return the time left there
     */
    @GetMapping("/time-left/blocking")
    Mono<String> inBlockingCall() {
      return Mono.fromCallable(this::timeLeftNow).subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Reads the time left after the work moved to another scheduler.
     *
     * @return the time left there
     */
    @GetMapping("/time-left/publish-on")
    Mono<String> afterPublishOn() {
      return Mono.just(WebTestSupport.NO_TIME_LIMIT)
          .publishOn(Schedulers.parallel())
          .map(ignored -> timeLeftNow());
    }

    /**
     * Reads the time left under the method's own limit.
     *
     * @return the time left
     */
    @ResponseTimeout(20)
    @GetMapping("/time-left/own-limit")
    Mono<String> ownLimit() {
      return Mono.fromSupplier(this::timeLeftNow);
    }

    /**
     * Reads the time left on a route with no limit.
     *
     * @return {@value WebTestSupport#NO_TIME_LIMIT}
     */
    @GetMapping("/unlimited/time-left")
    Mono<String> unlimited() {
      return Mono.fromSupplier(this::timeLeftNow);
    }

    /**
     * Keeps the request's deadline, as work that outlives the request can, to read after the
     * response.
     *
     * @return {@code kept}
     */
    @GetMapping("/time-left/kept")
    Mono<String> keepDeadline() {
      return Mono.deferContextual(this::keep);
    }

    /** Keeps the deadline the request carries in its Reactor context. */
    private Mono<String> keep(ContextView context) {
      keptDeadline.complete(context.get(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY));
      return Mono.just("kept");
    }

    /** Returns the time left on the calling thread, as an endpoint answers it. */
    private String timeLeftNow() {
      return WebTestSupport.describeTimeLeft(requestTimeLeft.timeLeft());
    }
  }
}
