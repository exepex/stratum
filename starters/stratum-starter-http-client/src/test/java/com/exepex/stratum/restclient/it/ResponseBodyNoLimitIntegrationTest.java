package com.exepex.stratum.restclient.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.httpclient.HttpClientConstants;
import com.exepex.stratum.restclient.testsupport.LargeAnswerDownstream;
import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import com.exepex.stratum.restclient.testsupport.answers.AnswersApplication;
import com.exepex.stratum.restclient.testsupport.answers.AnswersClient;
import java.io.IOException;
import org.junit.jupiter.api.AutoClose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(
    classes = AnswersApplication.class,
    properties = HttpClientConstants.DEFAULTS + ".response-body-limit=-1")
@Timeout(60)
@DisplayName("With response-body-limit: -1, a body of any size is read")
class ResponseBodyNoLimitIntegrationTest {

  /** One byte over Stratum's default of 32 MB. */
  private static final int OVER_DEFAULT =
      (int) HttpClientConstants.DEFAULT_RESPONSE_BODY_LIMIT.toBytes() + 1;

  /** The server the answers client calls. */
  @AutoClose private static final LargeAnswerDownstream DOWNSTREAM = LargeAnswerDownstream.start();

  @Autowired private AnswersClient answers;

  /**
   * Points the answers client at the server.
   *
   * @param registry the test's properties
   */
  @DynamicPropertySource
  static void address(DynamicPropertyRegistry registry) {
    registry.add(RestClientTestData.INVENTORY_URL, DOWNSTREAM::url);
  }

  @Test
  @DisplayName("a body over Stratum's default of 32 MB is read whole")
  void bodyOverDefaultRead() throws IOException {
    assertThat(answers.resource(OVER_DEFAULT).contentLength()).isEqualTo(OVER_DEFAULT);
  }
}
