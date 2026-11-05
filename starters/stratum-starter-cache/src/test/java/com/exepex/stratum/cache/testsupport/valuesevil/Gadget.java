package com.exepex.stratum.cache.testsupport.valuesevil;

import com.exepex.stratum.cache.testsupport.values.CachedValuesTestData;
import com.exepex.stratum.cache.testsupport.values.Payment;

/**
 * A payment from a package that is not allowed, standing for a gadget class: it records when it is
 * loaded, so a test can prove a refused class is never loaded.
 *
 * @param reference a reference
 */
public record Gadget(String reference) implements Payment {

  static {
    CachedValuesTestData.GADGET_LOADED.set(true);
  }
}
