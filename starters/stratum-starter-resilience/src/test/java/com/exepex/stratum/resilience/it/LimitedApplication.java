package com.exepex.stratum.resilience.it;

import com.exepex.stratum.resilience.testsupport.HeldRequests;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.RouterFunctions;
import org.springframework.web.servlet.function.ServerResponse;

/**
 * A Spring MVC service with the resilience starter and the microservice bundle's single starters,
 * whose endpoints hold their work until a test lets it go, in every way Spring MVC answers.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import({AsyncTypesController.class, HeldController.class, ServiceErrorHandlers.class})
public class LimitedApplication {

  /** The header with which a request is denied before it reaches the limit, as a 401. */
  static final String DENY_HEADER = "X-Deny";

  /** Spring Security's filter order, at which the stand-in for its 401 runs. */
  private static final int SPRING_SECURITY_ORDER = -100;

  /**
   * Returns the work the endpoints hold.
   *
   * @return the held work
   */
  @Bean
  HeldRequests heldRequests() {
    return new HeldRequests();
  }

  /**
   * Answers 401 to a request with {@link #DENY_HEADER} at Spring Security's order, as Spring
   * Security does to a request without valid credentials.
   *
   * @return the filter's registration
   */
  @Bean
  FilterRegistrationBean<OncePerRequestFilter> denyingFilter() {
    var registration = new FilterRegistrationBean<OncePerRequestFilter>(new DenyingFilter());
    registration.setOrder(SPRING_SECURITY_ORDER);
    return registration;
  }

  /**
   * Holds a functional endpoint's work, as a {@code RouterFunction} route.
   *
   * @param held the held work
   * @return the route
   */
  @Bean
  RouterFunction<ServerResponse> functionalRoute(HeldRequests held) {
    return RouterFunctions.route()
        .GET("/fn/held", request -> ServerResponse.ok().body(held.hold()))
        .build();
  }

  /**
   * Returns a health indicator that needs other beans, as a service's check of its own store does,
   * so startup shows that reading actuator's paths for the filter closes no bean cycle.
   *
   * @param held the held work, standing in for the service's store
   * @param meterRegistry the service's meter registry
   * @return the indicator
   */
  @Bean
  HealthIndicator ordersStoreHealth(HeldRequests held, MeterRegistry meterRegistry) {
    return () -> Health.up().withDetail("meters", meterRegistry.getMeters().isEmpty()).build();
  }

  /**
   * Returns a custom actuator endpoint, as a service declares one.
   *
   * @return the endpoint
   */
  @Bean
  OrdersAdminEndpoint ordersAdminEndpoint() {
    return new OrdersAdminEndpoint();
  }

  /** A custom actuator endpoint, at {@code /actuator/orders-admin} once exposed. */
  @Endpoint(id = "orders-admin")
  static class OrdersAdminEndpoint {

    /**
     * Reads the endpoint.
     *
     * @return its answer
     */
    @ReadOperation
    Map<String, String> read() {
      return Map.of("orders", "open");
    }
  }

  /** Answers 401 to a request with the deny header, and passes on every other. */
  static class DenyingFilter extends OncePerRequestFilter {

    /**
     * Answers 401 or runs the rest of the chain.
     *
     * @param request the request
     * @param response the response
     * @param filterChain the rest of the chain
     * @throws jakarta.servlet.ServletException if the rest of the chain fails
     * @throws IOException if the answer cannot be written
     */
    @Override
    protected void doFilterInternal(
        HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws jakarta.servlet.ServletException, IOException {
      if (request.getHeader(DENY_HEADER) == null) {
        filterChain.doFilter(request, response);
      } else {
        response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
      }
    }
  }
}
