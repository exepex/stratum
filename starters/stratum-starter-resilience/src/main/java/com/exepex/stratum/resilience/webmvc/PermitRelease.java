package com.exepex.stratum.resilience.webmvc;

import com.exepex.stratum.resilience.LimitedRoute;
import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;

/**
 * Gives an asynchronous request's permit back once, when the server completes the request, also
 * after a timeout or an error, which the server follows with completion. Tomcat completes a request
 * that timed out twice, so a second completion gives nothing back.
 */
@RequiredArgsConstructor
class PermitRelease implements AsyncListener {

  /** The route whose permit the request holds. */
  private final LimitedRoute route;

  /** Whether the permit was given back. */
  private final AtomicBoolean released = new AtomicBoolean();

  /**
   * Gives the permit back, unless an earlier completion did.
   *
   * @param event the completion
   */
  @Override
  public void onComplete(AsyncEvent event) {
    if (released.compareAndSet(false, true)) {
      route.release();
    }
  }

  /**
   * Does nothing: the server completes the request after its timeout.
   *
   * @param event the timeout
   */
  @Override
  public void onTimeout(AsyncEvent event) {
    // onComplete follows and gives the permit back.
  }

  /**
   * Does nothing: the server completes the request after its error.
   *
   * @param event the error
   */
  @Override
  public void onError(AsyncEvent event) {
    // onComplete follows and gives the permit back.
  }

  /**
   * Listens again when the request goes asynchronous a second time, such as a {@code
   * CompletableFuture} that returns a streamed body: the server drops its listeners then, and the
   * permit would never come back.
   *
   * @param event the new start
   */
  @Override
  public void onStartAsync(AsyncEvent event) {
    event.getAsyncContext().addListener(this);
  }
}
