package com.exepex.stratum.webflux.it;

import com.exepex.stratum.restclient.DownstreamException;
import com.exepex.stratum.restclient.UnreadableAnswerException;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import reactor.core.publisher.Mono;

/** A reactive service whose endpoints let a failed call of a Stratum client go uncaught. */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(DownstreamFailuresApplication.InventoryController.class)
public class DownstreamFailuresApplication {

  /** A value in the other service's answer that the caller must never see. */
  static final String SECRET = "not-a-real-secret-77c1";

  /** The path whose call the other service refused for now, with 503. */
  static final String REFUSED_PATH = "/stock/refused";

  /** The path whose call the other service answered with 404. */
  static final String MISSING_PATH = "/stock/missing";

  /** The path whose call got an answer that cannot be read. */
  static final String UNREADABLE_PATH = "/stock/unreadable";

  /** The client's name, which the caller must never see. */
  static final String CLIENT = "inventory";

  /** A controller whose calls of the inventory service failed. */
  @RestController
  static class InventoryController {

    /**
     * Fails as a call the inventory service refused with 503 after the retries.
     *
     * @return the failure
     */
    @GetMapping(REFUSED_PATH)
    Mono<String> refused() {
      return Mono.error(downstream(HttpStatus.SERVICE_UNAVAILABLE));
    }

    /**
     * Fails as a call the inventory service answered with 404.
     *
     * @return the failure
     */
    @GetMapping(MISSING_PATH)
    Mono<String> missing() {
      return Mono.error(downstream(HttpStatus.NOT_FOUND));
    }

    /**
     * Fails as a call whose answer cannot be read.
     *
     * @return the failure
     */
    @GetMapping(UNREADABLE_PATH)
    Mono<String> unreadable() {
      return Mono.error(
          new UnreadableAnswerException(
              CLIENT, "GET /stock/{sku}", new RestClientException(SECRET)));
    }

    /** Returns the failure of a call the inventory service answered with the status. */
    private DownstreamException downstream(HttpStatus status) {
      var headers = new HttpHeaders();
      headers.setContentType(MediaType.TEXT_PLAIN);
      return new DownstreamException(
          CLIENT,
          "GET /stock/{sku}",
          new RestClientResponseException(
              status.getReasonPhrase(),
              status,
              status.getReasonPhrase(),
              headers,
              SECRET.getBytes(StandardCharsets.UTF_8),
              StandardCharsets.UTF_8));
    }
  }
}
