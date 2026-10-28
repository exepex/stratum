package com.exepex.stratum.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName("ConfigFile loads a configuration file into a context under test")
class ConfigFileTest {

  private static final String MESSAGE = "sample.message";

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner();

  @ParameterizedTest(name = "{0}")
  @CsvSource({
    "sample/application.properties, Hi from properties",
    "sample/application.yml, Hi from YAML"
  })
  @DisplayName("the context sees every value of a .properties or .yml file")
  void loadsEveryValue(String file, String message) {
    contextRunner
        .withInitializer(ConfigFile.load(file))
        .run(
            context -> {
              assertThat(context.getEnvironment().getProperty(MESSAGE)).isEqualTo(message);
              assertThat(context.getEnvironment().getProperty("sample.timeout")).isEqualTo("5s");
            });
  }

  @Test
  @DisplayName("in a YAML file with two documents, the second one wins, as at startup")
  void laterYamlDocumentWins() {
    contextRunner
        .withInitializer(ConfigFile.load("sample/two-documents.yml"))
        .run(
            context -> {
              assertThat(context.getEnvironment().getProperty(MESSAGE))
                  .isEqualTo("Hi from the second document");
              assertThat(context.getEnvironment().getProperty("sample.timeout")).isEqualTo("5s");
            });
  }

  @Test
  @DisplayName("of two files, the one loaded last wins")
  void lastLoadedFileWins() {
    contextRunner
        .withInitializer(ConfigFile.load("sample/application.properties"))
        .withInitializer(ConfigFile.load("sample/application.yml"))
        .run(
            context ->
                assertThat(context.getEnvironment().getProperty(MESSAGE))
                    .isEqualTo("Hi from YAML"));
  }

  @Test
  @DisplayName("a value in the file wins over the same value from withPropertyValues")
  void fileWinsOverPropertyValues() {
    contextRunner
        .withPropertyValues(MESSAGE + "=Hi from the runner")
        .withInitializer(ConfigFile.load("sample/application.yml"))
        .run(
            context ->
                assertThat(context.getEnvironment().getProperty(MESSAGE))
                    .isEqualTo("Hi from YAML"));
  }

  @ParameterizedTest(name = "{0}")
  @ValueSource(strings = {"sample/missing.properties", "sample/missing.yml"})
  @DisplayName("a file that is not on the classpath is refused at once, naming the file")
  void refusesMissingFile(String file) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ConfigFile.load(file))
        .withMessage("No configuration file on the classpath at " + file);
  }

  @ParameterizedTest(name = "\"{0}\"")
  @ValueSource(strings = {"sample/application.txt", "sample/application", "", "yml"})
  @DisplayName("a file that is not .properties, .xml, .yml or .yaml is refused at once")
  void refusesOtherFiles(String file) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ConfigFile.load(file))
        .withMessage("Not a .properties, .xml, .yml or .yaml file: " + file);
  }
}
