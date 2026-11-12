package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.httpclient.HttpClientConstants;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.classic.ExecChain;
import org.apache.hc.client5.http.classic.ExecChainHandler;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpException;
import org.apache.hc.core5.http.HttpStatus;

/**
 * Lets at most a client's {@code response-body-limit} of a successful answer's body, status below
 * 400, be read: a larger one fails with {@code SX-HTTPC-0006}, and one the call does not read is
 * dropped once it passes the limit. A streamed download, a method returning an {@code InputStream},
 * is not limited, except through a pool that does not stream answers, as for a token request. It
 * must be the first handler of Apache's chain, outside the one that undoes gzip, so that it counts
 * the bytes as read and not as sent.
 */
@StratumInternal
@RequiredArgsConstructor
public class ResponseBodyLimit implements ExecChainHandler {

  /** The handler's name in Apache's chain. */
  public static final String NAME = "stratum-response-body-limit";

  /** The client's name, for the failure's message. */
  private final String client;

  /** The client's {@code response-body-limit}, in bytes, or -1 for none. */
  private final long limit;

  /** Whether the pool lets a streamed download read its answer without the limit. */
  private final boolean streamsAnswers;

  /**
   * Sends the request on, and limits the body of a successful answer.
   *
   * @param request the request
   * @param scope the request's scope
   * @param chain the rest of Apache's chain
   * @return the answer, with at most the limit of a successful body to read
   * @throws IOException when the request fails
   * @throws HttpException when the answer breaks the protocol
   */
  @Override
  public ClassicHttpResponse execute(
      ClassicHttpRequest request, ExecChain.Scope scope, ExecChain chain)
      throws IOException, HttpException {
    var response = chain.proceed(request, scope);
    if (response.getCode() < HttpStatus.SC_BAD_REQUEST
        && response.getEntity() != null
        && limit != HttpClientConstants.NO_RESPONSE_BODY_LIMIT.toBytes()
        && !(streamsAnswers && AttemptTimeLeft.keepsAnswerTimeout())) {
      response.setEntity(LimitedAnswerEntity.failingPast(response.getEntity(), limit, client));
    }
    return response;
  }
}
