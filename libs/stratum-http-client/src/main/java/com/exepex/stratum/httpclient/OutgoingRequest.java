package com.exepex.stratum.httpclient;

import java.net.URI;
import java.util.Optional;

/** One attempt of a client's call, as an interceptor sees it before it is sent. */
public interface OutgoingRequest {

  /**
   * Returns the name of the client making the call, as in {@code stratum.http-client.clients}.
   *
   * @return the client's name, such as {@code inventory}
   */
  String client();

  /**
   * Returns the call's HTTP method.
   *
   * @return the method, such as {@code GET}
   */
  String method();

  /**
   * Returns where the call goes.
   *
   * @return the full address, with its query
   */
  URI uri();

  /**
   * Returns the first value of a header the call carries.
   *
   * @param name the header's name, compared without case
   * @return the value, or empty when the call has no such header
   */
  Optional<String> header(String name);

  /**
   * Sets a header on the call, replacing any value it had.
   *
   * @param name the header's name
   * @param value the value
   */
  void setHeader(String name, String value);

  /**
   * Returns the body the call sends, read once and then sent as read. It is empty when the call has
   * no body, when the body is streamed, such as a file or an {@code InputStream}, which is never
   * read here, and on calls through {@code stratumHttpClients.restClient(name)}. A body larger than
   * the client's {@code interceptor-body-limit} (1 MB by default) is not read: the call fails with
   * {@code SX-HTTPC-0005} and is not sent.
   *
   * @return a copy of the body, or empty
   */
  Optional<byte[]> body();
}
