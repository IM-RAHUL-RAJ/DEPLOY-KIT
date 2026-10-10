package com.trading.tradeapi.config;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;

/**
 * Where mapper interfaces are registered, rather than on {@code TradeApiApplication}.
 *
 * <p>The reason is the {@code @WebMvcTest} slices. They take their single configuration class
 * from the application, exclude the MyBatis auto-configuration, and replace with
 * {@code @MockitoBean} exactly the mappers the controller under test touches. A mapper package
 * registered directly on the application class is registered anyway, with no
 * {@code SqlSessionFactory} to back it, and every such slice fails on bean creation the moment
 * a new module adds a mapper - for a mapper nothing in that test asked for.
 *
 * <p>As a scanned {@code @Configuration} this is subject to the slice's type filter instead: a
 * web slice does not pick it up, so it sees only the mappers it declares mocks for. The full
 * application still scans it, so nothing changes at run time.
 *
 * <p>Each extension keeps its mapper in its own package and adds that package here. The list is
 * explicit rather than a scan of {@code com.trading.tradeapi}: a broad scan would register every
 * interface under it as a mapper, including the published module interfaces, which are not
 * mappers at all.
 */
@Configuration
@MapperScan({
        "com.trading.tradeapi.mapper",
        "com.trading.tradeapi.preferences.mapper",
        "com.trading.tradeapi.notifications.mapper",
        "com.trading.tradeapi.watchlist.mapper",
        "com.trading.tradeapi.watchlist.alerts.mapper",
        "com.trading.tradeapi.portfolio.mapper",
        "com.trading.tradeapi.marketdata",
        "com.trading.tradeapi.strategy.mapper"
})
public class MyBatisMapperConfig {
}
