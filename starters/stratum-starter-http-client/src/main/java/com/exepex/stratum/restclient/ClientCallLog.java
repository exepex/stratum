package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.logging.LogRedaction;
import com.exepex.stratum.httpclient.CallLog;
import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.httpclient.HttpClientProperties;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.slf4j.spi.LoggingEventBuilder;

/**
 * Writes the one log line of a client's call, as its {@code call-log} says: a WARN per failed call,
 * and with {@code all} an INFO per successful one; never a line for a call the open breaker
 * refused. The line holds the client, method, URI template, status, outcome, attempts and duration,
 * never a header, body or exception message. While the logging starter's redaction runs, the facts
 * are key-value fields and the called {@code url} is one of them; without it, they are written in
 * the message, with the template cut at its first {@code ?} and at 256 characters, and no {@code
 * url}. Each client writes through its own logger, {@code stratum.http-client.<client>}.
 */
@StratumInternal
@RequiredArgsConstructor
public class ClientCallLog {

  /** The message of a failed call's line. */
  private static final String FAILED = "HTTP client call failed";

  /** The message of a successful call's line. */
  private static final String SUCCEEDED = "HTTP client call";

  /** The template written for a call given a whole URI, Spring's own word for it. */
  private static final String NO_TEMPLATE = "none";

  /** The settings of every client. */
  private final HttpClientProperties properties;

  /**
   * The marker that the logging starter's redaction runs over every line, which allows the {@code
   * url}, or {@code null} when it does not run.
   */
  private final @Nullable LogRedaction redaction;

  /** The logger and {@code call-log} of each client, made on its first call. */
  private final Map<String, ClientLogger> loggers = new ConcurrentHashMap<>();

  /**
   * Writes the line of a call that ended, if its client's {@code call-log} asks for one.
   *
   * @param outcome how the call ended
   */
  public void write(ClientCallOutcome outcome) {
    var client = loggerOf(outcome.call().client());
    levelOf(client.callLog(), outcome.outcome())
        .filter(client.logger()::isEnabledForLevel)
        .ifPresent(level -> writeLine(client.logger().atLevel(level), outcome));
  }

  /** Writes the line in the form the redaction allows. */
  private void writeLine(LoggingEventBuilder line, ClientCallOutcome outcome) {
    if (redaction != null) {
      withFields(line, outcome).log();
    } else {
      line.log(inMessage(outcome));
    }
  }

  /** Returns the level of a call's line, or empty when it writes none. */
  private Optional<Level> levelOf(CallLog callLog, CallOutcome outcome) {
    if (callLog == CallLog.NONE || outcome == CallOutcome.REFUSED) {
      return Optional.empty();
    }
    if (outcome == CallOutcome.SUCCESS) {
      return callLog == CallLog.ALL ? Optional.of(Level.INFO) : Optional.empty();
    }
    return Optional.of(Level.WARN);
  }

  /** Returns the line with the facts as key-value fields, which the redaction masks one by one. */
  private LoggingEventBuilder withFields(LoggingEventBuilder line, ClientCallOutcome outcome) {
    var call = outcome.call();
    var fields =
        line.setMessage(messageOf(outcome))
            .addKeyValue("client", call.client())
            .addKeyValue("method", call.method().name())
            .addKeyValue("uri", Objects.requireNonNullElse(call.uriTemplate(), NO_TEMPLATE));
    var url = outcome.url();
    if (url != null) {
      fields = fields.addKeyValue("url", url.toString());
    }
    var status = outcome.status();
    if (status != null) {
      fields = fields.addKeyValue("status", status);
    }
    return fields
        .addKeyValue("outcome", outcome.outcome().name())
        .addKeyValue("attempts", outcome.attempts())
        .addKeyValue("durationMs", outcome.duration().toMillis());
  }

  /** Returns the message with the facts in it, for a log format that prints only the message. */
  private String inMessage(ClientCallOutcome outcome) {
    var call = outcome.call();
    var status = Optional.ofNullable(outcome.status()).map(value -> " status=" + value).orElse("");
    return messageOf(outcome)
        + ": client="
        + call.client()
        + " method="
        + call.method().name()
        + " uri="
        + shortTemplate(call.uriTemplate())
        + status
        + " outcome="
        + outcome.outcome().name()
        + " attempts="
        + outcome.attempts()
        + " durationMs="
        + outcome.duration().toMillis();
  }

  /** Returns a call's line message, by whether it failed. */
  private String messageOf(ClientCallOutcome outcome) {
    return outcome.outcome() == CallOutcome.SUCCESS ? SUCCEEDED : FAILED;
  }

  /**
   * Returns a template up to its first {@code ?} and at most 256 characters, as a team's own call
   * may join values into it, and with no line break, which Spring encodes anyway.
   */
  private String shortTemplate(@Nullable String template) {
    if (template == null) {
      return NO_TEMPLATE;
    }
    var query = template.indexOf('?');
    var path = query < 0 ? template : template.substring(0, query);
    var cut =
        path.length() > HttpClientConstants.MAX_LOGGED_URI_LENGTH
            ? path.substring(0, HttpClientConstants.MAX_LOGGED_URI_LENGTH)
            : path;
    return cut.replace('\r', ' ').replace('\n', ' ');
  }

  /** Returns a client's logger and {@code call-log}, made on first use. */
  private ClientLogger loggerOf(String client) {
    return loggers.computeIfAbsent(client, this::newLogger);
  }

  /** Makes a client's logger and reads its {@code call-log}, merged with the defaults. */
  private ClientLogger newLogger(String client) {
    return new ClientLogger(
        LoggerFactory.getLogger(HttpClientConstants.CALL_LOGGER_PREFIX + client),
        Objects.requireNonNull(properties.clientSettings(client).callLog()));
  }

  /**
   * The logger of one client and its {@code call-log}.
   *
   * @param logger the client's logger
   * @param callLog which of its calls write a line
   */
  private record ClientLogger(Logger logger, CallLog callLog) {}
}
