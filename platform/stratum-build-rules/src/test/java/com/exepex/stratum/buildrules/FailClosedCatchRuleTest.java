package com.exepex.stratum.buildrules;

import net.sourceforge.pmd.test.SimpleAggregatorTst;
import org.junit.jupiter.api.DisplayName;

/**
 * Runs {@code AvoidCatchingGenericException} of {@code stratum/pmd-ruleset.xml} against the cases
 * in {@code xml/AvoidCatchingGenericException.xml}: only the logging redactor's fail-closed method
 * and the cache layer's run-once methods may catch {@code RuntimeException}; the same catch
 * anywhere else is reported.
 */
@DisplayName(
    "Only the redactor's fail-closed method and StratumCache's run-once methods may catch"
        + " RuntimeException")
class FailClosedCatchRuleTest extends SimpleAggregatorTst {

  /** The rule file under test, as stratum-parent loads it from this module's jar. */
  private static final String RULESET = "stratum/pmd-ruleset.xml";

  @Override
  protected void setUp() {
    addRule(RULESET, "AvoidCatchingGenericException");
  }
}
