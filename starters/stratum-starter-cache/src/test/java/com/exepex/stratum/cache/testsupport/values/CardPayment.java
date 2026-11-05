package com.exepex.stratum.cache.testsupport.values;

/**
 * A card payment, an allowed payment class.
 *
 * @param lastDigits the card's last digits
 */
public record CardPayment(String lastDigits) implements Payment {}
