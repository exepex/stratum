package com.exepex.stratum.cache.testsupport.values;

/**
 * An order whose payment names its class in the JSON.
 *
 * @param id the order's identifier
 * @param payment the payment, whose class the JSON names
 */
public record PaidOrder(String id, Payment payment) {}
