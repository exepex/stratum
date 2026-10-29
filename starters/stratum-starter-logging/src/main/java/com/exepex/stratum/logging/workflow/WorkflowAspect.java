package com.exepex.stratum.logging.workflow;

import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;

/** Runs each method annotated with {@link Workflow @Workflow} as that workflow. */
@Aspect
@RequiredArgsConstructor
public class WorkflowAspect {

  /** Runs the workflows. */
  private final Workflows workflows;

  /**
   * Runs the workflows of methods that return a {@code Mono} or {@code Flux}; without Reactor,
   * null.
   */
  private final @Nullable ReactiveWorkflows reactiveWorkflows;

  /**
   * Runs the annotated method as its workflow. A method that returns a {@code Mono} or {@code Flux}
   * only builds the work, so the workflow runs each time the result is subscribed.
   *
   * @param annotatedMethod the call to the annotated method
   * @param workflow the method's annotation
   * @return what the method returns
   * @throws Throwable what the method throws, unchanged
   */
  @Around("@annotation(workflow)")
  public @Nullable Object runAsWorkflow(ProceedingJoinPoint annotatedMethod, Workflow workflow)
      throws Throwable {
    var returnType = ((MethodSignature) annotatedMethod.getSignature()).getReturnType();
    if (reactiveWorkflows != null && reactiveWorkflows.isReactive(returnType)) {
      return reactiveWorkflows.asWorkflow(workflow.value(), annotatedMethod.proceed());
    }
    return workflows.call(workflow.value(), annotatedMethod::proceed);
  }
}
