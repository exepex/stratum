package com.exepex.stratum.logging.workflow;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.MethodClassKey;

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
   * Whether each annotated method returns a {@code Mono} or {@code Flux}, found once per method.
   */
  private final Map<MethodClassKey, Boolean> reactiveMethods = new ConcurrentHashMap<>();

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
    if (reactiveWorkflows != null && returnsReactive(annotatedMethod, reactiveWorkflows)) {
      return reactiveWorkflows.asWorkflow(workflow.value(), annotatedMethod.proceed());
    }
    return workflows.call(workflow.value(), annotatedMethod::proceed);
  }

  /**
   * Returns whether the method that runs returns a {@code Mono} or {@code Flux}. The called method
   * can declare less, such as an interface's {@code Publisher}, so this reads the class's method.
   */
  private boolean returnsReactive(
      ProceedingJoinPoint annotatedMethod, ReactiveWorkflows reactiveWorkflows) {
    var calledMethod = ((MethodSignature) annotatedMethod.getSignature()).getMethod();
    var targetClass = AopUtils.getTargetClass(annotatedMethod.getTarget());
    return reactiveMethods.computeIfAbsent(
        new MethodClassKey(calledMethod, targetClass),
        methodInClass ->
            reactiveWorkflows.isReactive(
                AopUtils.getMostSpecificMethod(calledMethod, targetClass).getReturnType()));
  }
}
