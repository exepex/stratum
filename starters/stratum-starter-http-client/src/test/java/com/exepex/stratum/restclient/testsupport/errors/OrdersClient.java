package com.exepex.stratum.restclient.testsupport.errors;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.net.URI;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.service.annotation.GetExchange;

/** The orders service's client, with one method per answer of {@link ErrorDownstream}. */
@StratumClient(RestClientTestData.ORDERS)
public interface OrdersClient {

  /**
   * Reads an order the service does not have.
   *
   * @return never, as the answer is a 404 problem detail
   */
  @GetExchange(ErrorDownstream.PROBLEM)
  String missing();

  /**
   * Reads an order whose 404 says it is a problem detail but is not JSON.
   *
   * @return never, as the answer is a 404
   */
  @GetExchange(ErrorDownstream.BROKEN_PROBLEM)
  String missingUnreadable();

  /**
   * Reads one order of a customer, which fails with a 500 whose body holds a secret.
   *
   * @param id the order's ID, a path value
   * @param customer the customer, a query value
   * @return never, as the answer is a 500
   */
  @GetExchange(ErrorDownstream.FAILED + "/{id}")
  String order(@PathVariable String id, @RequestParam String customer);

  /**
   * Reads an order at a whole URI the caller built, which fails with a 500.
   *
   * @param order the order's URI, whose path and query hold values
   * @return never, as the answer is a 500
   */
  @GetExchange
  String orderAt(URI order);

  /**
   * Reads an error answer whose body is far over 64 KB.
   *
   * @return never, as the answer is a 500
   */
  @GetExchange(ErrorDownstream.LARGE_ERROR)
  String largeError();

  /**
   * Reads an error answer whose gzip body is small but far over 64 KB once undone.
   *
   * @return never, as the answer is a 500
   */
  @GetExchange(ErrorDownstream.LARGE_GZIP_ERROR)
  String largeGzipError();

  /**
   * Reads an answer that succeeds with a body far over 64 KB.
   *
   * @return the body
   */
  @GetExchange(ErrorDownstream.LARGE_ANSWER)
  String largeAnswer();
}
