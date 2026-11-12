package com.exepex.stratum.restclient.testsupport.errors;

import java.util.List;

/**
 * An order as the orders service sends it, a record with a list of records, which Jackson 2 reads
 * through its bean deserializers.
 *
 * @param id the order's ID
 * @param lines the order's lines
 */
public record Order(String id, List<Line> lines) {

  /**
   * One line of an order.
   *
   * @param sku the item's stock-keeping unit
   * @param quantity how many items
   */
  public record Line(String sku, int quantity) {}
}
