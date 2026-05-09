package hp.microservice.demo.transaction_service.repository;

import hp.microservice.demo.transaction_service.domain.RoutingRule;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface RoutingRulesRepository extends JpaRepository<RoutingRule, Long> {

    @Query("""
            SELECT r FROM RoutingRule r
            WHERE r.active = true
              AND (r.cardNetwork = :cardNetwork OR r.cardNetwork IS NULL)
              AND (r.country = :country OR r.country IS NULL)
              AND (r.fromCurrency = :fromCurrency OR r.fromCurrency IS NULL)
            ORDER BY r.priority DESC
            """)
    List<RoutingRule> findTopMatches(
            @Param("cardNetwork") String cardNetwork,
            @Param("country") String country,
            @Param("fromCurrency") String fromCurrency,
            Pageable pageable
    );

    default Optional<RoutingRule> findBestMatch(String cardNetwork, String country, String fromCurrency) {
        List<RoutingRule> results = findTopMatches(cardNetwork, country, fromCurrency, Pageable.ofSize(1));
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }
}
