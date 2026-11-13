package com.exepex.stratum.data.testsupport;

import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.autoconfigure.FlywayBoundaryConfiguration;
import com.exepex.stratum.data.autoconfigure.JdbcBoundaryConfiguration;
import com.exepex.stratum.data.autoconfigure.LiquibaseBoundaryConfiguration;
import lombok.experimental.UtilityClass;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Import;

/**
 * Auto-configurations that import one of the library's configurations each, with the order every
 * Stratum auto-configuration has, as a data starter imports them. They let the library's tests
 * check the order and the back-off a service sees.
 */
@UtilityClass
public class DataStarterStandIns {

  /** Imports the JDBC boundary, as a data starter does. */
  @AutoConfiguration
  @AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
  @Import(JdbcBoundaryConfiguration.class)
  public static class JdbcBoundaryStandIn {}

  /** Imports Flyway's boundary, as a data starter does. */
  @AutoConfiguration
  @AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
  @Import(FlywayBoundaryConfiguration.class)
  public static class FlywayBoundaryStandIn {}

  /** Imports Liquibase's boundary, as a data starter does. */
  @AutoConfiguration
  @AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
  @Import(LiquibaseBoundaryConfiguration.class)
  public static class LiquibaseBoundaryStandIn {}
}
