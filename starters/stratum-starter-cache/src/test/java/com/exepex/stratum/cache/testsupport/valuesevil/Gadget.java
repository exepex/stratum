package com.exepex.stratum.cache.testsupport.valuesevil;

import com.exepex.stratum.cache.testsupport.values.Payment;

/**
 * A payment from a package that is not allowed, standing for a gadget class, whose package name
 * starts like the allowed one.
 *
 * @param reference a reference
 */
public record Gadget(String reference) implements Payment {}
