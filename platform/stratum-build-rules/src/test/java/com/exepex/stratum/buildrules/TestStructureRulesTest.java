package com.exepex.stratum.buildrules;

import net.sourceforge.pmd.test.SimpleAggregatorTst;
import org.junit.jupiter.api.DisplayName;

/**
 * Runs each rule of {@code stratum/pmd-tests.xml} against the cases in {@code xml/<rule>.xml}: code
 * the rule must report, with the expected lines, and code it must leave alone.
 */
@DisplayName("The test-structure rules report stray tests and inline @ValueSource data, and nothing else")
class TestStructureRulesTest extends SimpleAggregatorTst {

  /** The rule file under test, as stratum-library-parent loads it from this module's jar. */
  private static final String RULESET = "stratum/pmd-tests.xml";

  @Override
  protected void setUp() {
    addRule(RULESET, "TestOutsideNestedGroups");
    addRule(RULESET, "TestDataInValueSource");
  }
}
