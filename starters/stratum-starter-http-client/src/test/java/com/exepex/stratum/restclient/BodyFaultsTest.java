package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.argumentSet;

import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.MalformedJsonException;
import com.google.protobuf.InvalidProtocolBufferException;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.web.client.RestClientException;

@DisplayName("BodyFaults tells a body that is not valid from bytes lost on the way")
class BodyFaultsTest {

  /** The message of each failure, which nothing reads. */
  private static final String MESSAGE = "bad body";

  /** The read failures of each parser a client can be given, as the parser throws them. */
  private static final List<Arguments> PARSER_FAILURES =
      List.of(
          argumentSet("Jackson 2's bad JSON", new JsonParseException((JsonParser) null, MESSAGE)),
          argumentSet("bytes that are not protobuf", new InvalidProtocolBufferException(MESSAGE)),
          argumentSet("Gson's bad JSON", new MalformedJsonException(MESSAGE)),
          argumentSet("a body over the limit", new ResponseBodyTooLarge("orders", 1)));

  /** The I/O failures of a body cut off on the way. */
  private static final List<Arguments> LOST_BYTES =
      List.of(
          argumentSet("a dropped connection", new IOException("Connection reset")),
          argumentSet("a read timeout", new SocketTimeoutException("Read timed out")),
          argumentSet("an end of input with no parser around it", new EOFException()));

  @Nested
  @DisplayName("a parser's read failure")
  class ParserFailure {

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.restclient.BodyFaultsTest#PARSER_FAILURES")
    @DisplayName("is the body's fault, so the call has no I/O failure")
    void isBodyFault(IOException failure) {
      assertThat(BodyFaults.isBodyFault(failure)).isTrue();
      assertThat(RetryRules.ioFailureOf(callFailedWith(failure))).isEmpty();
    }

    @Test
    @DisplayName("over a read timeout still leaves the timeout as the call's I/O failure")
    void timeoutBelowFound() {
      var timeout = new SocketTimeoutException("Read timed out");

      assertThat(
              RetryRules.ioFailureOf(callFailedWith(new InvalidProtocolBufferException(timeout))))
          .containsSame(timeout);
    }
  }

  @Nested
  @DisplayName("bytes lost on the way")
  class LostBytes {

    @ParameterizedTest
    @FieldSource("com.exepex.stratum.restclient.BodyFaultsTest#LOST_BYTES")
    @DisplayName("are not the body's fault, so they are the call's I/O failure")
    void isIoFailure(IOException failure) {
      assertThat(BodyFaults.isBodyFault(failure)).isFalse();
      assertThat(RetryRules.ioFailureOf(callFailedWith(failure))).containsSame(failure);
    }
  }

  @Nested
  @DisplayName("Gson's wrapper")
  class GsonWrapper {

    @Test
    @DisplayName("around the end of the input is JSON that ended too soon, not lost bytes")
    void endOfInputIsBodyFault() {
      var failure = new JsonSyntaxException(new EOFException("End of input"));

      assertThat(BodyFaults.isGsonEndOfJson(failure)).isTrue();
      assertThat(RetryRules.ioFailureOf(callFailedWith(failure))).isEmpty();
    }

    @Test
    @DisplayName("around a dropped connection leaves it as the call's I/O failure")
    void droppedConnectionFound() {
      var dropped = new IOException("Connection reset");

      assertThat(BodyFaults.isGsonEndOfJson(new JsonSyntaxException(dropped))).isFalse();
      assertThat(RetryRules.ioFailureOf(callFailedWith(new JsonSyntaxException(dropped))))
          .containsSame(dropped);
    }

    @Test
    @DisplayName("is the only one: the end of the input in any other wrapper is lost bytes")
    void otherWrapperIsNot() {
      var endOfInput = new EOFException();

      assertThat(BodyFaults.isGsonEndOfJson(new IllegalStateException(endOfInput))).isFalse();
      assertThat(RetryRules.ioFailureOf(callFailedWith(new IllegalStateException(endOfInput))))
          .containsSame(endOfInput);
    }
  }

  /** Returns Spring's failure of a call whose read failed with the given cause. */
  private RestClientException callFailedWith(Throwable cause) {
    return new RestClientException("Error while extracting response", cause);
  }
}
