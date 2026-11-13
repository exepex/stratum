package com.exepex.stratum.jdbc.autoconfigure;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.data.DataConstants;
import com.exepex.stratum.data.autoconfigure.FlywayBoundaryConfiguration;
import com.exepex.stratum.data.autoconfigure.JdbcBoundaryConfiguration;
import com.exepex.stratum.data.autoconfigure.LiquibaseBoundaryConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.context.annotation.Import;

/**
 * Puts every data source of a service that uses plain JDBC, and the connections Spring Boot builds
 * for Flyway and Liquibase, behind the JDBC boundary of {@code stratum-data}, unless {@code
 * stratum.data.enabled=false}. A service with the data JPA starter too gets each bean once.
 */
@StratumInternal
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(DataConstants.MODULE)
@Import({
  JdbcBoundaryConfiguration.class,
  FlywayBoundaryConfiguration.class,
  LiquibaseBoundaryConfiguration.class
})
public final class JdbcStarterAutoConfiguration {}
