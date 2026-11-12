package com.exepex.stratum.restclient;

import com.exepex.stratum.core.api.StratumInternal;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;

/**
 * The answers and recorded calls of the clients set to {@code mock: true}. An answer comes from a
 * test's script, else from the file {@code stratum-mocks/<client>/<method>.json} on the classpath,
 * else a method that reads no body answers 200, else the call gets a 501 that names the file to
 * add. File texts are read once; each call reads its answer anew, so a caller that changes what it
 * got never changes the next answer. Calls are recorded only for a client a test asked for.
 */
@StratumInternal
@RequiredArgsConstructor
public class MockAnswers {

  /** The request attribute that carries the {@link MockCall} of a mocked interface's call. */
  static final String METHOD_ATTRIBUTE = "stratum.mock.method";

  /** The classpath path of a client method's answer file, from the client's and method's names. */
  private static final String ANSWER_FILE = "stratum-mocks/%s/%s.json";

  /** The application's JSON mapper, which writes scripted values. */
  private final ObjectProvider<JsonMapper> jsonMappers;

  /** The mocked client each interface belongs to. */
  private final Map<Class<?>, String> clientsByType = new ConcurrentHashMap<>();

  /** The scripted answers, by client, then method. */
  private final Map<String, Map<String, MockAnswer>> scripts = new ConcurrentHashMap<>();

  /** The calls of the clients a test asked for, by client. */
  private final Map<String, List<RecordedCall>> records = new ConcurrentHashMap<>();

  /** The answer files read so far, by path; empty for a file that does not exist. */
  private final Map<String, Optional<String>> files = new ConcurrentHashMap<>();

  /** Opened by {@link #reset()}, so that the delayed answers of a finished test end at once. */
  private final AtomicReference<CountDownLatch> resetSignal =
      new AtomicReference<>(new CountDownLatch(1));

  /**
   * Notes the interfaces of a mocked client, for {@link MockClients#of}.
   *
   * @param client the client's name
   * @param types its interfaces
   */
  void mocked(String client, Collection<Class<?>> types) {
    types.forEach(type -> clientsByType.put(type, client));
  }

  /**
   * Returns the mocked client an interface belongs to.
   *
   * @param type the interface
   * @return the client's name, or empty when its client is not mocked
   */
  Optional<String> clientOf(Class<?> type) {
    return Optional.ofNullable(clientsByType.get(type));
  }

  /**
   * Starts recording the calls of a client, if not already.
   *
   * @param client the client's name
   */
  void startRecording(String client) {
    records.computeIfAbsent(client, unused -> new CopyOnWriteArrayList<>());
  }

  /**
   * Records a call, when a test asked for the client.
   *
   * @param client the client's name
   * @param method the method's name
   * @param arguments the call's arguments
   */
  void addCall(String client, String method, @Nullable Object... arguments) {
    Optional.ofNullable(records.get(client))
        .ifPresent(calls -> calls.add(new RecordedCall(method, Arrays.asList(arguments))));
  }

  /**
   * Returns the recorded calls of one method of a client.
   *
   * @param client the client's name
   * @param method the method's name
   * @return the calls, oldest first
   */
  List<RecordedCall> calls(String client, String method) {
    return records.getOrDefault(client, List.of()).stream()
        .filter(call -> call.method().equals(method))
        .toList();
  }

  /**
   * Sets the answer of one method of a client.
   *
   * @param client the client's name
   * @param method the method's name
   * @param answer the answer
   */
  void script(String client, String method, MockAnswer answer) {
    scripts.computeIfAbsent(client, unused -> new ConcurrentHashMap<>()).put(method, answer);
  }

  /**
   * Returns the 200 answer of a scripted value: a text as it is, any other value as JSON.
   *
   * @param value the value, or {@code null} for no body
   * @return the answer
   */
  MockAnswer ok(@Nullable Object value) {
    return switch (value) {
      case null -> MockAnswer.empty();
      case CharSequence text -> MockAnswer.text(HttpStatus.OK, text.toString());
      default ->
          MockAnswer.json(
              HttpStatus.OK, jsonMappers.getIfAvailable(JsonMapper::new).writeValueAsString(value));
    };
  }

  /**
   * Returns the answer to one call of a mocked client.
   *
   * @param client the client's name
   * @param call the interface method the call came from, or {@code null} for a call through the
   *     client's {@code RestClient} or {@code RestTemplate}, which no method marks
   * @return the answer
   */
  MockAnswer answer(String client, @Nullable MockCall call) {
    if (call == null) {
      return MockAnswer.text(
          HttpStatus.NOT_IMPLEMENTED,
          "no mock answer for client "
              + client
              + ": only the methods of its interfaces are mocked, not calls through"
              + " its RestClient or RestTemplate");
    }
    var path = ANSWER_FILE.formatted(client, call.method());
    return Optional.ofNullable(scripts.getOrDefault(client, Map.of()).get(call.method()))
        .or(() -> file(path).map(body -> MockAnswer.json(HttpStatus.OK, body)))
        .orElseGet(() -> call.bodyless() ? MockAnswer.empty() : missing(client, call, path));
  }

  /**
   * Waits the given time, unless {@link #reset()} ends the test's calls first.
   *
   * @param time the wait
   * @throws InterruptedIOException if a reset ended the wait, or the thread is interrupted, which
   *     keeps its interrupt
   */
  void pause(Duration time) throws InterruptedIOException {
    boolean endedByReset;
    try {
      endedByReset = resetSignal.get().await(time.toNanos(), TimeUnit.NANOSECONDS);
    } catch (InterruptedException interruption) {
      Thread.currentThread().interrupt();
      var interrupted = new InterruptedIOException("A mocked answer's delay was interrupted");
      interrupted.initCause(interruption);
      throw interrupted;
    }
    if (endedByReset) {
      throw new InterruptedIOException("A mocked answer's delay was ended by a reset of the mocks");
    }
  }

  /**
   * Clears every script and recorded call, and ends the delays waiting now. A client a test asked
   * for keeps recording, so a mock held across a reset still sees the next calls.
   */
  void reset() {
    resetSignal.getAndSet(new CountDownLatch(1)).countDown();
    scripts.clear();
    records.values().forEach(List::clear);
  }

  /** Returns the 501 of a call with no script and no file, naming the file to add. */
  private MockAnswer missing(String client, MockCall call, String path) {
    return MockAnswer.text(
        HttpStatus.NOT_IMPLEMENTED,
        "no mock answer for client "
            + client
            + ", method "
            + call.method()
            + ": add "
            + path
            + " or script it with MockClient");
  }

  /** Returns the text of an answer file, read on first use. */
  private Optional<String> file(String path) {
    return files.computeIfAbsent(path, this::read);
  }

  /** Reads an answer file from the classpath, or returns empty when there is none. */
  private Optional<String> read(String path) {
    var resource = new ClassPathResource(path);
    if (!resource.exists()) {
      return Optional.empty();
    }
    try {
      return Optional.of(resource.getContentAsString(StandardCharsets.UTF_8));
    } catch (IOException unreadable) {
      throw new UncheckedIOException(unreadable);
    }
  }
}
