package com.exepex.stratum.observability.it;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/** A small service with the observability starter that sends and receives order events. */
@SpringBootConfiguration
@EnableAutoConfiguration
public class OrderEventsApplication {

  /**
   * Sends and receives the order events.
   *
   * @param kafkaTemplate Spring Boot's Kafka sender
   * @param jmsTemplate Spring Boot's JMS sender
   * @return the order events
   */
  @Bean
  OrderEvents orderEvents(KafkaTemplate<Object, Object> kafkaTemplate, JmsTemplate jmsTemplate) {
    return new OrderEvents(kafkaTemplate, jmsTemplate);
  }
}
