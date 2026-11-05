package com.exepex.stratum.cache.testsupport.values;

/**
 * An order, as a team's cached method returns it.
 *
 * @param id the order's identifier
 * @param quantity how many items
 */
public record Order(String id, int quantity) {}
