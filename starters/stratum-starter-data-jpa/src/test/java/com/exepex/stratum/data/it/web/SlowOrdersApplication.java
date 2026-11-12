package com.exepex.stratum.data.it.web;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** A service with one endpoint whose query runs past every limit the tests set. */
@SpringBootApplication
public class SlowOrdersApplication {

  /** The path of the endpoint whose query runs too long. */
  public static final String SLOW_ORDERS = "/orders/slow";

  /** Answers with the result of a query that sleeps for 5 seconds. */
  @RestController
  @RequiredArgsConstructor
  static class SlowOrdersController {

    /** Runs the queries. */
    private final JdbcTemplate jdbcTemplate;

    /**
     * Runs a query that sleeps for 5 seconds, which Stratum cancels first.
     *
     * @return never returns normally
     */
    @GetMapping(SLOW_ORDERS)
    String slowOrders() {
      return String.valueOf(jdbcTemplate.queryForObject("SELECT pg_sleep(5)::text", String.class));
    }
  }
}
