package com.exepex.stratum.restclient.testsupport.calls;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.QueuedDownstream;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;

/** The clients, settings and addresses the call log and meter tests share. */
@UtilityClass
public class CallsTestData {

  /** The settings of the clients of the call tests, for {@code @TestPropertySource}. */
  public static final String SETTINGS = "classpath:calls.properties";

  /** The client that logs its failed calls, as by default. */
  public static final String ORDERS = "orders";

  /** The client with {@code call-log: all}. */
  public static final String AUDITED = "audited";

  /** The client with {@code call-log: none}, on the shared pool. */
  public static final String SILENT = "silent";

  /** The client whose breaker opens after two failed attempts. */
  public static final String FRAGILE = "fragile";

  /** The client with one connection and a short wait for it. */
  public static final String HELD = "held";

  /** The URI template of the orders client's calls. */
  public static final String ORDER_TEMPLATE = "/orders/{id}";

  /** An order's ID, a path value that no log line without redaction or tag may hold. */
  public static final String ORDER_ID = "8812";

  /** An order's status, a query value. */
  public static final String STATUS = "OPEN";

  /** A made-up API key, which the redaction masks by its parameter's name. */
  public static final String API_KEY = "not-a-real-key-7f3e";

  /** A made-up JWT, which the redaction masks by its shape. */
  public static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJmYWtlIn0.c2lnbmF0dXJlLWZha2U";

  /** A made-up email address, which the redaction masks by its shape. */
  public static final String EMAIL = "jane@example.com";

  /** A test card number, which the redaction masks by its checksum. */
  public static final String CARD = "4111111111111111";

  /** A test IBAN, which the redaction masks by its checksum. */
  public static final String IBAN = "DE89370400440532013000";

  /** The retries of every client but fragile and held. */
  public static final int RETRIES = 3;

  /** An answer every client retries. */
  public static final int UNAVAILABLE = HttpStatus.SERVICE_UNAVAILABLE.value();

  /** An answer a method returning {@code Optional} reads as empty. */
  public static final int NOT_FOUND = HttpStatus.NOT_FOUND.value();

  /** An answer no client retries. */
  public static final int BAD_REQUEST = HttpStatus.BAD_REQUEST.value();

  /** The length of a hostile path or query, 100 KB. */
  public static final int HOSTILE_LENGTH = 100 * 1024;

  /**
   * Answers every attempt of the next call of a client that retries {@link #RETRIES} times with
   * 503, and of a client whose breaker opens sooner until it does.
   *
   * @param downstream the other service
   */
  public static void failEveryAttempt(QueuedDownstream downstream) {
    downstream.answerNext(IntStream.rangeClosed(0, RETRIES).map(attempt -> UNAVAILABLE).toArray());
  }

  /**
   * Points every client of the call tests at its address.
   *
   * @param registry the test's properties
   * @param queued the address of the clients that answer from a queue of statuses
   * @param held the address of the held client
   * @param fragile the address of the fragile client
   */
  public static void addresses(
      DynamicPropertyRegistry registry,
      Supplier<Object> queued,
      Supplier<Object> held,
      Supplier<Object> fragile) {
    registry.add(urlOf(ORDERS), queued);
    registry.add(urlOf(AUDITED), queued);
    registry.add(urlOf(SILENT), queued);
    registry.add(urlOf(FRAGILE), fragile);
    registry.add(urlOf(HELD), held);
  }

  /** Returns the key of a client's address. */
  private static String urlOf(String client) {
    return HttpClientConstants.CLIENTS + "." + client + ".url";
  }
}
