package com.exepex.stratum.cache;

import java.util.concurrent.Callable;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Records whether a cached method's loader ran and what it returned, so a failure of the write that
 * follows the load never makes the loader run again, and a loaded value is returned as the method's
 * own object.
 *
 * @param <T> the loaded value's type
 */
@RequiredArgsConstructor
final class RecordedLoader<T> implements Callable<T> {

  /** The loader Spring passes, which calls the cached method. */
  private final Callable<T> delegate;

  /** Whether the loader ran. */
  private boolean ran;

  /** What the loader returned; {@code null} until it ran. */
  private @Nullable T loaded;

  @Override
  public T call() throws Exception {
    loaded = delegate.call();
    ran = true;
    return loaded;
  }

  /**
   * Returns whether the loader ran.
   *
   * @return whether it ran
   */
  boolean hasRun() {
    return ran;
  }

  /**
   * Returns what the loader returned, or {@code null} when it has not run.
   *
   * @return the loaded value
   */
  @Nullable T value() {
    return loaded;
  }
}
