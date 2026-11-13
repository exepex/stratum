package com.exepex.stratum.data.benchmark;

import com.exepex.stratum.web.CurrentRequestTimeLeft;
import com.exepex.stratum.web.RequestDeadline;
import com.exepex.stratum.web.RequestDeadlineAccessor;
import com.exepex.stratum.web.RequestDeadlinePropagation;
import com.exepex.stratum.web.ResponseTimeouts;
import com.exepex.stratum.web.WebConstants;
import com.exepex.stratum.web.WebProperties;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ContextSnapshotFactory;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;
import org.springframework.mock.env.MockEnvironment;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;

/**
 * Measures what reading a request's time left costs, which the data boundary will do before each
 * statement, and what carrying the deadline to other threads adds: to each Reactor operator under
 * automatic context propagation, and to each task a context-carrying executor runs. The {@code
 * none} side has no deadline on the thread and no deadline accessor registered, as before the
 * deadline was carried, so the cost is the difference.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(3)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 10, time = 1)
public class RequestTimeLeftBenchmark {

  /** The side with a deadline on the thread and its accessor registered. */
  private static final String DEADLINE = "deadline";

  /**
   * Reads the time left of the request on this thread, on one thread.
   *
   * @param request this thread's request
   * @return the time left, which JMH consumes
   */
  @Benchmark
  @Threads(1)
  public Optional<Duration> timeLeft(ThreadRequest request) {
    return request.timeLeft.timeLeft();
  }

  /**
   * Reads the time left of the request on this thread, on 16 threads at once.
   *
   * @param request this thread's request
   * @return the time left, which JMH consumes
   */
  @Benchmark
  @Threads(16)
  public Optional<Duration> timeLeftConcurrently(ThreadRequest request) {
    return request.timeLeft.timeLeft();
  }

  /**
   * Runs a chain of five operators under automatic context propagation, which sets and clears the
   * registered accessors around each of them.
   *
   * @param request this thread's request
   * @return the chain's result, which JMH consumes
   */
  @Benchmark
  @Threads(1)
  public Integer reactorChain(ThreadRequest request) {
    return request.chain.block();
  }

  /**
   * Captures the context and runs a task in it, as a context-carrying executor does for each task.
   *
   * @param request this thread's request
   * @return the task's result, which JMH consumes
   * @throws Exception never: the task does no work
   */
  @Benchmark
  @Threads(1)
  public Integer wrappedTask(ThreadRequest request) throws Exception {
    return request.snapshots.captureAll().wrap(request.task).call();
  }

  /** The deadline accessor, registered or not for this trial, and Reactor's propagation. */
  @State(Scope.Benchmark)
  public static class Carrying {

    /** No deadline and no accessor, or a deadline carried by its accessor. */
    @Param({"none", "deadline"})
    public String setting;

    /** Registers the accessor on the deadline side, and turns on Reactor's propagation. */
    @Setup(Level.Trial)
    public void start() {
      if (DEADLINE.equals(setting)) {
        new RequestDeadlinePropagation().afterSingletonsInstantiated();
      } else {
        ContextRegistry.getInstance()
            .removeThreadLocalAccessor(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY);
      }
      Hooks.enableAutomaticContextPropagation();
    }

    /** Turns Reactor's propagation off again. */
    @TearDown(Level.Trial)
    public void stop() {
      Hooks.disableAutomaticContextPropagation();
    }
  }

  /** One thread's request, as one request thread has. */
  @State(Scope.Thread)
  public static class ThreadRequest {

    /** Reads the time left, as the bean does. */
    private final CurrentRequestTimeLeft timeLeft = new CurrentRequestTimeLeft();

    /** Puts the deadline on this thread. */
    private final RequestDeadlineAccessor deadlines = new RequestDeadlineAccessor();

    /** Takes the context of this thread, as Spring's executors do. */
    private final ContextSnapshotFactory snapshots = ContextSnapshotFactory.builder().build();

    /** The task a wrapped executor runs: it does no work. */
    private final Callable<Integer> task = () -> 1;

    /** The time limits, with the standard 10 second default. */
    private ResponseTimeouts timeouts;

    /** The request's deadline, on the deadline side. */
    private RequestDeadline deadline;

    /** Five operators, with the request's deadline in their context. */
    private Mono<Integer> chain;

    /**
     * Starts this thread's request and puts its deadline on the thread, on the deadline side.
     *
     * @param carrying the setting of this trial
     */
    @Setup(Level.Trial)
    public void start(Carrying carrying) {
      timeouts = new ResponseTimeouts(WebProperties.resolve(new MockEnvironment()), false);
      deadline = timeouts.start("/orders", () -> {});
      // Both sides carry the deadline in the Reactor context; only a registered accessor reads it.
      chain =
          Mono.just(1)
              .map(this::next)
              .map(this::next)
              .map(this::next)
              .map(this::next)
              .map(this::next)
              .contextWrite(
                  context -> context.put(WebConstants.REQUEST_DEADLINE_CONTEXT_KEY, deadline));
      if (DEADLINE.equals(carrying.setting)) {
        deadlines.setValue(deadline);
      }
    }

    /** Ends the request and stops its timer. */
    @TearDown(Level.Trial)
    public void stop() {
      deadlines.setValue();
      deadline.end();
      timeouts.close();
    }

    /** One operator's work: it adds one. */
    private Integer next(Integer value) {
      return value + 1;
    }
  }
}
