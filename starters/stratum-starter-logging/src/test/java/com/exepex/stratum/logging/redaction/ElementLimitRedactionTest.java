package com.exepex.stratum.logging.redaction;

import static com.exepex.stratum.logging.testsupport.LoggingTestData.EMAIL;
import static org.assertj.core.api.Assertions.assertThat;

import com.exepex.stratum.logging.testsupport.ElementsTestData;
import com.exepex.stratum.logging.testsupport.LoggingTestData;
import com.exepex.stratum.logging.testsupport.SpringBootJsonLines;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("Each logged list, map and array keeps its first max-elements elements")
class ElementLimitRedactionTest {

  /** The default limit, ten elements. */
  private static final int LIMIT = LoggingTestData.DEFAULT_MAX_ELEMENTS;

  /** A long list: two hundred elements. */
  private static final int MANY = 200;

  /** A huge list or array: a million elements. */
  private static final int MILLION = 1_000_000;

  /** A lower limit a service sets. */
  private static final int LOWER_LIMIT = 2;

  /** The field every test value is logged under. */
  private static final String FIELD = "orderIds";

  /** Returns the JSON line Stratum writes for the value under {@link #FIELD}, with the defaults. */
  private String jsonLine(@Nullable Object value) {
    return SpringBootJsonLines.redactedLine(new MockEnvironment(), FIELD, value);
  }

  /** Returns the JSON line written for a field holding the value as its only content. */
  private String line(String json) {
    return "{\"" + FIELD + "\":" + json + "}";
  }

  @Nested
  @DisplayName("in a list or an array")
  class ListsAndArrays {

    @Test
    @DisplayName("the default keeps ten elements and counts the rest in one marker")
    void keepsTenAndCountsTheRest() {
      assertThat(jsonLine(ElementsTestData.numbers(MANY)))
          .isEqualTo(line("[0,1,2,3,4,5,6,7,8,9,\"...[190 more]\"]"));
    }

    @Test
    @DisplayName("ten elements are kept whole; eleven leave one out")
    void boundaries() {
      assertThat(jsonLine(ElementsTestData.numbers(LIMIT)))
          .isEqualTo(line("[0,1,2,3,4,5,6,7,8,9]"));
      assertThat(jsonLine(ElementsTestData.numbers(LIMIT + 1))).endsWith("9,\"...[1 more]\"]}");
      assertThat(jsonLine(List.of())).isEqualTo(line("[]"));
    }

    @Test
    @DisplayName("every nested list is limited too")
    void nestedListsAreLimited() {
      var nested = List.of(ElementsTestData.numbers(LIMIT + 2), List.of("a"));

      assertThat(jsonLine(nested))
          .isEqualTo(line("[[0,1,2,3,4,5,6,7,8,9,\"...[2 more]\"],[\"a\"]]"));
    }

    @Test
    @DisplayName("an array of objects or of a million primitives is limited")
    void arraysAreLimited() {
      assertThat(jsonLine(new String[] {"a", "b"})).isEqualTo(line("[\"a\",\"b\"]"));
      assertThat(jsonLine(new int[MILLION]))
          .isEqualTo(line("[0,0,0,0,0,0,0,0,0,0,\"...[999990 more]\"]"));
    }

    @Test
    @DisplayName("no element after the limit is read, from a list or any other iterable")
    void noElementAfterTheLimitIsRead() {
      assertThat(jsonLine(ElementsTestData.unreadableAfter(LIMIT, MILLION)))
          .endsWith("9,\"...[999990 more]\"]}");
      assertThat(jsonLine(ElementsTestData.iterableUnreadableAfter(LIMIT)))
          .endsWith("9,\"" + ElementLimit.UNCOUNTED_MARKER + "\"]}");
    }

    @Test
    @DisplayName("a file path is written as text, as Spring Boot writes it")
    void pathStaysText() {
      assertThat(jsonLine(Path.of("/var/orders/a.csv"))).isEqualTo(line("\"/var/orders/a.csv\""));
    }

    @Test
    @DisplayName("a list modified while it is read is replaced, and the line is still written")
    void modifiedListFailsClosed() {
      var list = new ArrayList<>(List.of("a", "b"));
      var iterator = list.iterator();
      iterator.next();
      list.add("c");

      assertThat(jsonLine(ElementsTestData.iteratingWith(iterator)))
          .isEqualTo(line("\"" + Redactor.REDACTION_FAILED + "\""));
    }

