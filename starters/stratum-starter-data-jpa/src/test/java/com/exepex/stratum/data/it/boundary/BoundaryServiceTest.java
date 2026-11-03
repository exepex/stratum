package com.exepex.stratum.data.it.boundary;

import com.exepex.stratum.test.PostgresContainerConfiguration;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;

/**
 * Starts the boundary service on PostgreSQL with its schema, and captures the log. Test classes
 * with this annotation share one application and one database.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(
    classes = BoundaryApplication.class,
    properties = {
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:boundary-schema.sql"
    })
@Import(PostgresContainerConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
public @interface BoundaryServiceTest {}
