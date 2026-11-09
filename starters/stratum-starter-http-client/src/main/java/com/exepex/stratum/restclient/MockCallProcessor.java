package com.exepex.stratum.restclient;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.core.MethodParameter;
import org.springframework.core.ResolvableType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.service.invoker.HttpRequestValues;

/**
 * Marks each call of a mocked client's interface with its method, which the fake request reads to
 * find the answer, and records the call when a test asked for the client. Added only to the proxy
 * factories of clients set to {@code mock: true}.
 */
@RequiredArgsConstructor
final class MockCallProcessor implements HttpRequestValues.Processor {

  /** The client's name. */
  private final String client;

  /** The answers and calls of every mocked client. */
  private final MockAnswers answers;

  /** Each method's call mark, worked out once, so a call does no reflection (ST-ENG-54). */
  private final Map<Method, MockCall> calls = new ConcurrentHashMap<>();

  @Override
  public void process(
      Method method,
      MethodParameter[] parameters,
      @Nullable Object[] arguments,
      HttpRequestValues.Builder values) {
    values.addAttribute(MockAnswers.METHOD_ATTRIBUTE, calls.computeIfAbsent(method, this::markOf));
    answers.addCall(client, method.getName(), Objects.requireNonNullElse(arguments, new Object[0]));
  }

  /** Returns the call mark of a method: its name, and whether it reads no body. */
  private MockCall markOf(Method method) {
    return new MockCall(method.getName(), readsNoBody(method));
  }

  /**
   * Returns whether a method's return type reads no body: {@code void}, {@code HttpHeaders} or
   * {@code ResponseEntity<Void>}.
   */
  private static boolean readsNoBody(Method method) {
    var type = ResolvableType.forMethodReturnType(method);
    var returned = type.resolve(Object.class);
    if (returned == ResponseEntity.class) {
      return type.getGeneric(0).resolve(Object.class) == Void.class;
    }
    return returned == void.class
        || returned == Void.class
        || HttpHeaders.class.isAssignableFrom(returned);
  }
}
