package com.exepex.stratum.restclient.testsupport.answers;

import com.exepex.stratum.httpclient.StratumClient;
import com.exepex.stratum.restclient.testsupport.LargeAnswerDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.io.InputStream;
import java.util.Map;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.service.annotation.GetExchange;

/** A client of {@link LargeAnswerDownstream}, whose answers may pass its limit or never end. */
@StratumClient(RestClientTestData.INVENTORY)
public interface AnswersClient {

  /**
   * Reads a body of the given size, sent with its length.
   *
   * @param size the size, in bytes
   * @return the body
   */
  @GetExchange(LargeAnswerDownstream.SIZED + LargeAnswerDownstream.SIZE)
  String sized(@PathVariable int size);

  /**
   * Reads a chunked body of the given size.
   *
   * @param size the size, in bytes
   * @return the body
   */
  @GetExchange(LargeAnswerDownstream.CHUNKED + LargeAnswerDownstream.SIZE)
  String chunked(@PathVariable int size);

  /**
   * Downloads a chunked body of the given size as a stream, which the caller closes.
   *
   * @param size the size, in bytes
   * @return the stream
   */
  @GetExchange(LargeAnswerDownstream.CHUNKED + LargeAnswerDownstream.SIZE)
  InputStream download(@PathVariable int size);

  /**
   * Reads a chunked body of the given size as a resource, which Spring reads whole.
   *
   * @param size the size, in bytes
   * @return the resource
   */
  @GetExchange(LargeAnswerDownstream.CHUNKED + LargeAnswerDownstream.SIZE)
  Resource resource(@PathVariable int size);

  /**
   * Reads a gzip body of the given size once decoded.
   *
   * @param size the decoded size, in bytes
   * @return the body
   */
  @GetExchange(LargeAnswerDownstream.GZIPPED + LargeAnswerDownstream.SIZE)
  String gzipped(@PathVariable int size);

  /**
   * Reads a gzip JSON value followed by bytes that are not gzip and never end.
   *
   * @return the value
   */
  @GetExchange(LargeAnswerDownstream.GZIP_THEN_GARBAGE)
  Map<String, Integer> gzipThenGarbage();

  /**
   * Reads a JSON value followed by the given number of spaces.
   *
   * @param spaces the number of spaces after the value
   * @return the value
   */
  @GetExchange(LargeAnswerDownstream.TRAILING + "{spaces}")
  Map<String, Integer> trailing(@PathVariable int spaces);

  /** Calls a path whose body never ends, without reading it. */
  @GetExchange(LargeAnswerDownstream.ENDLESS)
  void endless();

  /**
   * Reads a JSON value followed by spaces that never end.
   *
   * @return the value
   */
  @GetExchange(LargeAnswerDownstream.ENDLESS)
  Map<String, Integer> endlessValue();

  /** Calls a path that announces 50 GB and sends nothing, without reading it. */
  @GetExchange(LargeAnswerDownstream.STALLED)
  void stalled();

  /**
   * Reads a path that announces 50 GB and sends nothing.
   *
   * @return the body
   */
  @GetExchange(LargeAnswerDownstream.STALLED)
  String stalledText();

  /**
   * Reads a small answer.
   *
   * @return the body
   */
  @GetExchange("/small")
  String small();
}
