package com.exepex.stratum.restclient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.exepex.stratum.restclient.testsupport.ServletFreeClassLoader;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;
import org.springframework.http.HttpHeaders;
import org.springframework.web.context.request.RequestContextHolder;

@DisplayName(
    "Copying headers works in a service without the servlet API, and loads no servlet class")
class CopiedHeadersServletFreeTest {

  /** The classes on the way of every copied header, which Spring reflects on as beans' parts. */
  private static final List<String> COPY_CLASSES =
      List.of(
          "com.exepex.stratum.restclient.CopiedHeaders",
          "com.exepex.stratum.restclient.OutgoingStepsRequestFactory",
          "com.exepex.stratum.restclient.DefaultOutgoingRequest");

  /** The loader with no servlet API. */
  private final ServletFreeClassLoader loader = new ServletFreeClassLoader();

  @Test
  @DisplayName("outside a request, the copy step copies nothing and asks for no servlet class")
  void copiesNothingWithoutServletApi() throws Throwable {
    RequestContextHolder.resetRequestAttributes();
    var type = Class.forName(COPY_CLASSES.getFirst(), true, loader);
    var lookup = MethodHandles.privateLookupIn(type, MethodHandles.lookup());
    var copiedHeaders =
        lookup
            .findConstructor(type, MethodType.methodType(void.class, List.class))
            .invoke(List.of("X-Tenant"));
    var headers = new HttpHeaders();

    lookup
        .findVirtual(type, "copyInto", MethodType.methodType(void.class, HttpHeaders.class))
        .invoke(copiedHeaders, headers);

    assertThat(headers.isEmpty()).isTrue();
    assertThat(loader.refusedNames()).isEmpty();
  }

  @ParameterizedTest(name = "{0}")
  @FieldSource("COPY_CLASSES")
  @DisplayName("reflecting on a class of the copy, as Spring does, asks for no servlet class")
  void reflectionLoadsNoServletClass(String className) throws ClassNotFoundException {
    var type = Class.forName(className, false, loader);

    assertThatCode(() -> reflectOn(type)).doesNotThrowAnyException();
    assertThat(loader.refusedNames()).isEmpty();
  }

  /** Reads a class's fields, methods and constructors, which loads every type they name. */
  private void reflectOn(Class<?> type) {
    assertThat(type.getDeclaredFields()).isNotNull();
    assertThat(type.getDeclaredMethods()).isNotEmpty();
    assertThat(type.getDeclaredConstructors()).isNotEmpty();
  }
}
