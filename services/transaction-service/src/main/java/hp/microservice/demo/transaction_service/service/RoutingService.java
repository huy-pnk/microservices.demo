package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.RoutingRule;
import hp.microservice.demo.transaction_service.repository.RoutingRulesRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoutingService {

    private final RoutingRulesRepository routingRulesRepository;

    public RoutingService(RoutingRulesRepository routingRulesRepository) {
        this.routingRulesRepository = routingRulesRepository;
    }

    @Transactional(readOnly = true)
    public String resolveConnector(String cardNetwork, String country, String fromCurrency) {
        return routingRulesRepository
                .findBestMatch(cardNetwork, country, fromCurrency)
                .map(RoutingRule::getBankConnectorId)
                .orElse("default-connector");
    }
}
