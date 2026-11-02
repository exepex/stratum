package com.exepex.stratum.logging.redaction;

import java.lang.reflect.Array;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;

/**
 * Keeps the first {@code stratum.logging.max-elements} elements of a logged list, map or array, and
 * adds one marker that says how many were left out, such as {@code ...[190 more]}. No element after
 * the limit is read.
 */
@RequiredArgsConstructor
final class ElementLimit {

  /** The key of the entry that ends a map with entries left out. */
  static final String MAP_MARKER_KEY = "...";

  /** Ends a list or array with elements left out, when they cannot be counted. */
  static final String UNCOUNTED_MARKER = "...[more]";

  /** Written for {@code null}, and for a {@code toString()} that returns {@code null}. */
  private static final String NULL_TEXT = "null";

  /** The most elements kept of each list, map or array. */
  private final int maxElements;

  /**
   * Returns whether Spring Boot writes the value as a JSON array or object: a map, an array, or an
   * iterable other than a file path, which Spring Boot writes as text.
   *
   * @param value the logged value
   * @return whether the value holds elements
   */
  boolean holdsElements(Object value) {
    return value instanceof Map
        || value instanceof Iterable && !(value instanceof Path)
        || value.getClass().isArray();
  }

  /**
   * Returns a copy of a value that holds elements, with at most the limit of them and a marker when
   * any were left out: a list for an iterable or an array, a map with text keys for a map.
   *
   * @param container a value for which {@link #holdsElements} is true
   * @return the copy
   */
  Object limited(Object container) {
    return container instanceof Map<?, ?> map ? limitedMap(map) : limitedElements(container);
  }

  /**
   * Returns the value as text: a list, map or array as its first elements, nested ones too, in
   * Java's own layout, such as {@code [a, b, ...[3 more]]} or {@code {a=1, ...=[3 more]}}; any
   * other value by its {@code toString()}.
   *
   * @param value the logged value
   * @return the text
   */
  String textOf(@Nullable Object value) {
    return switch (value) {
      case null -> NULL_TEXT;
      case Map<?, ?> map ->
          limitedMap(map).entrySet().stream()
              .map(this::entryText)
              .collect(Collectors.joining(", ", "{", "}"));
      case Object container when holdsElements(container) ->
          limitedElements(container).stream()
              .map(this::textOf)
              .collect(Collectors.joining(", ", "[", "]"));
      default -> Objects.requireNonNullElse(value.toString(), NULL_TEXT);
    };
  }

  /** Returns one map entry as {@code key=value}. */
  private String entryText(Map.Entry<String, @Nullable Object> entry) {
    return entry.getKey() + "=" + textOf(entry.getValue());
  }

  /** Returns the first elements of an iterable or an array, then the marker if any are left. */
  private List<@Nullable Object> limitedElements(Object container) {
    return switch (container) {
      case Collection<?> collection ->
          limitedIterator(
              collection.iterator(), () -> countedMarker(collection.size() - maxElements));
      case Iterable<?> iterable -> limitedIterator(iterable.iterator(), () -> UNCOUNTED_MARKER);
      default -> limitedArray(container);
    };
  }

  /** Returns the first elements of the iterator, then the marker if it has any left. */
  private List<@Nullable Object> limitedIterator(
      Iterator<?> elements, Supplier<String> leftOutMarker) {
    var kept = new ArrayList<@Nullable Object>(maxElements + 1);
    // A loop, because no element after the limit may be read.
    while (kept.size() < maxElements && elements.hasNext()) {
      kept.add(elements.next());
    }
    if (elements.hasNext()) {
      kept.add(leftOutMarker.get());
    }
    return kept;
  }

  /** Returns the first elements of an array, of objects or of primitives, then the marker. */
  private List<@Nullable Object> limitedArray(Object array) {
    var length = Array.getLength(array);
    var kept =
        IntStream.range(0, Math.min(length, maxElements))
            .mapToObj(position -> Array.get(array, position));
    var marker =
        length > maxElements ? Stream.of(countedMarker(length - maxElements)) : Stream.empty();
    return Stream.<@Nullable Object>concat(kept, marker).toList();
  }

  /**
   * Returns the first entries of a map, with text keys, then the marker entry if any are left. Of
   * two keys with the same text the later one is kept; the other, and a key equal to the marker's
   * own, is left out and counted, so that every key is written once.
   */
  private Map<String, @Nullable Object> limitedMap(Map<?, ?> map) {
    var kept = new LinkedHashMap<String, @Nullable Object>();
    var entries = map.entrySet().iterator();
    var read = 0;
    // A loop, because no entry after the limit may be read.
    while (read < maxElements && entries.hasNext()) {
      var entry = entries.next();
      kept.put(textOf(entry.getKey()), entry.getValue());
      read++;
    }
    kept.remove(MAP_MARKER_KEY);
    var leftOut = map.size() - kept.size();
    if (leftOut > 0) {
      kept.put(MAP_MARKER_KEY, "[" + leftOut + " more]");
    }
    return kept;
  }

  /** Returns the marker that ends a list or array with this many elements left out. */
  private String countedMarker(int leftOut) {
    return "...[" + leftOut + " more]";
  }
}
