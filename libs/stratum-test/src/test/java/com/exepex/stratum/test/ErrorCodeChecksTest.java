package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.exepex.stratum.core.error.CoreErrorCode;
import com.exepex.stratum.core.error.ErrorCode;
import com.exepex.stratum.core.error.Severity;
import com.exepex.stratum.core.error.StratumException;
import java.io.IOException;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@DisplayName(
    "ErrorCodeChecks checks a module's codes against the page, and a failed startup's code")
class ErrorCodeChecksTest {

  /** A context with no configuration of its own, which each test adds to. */
  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @Nested
  @DisplayName("checkErrorCodes keeps a module's codes, the error codes page and the fixes in step")
  class CheckErrorCodes {

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
  }

  @Nested
  @DisplayName("startupFailureOf returns the StratumException that stopped the application")
  class StartupFailureOf {

    @Test
    @DisplayName("returns the exception when it carries the expected code")
    void rightCodeReturns() {
      contextRunner
          .withUserConfiguration(FailsWithCode.class)
          .run(
              context ->
                  assertThat(ErrorCodeChecks.startupFailureOf(context, FailsWithCode.CODE))
                      .isNotNull()
                      .extracting(StratumException::getErrorCode)
                      .isEqualTo(FailsWithCode.CODE));
    }

    @Test
    @DisplayName("fails when the exception carries another code")
    void wrongCodeFails() {
      contextRunner
          .withUserConfiguration(FailsWithCode.class)
          .run(
              context ->
                  assertThatExceptionOfType(AssertionError.class)
                      .isThrownBy(
                          () ->
                              ErrorCodeChecks.startupFailureOf(
                                  context, CoreErrorCode.CUSTOMIZER_BROKEN)));
    }

    @Test
    @DisplayName("fails when the cause is no StratumException")
    void otherCauseFails() {
      contextRunner
          .withUserConfiguration(FailsWithoutCode.class)
          .run(
              context ->
                  assertThatExceptionOfType(AssertionError.class)
                      .isThrownBy(
                          () -> ErrorCodeChecks.startupFailureOf(context, FailsWithCode.CODE))
                      .withMessageContaining("no StratumException"));
    }

    @Test
    @DisplayName("fails when the application started")
    void startedFails() {
      contextRunner.run(
          context ->
              assertThatExceptionOfType(AssertionError.class)
                  .isThrownBy(() -> ErrorCodeChecks.startupFailureOf(context, FailsWithCode.CODE))
                  .withMessageContaining("the startup failure"));
    }
  }

  /** An application whose one bean fails with a Stratum code. */
  @Configuration(proxyBeanMethods = false)
  static class FailsWithCode {

    /** The code the bean fails with. */
    static final CoreErrorCode CODE = CoreErrorCode.INVALID_SETTINGS;

    /**
     * Fails with the code.
     *
     * @return never returns
     */
    @Bean
    Object failingBean() {
      throw new StratumException(CODE, "the test's settings");
    }
  }

  /** An application whose one bean fails with no Stratum code. */
  @Configuration(proxyBeanMethods = false)
  static class FailsWithoutCode {

    /**
     * Fails with a plain exception.
     *
     * @return never returns
     */
    @Bean
    Object failingBean() {
      throw new IllegalStateException("a plain failure");
    }
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
