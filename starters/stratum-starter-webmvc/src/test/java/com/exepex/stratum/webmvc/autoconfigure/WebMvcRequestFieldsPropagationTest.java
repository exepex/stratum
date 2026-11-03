package com.exepex.stratum.webmvc.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.web.RequestFieldsPropagation;
import com.exepex.stratum.web.WebConstants;
import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

@DisplayName(
    "WebMvcRequestFieldsAutoConfiguration carries the request fields to other threads while the"
        + " module is on")
class WebMvcRequestFieldsPropagationTest {

  /** The setting that turns the module on. */
  private static final String MODULE_ON = WebConstants.ENABLED + "=true";

  /** The key of the request fields accessor, alone, as the registry holds it once registered. */
  private static final List<Object> ACCESSOR_KEY = List.of(WebConstants.REQUEST_FIELDS_CONTEXT_KEY);

  /** A servlet application with Stratum's request fields. */
  private final WebApplicationContextRunner contextRunner =
      new WebApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(WebMvcRequestFieldsAutoConfiguration.class));

  /** Starts each test from a registry the module has not touched in this JVM. */
  @BeforeEach
  @AfterEach
  void removeRegistration() {
    ContextRegistry.getInstance()
        .removeThreadLocalAccessor(WebConstants.REQUEST_FIELDS_CONTEXT_KEY);
  }

  @Test
  @DisplayName("with the module unset, the accessor is not registered")
  void moduleUnsetRegistersNothing() {
    contextRunner.run(
        context -> {
          assertThat(context).doesNotHaveBean(RequestFieldsPropagation.class);
          assertThat(registeredRequestFieldKeys()).isEmpty();
        });
  }

  @Test
  @DisplayName("with the module on, the accessor is registered")
  void moduleOnRegistersAccessor() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .run(_ -> assertThat(registeredRequestFieldKeys()).isEqualTo(ACCESSOR_KEY));
  }

  @Test
  @DisplayName("with lazy initialization on, the accessor is still registered at startup")
  void lazyInitializationRegistersAccessor() {
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withInitializer(
            context ->
                context.addBeanFactoryPostProcessor(
                    new LazyInitializationBeanFactoryPostProcessor()))
        .run(_ -> assertThat(registeredRequestFieldKeys()).isEqualTo(ACCESSOR_KEY));
  }

  @Test
  @DisplayName("after one application closes, the accessor stays for the others in the JVM")
  void closingAnApplicationKeepsAccessor() {
    contextRunner.withPropertyValues(MODULE_ON).run(_ -> {});

    assertThat(registeredRequestFieldKeys()).isEqualTo(ACCESSOR_KEY);
  }

  @Test
  @DisplayName("the service's own propagation bean replaces Stratum's")
  void ownBeanBacksOff() {
    var servicePropagation = new RequestFieldsPropagation();
    contextRunner
        .withPropertyValues(MODULE_ON)
        .withBean(RequestFieldsPropagation.class, () -> servicePropagation)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(RequestFieldsPropagation.class)
                    .getBean(RequestFieldsPropagation.class)
                    .isSameAs(servicePropagation));
  }

  /** Returns the request fields accessor's key if Micrometer's shared registry holds it now. */
  private List<Object> registeredRequestFieldKeys() {
    return ContextRegistry.getInstance().getThreadLocalAccessors().stream()
        .map(ThreadLocalAccessor::key)
        .filter(WebConstants.REQUEST_FIELDS_CONTEXT_KEY::equals)
        .toList();
  }
}
