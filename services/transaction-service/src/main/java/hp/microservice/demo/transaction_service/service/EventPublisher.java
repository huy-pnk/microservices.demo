package hp.microservice.demo.transaction_service.service;

public interface EventPublisher {

    void publish(String topic, String key, Object payload);
}