    @Test
    @DisplayName("a service's lower limit is used")
    void lowerLimitIsUsed() {
      var redactor = Redactor.from(LoggingTestData.defaults().maxElements(LOWER_LIMIT).build());

      assertThat(redactor.redactAsText(FIELD, ElementsTestData.numbers(LOWER_LIMIT * 2 + 1)))
          .isEqualTo("[0, 1, ...[3 more]]");
    }
  }

  @Nested
  @DisplayName("in a map")
  class Maps {

    @Test
    @DisplayName("the first ten entries are kept and the rest counted in a ... entry")
    void keepsTenEntries() {
      assertThat(jsonLine(ElementsTestData.numberedMap(LIMIT + 2)))
          .endsWith("\"8\":8,\"9\":9,\"" + ElementLimit.MAP_MARKER_KEY + "\":\"[2 more]\"}}");
    }

    @Test
    @DisplayName("a kept key named ... is left out and counted, so the line is still written")
    void markerKeyIsNeverWrittenTwice() {
      var map = new LinkedHashMap<String, Integer>();
      map.put(ElementLimit.MAP_MARKER_KEY, -1);
      IntStream.range(0, LIMIT).forEach(number -> map.put("k" + number, number));

      var kept =
          IntStream.range(0, LIMIT - 1)
              .mapToObj(number -> "\"k" + number + "\":" + number)
              .collect(Collectors.joining(","));

      assertThat(jsonLine(map)).isEqualTo(line("{" + kept + ",\"...\":\"[2 more]\"}"));
    }

    @Test
    @DisplayName("keys with the same text, such as 1 and \"1\", are kept once and counted")
    void sameTextKeysAreKeptOnce() {
      var map = new LinkedHashMap<Object, String>();
      map.put(1, "number");
      map.put("1", "text");

      assertThat(jsonLine(map)).isEqualTo(line("{\"1\":\"text\",\"...\":\"[1 more]\"}"));
    }
  }

  @Nested
  @DisplayName("as text, in the text format or under a sensitive name")
  class AsText {

    @Test
    @DisplayName("lists, maps and arrays are written in Java's layout, nested ones limited too")
    void textIsLimitedAtEveryLevel() {
      var nested = Map.of("ids", ElementsTestData.numbers(LIMIT + 1));

      assertThat(LoggingTestData.DEFAULT_REDACTOR.redactAsText(FIELD, nested))
          .isEqualTo("{ids=[0, 1, 2, 3, 4, 5, 6, 7, 8, 9, ...[1 more]]}");
    }

    @Test
    @DisplayName("a map keeps its first ten entries and ends with ...=[2 more]")
    void textMapIsLimited() {
      assertThat(
              LoggingTestData.DEFAULT_REDACTOR.redactAsText(
                  FIELD, ElementsTestData.numberedMap(LIMIT + 2)))
          .isEqualTo("{0=0, 1=1, 2=2, 3=3, 4=4, 5=5, 6=6, 7=7, 8=8, 9=9, ...=[2 more]}");
    }

    @Test
    @DisplayName("a list that holds itself is replaced, never written")
    void selfHoldingListFailsClosed() {
      var list = new ArrayList<Object>();
      list.add(list);

      assertThat(LoggingTestData.DEFAULT_REDACTOR.redactAsText(FIELD, list))
          .isEqualTo(Redactor.REDACTION_FAILED);
    }

    @Test
    @DisplayName("a million emails under a keep-last-four name are read only up to the limit")
    void keepLastFourReadsOnlyTheLimit() {
      var emails = ElementsTestData.unreadableAfter(LIMIT, MILLION);

      assertThat(jsonLine(emails)).contains("...[999990 more]");
      assertThat(SpringBootJsonLines.redactedLine(new MockEnvironment(), "email", emails))
          .isEqualTo("{\"email\":\"****re]]\"}");
      assertThat(SpringBootJsonLines.redactedLine(new MockEnvironment(), "email", List.of(EMAIL)))
          .isEqualTo("{\"email\":\"****com]\"}");
    }
  }
}
