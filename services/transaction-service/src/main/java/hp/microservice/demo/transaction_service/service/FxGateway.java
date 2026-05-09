package hp.microservice.demo.transaction_service.service;

import hp.microservice.demo.transaction_service.domain.FxRateRequest;
import hp.microservice.demo.transaction_service.domain.FxRateResult;
import hp.microservice.demo.transaction_service.exception.FxServiceUnavailableException;

public interface FxGateway {

    FxRateResult lockRate(FxRateRequest request) throws FxServiceUnavailableException;
}
