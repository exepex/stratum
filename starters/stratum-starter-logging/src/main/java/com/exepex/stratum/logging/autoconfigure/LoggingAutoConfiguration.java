package com.exepex.stratum.logging.autoconfigure;

import com.exepex.stratum.core.condition.ConditionalOnStratumModule;
import com.exepex.stratum.core.condition.StratumModuleConstants;
import com.exepex.stratum.logging.LoggingConstants;
import com.exepex.stratum.logging.workflow.WorkflowAspect;
import com.exepex.stratum.logging.workflow.Workflows;
import io.micrometer.observation.ObservationRegistry;
import org.aspectj.weaver.Advice;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Fallback;

/** Creates the logging module's workflow beans, when {@code stratum.logging.enabled=true}. */
@AutoConfiguration
@AutoConfigureOrder(StratumModuleConstants.AUTO_CONFIGURATION_ORDER)
@ConditionalOnStratumModule(LoggingConstants.MODULE)
public class LoggingAutoConfiguration {

  /**
   * Runs workflows; they are timed and traced only when the service has an {@code
   * ObservationRegistry}, as with the observability starter.
   *
   * @param observationRegistry the service's registry, if it has one
   * @return the workflows
   */
  @Bean
  @Fallback
  @ConditionalOnMissingBean
  public Workflows workflows(ObjectProvider<ObservationRegistry> observationRegistry) {
    return new Workflows(observationRegistry.getIfAvailable(() -> ObservationRegistry.NOOP));
  }

  /**
   * Makes {@code @Workflow} work when AspectJ is on the classpath, the same condition Spring Boot
   * uses to apply aspects. A nested class, so the aspect's AspectJ types are only loaded then.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(Advice.class)
  public static class WorkflowAnnotationConfiguration {

    /**
     * Runs each {@code @Workflow} method as its workflow.
     *
     * @param workflows runs the workflows
     * @return the aspect
     */
    @Bean
    @Fallback
    @ConditionalOnMissingBean
    public WorkflowAspect workflowAspect(Workflows workflows) {
      return new WorkflowAspect(workflows);
    }
  }
}
