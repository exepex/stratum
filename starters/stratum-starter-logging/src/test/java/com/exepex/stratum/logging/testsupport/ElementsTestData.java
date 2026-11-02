package com.exepex.stratum.logging.testsupport;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lombok.experimental.UtilityClass;

/** Lists and iterables for the tests of the limit on logged lists, maps and arrays. */
@UtilityClass
public class ElementsTestData {

  /**
   * Returns the numbers from 0, as a list.
   *
   * @param count how many numbers
   * @return the numbers
   */
  public static List<Integer> numbers(int count) {
    return IntStream.range(0, count).boxed().toList();
  }

  /**
   * Returns the numbers from 0, each mapped to itself, in order.
   *
   * @param count how many entries
   * @return the map
   */
  public static Map<Integer, Integer> numberedMap(int count) {
    var map = new LinkedHashMap<Integer, Integer>();
    numbers(count).forEach(number -> map.put(number, number));
    return map;
  }

  /**
   * Returns a list of numbers whose elements after the first few fail when they are read.
   *
   * @param readable how many elements can be read
   * @param size the size the list reports
   * @return the list
   */
  public static List<Integer> unreadableAfter(int readable, int size) {
    return new AbstractList<>() {
      @Override
      public Integer get(int index) {
        if (index >= readable) {
          throw new AssertionError("element " + index + " was read");
        }
        return index;
      }

      @Override
      public int size() {
        return size;
      }
    };
  }

  /**
   * Returns an endless iterable of numbers, not a collection, whose elements after the first few
   * fail when they are read.
   *
   * @param readable how many elements can be read
   * @return the iterable
   */
  public static Iterable<Integer> iterableUnreadableAfter(int readable) {
    return () -> unreadableAfter(readable, Integer.MAX_VALUE).iterator();
  }

  /**
   * Returns an iterable, not a collection, that hands out the given iterator.
   *
   * @param iterator the iterator
   * @param <T> the element type
   * @return the iterable
   */
  public static <T> Iterable<T> iteratingWith(Iterator<T> iterator) {
    return () -> iterator;
  }
}
