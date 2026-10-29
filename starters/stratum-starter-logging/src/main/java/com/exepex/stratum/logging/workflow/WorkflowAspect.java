package com.exepex.stratum.logging.workflow;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.jspecify.annotations.Nullable;

/** Runs each method annotated with {@link Workflow @Workflow} as that workflow. */
@Aspect
@RequiredArgsConstructor
public class WorkflowAspect {

  /** Runs the workflows. */
  private final Workflows workflows;

  /**
   * Runs the annotated method as its workflow.
   *
   * @param annotatedMethod the call to the annotated method
   * @param workflow the method's annotation
   * @return what the method returns
   * @throws Throwable what the method throws, unchanged
   */
  @Around("@annotation(workflow)")
  public @Nullable Object runAsWorkflow(ProceedingJoinPoint annotatedMethod, Workflow workflow)
      throws Throwable {
    return workflows.call(workflow.value(), annotatedMethod::proceed);
  }
}
