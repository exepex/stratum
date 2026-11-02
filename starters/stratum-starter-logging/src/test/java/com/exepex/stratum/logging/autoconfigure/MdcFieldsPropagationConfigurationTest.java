package com.exepex.stratum.logging.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.mdc.MdcFieldsPropagation;
import com.exepex.stratum.logging.testsupport.MdcFieldsRegistrations;
import io.micrometer.context.ContextRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

@DisplayName(
    "LoggingAutoConfiguration carries the service's own and the workflow MDC fields to other"
        + " threads only while the module is on")
class MdcFieldsPropagationConfigurationTest {

  /** Both accessors, in the order the module registers them. */
  private static final List<Object> BOTH_ACCESSORS =
      List.of(LoggingConstants.SERVICE_MDC_FIELDS, LoggingConstants.WORKFLOW_FIELDS);

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

  /** Starts each test from a registry the module has not touched in this JVM. */
  @BeforeEach
  @AfterEach
  void removeRegistrations() {
    MdcFieldsRegistrations.remove();
  }

  @Test
  @DisplayName("with the module unset, neither accessor is registered")
  void moduleUnsetRegistersNeither() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(MdcFieldsPropagation.class);
          assertThat(MdcFieldsRegistrations.registeredKeys()).isEmpty();
        });
  }

  @Test
  @DisplayName("with the module off, neither accessor is registered")
  void moduleOffRegistersNeither() {
    contextRunner
        .withPropertyValues(LoggingConstants.ENABLED + "=false")
        .run(_ -> assertThat(MdcFieldsRegistrations.registeredKeys()).isEmpty());
  }

  @Test
  @DisplayName("Micrometer's own loading through META-INF/services registers neither accessor")
  void serviceLoaderRegistersNeither() {
    var loadedRegistry = new ContextRegistry().loadThreadLocalAccessors();

    assertThat(MdcFieldsRegistrations.registeredKeys(loadedRegistry)).isEmpty();
  }

  @Test
  @DisplayName("with the module on, both accessors are registered")
  void moduleOnRegistersBoth() {
    contextRunner
        .withPropertyValues(LoggingConstants.ENABLED + "=true")
        .run(_ -> assertThat(MdcFieldsRegistrations.registeredKeys()).isEqualTo(BOTH_ACCESSORS));
  }

  @Test
  @DisplayName("with lazy initialization on, both accessors are still registered at startup")
  void lazyInitializationRegistersBoth() {
    contextRunner
        .withPropertyValues(LoggingConstants.ENABLED + "=true")
        .withInitializer(
            context ->
                context.addBeanFactoryPostProcessor(
                    new LazyInitializationBeanFactoryPostProcessor()))
        .run(_ -> assertThat(MdcFieldsRegistrations.registeredKeys()).isEqualTo(BOTH_ACCESSORS));
  }

  @Test
  @DisplayName("after one application closes, both accessors stay for the others in the JVM")
  void closingAnApplicationKeepsBoth() {
    contextRunner.withPropertyValues(LoggingConstants.ENABLED + "=true").run(_ -> {});

    assertThat(MdcFieldsRegistrations.registeredKeys()).isEqualTo(BOTH_ACCESSORS);
  }
}
