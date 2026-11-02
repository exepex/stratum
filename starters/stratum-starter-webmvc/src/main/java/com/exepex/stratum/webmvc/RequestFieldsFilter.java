package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.web.RequestFieldValues;
import com.exepex.stratum.web.RequestFieldsAccessor;
import com.exepex.stratum.web.RequestFieldsReader;
import com.exepex.stratum.web.WebConstants;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's fields in the logging context (SLF4J's MDC) for the whole request, so every
 * log line written while it runs carries them, and removes them when it ends. When browsers are
 * asked for client hints, it also adds the {@code Accept-CH} response header.
 */
@StratumInternal
@RequiredArgsConstructor
public class RequestFieldsFilter extends OncePerRequestFilter {

  /** Runs just inside the request IDs filter, before the service's own filters. */
  public static final int ORDER = Ordered.HIGHEST_PRECEDENCE + 4;

  /** Reads the fields. */
  private final RequestFieldsReader requestFieldsReader;

  /** Puts the fields in the logging context and removes them. */
  private final RequestFieldsAccessor requestFieldsAccessor;

  /**
   * Puts the fields in the logging context, runs the rest of the chain, then removes them.
   *
   * @param request the request
   * @param response the response
   * @param filterChain the rest of the chain
   * @throws ServletException if the rest of the chain fails
   * @throws IOException if the rest of the chain fails to read or write
   */
  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {
    var serverRequest = new ServletServerHttpRequest(request);
    var fields =
        requestFieldsReader.fieldsOf(
            serverRequest.getMethod(),
            serverRequest.getURI(),
            serverRequest.getHeaders(),
            socketAddressOf(request));
    requestFieldsReader
        .clientHintsToRequest()
        .ifPresent(
            clientHints ->
                response.setHeader(WebConstants.ACCEPT_CLIENT_HINTS_HEADER, clientHints));
    requestFieldsAccessor.setValue(new RequestFieldValues(fields, () -> null));
    try {
      filterChain.doFilter(request, response);
    } finally {
      requestFieldsAccessor.setValue();
    }
  }

  /**
   * Returns false, so the fields are put again when a {@code Callable} or {@code DeferredResult}
   * finishes and its response is written on a second, asynchronous dispatch.
   *
   * @return false
   */
  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  /** Returns the address the request came from, without looking up a host name. */
  private @Nullable InetAddress socketAddressOf(HttpServletRequest request) {
    var remoteAddress = request.getRemoteAddr();
    if (remoteAddress == null) {
      return null;
    }
    try {
      return InetAddress.ofLiteral(remoteAddress);
    } catch (IllegalArgumentException unknownAddress) {
      return null;
    }
  }
}
