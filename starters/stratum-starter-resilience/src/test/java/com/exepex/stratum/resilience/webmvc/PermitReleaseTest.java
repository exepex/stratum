package com.exepex.stratum.resilience.webmvc;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.resilience.LimitedRoute;
import jakarta.servlet.AsyncEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("PermitRelease gives an asynchronous request's permit back once, on completion")
class PermitReleaseTest {

  /** A route that runs two requests at once, one of whose permits the request holds. */
  private final LimitedRoute route = new LimitedRoute("/reports/**", 2);

  /** The release of the request's permit. */
  private final PermitRelease release = new PermitRelease(route);

  /** The request's asynchronous context. */
  private final MockAsyncContext asyncContext =
      new MockAsyncContext(new MockHttpServletRequest(), new MockHttpServletResponse());

  /** The event the server passes the listener. */
  private final AsyncEvent event = new AsyncEvent(asyncContext);

  @Test
  @DisplayName("a timeout or an error keeps the permit; the completion after it gives it back")
  void completionReleases() {
    route.tryAdmit();

    release.onTimeout(event);
    release.onError(event);
    assertThat(route.active()).isEqualTo(1);

    release.onComplete(event);
    assertThat(route.active()).isZero();
  }

  @Test
  @DisplayName("a second completion, as Tomcat sends after a timeout, gives nothing back")
  void secondCompletionReleasesNothing() {
    route.tryAdmit();
    route.tryAdmit();

    release.onComplete(event);
    release.onComplete(event);

    assertThat(route.active()).isEqualTo(1);
    new PermitRelease(route).onComplete(event);
    assertThat(route.active()).isZero();
  }

  @Test
  @DisplayName("a second start listens again, as the server drops its listeners then")
  void secondStartListensAgain() {
    release.onStartAsync(event);

    assertThat(asyncContext.getListeners()).containsExactly(release);
  }
}
