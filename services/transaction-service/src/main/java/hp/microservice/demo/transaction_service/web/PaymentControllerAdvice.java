package hp.microservice.demo.transaction_service.web;

import hp.microservice.demo.transaction_service.exception.FraudRejectedException;
import hp.microservice.demo.transaction_service.exception.PaymentValidationException;
import hp.microservice.demo.transaction_service.exception.TransactionNotFoundException;
import hp.microservice.demo.transaction_service.repository.TransactionReadRepository;
import hp.microservice.demo.transaction_service.web.dto.PaymentResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

import java.net.URI;
import java.util.List;

@RestControllerAdvice
public class PaymentControllerAdvice {

    private static final Logger log = LoggerFactory.getLogger(PaymentControllerAdvice.class);

    private final TransactionReadRepository transactions;

    public PaymentControllerAdvice(TransactionReadRepository transactions) {
        this.transactions = transactions;
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<PaymentResponse> handleDuplicate(DataIntegrityViolationException ex,
                                                            HttpServletRequest request) {
        String idempotencyKey = request.getHeader("Idempotency-Key");
        if (idempotencyKey != null) {
            return transactions.findByIdempotencyKey(idempotencyKey)
                    .map(tx -> ResponseEntity.ok(PaymentResponse.from(tx)))
                    .orElseGet(() -> ResponseEntity.internalServerError().build());
        }
        return ResponseEntity.internalServerError().build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleMethodArgumentNotValid(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation Failed");
        problem.setDetail(String.join("; ", errors));
        problem.setProperty("errors", errors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ProblemDetail> handleValidation(ConstraintViolationException ex, WebRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Validation Failed");
        problem.setDetail(ex.getMessage());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(PaymentValidationException.class)
    public ResponseEntity<ProblemDetail> handlePaymentValidation(PaymentValidationException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Payment Validation Failed");
        problem.setDetail(String.join("; ", ex.getErrors()));
        problem.setProperty("errors", ex.getErrors());
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ProblemDetail> handleAccess(AccessDeniedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Access Denied");
        problem.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(TransactionNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(TransactionNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
        problem.setTitle("Transaction Not Found");
        problem.setDetail(ex.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(FraudRejectedException.class)
    public ResponseEntity<ProblemDetail> handleFraudRejected(FraudRejectedException ex) {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setTitle("Payment Rejected");
        problem.setDetail(ex.getMessage());
        problem.setProperty("fraudReason", ex.getFraudReason());
        problem.setType(URI.create("urn:problem:fraud-rejected"));
        return ResponseEntity.unprocessableEntity().body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex) {
        log.error("Unhandled exception in PaymentController: {}", ex.toString(), ex);
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
        problem.setTitle("Internal Server Error");
        problem.setDetail("An unexpected error occurred");
        return ResponseEntity.internalServerError().body(problem);
    }
}
