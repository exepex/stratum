package com.exepex.stratum.core.error;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ExternalError is the error shown outside the application")
class ExternalErrorTest {

  @Test
  @DisplayName("it keeps a copy of the details that later changes to the list do not reach")
  void copiesDetails() {
    var detail = new ErrorDetail("quantity", "must be positive");
    var details = new ArrayList<>(List.of(detail));

    var external =
        new ExternalError("ORD-0001", Severity.ERROR, "Not enough stock.", details, null, null);
    details.clear();

    assertThat(external.details()).containsExactly(detail);
  }
}
