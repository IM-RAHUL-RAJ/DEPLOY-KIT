package com.trading.tradeapi;

import com.fasterxml.jackson.databind.exc.InvalidFormatException;
import com.trading.tradeapi.dto.ErrorResponseDto;
import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.exception.AccountNotFoundException;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.exception.DuplicateOrderException;
import com.trading.tradeapi.exception.GlobalExceptionHandler;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.exception.InsufficientFundsException;
import com.trading.tradeapi.exception.InsufficientHoldingsException;
import com.trading.tradeapi.exception.OrderNotFoundException;
import com.trading.tradeapi.exception.UnauthorizedAccountAccessException;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("GlobalExceptionHandler")
class GlobalExceptionHandlerUnitTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static void assertError(ResponseEntity<ErrorResponseDto> response, HttpStatus status, String code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().errorCode()).isEqualTo(code);
    }

    @Nested
    @DisplayName("Domain exceptions")
    class DomainExceptions {

        static Stream<Arguments> exceptions() {
            return Stream.of(
                    Arguments.of(new AccountNotFoundException(), HttpStatus.NOT_FOUND, "ACC-404", "Account not found"),
                    Arguments.of(new AccountNotActiveException(), HttpStatus.FORBIDDEN, "ACC-403", "Account is not active"),
                    Arguments.of(new InstrumentNotFoundException(), HttpStatus.NOT_FOUND, "INS-404", null),
                    Arguments.of(new InsufficientFundsException(), HttpStatus.BAD_REQUEST, "ORD-400", null),
                    Arguments.of(new InsufficientHoldingsException(), HttpStatus.CONFLICT, "ORD-409", null),
                    Arguments.of(new DuplicateOrderException(), HttpStatus.CONFLICT, "ORD-409", "Duplicate order"),
                    Arguments.of(new OrderNotFoundException(), HttpStatus.NOT_FOUND, "ORD-404", null),
                    Arguments.of(new UnauthorizedAccountAccessException(), HttpStatus.FORBIDDEN, "ACC-403", null));
        }

        @ParameterizedTest(name = "{0} answers {1} {2}")
        @MethodSource("exceptions")
        @DisplayName("Each answers its own status and code")
        void answersItsStatusAndCode(DomainException ex, HttpStatus status, String code, String message) {
            ResponseEntity<ErrorResponseDto> response = handler.handleDomainException(ex);

            assertError(response, status, code);
            if (message != null) {
                assertThat(response.getBody().message()).isEqualTo(message);
            }
        }

        @Test
        @DisplayName("A domain exception with no message answers a default one")
        void nullMessageUsesTheDefault() {
            ResponseEntity<ErrorResponseDto> response =
                    handler.handleDomainException(new DomainException("TEST-500", null, HttpStatus.INTERNAL_SERVER_ERROR));

            assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "TEST-500");
            assertThat(response.getBody().message()).isEqualTo("An error occurred");
        }
    }

    @Nested
    @DisplayName("Validation failures answer VAL-422")
    class Validation {

        private ResponseEntity<ErrorResponseDto> bodyErrors(FieldError... errors) {
            MethodArgumentNotValidException ex = mock(MethodArgumentNotValidException.class);
            BindingResult binding = mock(BindingResult.class);
            when(binding.getFieldErrors()).thenReturn(List.of(errors));
            when(ex.getBindingResult()).thenReturn(binding);
            return handler.handleMethodArgumentNotValid(ex);
        }

        private ConstraintViolation<Object> violation(String path, String message) {
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            Path mockPath = mock(Path.class);
            when(mockPath.toString()).thenReturn(path);
            when(violation.getPropertyPath()).thenReturn(mockPath);
            when(violation.getMessage()).thenReturn(message);
            return violation;
        }

        @Test
        @DisplayName("Body field errors name every field, or say Invalid input when there are none")
        void bodyFieldErrors() {
            ResponseEntity<ErrorResponseDto> one =
                    bodyErrors(new FieldError("dto", "price", "Price must be greater than 0"));
            assertError(one, HttpStatus.UNPROCESSABLE_ENTITY, "VAL-422");
            assertThat(one.getBody().message()).contains("price").contains("Price must be greater than 0");

            ResponseEntity<ErrorResponseDto> two = bodyErrors(
                    new FieldError("dto", "price", "Price must be greater than 0"),
                    new FieldError("dto", "quantity", "Quantity must be at least 1"));
            assertThat(two.getBody().message()).contains("price").contains("quantity");

            assertThat(bodyErrors().getBody().message()).isEqualTo("Invalid input");
        }

        @Test
        @DisplayName("Parameter constraint violations name the parameter")
        void constraintViolations() {
            ResponseEntity<ErrorResponseDto> one = handler.handleConstraintViolations(
                    new ConstraintViolationException(Set.of(violation("symbol", "Symbol must not be empty"))));
            assertError(one, HttpStatus.UNPROCESSABLE_ENTITY, "VAL-422");
            assertThat(one.getBody().message()).contains("symbol");

            Set<ConstraintViolation<?>> both = new LinkedHashSet<>();
            both.add(violation("price", "Price must be positive"));
            both.add(violation("quantity", "Quantity must be positive"));
            assertError(handler.handleConstraintViolations(new ConstraintViolationException(both)),
                    HttpStatus.UNPROCESSABLE_ENTITY, "VAL-422");
        }

        @Test
        @DisplayName("An IllegalArgumentException keeps its message, or says Invalid input")
        void illegalArgument() {
            ResponseEntity<ErrorResponseDto> withMessage =
                    handler.handleIllegalArgument(new IllegalArgumentException("Invalid order side"));
            assertError(withMessage, HttpStatus.UNPROCESSABLE_ENTITY, "VAL-422");
            assertThat(withMessage.getBody().message()).isEqualTo("Invalid order side");

            assertThat(handler.handleIllegalArgument(new IllegalArgumentException()).getBody().message())
                    .isEqualTo("Invalid input");
        }
    }

    @Nested
    @DisplayName("Unexpected exceptions answer SRV-500 without leaking internals")
    class Unexpected {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.trading.tradeapi.GlobalExceptionHandlerUnitTest#unexpected")
        void genericExceptions(Exception ex) {
            ResponseEntity<ErrorResponseDto> response = handler.handleGenericException(ex);

            assertError(response, HttpStatus.INTERNAL_SERVER_ERROR, "SRV-500");
            // The raw text names internal classes and reaches a browser: it goes to the log only.
            assertThat(response.getBody().message())
                    .doesNotContain("Database connection error")
                    .doesNotContain("RuntimeException");
        }
    }

    static Stream<Exception> unexpected() {
        return Stream.of(
                new RuntimeException("Database connection error"),
                new RuntimeException(),
                new Exception("Unexpected error"));
    }

    @Nested
    @DisplayName("Caller mistakes are 4xx, not server faults")
    class CallerMistakes {

        @Test
        @DisplayName("Malformed JSON is API-400 and does not echo the framework's text")
        void malformedJson() {
            // Sending "side":"MARKET" used to fall through to the generic handler and
            // answer SRV-500, which reads as a platform outage rather than a bad field.
            InvalidFormatException cause = new InvalidFormatException(
                    null, "MARKET", com.trading.tradeapi.enums.OrderSide.class);
            ResponseEntity<ErrorResponseDto> response =
                    handler.handleUnreadableBody(new HttpMessageNotReadableException("JSON parse error", cause));

            assertError(response, HttpStatus.BAD_REQUEST, "API-400");
            assertThat(response.getBody().message()).doesNotContain("MARKET").doesNotContain("OrderSide");
        }

        @Test
        @DisplayName("An unmapped path is API-404 naming the path")
        void unmappedPath() {
            ResponseEntity<ErrorResponseDto> response =
                    handler.handleNoResource(new NoResourceFoundException(null, "/no/such/route"));

            assertError(response, HttpStatus.NOT_FOUND, "API-404");
            assertThat(response.getBody().message()).contains("/no/such/route");
        }
    }
}
