package com.exepex.stratum.observability.it;

import com.exepex.stratum.observability.testsupport.ReceivedLoggingContexts;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Sends order events over Kafka and JMS, as a service would, and keeps the logging context that
 * each received event was handled in.
 */
@RequiredArgsConstructor
public class OrderEvents {

  /** The Kafka topic and the JMS queue the events travel on. */
  static final String DESTINATION = "order-events";

  /** Sends events to Kafka. */
  private final KafkaTemplate<Object, Object> kafkaTemplate;

  /** Sends events to the JMS queue. */
  private final JmsTemplate jmsTemplate;

  /** The logging context of each received event. */
  @Getter
  private final ReceivedLoggingContexts receivedLoggingContexts = new ReceivedLoggingContexts();

  /**
   * Sends an event to Kafka and waits until the broker has it.
   *
   * @param event the event
   */
  void sendToKafka(String event) {
    kafkaTemplate.send(DESTINATION, event).join();
  }

  /**
   * Sends an event to the JMS queue.
   *
   * @param event the event
   */
  void sendToJms(String event) {
    jmsTemplate.convertAndSend(DESTINATION, event);
  }

  /**
   * Handles an event from Kafka.
   *
   * @param event the event
   */
  @KafkaListener(topics = DESTINATION, groupId = DESTINATION)
  void receiveFromKafka(String event) {
    receivedLoggingContexts.keepCurrent();
  }

  /**
   * Handles an event from the JMS queue.
   *
   * @param event the event
   */
  @JmsListener(destination = DESTINATION)
  void receiveFromJms(String event) {
    receivedLoggingContexts.keepCurrent();
  }
}
