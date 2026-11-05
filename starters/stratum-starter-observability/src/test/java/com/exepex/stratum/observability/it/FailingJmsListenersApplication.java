package com.exepex.stratum.observability.it;

import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageListener;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * A service with the observability starter whose JMS listeners fail: one {@code @JmsListener} on
 * Spring Boot's own listener factory and one listener container bean with a plain listener, beside
 * a listener that succeeds.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class FailingJmsListenersApplication {

  /** The queue of the {@code @JmsListener} that fails. */
  static final String FAILING_ANNOTATED_QUEUE = "refunds-annotated";

  /** The queue of the listener container bean that fails. */
  static final String FAILING_CONTAINER_QUEUE = "refunds-container";

  /** The queue of the listener that succeeds. */
  static final String SUCCEEDING_QUEUE = "refunds-audit";

  /**
   * Fails on every refund, as a listener whose database is down would.
   *
   * @param refund the refund
   */
  @JmsListener(destination = FAILING_ANNOTATED_QUEUE)
  void storeRefund(String refund) {
    throw new IllegalStateException("refund store unavailable");
  }

  /**
   * Accepts every refund.
   *
   * @param refund the refund
   */
  @JmsListener(destination = SUCCEEDING_QUEUE)
  void auditRefund(String refund) {
    // Succeeds: the message is handled without error.
  }

  /**
   * Reads the container's queue with a plain listener that fails on every message.
   *
   * @param connectionFactory the broker's connection factory
   * @return the listener container
   */
  @Bean
  DefaultMessageListenerContainer refundContainer(ConnectionFactory connectionFactory) {
    var container = new DefaultMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setDestinationName(FAILING_CONTAINER_QUEUE);
    container.setMessageListener((MessageListener) message -> rejectRefund());
    return container;
  }

  /** Fails on every message. */
  private void rejectRefund() {
    throw new IllegalStateException("refund rejected");
  }
}
