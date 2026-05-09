package hp.microservice.demo.transaction_service.service;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class KafkaEventPublisher implements EventPublisher {

    private final KafkaTemplate<String, Object> kafka;

    public KafkaEventPublisher(KafkaTemplate<String, Object> kafka) {
        this.kafka = kafka;
    }

    @Override
    public void publish(String topic, String key, Object payload) {
        try {
            kafka.send(topic, key, payload).get(5, TimeUnit.SECONDS);
        } catch (Exception ex) {
            throw new RuntimeException("Kafka publish failed", ex);
        }
    }
}
