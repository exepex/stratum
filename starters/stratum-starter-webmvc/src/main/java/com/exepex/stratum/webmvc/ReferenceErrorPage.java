package com.exepex.stratum.webmvc;

import com.exepex.stratum.core.error.LoggingContextConstants;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.View;
import org.springframework.web.util.HtmlUtils;

/**
 * The HTML error page a browser gets when the service has no error template of its own: the status
 * and a reference, the trace ID, that the user can quote to support. It replaces Spring Boot's
 * whitelabel page and, like it, shows no stack trace or exception message.
 */
public class ReferenceErrorPage implements View {

  /** The member of the error model that holds the status's reason phrase. */
  private static final String TITLE = "title";

  /** The member of the error model that holds the status. */
  private static final String STATUS = "status";

  /** The page's usual length in characters, so it is built without copying. */
  private static final int PAGE_LENGTH = 512;

  /**
   * Returns the page's content type.
   *
   * @return {@code text/html;charset=UTF-8}
   */
  @Override
  public String getContentType() {
    return MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";
  }

  /**
   * Writes the page, with every value HTML-escaped.
   *
   * @param model the error model: {@code status}, {@code title} and, when known, {@code traceId}
   * @param request the request
   * @param response the response
   * @throws IOException if the page cannot be written
   */
  @Override
  public void render(
      @Nullable Map<String, ?> model, HttpServletRequest request, HttpServletResponse response)
      throws IOException {
    if (response.isCommitted()) {
      return;
    }
    var errorModel = model == null ? Map.<String, Object>of() : model;
    var title = HtmlUtils.htmlEscape(String.valueOf(errorModel.get(TITLE)));
    var page = new StringBuilder(PAGE_LENGTH);
    page.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>")
        .append(title)
        .append("</title></head><body><h1>")
        .append(title)
        .append("</h1><p>The request could not be completed (status ")
        .append(HtmlUtils.htmlEscape(String.valueOf(errorModel.get(STATUS))))
        .append(").</p>");
    var traceId = errorModel.get(LoggingContextConstants.TRACE_ID);
    if (traceId != null) {
      page.append("<p>Reference: <code>")
          .append(HtmlUtils.htmlEscape(traceId.toString()))
          .append("</code>. Quote it to support so they can find what went wrong.</p>");
    }
    page.append("</body></html>");
    response.setContentType(getContentType());
    response.getWriter().write(page.toString());
  }
}
