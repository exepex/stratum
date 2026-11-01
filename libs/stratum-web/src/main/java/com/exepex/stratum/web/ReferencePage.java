package com.exepex.stratum.web;

import com.exepex.stratum.core.error.LoggingContextConstants;
import java.util.Map;
import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;
import org.springframework.web.util.HtmlUtils;

/**
 * The HTML error page a browser gets when the service has no error template of its own: the status
 * and a reference, the trace ID, that the user can quote to support. Like Spring Boot's whitelabel
 * page, which it replaces on both web stacks, it shows no stack trace or exception message.
 */
@UtilityClass
public class ReferencePage {

  /** The page's content type. */
  public static final String CONTENT_TYPE = MediaType.TEXT_HTML_VALUE + ";charset=UTF-8";

  /** The member of the error model that holds the status's reason phrase. */
  private static final String TITLE = "title";

  /** The member of Spring Boot's own error model that holds the status's reason phrase. */
  private static final String ERROR = "error";

  /** The member of the error model that holds the status. */
  private static final String STATUS = "status";

  /** The page's usual length in characters, so it is built without copying. */
  private static final int PAGE_LENGTH = 512;

  /**
   * Returns the page, with every value HTML-escaped.
   *
   * @param errorModel the error body's members: {@code status}, {@code title} and, when known,
   *     {@code traceId}; or Spring Boot's, whose reason phrase is {@code error}
   * @return the page
   */
  public static String of(Map<String, ?> errorModel) {
    var reasonPhrase =
        errorModel.containsKey(TITLE) ? errorModel.get(TITLE) : errorModel.get(ERROR);
    var title = HtmlUtils.htmlEscape(String.valueOf(reasonPhrase));
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
    return page.append("</body></html>").toString();
  }
}
