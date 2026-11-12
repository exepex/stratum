package com.exepex.stratum.restclient.testsupport.headers;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.EchoDownstream;
import com.exepex.stratum.restclient.testsupport.ScriptedDownstream;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.io.Resource;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/**
 * A client that names the signing interceptor, and the audit interceptor, which is global too, so
 * that it must still run once.
 */
@StratumClient(
    value = HeadersTestData.SIGNED,
    interceptors = {SigningInterceptor.class, AuditInterceptor.class})
public interface SignedClient {

  /**
   * Calls the echo path.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echo();

  /**
   * Calls the echo path, where a 404 reads as empty.
   *
   * @return the body, or empty after a 404
   */
  @GetExchange(EchoDownstream.ECHO)
  Optional<String> find();

  /**
   * Calls the echo path with the tenant header set by the method.
   *
   * @param tenant the tenant
   * @return the body
   */
  @GetExchange(EchoDownstream.ECHO)
  String echoFor(@RequestHeader(HeadersTestData.TENANT) String tenant);

  /**
   * Sends an order as JSON.
   *
   * @param order the order
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO)
  String send(@RequestBody Map<String, String> order);

  /**
   * Sends a text.
   *
   * @param text the text
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO)
  String sendText(@RequestBody String text);

  /**
   * Uploads a file, which streams.
   *
   * @param file the file
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO)
  String upload(@RequestBody Resource file);

  /**
   * Uploads a file into a folder, the body after a path variable; the file streams.
   *
   * @param folder the folder
   * @param file the file
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO + "/{folder}")
  String uploadTo(@PathVariable String folder, @RequestBody Resource file);

  /**
   * Uploads a body the caller writes, which streams.
   *
   * @param body writes the body
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO)
  String stream(@RequestBody StreamingHttpOutputMessage.Body body);

  /**
   * Sends a note through the converter that writes to the request's body stream.
   *
   * @param note the note
   * @return the body
   */
  @PostExchange(EchoDownstream.ECHO)
  String write(@RequestBody Note note);

  /**
   * Calls the stock path, the protected service of {@code TokenDownstream}.
   *
   * @return the body
   */
  @GetExchange(ScriptedDownstream.STOCK)
  String stock();

  /**
   * Calls the path whose first request answers 503, so that the call is retried once.
   *
   * @return the body
   */
  @GetExchange(EchoDownstream.FLAKY)
  String flaky();
}
