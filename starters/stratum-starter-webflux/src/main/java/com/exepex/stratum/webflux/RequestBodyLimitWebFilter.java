package com.exepex.stratum.webflux;

import com.exepex.stratum.web.RequestBodyCounter;
import com.exepex.stratum.web.RequestBodyLimit;
import com.exepex.stratum.web.RequestBodyLimits;
import com.exepex.stratum.web.RequestBodyTooLargeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpStatus;
import org.springframework.http.codec.multipart.Part;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.util.MultiValueMap;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SynchronousSink;

/**
 * Counts each reactive request's body as it arrives, and fails the request with {@code 413} once
 * the body is larger than its limit, before it is held in memory. Spring reads form fields and
 * multipart parts from the original body, so those are checked by their declared length only. Just
 * before the handler runs, it gives the request the limit of its handler's {@link RequestBodyLimit}
 * and checks the declared {@code Content-Length}. A multipart or form body that Spring refuses over
 * one of its own limits, such as a part over its disk limit, is answered with {@code 413} too,
 * where Spring Boot would answer {@code 500}. Any other buffer limit, such as on a downstream
 * response, keeps its {@code 500}.
 */
@Slf4j
@RequiredArgsConstructor
public class RequestBodyLimitWebFilter implements WebFilter, HandlerStartListener, Ordered {

  /** Runs just inside the response timeout filter, before anything reads the body. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 7;

  /** The exchange attribute that holds the request's counter, for its handler's own limit. */
  private static final String COUNTER_ATTRIBUTE =
      RequestBodyLimitWebFilter.class.getName() + ".counter";

  /** Decides each request's limit. */
  private final RequestBodyLimits requestBodyLimits;

  /**
   * Runs the rest of the chain with the request's body counted, and answers a multipart or form
   * body over one of Spring's own limits with {@code 413}.
   *
   * @param exchange the request and its response
   * @param chain the rest of the chain
   * @return completes when the request has been answered
   */
  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    var request = exchange.getRequest();
    var counter = requestBodyLimits.start(request.getPath().pathWithinApplication().value());
    exchange.getAttributes().put(COUNTER_ATTRIBUTE, counter);
    var countedRequest =
        new ServerHttpRequestDecorator(request) {
          @Override
          public Flux<DataBuffer> getBody() {
            return super.getBody()
                .handle(
                    (DataBuffer buffer, SynchronousSink<DataBuffer> sink) ->
                        countOrFail(counter, buffer, sink));
          }
        };
    var countedExchange =
        new ServerWebExchangeDecorator(exchange.mutate().request(countedRequest).build()) {
          @Override
          public Mono<MultiValueMap<String, Part>> getMultipartData() {
            return super.getMultipartData()
                .onErrorMap(
                    DataBufferLimitException.class, RequestBodyLimitWebFilter.this::tooLarge);
          }

          @Override
          public Mono<MultiValueMap<String, String>> getFormData() {
            return super.getFormData()
                .onErrorMap(
                    DataBufferLimitException.class, RequestBodyLimitWebFilter.this::tooLarge);
          }
        };
    return chain.filter(countedExchange);
  }

  /**
   * Logs that Spring refused a multipart or form body over one of its own limits, and returns the
   * {@code 413} the request is answered with.
   */
  private ResponseStatusException tooLarge(DataBufferLimitException springLimitPassed) {
    log.warn("The request body passed one of Spring's multipart or form limits");
    return new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE, null, springLimitPassed);
  }

  /**
   * Gives the request its handler's limit, if the handler has one, and checks the declared length.
   *
   * @param exchange the request and its response
   * @param handler the chosen handler
   * @throws RequestBodyTooLargeException if the declared length is larger than the limit
   */
  @Override
  public void onHandlerStart(ServerWebExchange exchange, Object handler) {
    if (exchange.getAttribute(COUNTER_ATTRIBUTE) instanceof RequestBodyCounter counter) {
      requestBodyLimits.applyHandlerLimit(counter, handler);
      counter.checkDeclaredLength(exchange.getRequest().getHeaders().getContentLength());
    }
  }

  /**
   * Returns the order of this filter.
   *
   * @return {@link #ORDER}
   */
  @Override
  public int getOrder() {
    return ORDER;
  }

  /**
   * Hands on a piece of the body once counted, or releases it and fails the body when it makes the
   * body too large.
   */
  private void countOrFail(
      RequestBodyCounter counter, DataBuffer buffer, SynchronousSink<DataBuffer> sink) {
    try {
      counter.count(buffer.readableByteCount());
      sink.next(buffer);
    } catch (RequestBodyTooLargeException tooLarge) {
      DataBufferUtils.release(buffer);
      sink.error(tooLarge);
    }
  }
}
