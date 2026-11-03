package com.exepex.stratum.data;

import com.exepex.stratum.core.api.StratumInternal;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import lombok.experimental.UtilityClass;

/** The property keys and names the data module uses (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class DataConstants {

  /** The module's name, as its switch {@code stratum.data.enabled} spells it. */
  public static final String MODULE = "data";

  /** The first part of every data property. */
  public static final String PREFIX = StratumModuleConstants.PREFIX + "." + MODULE;

  /** The module's on/off property. */
  public static final String ENABLED = StratumModuleConstants.enabledProperty(MODULE);

  /** The part of the error codes that names this module, as in {@code SX-DATA-1001}. */
  public static final String ERROR_CODE_MODULE = "DATA";

  /** Spring Boot's map of Hibernate settings, which takes each Hibernate key after a dot. */
  public static final String JPA_PROPERTIES = "spring.jpa.properties.";

  /** How long a caller waits for a free connection from the pool, in milliseconds. */
  public static final String CONNECTION_TIMEOUT = "spring.datasource.hikari.connection-timeout";

  /** Whether a web request keeps its database connection until the response is written. */
  public static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";

  /** The time limit of every transaction that sets none of its own. */
  public static final String TRANSACTION_TIMEOUT = "spring.transaction.default-timeout";

  /** A class of Spring Batch, whose presence means the service runs batch jobs. */
  public static final String SPRING_BATCH_JOB = "org.springframework.batch.core.job.Job";

  /**
   * The value of Hibernate's schema setting with which it checks the schema and changes nothing.
   */
  public static final String VALIDATE = "validate";

  /** The name of the bean that gives Hibernate's schema setting Stratum's default. */
  public static final String SCHEMA_VALIDATION_DEFAULT = "stratumSchemaValidationDefault";

  /** The name of the bean that wraps every data source in the JDBC boundary. */
  public static final String JDBC_BOUNDARY = "stratumJdbcBoundary";
}
