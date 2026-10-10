package com.trading.tradeapi.service;

import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.exception.InstrumentNotFoundException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InstrumentService {

    private final InstrumentMapper instrumentMapper;

    public InstrumentService(InstrumentMapper instrumentMapper) {
        this.instrumentMapper = instrumentMapper;
    }

    @Transactional(readOnly = true)
    public InstrumentRecord validateInstrumentExists(String symbol) {
        InstrumentRecord instrument = instrumentMapper.findBySymbol(symbol);
        if (instrument == null) {
            throw new InstrumentNotFoundException();
        }
        return instrument;
    }

    @Transactional(readOnly = true)
    public void validateInstrumentTradeable(InstrumentRecord instrument) {
        if ("DELISTED".equalsIgnoreCase(instrument.getStatus())) {
            throw new InstrumentNotFoundException();
        }
    }

    @Transactional(readOnly = true)
    public InstrumentRecord getInstrumentById(Long instrumentId) {
        return instrumentMapper.findById(instrumentId);
    }
}
