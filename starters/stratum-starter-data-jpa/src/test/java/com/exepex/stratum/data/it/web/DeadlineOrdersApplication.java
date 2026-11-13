package com.exepex.stratum.data.it.web;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A service whose endpoints run statements within a web request's time limit: a slow query, a
 * borrow after the request's time is almost spent, a query in {@code @Async} work, and a stream
 * that queries after its limit has passed. The tests give {@link #DEADLINE_ROUTES} a limit of one
 * second.
 */
@SpringBootApplication
@EnableAsync
public class DeadlineOrdersApplication {

  /** The routes the tests give a limit of one second. */
  public static final String DEADLINE_ROUTES = "/deadline/**";

  /** The path of the endpoint whose query runs past the request's limit. */
  public static final String SLOW_QUERY = "/deadline/slow";

  /** The path of the endpoint that borrows a connection once the request's time is almost spent. */
  public static final String LATE_BORROW = "/deadline/late-borrow";

  /** The path of the endpoint whose slow query runs in {@code @Async} work. */
  public static final String ASYNC_QUERY = "/deadline/async";

  /** The path of the endpoint that starts its response, then queries past its limit. */
  public static final String LATE_STREAM = "/deadline/stream";

  /** A query that sleeps for 5 seconds, longer than every limit the tests set. */
  static final String SLEEP_FIVE_SECONDS = "SELECT pg_sleep(5)::text";

  /** A query that answers at once. */
  static final String ANSWER = "SELECT 'answered'";

  /**
   * How long the late borrow waits before it queries, in milliseconds: it then has less time left
   * than the tests' margin of 500 ms, well before the one second limit.
   */
  static final long WAIT_MILLIS = 700;

  /** How long the stream waits past its one second limit before it queries, in milliseconds. */
  static final long PAST_LIMIT_MILLIS = 1_300;

  /**
   * Keeps the counters, so the tests can read {@code stratum.data.timeouts}.
   *
   * @return the registry
   */
  @Bean
  MeterRegistry meterRegistry() {
    return new SimpleMeterRegistry();
  }

  /** The service's async work, which runs a slow query on the executor's thread. */
  @Component
  @RequiredArgsConstructor
  static class AsyncOrders {

    /** Runs the queries. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs the slow query on the executor's thread.
     *
     * @return the query's answer, never normally
     */
    @Async
    public CompletableFuture<String> slowQuery() {
      return CompletableFuture.completedFuture(
          jdbcTemplate.queryForObject(SLEEP_FIVE_SECONDS, String.class));
    }
  }

  /** The endpoints. */
  @RestController
  @RequiredArgsConstructor
  static class DeadlineOrdersController {

    /** Runs the queries. */
    private final JdbcTemplate jdbcTemplate;

    /** The service's async work. */
    private final AsyncOrders asyncOrders;

    /**
     * Runs a query that sleeps past the request's limit.
     *
     * @return never returns normally
     */
    @GetMapping(SLOW_QUERY)
    String slowQuery() {
      return jdbcTemplate.queryForObject(SLEEP_FIVE_SECONDS, String.class);
    }

    /**
     * Waits until less time is left than the margin, then borrows a connection.
     *
     * @return never returns normally
     */
    @GetMapping(LATE_BORROW)
    String lateBorrow() {
      waitIgnoringInterrupt(WAIT_MILLIS);
      return jdbcTemplate.queryForObject(ANSWER, String.class);
    }

    /**
     * Runs the slow query in async work and waits for it.
     *
     * @return never returns normally
     */
    @GetMapping(ASYNC_QUERY)
    String asyncQuery() {
      return asyncOrders.slowQuery().join();
    }

    /**
     * Starts the response, waits past the request's limit, then queries and writes the answer.
     *
     * @param response the response, which is started at once
     * @throws IOException if the response cannot be written
     */
    @GetMapping(LATE_STREAM)
    void lateStream(HttpServletResponse response) throws IOException {
      var writer = response.getWriter();
      writer.write("started ");
      response.flushBuffer();
      waitIgnoringInterrupt(PAST_LIMIT_MILLIS);
      writer.write(jdbcTemplate.queryForObject(ANSWER, String.class));
    }

    /**
     * Waits without being cut short, as code that ignores interrupts does.
     *
     * @param millis how long to wait, in milliseconds
     */
    private void waitIgnoringInterrupt(long millis) {
      var until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis);
      var neverReleased = new CountDownLatch(1);
      var interrupted = false;
      // A loop, not a stream: it waits until a point in time, which no stream expresses.
      while (System.nanoTime() < until) {
        try {
          neverReleased.await(until - System.nanoTime(), TimeUnit.NANOSECONDS);
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
