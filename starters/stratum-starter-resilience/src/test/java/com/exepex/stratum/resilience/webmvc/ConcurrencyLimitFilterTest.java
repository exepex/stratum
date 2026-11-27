package com.exepex.stratum.resilience.webmvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.exepex.stratum.core.request.HealthProbePaths;
import com.exepex.stratum.resilience.LimitedRoute;
import com.exepex.stratum.resilience.RequestRefusedException;
import com.exepex.stratum.resilience.RouteLimits;
import com.exepex.stratum.resilience.testsupport.ResilienceTestData;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

@DisplayName("ConcurrencyLimitFilter holds each request on a limited route within its limit")
class ConcurrencyLimitFilterTest {

  /** A path of the limited route. */
  private static final String REPORT_PATH = "/reports/1";

  /** The limit of the reports route: one request at once. */
  private final RouteLimits routeLimits =
      new RouteLimits(
          ResilienceTestData.incoming(Map.of(ResilienceTestData.REPORTS_ROUTE, 1)).build());

  /** The reports route's limit. */
  private final LimitedRoute reports = routeLimits.find(REPORT_PATH).orElseThrow();

  /** The failure the resolver was given, if it ran. */
  private final AtomicReference<@Nullable Exception> resolved = new AtomicReference<>();

  private final MockHttpServletResponse response = new MockHttpServletResponse();

  /** Returns the filter with a resolver that answers with the given model, or with none. */
  private ConcurrencyLimitFilter filter(List<HandlerExceptionResolver> resolvers) {
    var beans = new StaticListableBeanFactory();
    resolvers.forEach(resolver -> beans.addBean("handlerExceptionResolver", resolver));
    return new ConcurrencyLimitFilter(
        routeLimits,
        HealthProbePaths.onMainPort(new MockEnvironment(), List.of("/reports/health")),
        "2",
        beans.getBeanProvider(HandlerExceptionResolver.class));
  }

  /** Returns a resolver that records the failure and answers with the model. */
  private HandlerExceptionResolver resolverAnswering(@Nullable ModelAndView answer) {
    return (request, answeredResponse, handler, failure) -> recordAndAnswer(failure, answer);
  }

  /** Records the failure a resolver was given and returns its answer. */
  private @Nullable ModelAndView recordAndAnswer(Exception failure, @Nullable ModelAndView answer) {
    resolved.set(failure);
    return answer;
  }

  /** Returns a GET request to the path. */
  private MockHttpServletRequest get(String path) {
    return new MockHttpServletRequest("GET", path);
  }

  /** Runs the filter with a chain that does nothing. */
  private void run(ConcurrencyLimitFilter filter, MockHttpServletRequest request) throws Exception {
    filter.doFilter(request, response, new MockFilterChain());
  }

  @Nested
  @DisplayName("a request the route admits")
  class Admitted {

    @Test
    @DisplayName("holds a permit while the chain runs and gives it back when it returns")
    void holdsWhileChainRuns() throws Exception {
      var activeInChain = new AtomicReference<Integer>();
      FilterChain chain = (request, chainResponse) -> activeInChain.set(reports.active());

      filter(List.of()).doFilter(get(REPORT_PATH), response, chain);

      assertThat(activeInChain).hasValue(1);
      assertThat(reports.active()).isZero();
    }

    @Test
    @DisplayName("gives the permit back when the chain fails")
    void releasesOnFailure() throws Exception {
      var chain = mock(FilterChain.class);
      doThrow(new IllegalStateException("handler failed")).when(chain).doFilter(any(), any());

      assertThatExceptionOfType(IllegalStateException.class)
          .isThrownBy(() -> filter(List.of()).doFilter(get(REPORT_PATH), response, chain));
      assertThat(reports.active()).isZero();
    }

    @Test
    @DisplayName("an asynchronous request keeps its permit until the server completes it")
    void asyncKeepsPermitUntilComplete() throws Exception {
      var request = get(REPORT_PATH);
      request.setAsyncSupported(true);

      filter(List.of())
          .doFilter(request, response, (chainRequest, chainResponse) -> chainRequest.startAsync());

      assertThat(reports.active()).isEqualTo(1);
      request.getAsyncContext().complete();
      assertThat(reports.active()).isZero();
    }

    @Test
    @DisplayName("the route is found by the path within the application, without the context path")
    void pathWithinApplication() throws Exception {
      var request = get("/api" + REPORT_PATH);
      request.setContextPath("/api");
      reports.tryAdmit();

      run(filter(List.of()), request);

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("a request on a full route")
  class Refused {

    @Test
    @DisplayName(
        "gets Retry-After and goes to Spring MVC's resolvers as a refusal, with no handler")
    void goesToResolvers() throws Exception {
      reports.tryAdmit();

      run(filter(List.of(resolverAnswering(new ModelAndView()))), get(REPORT_PATH));

      assertThat(response.getHeaders("Retry-After")).containsExactly("2");
      assertThat(resolved.get()).isInstanceOf(RequestRefusedException.class);
      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
      assertThat(reports.refusals()).isEqualTo(1);
      assertThat(reports.active()).isEqualTo(1);
    }

    @Test
    @DisplayName("gets 503 when no resolver answers it")
    void noAnswerGets503() throws Exception {
      reports.tryAdmit();

      run(filter(List.of(resolverAnswering(null))), get(REPORT_PATH));

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
      assertThat(response.getHeaders("Retry-After")).containsExactly("2");
    }

    @Test
    @DisplayName("gets 503 when a resolver answers with a view, which a filter cannot show")
    void viewGets503() throws Exception {
      reports.tryAdmit();

      run(filter(List.of(resolverAnswering(new ModelAndView("error")))), get(REPORT_PATH));

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("gets 503 when the service has no Spring MVC resolvers")
    void noResolverGets503() throws Exception {
      reports.tryAdmit();

      run(filter(List.of()), get(REPORT_PATH));

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
    }
  }

  @Nested
  @DisplayName("a request that is never counted")
  class Uncounted {

    @Test
    @DisplayName("a path no route matches runs, even with every route full")
    void unmatchedPath() throws Exception {
      reports.tryAdmit();

      run(filter(List.of()), get("/orders/1"));

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    @DisplayName("an actuator path inside a limited route runs, even with the route full")
    void actuatorPath() throws Exception {
      reports.tryAdmit();

      run(filter(List.of()), get("/reports/health"));

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
      assertThat(reports.refusals()).isZero();
    }

    @Test
    @DisplayName("a CORS preflight runs, even with the route full")
    void corsPreflight() throws Exception {
      reports.tryAdmit();
      var preflight = new MockHttpServletRequest("OPTIONS", REPORT_PATH);
      preflight.addHeader("Origin", "https://shop.example");
      preflight.addHeader("Access-Control-Request-Method", "GET");

      run(filter(List.of()), preflight);

      assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
      assertThat(reports.refusals()).isZero();
    }
  }
}
