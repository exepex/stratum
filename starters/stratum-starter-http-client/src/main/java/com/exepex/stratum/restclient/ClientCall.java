package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.Builder;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpMethod;

/**
 * One call of a Stratum client, as its retries, breaker, call log and meters see it.
 *
 * @param client the client's name
 * @param method the call's HTTP method
 * @param uriTemplate the call's URI template, such as {@code /orders/{id}}, or {@code null} when it
 *     was given a whole URI
 * @param streamedBody whether the request's body is streamed, which is never sent twice
 * @param streamedDownload whether the answer is streamed, which keeps its full answer timeout
 * @param notFoundIsEmpty whether a 404 answers the call with nothing, as for a method that returns
 *     {@code Optional}
 */
@StratumInternal
@Builder
public record ClientCall(
    String client,
    HttpMethod method,
    @Nullable String uriTemplate,
    boolean streamedBody,
    boolean streamedDownload,
    boolean notFoundIsEmpty) {

  /** Builds the call; Lombok writes its methods, the label keeps it internal. */
  @StratumInternal
  public static class ClientCallBuilder {}
}
