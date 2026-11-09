package com.exepex.stratum.restclient.testsupport.payments;

import com.exepex.stratum.restclient.StratumClientCustomizer;
import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClient;

/**
 * A team's set-up of its clients: a 404 becomes {@link PaymentMissingException}, and the {@code
 * Authorization} header of each request is noted, so a test sees whether a credential was sent.
 */
public class PaymentsSetup implements StratumClientCustomizer {

  /** The {@code Authorization} header of the last request, or {@code null} when it had none. */
  private final AtomicReference<@Nullable String> authorization = new AtomicReference<>();

  @Override
  public void customize(String client, RestClient.Builder builder) {
    builder
        .defaultStatusHandler(this::isNotFound, (request, response) -> paymentMissing())
        .requestInitializer(this::noteAuthorization);
  }

  /**
   * Returns the {@code Authorization} header of the last request.
   *
   * @return the header, or {@code null} when the request had none
   */
  public @Nullable String lastAuthorization() {
    return authorization.get();
  }

  /** Returns whether a status is 404. */
  private boolean isNotFound(HttpStatusCode status) {
    return status.value() == HttpStatus.NOT_FOUND.value();
  }

  /** Throws the team's exception for a 404. */
  private void paymentMissing() {
    throw new PaymentMissingException();
  }

  /** Notes the request's {@code Authorization} header. */
  private void noteAuthorization(HttpRequest request) {
    authorization.set(request.getHeaders().getFirst(HttpHeaders.AUTHORIZATION));
  }
}
