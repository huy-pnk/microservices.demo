package hp.microservice.demo.transaction_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Entity
@Table(name = "routing_rules")
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class RoutingRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "card_network", length = 50)
    private String cardNetwork;

    @Column(length = 2)
    private String country;

    @Column(name = "from_currency", length = 3)
    private String fromCurrency;

    @Column(name = "bank_connector_id", nullable = false, length = 100)
    private String bankConnectorId;

    @Column(nullable = false)
    private int priority;

    @Column(nullable = false)
    private boolean active;
}
