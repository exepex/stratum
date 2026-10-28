package com.exepex.stratum.buildrules;

import net.sourceforge.pmd.test.SimpleAggregatorTst;
import org.junit.jupiter.api.DisplayName;

/**
 * Runs each rule of {@code stratum/pmd-lombok.xml} against the cases in {@code xml/<rule>.xml}: code
 * the rule must report, with the expected lines, and code it must leave alone.
 */
@DisplayName("The Lombok rules report hand-written boilerplate and nothing else")
class LombokRulesTest extends SimpleAggregatorTst {

  /** The rule file under test, as stratum-library-parent loads it from this module's jar. */
  private static final String RULESET = "stratum/pmd-lombok.xml";

  @Override
  protected void setUp() {
    addRule(RULESET, "UseLombokGetter");
    addRule(RULESET, "UseLombokSetter");
    addRule(RULESET, "UseLombokConstructor");
    addRule(RULESET, "UseLombokEqualsAndHashCode");
    addRule(RULESET, "UseLombokToString");
    addRule(RULESET, "UseLombokBuilder");
    addRule(RULESET, "UseLombokLogger");
  }
}
