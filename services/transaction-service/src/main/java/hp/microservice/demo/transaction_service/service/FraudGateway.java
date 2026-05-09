package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.FraudRequest;
import hp.microservice.demo.transaction_service.domain.FraudResult;
import hp.microservice.demo.transaction_service.exception.FraudServiceUnavailableException;

public interface FraudGateway {

    FraudResult evaluate(FraudRequest request) throws FraudServiceUnavailableException;
}
