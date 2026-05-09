package hp.microservice.demo.transaction_service.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Transaction {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "merchant_id", nullable = false)
    private String merchantId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "from_currency", nullable = false, length = 3)
    private String fromCurrency;

    @Column(name = "to_currency", nullable = false, length = 3)
    private String toCurrency;

    @Column(name = "locked_rate", precision = 19, scale = 8)
    private BigDecimal lockedRate;

    @Column(name = "card_network", length = 50)
    private String cardNetwork;

    @Column(length = 2)
    private String country;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    @Builder.Default
    private TransactionStatus status = TransactionStatus.RECEIVED;

    @Enumerated(EnumType.STRING)
    @Column(name = "fraud_verdict", length = 20)
    private FraudVerdict fraudVerdict;

    @Column(name = "fraud_reason")
    private String fraudReason;

    @Column(name = "bank_reference")
    private String bankReference;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    public Transaction withStatus(TransactionStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
        return this;
    }
}
