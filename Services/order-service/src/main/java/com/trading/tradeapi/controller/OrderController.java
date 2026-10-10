package com.trading.tradeapi.controller;

import com.trading.tradeapi.exception.AccountNotActiveException;
import com.trading.tradeapi.dto.OrderResponseDto;
import com.trading.tradeapi.dto.PlaceOrderRequestDto;
import com.trading.tradeapi.security.JwtAuthenticationFilter;
import com.trading.tradeapi.service.OrderService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @PostMapping
    public ResponseEntity<OrderResponseDto> placeOrder(@Valid @RequestBody PlaceOrderRequestDto requestDto,
                                                      HttpServletRequest request) {
        Long authAccountId = (Long) request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);
        if (authAccountId != null && !authAccountId.equals(requestDto.accountId())) {
            throw new AccountNotActiveException();
        }

        OrderResponseDto response = orderService.placeOrder(requestDto);
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<OrderResponseDto> cancelOrder(@PathVariable("id") String id,
                                                        HttpServletRequest request) {
        // Null for an administrator token, which may cancel any order.
        Long authAccountId = (Long) request.getAttribute(JwtAuthenticationFilter.AUTHENTICATED_ACCOUNT_ID_ATTR);
        OrderResponseDto response = orderService.cancelOrder(id, authAccountId);
        return ResponseEntity.ok(response);
    }
}
