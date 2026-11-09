package com.exepex.stratum.restclient.testsupport.payments;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.HttpExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * A client of the payments service, set to mock: true in the mock tests. The file {@code
 * stratum-mocks/payments/createPayment.json} answers {@link #createPayment}, and {@code
 * lastPayment.json} answers {@link #lastPayment}; {@link #payment} has no file.
 */
@StratumClient(RestClientTestData.PAYMENTS)
public interface PaymentClient {

  /**
   * Starts a payment.
   *
   * @param payment the payment
   * @return the payment as the service took it
   */
  @PostExchange("/payments")
  Payment createPayment(@RequestBody Payment payment);

  /**
   * Reads one payment.
   *
   * @param id the payment's id
   * @return the payment
   */
  @GetExchange("/payments/{id}")
  Payment payment(@PathVariable String id);

  /**
   * Reads the last payment as loose fields, which the caller may change.
   *
   * @return the fields
   */
  @GetExchange("/payments/last")
  Map<String, Object> lastPayment();

  /**
   * Cancels a payment; reads no body.
   *
   * @param id the payment's id
   */
  @DeleteExchange("/payments/{id}")
  void cancel(@PathVariable String id);

  /**
   * Reads the headers of a payment only.
   *
   * @param id the payment's id
   * @return the headers
   */
  @HttpExchange(method = "HEAD", url = "/payments/{id}")
  HttpHeaders paymentHeaders(@PathVariable String id);
}
