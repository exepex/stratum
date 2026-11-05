package com.exepex.stratum.observability.it;

import com.exepex.stratum.observability.testsupport.ReceivedLoggingContexts;
import jakarta.jms.ConnectionFactory;
import jakarta.jms.MessageListener;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.jms.config.DefaultJmsListenerContainerFactory;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.jms.listener.DefaultMessageListenerContainer;

/**
 * A service with the observability starter that sets up JMS itself, as a service with several
 * brokers does: its own sender, its own listener container and its own listener factory, none of
 * which Spring Boot observes.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
public class ServiceJmsApplication {

  /** The queue its own listener container reads. */
  static final String CONTAINER_QUEUE = "invoices-by-container";

  /** The queue its {@code @JmsListener} reads, through its own listener factory. */
  static final String FACTORY_QUEUE = "invoices-by-factory";

  /** The name of its own listener factory. */
  private static final String INVOICE_LISTENER_FACTORY = "invoiceListenerFactory";

  /** The logging context of each received invoice. */
  private final ReceivedLoggingContexts receivedInvoices = new ReceivedLoggingContexts();

  /**
   * Returns what each receiver saw.
   *
   * @return the received logging contexts
   */
  @Bean
  ReceivedLoggingContexts receivedLoggingContexts() {
    return receivedInvoices;
  }

  /**
   * Sends invoices; declaring it makes Spring Boot's own sender back off.
   *
   * @param connectionFactory the broker's connection factory
   * @return the sender
   */
  @Bean
  JmsTemplate invoiceSender(ConnectionFactory connectionFactory) {
    return new JmsTemplate(connectionFactory);
  }

  /**
   * Reads the container's queue.
   *
   * @param connectionFactory the broker's connection factory
   * @return the listener container
   */
  @Bean
  DefaultMessageListenerContainer invoiceContainer(ConnectionFactory connectionFactory) {
    var container = new DefaultMessageListenerContainer();
    container.setConnectionFactory(connectionFactory);
    container.setDestinationName(CONTAINER_QUEUE);
    container.setMessageListener((MessageListener) message -> receivedInvoices.keepCurrent());
    return container;
  }

  /**
   * Builds the listener containers of {@link #receiveInvoice}, without Spring Boot's configurer.
   * Static, so this class's own listener can use it while this class is being created.
   *
   * @param connectionFactory the broker's connection factory
   * @return the listener factory
   */
  @Bean(INVOICE_LISTENER_FACTORY)
  static DefaultJmsListenerContainerFactory invoiceListenerFactory(
      ConnectionFactory connectionFactory) {
    var factory = new DefaultJmsListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    return factory;
  }

  /**
   * Handles an invoice from the factory's queue.
   *
   * @param invoice the invoice
   */
  @JmsListener(destination = FACTORY_QUEUE, containerFactory = INVOICE_LISTENER_FACTORY)
  void receiveInvoice(String invoice) {
    receivedInvoices.keepCurrent();
  }
}
