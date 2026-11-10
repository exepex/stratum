package com.exepex.stratum.restclient;

import com.exepex.stratum.httpclient.HttpClientErrorCode;
import java.io.IOException;
import java.io.Serial;

/**
 * The failure of a read that passes a client's {@code response-body-limit}. Its message starts with
 * {@code SX-HTTPC-0006} and names the client and the limit in bytes, never a byte of the body.
 */
final class ResponseBodyTooLarge extends IOException {

  /** The version of this exception's serialized form. */
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Makes the failure.
   *
   * @param client the client's name
   * @param limit the client's limit, in bytes
   */
  ResponseBodyTooLarge(String client, long limit) {
    super(
        HttpClientErrorCode.RESPONSE_TOO_LARGE.getCode()
            + ": the answer to client "
            + client
            + " is larger than its response-body-limit of "
            + limit
            + " bytes");
  }
}
