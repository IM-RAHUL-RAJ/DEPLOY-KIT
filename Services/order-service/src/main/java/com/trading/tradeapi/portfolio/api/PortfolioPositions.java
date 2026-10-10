package com.trading.tradeapi.portfolio.api;

import com.trading.tradeapi.portfolio.dto.PricedPositionDto;

import java.util.List;

/**
 * What the portfolio module publishes to the rest of the service: an account's open
 * holdings, priced. Other modules (trade advice) depend on this interface, never on
 * the portfolio module's service, mapper or tables.
 */
public interface PortfolioPositions {

    /** Open holdings for the account, each priced from the latest quote where one exists. */
    List<PricedPositionDto> pricedPositions(long accountId);
}
