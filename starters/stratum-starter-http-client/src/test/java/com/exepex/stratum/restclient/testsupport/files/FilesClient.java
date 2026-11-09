package com.exepex.stratum.restclient.testsupport.files;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.StreamingDownstream;
import java.io.InputStream;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ResponseEntity;
import org.springframework.http.StreamingHttpOutputMessage;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;

/** A client that downloads and uploads files from {@link StreamingDownstream}. */
@StratumClient(RestClientTestData.FILES)
public interface FilesClient {

  /**
   * Downloads the slow answer as a stream, which the caller closes.
   *
   * @return the stream, open from the answer's headers on
   */
  @GetExchange(StreamingDownstream.SLOW)
  InputStream download();

  /**
   * Downloads the slow answer as a stream, with the answer's status and headers.
   *
   * @return the answer, whose body the caller closes
   */
  @GetExchange(StreamingDownstream.SLOW)
  ResponseEntity<InputStream> downloadWithHeaders();

  /**
   * Reads the slow answer as text, which Spring reads whole before it returns.
   *
   * @return the text
   */
  @GetExchange(StreamingDownstream.SLOW)
  String downloadText();

  /**
   * Downloads an answer that stops after its first byte.
   *
   * @return the stream, which the caller closes
   */
  @GetExchange(StreamingDownstream.STALL)
  InputStream downloadStalled();

  /**
   * Uploads a body that the caller writes.
   *
   * @param body writes the body
   * @return the downstream's answer
   */
  @PostExchange(StreamingDownstream.UPLOAD)
  String upload(@RequestBody StreamingHttpOutputMessage.Body body);

  /**
   * Uploads a body read from a stream.
   *
   * @param body the stream
   * @return the downstream's answer
   */
  @PostExchange(StreamingDownstream.UPLOAD)
  String upload(@RequestBody InputStreamResource body);
}
