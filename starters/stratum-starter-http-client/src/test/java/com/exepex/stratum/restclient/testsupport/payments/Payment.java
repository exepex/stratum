package com.exepex.stratum.restclient.testsupport.payments;

/**
 * A payment, as the payments service answers it.
 *
 * @param id the payment's id
 * @param status the payment's status
 */
public record Payment(String id, String status) {}
