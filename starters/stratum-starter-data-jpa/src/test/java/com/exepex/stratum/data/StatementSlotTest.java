package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.data.StatementLimitRule.Limit;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import com.exepex.stratum.data.StatementSlot.Outcome;
import java.sql.Statement;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StatementSlot keeps one connection's running statement for the sweeper")
class StatementSlotTest {

  /** A limit of one second. */
  private static final Limit ONE_SECOND = new Limit(1_000, LimitSource.QUERY_TIMEOUT);

  /** How far past a one-second limit the tests move the clock. */
  private static final long PAST_LIMIT_MILLIS = 2_000;

  /** How long a statement waits for its cancel in the test where the wait runs out. */
  private static final long SHORT_WAIT_MILLIS = 20;

  /** How long a waiting statement is checked to still wait. */
  private static final Duration STILL_WAITING = Duration.ofMillis(100);

  /** How long a released statement may take to return. */
  private static final Duration RELEASE_WAIT = Duration.ofSeconds(5);

  /** How long a statement waits for a cancel in these tests, unless the test is about it. */
  private static final long LONG_WAIT_MILLIS = 30_000;

  /** The test's clock, which only moves when a test moves it. */
  private final AtomicLong now = new AtomicLong(1_000);

  private final StatementSlot slot = new StatementSlot();

  private final Statement statement = mock(Statement.class);

  /** Returns a time past the one-second limit of a statement started now. */
  private long afterLimit() {
    return now.get() + PAST_LIMIT_MILLIS;
  }

  /** Starts the statement now, with a one-second limit. */
  private long startStatement() {
    return slot.start(statement, now.get(), ONE_SECOND);
  }

  /** Ends a statement on a virtual thread, which may wait for its cancel. */
  private CompletableFuture<Outcome> endOnVirtualThread(long running, long maxWaitMillis) {
    var outcome = new CompletableFuture<Outcome>();
    Thread.ofVirtual().start(() -> outcome.complete(slot.end(running, maxWaitMillis)));
    return outcome;
  }

  @Nested
  @DisplayName("a statement the sweeper did not claim")
  class NotClaimed {

    @Test
    @DisplayName("ends without waiting, and the slot is idle again")
    void endsWithoutWaiting() {
      long running = startStatement();

      assertThat(slot.end(running, LONG_WAIT_MILLIS)).isEqualTo(Outcome.NOT_CLAIMED);
      now.addAndGet(PAST_LIMIT_MILLIS);
      assertThat(slot.claimIfExpired(now.get())).isNull();
    }

    @Test
    @DisplayName("is never claimed at its deadline, only after it")
    void neverEarly() {
      startStatement();

      assertThat(slot.claimIfExpired(now.get() + ONE_SECOND.millis())).isNull();
      assertThat(slot.claimIfExpired(now.get() + ONE_SECOND.millis() + 1)).isNotNull();
    }

    @Test
    @DisplayName("gets a new value each time, so a stale value never matches the next statement")
    void uniqueValues() {
      long first = startStatement();
      slot.end(first, LONG_WAIT_MILLIS);

      assertThat(startStatement()).isNotEqualTo(first);
    }

    @Test
    @DisplayName("keeps a limit too long to count, so it is never claimed and nothing overflows")
    void longestLimit() {
      long running =
          slot.start(statement, now.get(), new Limit(Long.MAX_VALUE, LimitSource.CALLER));

      assertThat(running).isNotNegative();
      assertThat(slot.claimIfExpired(StatementSlot.MAX_DEADLINE)).isNull();
    }
  }

  @Nested
  @DisplayName("a statement the sweeper claimed")
  class Claimed {

    @Test
    @DisplayName("is handed to the sweeper with the statement read at the claim")
    void claimHoldsStatement() {
      long running = startStatement();

      var claim = slot.claimIfExpired(afterLimit());

      assertThat(claim).isEqualTo(new StatementSlot.Claim(slot, running, statement));
    }

    @Test
    @DisplayName("ends only after its cancel has ended, on a virtual thread too")
    void waitsForCancel() throws Exception {
      long running = startStatement();
      slot.claimIfExpired(afterLimit());

      var outcome = endOnVirtualThread(running, LONG_WAIT_MILLIS);
      await().during(STILL_WAITING).atMost(Duration.ofSeconds(1)).until(() -> !outcome.isDone());
      slot.cancelEnded(running);

      assertThat(outcome.get(RELEASE_WAIT.toSeconds(), TimeUnit.SECONDS))
          .isEqualTo(Outcome.CLAIMED);
      assertThat(slot.claimIfExpired(afterLimit())).isNull();
    }

    @Test
    @DisplayName("stops waiting when the wait runs out, and a later end of the cancel does nothing")
    void waitRunsOut() {
      long running = startStatement();
      slot.claimIfExpired(afterLimit());

      assertThat(slot.end(running, SHORT_WAIT_MILLIS)).isEqualTo(Outcome.CLAIMED_WAIT_EXPIRED);
      slot.cancelEnded(running);
      long next = startStatement();
      assertThat(slot.end(next, LONG_WAIT_MILLIS)).isEqualTo(Outcome.NOT_CLAIMED);
    }

    @Test
    @DisplayName("keeps waiting when interrupted, and returns with the interrupt flag set")
    void interruptKeepsWaiting() throws Exception {
      long running = startStatement();
      slot.claimIfExpired(afterLimit());
      var stillInterrupted = new AtomicBoolean();
      var outcome = new CompletableFuture<Outcome>();
      var waiter =
          Thread.ofPlatform()
              .start(
                  () -> {
                    outcome.complete(slot.end(running, LONG_WAIT_MILLIS));
                    stillInterrupted.set(Thread.currentThread().isInterrupted());
                  });

      await().until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
      waiter.interrupt();
      await().until(() -> waiter.getState() == Thread.State.TIMED_WAITING);
      assertThat(outcome).isNotDone();
      slot.cancelEnded(running);
      waiter.join(RELEASE_WAIT);

      assertThat(outcome.get()).isEqualTo(Outcome.CLAIMED);
      assertThat(stillInterrupted).isTrue();
    }
  }

  @Nested
  @DisplayName("two statements on one connection at once, which JDBC does not allow")
  class SharedConnection {

    @Test
    @DisplayName("the earlier statement's end leaves the later one's limit in place")
    void earlierEndKeepsLaterLimit() {
      long earlier = startStatement();
      long later = startStatement();

      assertThat(slot.end(earlier, LONG_WAIT_MILLIS)).isEqualTo(Outcome.NOT_CLAIMED);

      assertThat(slot.claimIfExpired(afterLimit()))
          .isEqualTo(new StatementSlot.Claim(slot, later, statement));
    }

    @Test
    @DisplayName("the earlier statement's end leaves the sweeper's claim on the later one")
    void earlierEndKeepsLaterClaim() {
      long earlier = startStatement();
      long later = startStatement();
      slot.claimIfExpired(afterLimit());

      assertThat(slot.end(earlier, LONG_WAIT_MILLIS)).isEqualTo(Outcome.NOT_CLAIMED);
      slot.cancelEnded(later);

      assertThat(slot.end(later, LONG_WAIT_MILLIS)).isEqualTo(Outcome.CLAIMED);
    }
  }
}
