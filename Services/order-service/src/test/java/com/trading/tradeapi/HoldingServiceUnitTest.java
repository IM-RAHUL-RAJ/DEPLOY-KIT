package com.trading.tradeapi;

import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.mapper.HoldingMapper;
import com.trading.tradeapi.service.HoldingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class HoldingServiceUnitTest {

    @Mock
    private HoldingMapper holdingMapper;

    private HoldingService holdingService;

    @BeforeEach
    public void setUp() {
        holdingService = new HoldingService(holdingMapper);
    }

    @Test
    public void getHoldingsByAccountIdSuccessReturnsEmptyList() {
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(new ArrayList<>());

        List<PositionResponseDto> result = holdingService.getHoldingsByAccountId(1L);

        assertThat(result).isNotNull().isEmpty();
        verify(holdingMapper).findHoldingsByAccountId(1L);
    }

    @Test
    public void getHoldingsByAccountIdSuccessReturnsSinglePosition() {
        List<PositionResponseDto> mockHoldings = List.of(
                new PositionResponseDto(1L, "AAPL", 100L, new BigDecimal("150.00"))
        );
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(mockHoldings);

        List<PositionResponseDto> result = holdingService.getHoldingsByAccountId(1L);

        assertThat(result).isNotNull().hasSize(1);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(0).quantity()).isEqualTo(100L);
        assertThat(result.get(0).averageCost()).isEqualTo(new BigDecimal("150.00"));
        verify(holdingMapper).findHoldingsByAccountId(1L);
    }

    @Test
    public void getHoldingsByAccountIdSuccessReturnsMultiplePositions() {
        List<PositionResponseDto> mockHoldings = List.of(
                new PositionResponseDto(1L, "AAPL", 100L, new BigDecimal("150.00")),
                new PositionResponseDto(1L, "GOOGL", 50L, new BigDecimal("2800.00")),
                new PositionResponseDto(1L, "MSFT", 75L, new BigDecimal("300.00"))
        );
        when(holdingMapper.findHoldingsByAccountId(2L)).thenReturn(mockHoldings);

        List<PositionResponseDto> result = holdingService.getHoldingsByAccountId(2L);

        assertThat(result).isNotNull().hasSize(3);
        assertThat(result.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result.get(1).symbol()).isEqualTo("GOOGL");
        assertThat(result.get(2).symbol()).isEqualTo("MSFT");
        verify(holdingMapper).findHoldingsByAccountId(2L);
    }

    @Test
    public void getHoldingsByAccountIdDifferentAccountsReturnDifferentHoldings() {
        List<PositionResponseDto> account1Holdings = List.of(
                new PositionResponseDto(1L, "AAPL", 100L, new BigDecimal("150.00"))
        );
        List<PositionResponseDto> account2Holdings = List.of(
                new PositionResponseDto(2L, "GOOGL", 50L, new BigDecimal("2800.00"))
        );
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(account1Holdings);
        when(holdingMapper.findHoldingsByAccountId(2L)).thenReturn(account2Holdings);

        List<PositionResponseDto> result1 = holdingService.getHoldingsByAccountId(1L);
        List<PositionResponseDto> result2 = holdingService.getHoldingsByAccountId(2L);

        assertThat(result1).hasSize(1);
        assertThat(result1.get(0).symbol()).isEqualTo("AAPL");
        assertThat(result2).hasSize(1);
        assertThat(result2.get(0).symbol()).isEqualTo("GOOGL");
    }

    @Test
    public void getHoldingsByAccountIdCallsMapperWithCorrectAccountId() {
        when(holdingMapper.findHoldingsByAccountId(123L)).thenReturn(new ArrayList<>());

        holdingService.getHoldingsByAccountId(123L);

        verify(holdingMapper).findHoldingsByAccountId(123L);
    }

    @Test
    public void getHoldingsByAccountIdPreservesHoldingOrder() {
        List<PositionResponseDto> mockHoldings = List.of(
                new PositionResponseDto(1L, "AAPL", 10L, new BigDecimal("100.00")),
                new PositionResponseDto(1L, "GOOGL", 20L, new BigDecimal("200.00")),
                new PositionResponseDto(1L, "MSFT", 30L, new BigDecimal("300.00"))
        );
        when(holdingMapper.findHoldingsByAccountId(1L)).thenReturn(mockHoldings);

        List<PositionResponseDto> result = holdingService.getHoldingsByAccountId(1L);

        assertThat(result).containsExactly(
                new PositionResponseDto(1L, "AAPL", 10L, new BigDecimal("100.00")),
                new PositionResponseDto(1L, "GOOGL", 20L, new BigDecimal("200.00")),
                new PositionResponseDto(1L, "MSFT", 30L, new BigDecimal("300.00"))
        );
    }
}
