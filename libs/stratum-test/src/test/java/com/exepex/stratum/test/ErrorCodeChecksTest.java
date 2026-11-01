package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import java.io.IOException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("checkErrorCodes keeps a module's codes, the error codes page and the fixes in step")
class ErrorCodeChecksTest {

  @Test
  @DisplayName(
      "passes when the codes have the format, the page lists exactly them and the fixes match")
  void passesForDocumentedCodes() throws IOException {
    ErrorCodeChecks.checkErrorCodes("CORE", CoreErrorCode.values());
  }

  @Test
  @DisplayName("fails when a code is not on the error codes page")
  void failsForUndocumentedCode() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> ErrorCodeChecks.checkErrorCodes("CORE", UndocumentedCode.values()));
  }

  @Test
  @DisplayName("fails when a code does not have the module's format")
  void failsForBadlyFormattedCode() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> ErrorCodeChecks.checkErrorCodes("LOGGING", CoreErrorCode.values()));
  }

  @Test
  @DisplayName("fails when a code's fix is not where its Fix column on the page starts")
  void failsForFixThePageDoesNotShow() {
    assertThatExceptionOfType(AssertionError.class)
        .isThrownBy(() -> ErrorCodeChecks.checkErrorCodes("CORE", OtherFixCode.values()))
        .withMessageContaining("SX-CORE-1003");
  }

  /** A code in the right format that the error codes page does not list. */
  @Getter
  @RequiredArgsConstructor
  enum UndocumentedCode implements ErrorCode {
    NOT_ON_THE_PAGE("SX-CORE-9999", Severity.ERROR, "Not on the error codes page");

    private final String code;

    private final Severity severity;

    private final String description;
  }

  /** Copies stratum-core's codes, with a fix for SX-CORE-1003 that the page does not show. */
  @RequiredArgsConstructor
  enum OtherFixCode implements ErrorCode {
    MODULE_NAME(CoreErrorCode.MODULE_NAME_NOT_KEBAB_CASE),
    CUSTOMIZER(CoreErrorCode.CUSTOMIZER_BROKEN),
    SETTINGS(CoreErrorCode.INVALID_SETTINGS),
    CUSTOMIZER_BEAN(CoreErrorCode.CUSTOMIZER_DECLARED_AS_BEAN),
    BOOT_VERSION(CoreErrorCode.SPRING_BOOT_VERSION_UNSUPPORTED);

    /** The real code this one copies. */
    private final CoreErrorCode copied;

    @Override
    public String getCode() {
      return copied.getCode();
    }

    @Override
    public Severity getSeverity() {
      return copied.getSeverity();
    }

    @Override
    public String getDescription() {
      return copied.getDescription();
    }

    @Override
    public String getFix() {
      return this == SETTINGS ? "Remove the named properties." : copied.getFix();
    }
  }
}
