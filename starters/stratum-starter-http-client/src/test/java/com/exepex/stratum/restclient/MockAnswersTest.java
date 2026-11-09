package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.restclient.testsupport.RestClientTestData;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("MockAnswers records calls only for a client a test asked for")
class MockAnswersTest {

  /** The method recorded. */
  private static final String METHOD = "payment";

  /** The answers under test, with no JSON mapper bean. */
  private final MockAnswers answers =
      new MockAnswers(new StaticListableBeanFactory().getBeanProvider(JsonMapper.class));

  @Test
  @DisplayName("records nothing before a test asks for the client, as a demo app never does")
  void nothingRecordedBeforeAsked() {
    answers.addCall(RestClientTestData.PAYMENTS, METHOD, "p-1");

    assertThat(answers.calls(RestClientTestData.PAYMENTS, METHOD)).isEmpty();
  }

  @Test
  @DisplayName("records each call once a test asked for the client")
  void recordedAfterAsked() {
    answers.startRecording(RestClientTestData.PAYMENTS);
    answers.addCall(RestClientTestData.PAYMENTS, METHOD, "p-1");

    assertThat(answers.calls(RestClientTestData.PAYMENTS, METHOD))
        .containsExactly(new RecordedCall(METHOD, List.of("p-1")));
  }
}
