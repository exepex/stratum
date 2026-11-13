package com.exepex.stratum.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.exepex.stratum.data.StatementLimitRule.Limit;
import com.exepex.stratum.data.StatementLimitRule.LimitSource;
import com.exepex.stratum.data.StatementSlot.Outcome;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("StatementSweeper cancels the statements past their limit")
class StatementSweeperTest {

  /** A limit of one second. */
  private static final Limit ONE_SECOND = new Limit(1_000, LimitSource.QUERY_TIMEOUT);

  /** How long a statement waits for its cancel in these tests. */
  private static final long WAIT_MILLIS = 30_000;

  /** The test's clock, which only moves when a test moves it. */
  private final AtomicLong now = new AtomicLong(1_000);

  /** The cancels the sweeper started, run when a test runs them. */
  private final List<Runnable> startedCancels = new ArrayList<>();

  private StatementSweeper sweeper = sweeperStartingWith(startedCancels::add);

  /** How many cancels the refusing starter was given. */
  private final AtomicLong offeredCancels = new AtomicLong();

  /** How many cancels the refusing starter ran. */
  private final AtomicLong ranCancels = new AtomicLong();

  /** Refuses the first cancel, as a scheduler with no carrier thread does, and runs the others. */
  private void refuseFirstThenRun(Runnable cancel) {
    if (offeredCancels.getAndIncrement() == 0) {
      throw new RejectedExecutionException("no carrier thread");
    }
    ranCancels.incrementAndGet();
    cancel.run();
  }

  /** Returns a sweeper on the test's clock that starts each cancel with the given starter. */
  private StatementSweeper sweeperStartingWith(Consumer<Runnable> starter) {
    return new StatementSweeper(now::get, starter);
  }

  /** Returns a registered slot running a statement with a one-second limit, and its value. */
  private long startIn(StatementSlot slot, Statement statement) {
    sweeper.register(slot);
    return slot.start(statement, now.get(), ONE_SECOND);
  }

  /** Moves the clock past every one-second limit started so far. */
  private void passLimits() {
    now.addAndGet(ONE_SECOND.millis() + 1);
  }

  /** Stops the sweeper's thread, if it started one. */
  @AfterEach
  void stopSweeper() {
    sweeper.close();
  }

  @Nested
  @DisplayName("a statement past its limit")
  class PastLimit {

    @Test
    @DisplayName("is cancelled, and its end reports the claim")
    void isCancelled() throws SQLException {
      var slot = new StatementSlot();
      var statement = mock(Statement.class);
      final long running = startIn(slot, statement);

      passLimits();
      sweeper.sweep();
      startedCancels.forEach(Runnable::run);

      verify(statement).cancel();
      assertThat(slot.end(running, WAIT_MILLIS)).isEqualTo(Outcome.CLAIMED);
    }

    @Test
    @DisplayName("whose cancel fails is left to the network timeout, and its end still returns")
    void failedCancelDropped() throws SQLException {
      var slot = new StatementSlot();
      var statement = mock(Statement.class);
      doThrow(new SQLException("no route")).when(statement).cancel();
      final long running = startIn(slot, statement);

      passLimits();
      sweeper.sweep();
      startedCancels.forEach(Runnable::run);

      assertThat(slot.end(running, WAIT_MILLIS)).isEqualTo(Outcome.CLAIMED);
    }

    @Test
    @DisplayName("is cancelled even when its cancel starts after the next statement began")
    void lateCancelHitsClaimedStatement() throws SQLException {
      var slot = new StatementSlot();
      var claimed = mock(Statement.class);
      final var next = mock(Statement.class);
      final long running = startIn(slot, claimed);
      passLimits();
      sweeper.sweep();

      assertThat(slot.end(running, 0)).isEqualTo(Outcome.CLAIMED_WAIT_EXPIRED);
      final long nextRunning = slot.start(next, now.get(), ONE_SECOND);
      startedCancels.forEach(Runnable::run);

      verify(claimed).cancel();
      verify(next, never()).cancel();
      assertThat(slot.end(nextRunning, WAIT_MILLIS)).isEqualTo(Outcome.NOT_CLAIMED);
    }
  }

  @Test
  @DisplayName("a statement within its limit is not cancelled")
  void withinLimit() throws SQLException {
    var slot = new StatementSlot();
    var statement = mock(Statement.class);
    startIn(slot, statement);

    now.addAndGet(ONE_SECOND.millis());
    sweeper.sweep();

    assertThat(startedCancels).isEmpty();
    verify(statement, never()).cancel();
  }

  @Test
  @DisplayName("a cancel that cannot start frees its statement at once, and the sweeps go on")
  void cancelThatCannotStart() throws SQLException {
    sweeper.close();
    sweeper = sweeperStartingWith(this::refuseFirstThenRun);
    var first = new StatementSlot();
    final long firstRunning = startIn(first, mock(Statement.class));
    var second = new StatementSlot();
    final long secondRunning = startIn(second, mock(Statement.class));
    passLimits();

    sweeper.sweep();
    var later = mock(Statement.class);
    startIn(new StatementSlot(), later);
    passLimits();
    sweeper.sweep();

    assertThat(first.end(firstRunning, WAIT_MILLIS)).isEqualTo(Outcome.CLAIMED);
    assertThat(second.end(secondRunning, WAIT_MILLIS)).isEqualTo(Outcome.CLAIMED);
    assertThat(ranCancels).hasValue(2);
    verify(later).cancel();
  }

  @Test
  @DisplayName("a released slot is no longer checked")
  void releasedSlot() {
    var slot = new StatementSlot();
    startIn(slot, mock(Statement.class));

    sweeper.release(slot);
    passLimits();
    sweeper.sweep();

    assertThat(sweeper.size()).isZero();
    assertThat(startedCancels).isEmpty();
  }
}
