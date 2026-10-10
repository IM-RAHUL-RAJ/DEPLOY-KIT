package com.trading.tradeapi.service;

import com.trading.tradeapi.dto.PositionResponseDto;
import com.trading.tradeapi.mapper.HoldingMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class HoldingService {

    private final HoldingMapper holdingMapper;

    public HoldingService(HoldingMapper holdingMapper) {
        this.holdingMapper = holdingMapper;
    }

    @Transactional(readOnly = true)
    public List<PositionResponseDto> getHoldingsByAccountId(Long accountId) {
        return holdingMapper.findHoldingsByAccountId(accountId);
    }
}
