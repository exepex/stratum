package com.exepex.stratum.cache.testsupport.values;

/**
 * The order after a release added a field, which old entries do not have.
 *
 * @param id the order's identifier
 * @param quantity how many items
 * @param priority the added field
 */
public record OrderWithPriority(String id, int quantity, int priority) {}
