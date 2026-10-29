package com.exepex.stratum.observability.it;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.slf4j.MDC;
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

  /** How long a test waits for an event to arrive. */
  private static final Duration RECEIVE_TIMEOUT = Duration.ofSeconds(30);

  /** Sends events to Kafka. */
  private final KafkaTemplate<Object, Object> kafkaTemplate;

  /** Sends events to the JMS queue. */
  private final JmsTemplate jmsTemplate;

  /** The logging context of each received event, in the order they arrived. */
  private final BlockingQueue<Map<String, String>> receivedLoggingContexts =
      new LinkedBlockingQueue<>();

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
    keepLoggingContext();
  }

  /**
   * Handles an event from the JMS queue.
   *
   * @param event the event
   */
  @JmsListener(destination = DESTINATION)
  void receiveFromJms(String event) {
    keepLoggingContext();
  }

  /**
   * Returns the logging context of the next received event, or {@code null} if none arrives in
   * time.
   *
   * @return the logging context's keys and values
   * @throws InterruptedException if the test is stopped while waiting
   */
  @Nullable Map<String, String> nextReceivedLoggingContext() throws InterruptedException {
    return receivedLoggingContexts.poll(RECEIVE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
  }

  /** Keeps a copy of the logging context the current event is handled in. */
  private void keepLoggingContext() {
    receivedLoggingContexts.add(
        Map.copyOf(Objects.requireNonNullElse(MDC.getCopyOfContextMap(), Map.of())));
  }
}
