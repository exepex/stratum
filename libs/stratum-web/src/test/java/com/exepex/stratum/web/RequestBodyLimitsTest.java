package com.exepex.stratum.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.exepex.stratum.web.testsupport.WebTestData;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpStatus;
import org.springframework.util.unit.DataSize;
import org.springframework.util.unit.DataUnit;
import org.springframework.web.method.HandlerMethod;

@DisplayName("RequestBodyLimits gives each request body its limit and stops one that passes it")
class RequestBodyLimitsTest {

  /** The path of an order, which matches no route. */
  private static final String ORDER = "/orders/42";

  /** The path of an image upload, which has a larger limit than other uploads. */
  private static final String IMAGE_UPLOAD = "/uploads/images/cat.png";

  /** The limit of a request whose path matches no route. */
  private static final DataSize DEFAULT_LIMIT = DataSize.ofKilobytes(256);

  /** The limit of image uploads. */
  private static final DataSize IMAGE_LIMIT = DataSize.ofMegabytes(20);

  /** The limit of the import API that accepts larger bodies. */
  private static final DataSize LARGE_IMPORT_LIMIT = DataSize.ofKilobytes(512);

  /** The limits: a 256KB default, image uploads 20MB, other uploads none. */
  private final RequestBodyLimits limits =
      new RequestBodyLimits(
          WebTestData.defaults()
              .bodyLimit(
                  new WebProperties.BodyLimitSettings(
                      DEFAULT_LIMIT,
                      Map.of("/uploads/**", DataSize.ofBytes(0), "/uploads/images/*", IMAGE_LIMIT)))
              .build());

  @Nested
  @DisplayName("the limit of a request")
  class Limits {

    @Test
    @DisplayName("is the default when no route matches")
    void defaultLimit() {
      assertThat(limits.start(ORDER).limit()).isEqualTo(DEFAULT_LIMIT);
    }

    @Test
    @DisplayName("is the most specific route's, and 0 means no limit")
    void mostSpecificRoute() {
      assertThat(limits.start(IMAGE_UPLOAD).limit()).isEqualTo(IMAGE_LIMIT);
      assertThat(limits.start("/uploads/videos/42").limit()).isEqualTo(DataSize.ofBytes(0));
    }

    @Test
    @DisplayName("is the handler's @RequestBodyLimit, when it has one")
    void handlerLimitWins() throws NoSuchMethodException {
      var counter = limits.start(ORDER);
      limits.applyHandlerLimit(counter, handler("plainImport"));
      limits.applyHandlerLimit(counter, "a handler that is not a method");
      assertThat(counter.limit()).isEqualTo(DEFAULT_LIMIT);

      limits.applyHandlerLimit(counter, handler("largeImport"));
      assertThat(counter.limit()).isEqualTo(LARGE_IMPORT_LIMIT);
    }
  }

  @Nested
  @DisplayName("a body")
  @ExtendWith(OutputCaptureExtension.class)
  class Counting {

    @Test
    @DisplayName("of exactly the limit is accepted, and one byte more fails with 413 and a WARN")
    void oneByteOverFails(CapturedOutput output) {
      var counter = limits.start(ORDER);
      counter.count(DEFAULT_LIMIT.toBytes() - 1);
      counter.count(1);

      assertThatExceptionOfType(RequestBodyTooLargeException.class)
          .isThrownBy(() -> counter.count(1))
          .satisfies(
              tooLarge ->
                  assertThat(tooLarge.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE));
      assertThat(output).contains("WARN", "larger than its limit of 262144 bytes");
    }

    @Test
    @DisplayName("whose declared length is over the limit fails before any of it is read")
    void declaredLengthOverLimitFails() {
      var counter = limits.start(ORDER);

      assertThatNoException().isThrownBy(() -> counter.checkDeclaredLength(-1));
      assertThatNoException()
          .isThrownBy(() -> counter.checkDeclaredLength(DEFAULT_LIMIT.toBytes()));
      assertThatExceptionOfType(RequestBodyTooLargeException.class)
          .isThrownBy(() -> counter.checkDeclaredLength(DEFAULT_LIMIT.toBytes() + 1));
    }

    @Test
    @DisplayName("already read past a smaller limit set later, such as its handler's, fails")
    void smallerLimitAfterReadingFails() {
      var counter = limits.start(ORDER);
      counter.count(DataSize.ofKilobytes(2).toBytes());

      assertThatExceptionOfType(RequestBodyTooLargeException.class)
          .isThrownBy(() -> counter.limitTo(DataSize.ofKilobytes(1)));
    }

    @Test
    @DisplayName("on a path with no limit is never stopped")
    void noLimit() {
      var counter = limits.start("/uploads/videos/42");

      assertThatNoException()
          .isThrownBy(
              () -> {
                counter.checkDeclaredLength(Long.MAX_VALUE);
                counter.count(DataSize.ofGigabytes(1).toBytes());
              });
    }
  }

  /** Returns the handler method of {@link ImportController} with the name. */
  private HandlerMethod handler(String methodName) throws NoSuchMethodException {
    return new HandlerMethod(new ImportController(), methodName);
  }

  /** A controller with one API that accepts larger bodies. */
  public static class ImportController {

    /**
     * Has no limit of its own.
     *
     * @return the result
     */
    public String plainImport() {
      return "plain";
    }

    /**
     * Accepts 512KB.
     *
     * @return the result
     */
    @RequestBodyLimit(value = 512, unit = DataUnit.KILOBYTES)
    public String largeImport() {
      return "large";
    }
  }
}
