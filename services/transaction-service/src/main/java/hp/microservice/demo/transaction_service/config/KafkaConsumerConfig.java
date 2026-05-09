package hp.microservice.demo.transaction_service.config;

import hp.microservice.demo.transaction_service.event.PaymentResultEvent;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
@EnableKafka
public class KafkaConsumerConfig {

    @Bean
    public NewTopic paymentSubmittedTopic() {
        return TopicBuilder.name("payment.submitted").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentResultTopic() {
        return TopicBuilder.name("payment.result").partitions(3).replicas(1).build();
    }

    @Bean
    public NewTopic paymentDlqTopic() {
        return TopicBuilder.name("payment.dlq").partitions(1).replicas(1).build();
    }

    @Bean
    public DefaultErrorHandler errorHandler(KafkaTemplate<String, Object> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(template,
                (r, e) -> new TopicPartition("payment.dlq", 0));
        ExponentialBackOff backoff = new ExponentialBackOff(1000L, 2.0);
        backoff.setMaxAttempts(3);
        return new DefaultErrorHandler(recoverer, backoff);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, PaymentResultEvent> kafkaListenerContainerFactory(
            ConsumerFactory<String, PaymentResultEvent> consumerFactory,
            DefaultErrorHandler errorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, PaymentResultEvent> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
