package com.exepex.stratum.datajpa;

import com.exepex.stratum.core.api.StratumInternal;
import lombok.experimental.UtilityClass;

/** The property keys and names only the data JPA starter uses (ST-ENG-45). */
@StratumInternal
@UtilityClass
public class DataJpaConstants {

  /** Spring Boot's map of Hibernate settings, which takes each Hibernate key after a dot. */
  public static final String JPA_PROPERTIES = "spring.jpa.properties.";

  /** Whether a web request keeps its database connection until the response is written. */
  public static final String OPEN_IN_VIEW = "spring.jpa.open-in-view";

  /**
   * The value of Hibernate's schema setting with which it checks the schema and changes nothing.
   */
  public static final String VALIDATE = "validate";

  /** The name of the bean that gives Hibernate's schema setting Stratum's default. */
  public static final String SCHEMA_VALIDATION_DEFAULT = "stratumSchemaValidationDefault";
}
