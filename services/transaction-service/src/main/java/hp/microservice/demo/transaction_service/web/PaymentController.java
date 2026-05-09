package hp.microservice.demo.transaction_service.web;

import hp.microservice.demo.transaction_service.domain.AuditLog;
import hp.microservice.demo.transaction_service.service.AuditService;
import hp.microservice.demo.transaction_service.service.PaymentService;
import hp.microservice.demo.transaction_service.service.validation.PaymentValidationChain;
import hp.microservice.demo.transaction_service.web.dto.PaymentRequest;
import hp.microservice.demo.transaction_service.web.dto.PaymentResponse;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentValidationChain validationChain;
    private final AuditService auditService;

    public PaymentController(PaymentService paymentService,
                             PaymentValidationChain validationChain,
                             AuditService auditService) {
        this.paymentService = paymentService;
        this.validationChain = validationChain;
        this.auditService = auditService;
    }

    @PostMapping
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<PaymentResponse> submit(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request,
            @AuthenticationPrincipal Jwt jwt) {
        validationChain.validate(request);
        String merchantId = jwt.getSubject();
        var tx = paymentService.submit(request, merchantId, idempotencyKey);
        URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                .path("/{id}").buildAndExpand(tx.getId()).toUri();
        return ResponseEntity.created(location).body(PaymentResponse.from(tx));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<PaymentResponse> getById(
            @PathVariable UUID id,
            @AuthenticationPrincipal Jwt jwt) {
        String merchantId = jwt.getSubject();
        var tx = paymentService.findByIdForMerchant(id, merchantId);
        return ResponseEntity.ok(PaymentResponse.from(tx));
    }

    @GetMapping
    @PreAuthorize("hasRole('MERCHANT')")
    public ResponseEntity<Page<PaymentResponse>> list(
            @AuthenticationPrincipal Jwt jwt,
            @PageableDefault(size = 20, sort = "createdAt") Pageable pageable) {
        String merchantId = jwt.getSubject();
        Page<PaymentResponse> page = paymentService.findByMerchant(merchantId, pageable)
                .map(PaymentResponse::from);
        return ResponseEntity.ok(page);
    }

    @GetMapping("/{id}/audit")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<AuditLog>> getAudit(@PathVariable UUID id) {
        return ResponseEntity.ok(auditService.findByTransaction(id));
    }
}
