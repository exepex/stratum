package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.IOException;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Lets at most 64 KB of an error answer's body, status 400 or more, be read, so that a huge error
 * body is never held in memory; an answer that succeeded is untouched. It must be the first handler
 * of Apache's chain, outside the one that undoes gzip, so that it counts the bytes as read and not
 * as sent. Closing the body drains the rest, so that the connection can be used again, but no
 * further than 64 KB in all: a body that does not end within 64 KB is left unread and its
 * connection dropped.
 */
@StratumInternal
public class ErrorBodyLimit implements ExecChainHandler {

  /** The handler's name in Apache's chain. */
  public static final String NAME = "stratum-error-body-limit";

  /**
   * Sends the request on, and limits the body of an error answer.
   *
   * @param request the request
   * @param scope the request's scope
   * @param chain the rest of Apache's chain
   * @return the answer, with at most 64 KB of an error body to read
   * @throws IOException when the request fails
   * @throws HttpException when the answer breaks the protocol
   */
  @Override
  public ClassicHttpResponse execute(
      ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
      throws IOException, HttpException {
    var response = chain.proceed(request, scope);
    if (response.getCode() >= HttpStatus.SC_BAD_REQUEST && response.getEntity() != null) {
      response.setEntity(
          LimitedAnswerEntity.endingAt(response.getEntity(), HttpClientConstants.ERROR_BODY_LIMIT));
    }
    return response;
  }
}
