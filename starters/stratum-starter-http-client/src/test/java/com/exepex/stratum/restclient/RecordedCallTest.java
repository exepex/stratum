package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A recorded call's text never shows its arguments, which may hold personal data")
class RecordedCallTest {

  @Test
  @DisplayName("the text names the method and counts the arguments")
  void textHidesArguments() {
    assertThat(new RecordedCall("payment", List.of("person@example.invalid")))
        .hasToString("RecordedCall[method=payment, 1 arguments hidden]");
  }
}
