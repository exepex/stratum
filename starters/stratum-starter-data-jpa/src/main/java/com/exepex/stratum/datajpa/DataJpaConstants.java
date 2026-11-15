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

  /** Spring Boot's key that leaves routing data sources out of the database health check. */
  public static final String IGNORE_ROUTING_HEALTH =
      "management.health.db.ignore-routing-data-sources";

  /** The name of Spring Boot's entity manager factory, which JPA repositories use by default. */
  public static final String ENTITY_MANAGER_FACTORY = "entityManagerFactory";

  /** The name of the bean that maps repositories to named pools. */
  public static final String ROUTING_REGISTRAR = "stratumRoutingRegistrar";

  /** The name of the bean that holds each mapped repository's pool. */
  public static final String REPOSITORY_POOLS = "stratumRepositoryPools";

  /** The name of the bean that adds the pool advice to every JPA repository. */
  public static final String REPOSITORY_ADVICE = "stratumRepositoryPoolAdvice";

  /** The name of the bean that checks the mapped pools' database product and logs the mapping. */
  public static final String ROUTING_CHECK = "stratumRoutingCheck";

  /** The name of the bean that stops startup when open-in-view is on with a mapping. */
  public static final String OPEN_IN_VIEW_CHECK = "stratumOpenInViewCheck";
}
