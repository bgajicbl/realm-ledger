package io.realmledger.adapter.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Turns domain and validation failures into the uniform {@link ApiError} body.
 *
 * <p>Domain constructors throw {@link IllegalArgumentException} when an invariant is
 * violated (a negative amount, a blank idempotency key). That is a bad request, not a
 * server fault, so it must not fall through to the default 500 handler.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> onIllegalArgument(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", e.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> onValidationFailure(MethodArgumentNotValidException e) {
        String message = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("Request body failed validation");
        return ResponseEntity.badRequest().body(ApiError.of("INVALID_REQUEST", message));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiError> onIllegalState(IllegalStateException e) {
        // A broken ledger invariant is a real fault: answer 500 and let it page someone.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiError.of("LEDGER_INVARIANT_VIOLATED", e.getMessage()));
    }
}
