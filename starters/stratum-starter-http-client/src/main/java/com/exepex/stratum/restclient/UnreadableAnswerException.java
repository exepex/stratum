package com.exepex.stratum.restclient;

import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.FailureWithErrorCode;
import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.io.Serial;
import lombok.Getter;
import org.springframework.web.client.RestClientException;

/**
 * Another service answered a call of a Stratum client, but its answer cannot be read, such as JSON
 * that does not match the method's type or a content type no converter reads. Its code is {@code
 * SX-HTTPC-0008}: the call reached the other service, so its work may be done, and a write must not
 * be repeated blindly. It is a Spring {@code RestClientException}, so a {@code catch} of that still
 * catches it; uncaught, its caller gets {@code 500} and never the answer's body.
 *
 * <p>Its message names the client and the endpoint's template, never the body or a value of the
 * path or query. Spring's exception, which says why the body could not be read, is its cause.
 */
public final class UnreadableAnswerException extends RestClientException
    implements FailureWithErrorCode {

  @Serial private static final long serialVersionUID = 1L;

  /** The name of the client that made the call, such as {@code inventory}. */
  @Getter private final String client;

  /**
   * The call's method and URI template, such as {@code GET /stock/{sku}}, or its method alone for a
   * call given a whole URI.
   */
  @Getter private final String endpoint;

  /**
   * Makes the exception from Spring's exception for the answer that cannot be read.
   *
   * @param client the client's name
   * @param endpoint the call's method and URI template
   * @param failure Spring's exception, kept as the cause
   */
  public UnreadableAnswerException(String client, String endpoint, RestClientException failure) {
    super(client + " could not read the answer to " + endpoint, failure);
    this.client = client;
    this.endpoint = endpoint;
  }

  /**
   * Returns the code of the failure, {@code SX-HTTPC-0008}.
   *
   * @return the code
   */
  @Override
  public ErrorCode getErrorCode() {
    return HttpClientErrorCode.ANSWER_UNREADABLE;
  }

  /**
   * Returns no detail, so that the caller's error body never names the other service.
   *
   * @return an empty detail
   */
  @Override
  public String getErrorDetail() {
    return "";
  }
}
