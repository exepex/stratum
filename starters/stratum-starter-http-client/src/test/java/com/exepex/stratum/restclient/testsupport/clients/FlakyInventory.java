package com.exepex.stratum.restclient.testsupport.clients;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import java.io.InputStream;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.DeleteExchange;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PatchExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * An interface of the inventory client whose calls fail in the ways {@link ScriptedDownstream} can
 * script, for the tests of retries and the circuit breaker.
 */
@StratumClient(RestClientTestData.INVENTORY)
public interface FlakyInventory {

  /**
   * Gets an answer with the given status.
   *
   * @param status the status, such as 502
   * @return the answer, when it is a success
   */
  @GetExchange(ScriptedDownstream.ANY_STATUS)
  ResponseEntity<Void> get(@PathVariable int status);

  /**
   * Posts to a path that answers with the given status.
   *
   * @param status the status, such as 503
   * @return the answer, when it is a success
   */
  @PostExchange(ScriptedDownstream.ANY_STATUS)
  ResponseEntity<Void> post(@PathVariable int status);

  /**
   * Patches a path that answers with the given status.
   *
   * @param status the status, such as 503
   * @return the answer, when it is a success
   */
  @PatchExchange(ScriptedDownstream.ANY_STATUS)
  ResponseEntity<Void> patch(@PathVariable int status);

  /**
   * Deletes at a path that answers with the given status.
   *
   * @param status the status, such as 503
   * @return the answer, when it is a success
   */
  @DeleteExchange(ScriptedDownstream.ANY_STATUS)
  ResponseEntity<Void> delete(@PathVariable int status);

  /**
   * Gets a 503 with the given {@code Retry-After}.
   *
   * @param retryAfter the header's value, such as 1
   * @return never, as the call fails
   */
  @GetExchange(ScriptedDownstream.RETRY_AFTER + "{retryAfter}")
  ResponseEntity<Void> getRetryingAfter(@PathVariable String retryAfter);

  /**
   * Gets from a path that closes the connection without an answer.
   *
   * @return never, as the call fails
   */
  @GetExchange(ScriptedDownstream.DROPPED)
  String getDropped();

  /**
   * Posts to a path that closes the connection without an answer.
   *
   * @return never, as the call fails
   */
  @PostExchange(ScriptedDownstream.DROPPED)
  String postDropped();

  /**
   * Gets an answer once the downstream releases it.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.HELD)
  String getHeld();

  /**
   * Posts to a path that answers once the downstream releases it.
   *
   * @return the body
   */
  @PostExchange(ScriptedDownstream.HELD)
  String postHeld();

  /**
   * Gets an answer, released by the downstream, as a stream: a streamed download.
   *
   * @return the body as a stream, which the caller closes
   */
  @GetExchange(ScriptedDownstream.HELD)
  InputStream downloadHeld();

  /**
   * Puts a streamed body to a path that answers 503: a streamed upload.
   *
   * @param upload the body
   * @return never, as the call fails
   */
  @PostExchange(ScriptedDownstream.STATUS + "503")
  ResponseEntity<Void> upload(@RequestBody Resource upload);
}
