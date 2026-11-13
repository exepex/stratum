package com.exepex.stratum.webflux;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.CurrentRequestTimeLeft;
import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.RequestDeadlinePropagation;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;
import reactor.core.scheduler.Schedulers;

@DisplayName(
    "On Reactor's threads, the request's deadline is seen as spring.reactor.context-propagation"
        + " allows")
class RequestDeadlineReactorPropagationTest {

  /** How long a test waits for its work. */
  private static final Duration WAIT = Duration.ofSeconds(5);

  /** The time limits, with the standard 10 second default. */
  private final ResponseTimeouts timeouts =
      new ResponseTimeouts(WebProperties.resolve(new MockEnvironment()), false);

  /** The time left on the calling thread. */
  private final CurrentRequestTimeLeft timeLeft = new CurrentRequestTimeLeft();

  /** The deadline of the request under test. */
  private final RequestDeadline deadline = timeouts.start("/orders", () -> {});

  /** Registers the accessor, as the web module does when it starts. */
  @BeforeAll
  static void registerAccessor() {
    new RequestDeadlinePropagation().afterSingletonsInstantiated();
  }

  /** Leaves Reactor as a unit test finds it, and stops the timer. */
  @AfterEach
  void restore() {
    Hooks.disableAutomaticContextPropagation();
    deadline.end();
    timeouts.close();
  }

  @Nested
  @DisplayName("with auto, Stratum's default on WebFlux")
  class Auto {

    @Test
    @DisplayName("a blocking call on the bounded elastic scheduler sees the time left")
    void blockingCallSeesTimeLeft() {
      Hooks.enableAutomaticContextPropagation();

      assertThat(readInBlockingCall()).isPresent();
    }
  }

  @Nested
  @DisplayName("with limited, Spring Boot's default")
  class Limited {

    @Test
    @DisplayName("a blocking call on the bounded elastic scheduler sees no limit")
    void blockingCallSeesNoLimit() {
      assertThat(readInBlockingCall()).isEmpty();
    }

    @Test
    @DisplayName("handle sees the time left")
    void handleSeesTimeLeft() {
      var seen =
          Mono.just(deadline)
              .subscribeOn(Schedulers.boundedElastic())
              .<Optional<Duration>>handle(this::readTimeLeft)
              .contextWrite(
                  context -> context.put(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY, deadline))
              .block(WAIT);

      assertThat(seen).isPresent();
    }

    /** Passes on the time left on the thread that runs the step. */
    private void readTimeLeft(RequestDeadline ignored, SynchronousSink<Optional<Duration>> sink) {
      sink.next(timeLeft.timeLeft());
    }
  }

  /** Reads the time left in a blocking call on the bounded elastic scheduler, within a request. */
  private Optional<Duration> readInBlockingCall() {
    return Mono.fromCallable(timeLeft::timeLeft)
        .subscribeOn(Schedulers.boundedElastic())
        .contextWrite(context -> context.put(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY, deadline))
        .block(WAIT);
  }
}
