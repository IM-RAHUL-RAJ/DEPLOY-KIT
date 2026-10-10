package com.trading.tradeapi.watchlist.service;

import com.trading.tradeapi.watchlist.alerts.service.AlertService;
import com.trading.tradeapi.marketdata.MarketDataService;
import com.trading.tradeapi.marketdata.MarketQuote;
import static com.trading.tradeapi.marketdata.TestQuotes.*;
import com.trading.tradeapi.watchlist.dto.AddWatchlistInstrumentRequest;
import com.trading.tradeapi.watchlist.dto.CreateWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.RenameWatchlistRequest;
import com.trading.tradeapi.watchlist.dto.WatchlistDto;
import com.trading.tradeapi.entity.InstrumentRecord;
import com.trading.tradeapi.watchlist.entity.WatchlistInstrumentRecord;
import com.trading.tradeapi.watchlist.entity.WatchlistRecord;
import com.trading.tradeapi.exception.DomainException;
import com.trading.tradeapi.mapper.InstrumentMapper;
import com.trading.tradeapi.mapper.UserMapper;
import com.trading.tradeapi.watchlist.mapper.WatchlistMapper;
import com.trading.tradeapi.watchlist.service.WatchlistService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The named, multiple watchlist rules in isolation: ownership, naming, the move, and the
 * difference from the legacy single-list path (DomainException instead of IllegalArgumentException).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Watchlist service")
class WatchlistServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-05T00:00:00Z");

    @Mock private WatchlistMapper watchlistMapper;
    @Mock private InstrumentMapper instrumentMapper;
    @Mock private UserMapper userMapper;
    @Mock private MarketDataService fauxnanceClient;
    @Mock private AlertService alertService;

    private WatchlistService service;

    @BeforeEach
    void setUp() {
        service = new WatchlistService(watchlistMapper, instrumentMapper, userMapper, fauxnanceClient, alertService);
    }

    private void clientUser() {
        when(userMapper.findUserByUuid("uuid-7")).thenReturn(Map.of("user_type", "client"));
    }

    private static WatchlistRecord list(long id, String name) {
        return new WatchlistRecord(id, 7L, name, NOW, NOW);
    }

    private static BigDecimal big(String value) {
        return new BigDecimal(value);
    }

    @Nested
    @DisplayName("Lists")
    class Lists {

        @Test
        @DisplayName("an administrator is ACC-403, never a list")
        void adminIsRejected() {
            when(userMapper.findUserByUuid("uuid-admin")).thenReturn(Map.of("user_type", "admin"));

            assertThatThrownBy(() -> service.listWatchlists(7L, "uuid-admin"))
                    .isInstanceOf(DomainException.class)
                    .extracting("code").isEqualTo("ACC-403");
            verify(watchlistMapper, never()).findAllByAccountId(any());
        }

        @Test
        @DisplayName("list returns every owned list, oldest first, with its name")
        void listReturnsNamedLists() {
            clientUser();
            when(watchlistMapper.findAllByAccountId(7L)).thenReturn(List.of(list(2L, "Tech leaders"), list(1L, "My Watchlist")));
            when(watchlistMapper.findInstrumentsByWatchlistId(any())).thenReturn(List.of());

            List<WatchlistDto> lists = service.listWatchlists(7L, "uuid-7");

            assertThat(lists).hasSize(2);
            assertThat(lists.get(0).name()).isEqualTo("Tech leaders");
            assertThat(lists.get(1).name()).isEqualTo("My Watchlist");
        }

        @Test
        @DisplayName("create trims the name and stores it, then returns the created list")
        void createNormalizesAndReturns() {
            clientUser();
            when(watchlistMapper.findAllByAccountId(7L)).thenReturn(List.of());
            when(watchlistMapper.findByAccountIdAndName(7L, "Earnings watch")).thenReturn(list(5L, "Earnings watch"));
            when(watchlistMapper.findInstrumentsByWatchlistId(5L)).thenReturn(List.of());

            WatchlistDto dto = service.createWatchlist(7L, "uuid-7", new CreateWatchlistRequest("  Earnings watch  "));

            assertThat(dto.name()).isEqualTo("Earnings watch");
            verify(watchlistMapper).createNamedWatchlist(7L, "Earnings watch");
        }

        @Test
        @DisplayName("create refuses a blank name, a too-long name and a taken name (VAL-422)")
        void createRejectsBadNames() {
            clientUser();
            when(userMapper.findUserByUuid("uuid-7")).thenReturn(Map.of("user_type", "client"));

            assertThatThrownBy(() -> service.createWatchlist(7L, "uuid-7", new CreateWatchlistRequest("   ")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
            assertThatThrownBy(() -> service.createWatchlist(7L, "uuid-7", new CreateWatchlistRequest("x".repeat(41))))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");

            when(watchlistMapper.findAllByAccountId(7L)).thenReturn(List.of(list(1L, "Tech")));
            assertThatThrownBy(() -> service.createWatchlist(7L, "uuid-7", new CreateWatchlistRequest("tech")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
            verify(watchlistMapper, never()).createNamedWatchlist(any(), any());
        }

        @Test
        @DisplayName("rename only touches the account's own list and keeps names unique")
        void renameOwnedOnly() {
            clientUser();
            assertThatThrownBy(() -> service.renameWatchlist(7L, "uuid-7", 99L, new RenameWatchlistRequest("New")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");

            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list(1L, "Old"));
            when(watchlistMapper.findAllByAccountId(7L)).thenReturn(List.of(list(1L, "Old"), list(2L, "New")));
            assertThatThrownBy(() -> service.renameWatchlist(7L, "uuid-7", 1L, new RenameWatchlistRequest("new")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
        }

        @Test
        @DisplayName("a successful rename returns the renamed list")
        void renameSucceeds() {
            clientUser();
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list(1L, "Old"));
            when(watchlistMapper.findAllByAccountId(7L)).thenReturn(List.of(list(1L, "Old")));
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list(1L, "Dividends"));
            when(watchlistMapper.findInstrumentsByWatchlistId(1L)).thenReturn(List.of());

            WatchlistDto dto = service.renameWatchlist(7L, "uuid-7", 1L, new RenameWatchlistRequest("Dividends"));

            assertThat(dto.name()).isEqualTo("Dividends");
            verify(watchlistMapper).renameWatchlist(1L, "Dividends");
        }

        @Test
        @DisplayName("the last watchlist cannot be deleted")
        void cannotDeleteLastList() {
            clientUser();
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list(1L, "Only"));
            when(watchlistMapper.countByAccountId(7L)).thenReturn(1);

            assertThatThrownBy(() -> service.deleteWatchlist(7L, "uuid-7", 1L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
            verify(watchlistMapper, never()).deleteWatchlist(any());
        }

        @Test
        @DisplayName("a foreign watchlist cannot be deleted")
        void cannotDeleteForeignList() {
            clientUser();
            assertThatThrownBy(() -> service.deleteWatchlist(7L, "uuid-7", 9L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");
        }

        @Test
        @DisplayName("delete removes an owned list once another remains")
        void deleteSucceeds() {
            clientUser();
            when(watchlistMapper.findByIdAndAccountId(2L, 7L)).thenReturn(list(2L, "Spare"));
            when(watchlistMapper.countByAccountId(7L)).thenReturn(2);

            service.deleteWatchlist(7L, "uuid-7", 2L);

            verify(watchlistMapper).deleteWatchlist(2L);
        }
    }

    @Nested
    @DisplayName("Instruments in a list")
    class InstrumentsInAList {

        @Test
        @DisplayName("adding to an unknown list is ACC-404; an unknown symbol is VAL-422")
        void addToListValidates() {
            clientUser();
            assertThatThrownBy(() -> service.addInstrumentToList(7L, "uuid-7", 99L,
                            new AddWatchlistInstrumentRequest("AAPL")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");

            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list(1L, "Tech"));
            when(fauxnanceClient.quote("ZZZ")).thenReturn(missing());
            assertThatThrownBy(() -> service.addInstrumentToList(7L, "uuid-7", 1L,
                            new AddWatchlistInstrumentRequest("  zzz ")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
            verify(watchlistMapper, never()).addInstrument(any(), any());
        }

        @Test
        @DisplayName("a symbol already on the list, or a full list, is VAL-422")
        void addToListEnforcesCapAndUniqueness() {
            clientUser();
            WatchlistRecord list = list(1L, "Tech");
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list);
            when(fauxnanceClient.quote("AAPL")).thenReturn(lookup(q("AAPL", big("238.50"), "USD", false)));
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(new InstrumentRecord(101L, "AAPL", "AAPL", "EQUITY", "USD", "ACTIVE", null));
            when(watchlistMapper.hasInstrument(1L, 101L)).thenReturn(true);

            assertThatThrownBy(() -> service.addInstrumentToList(7L, "uuid-7", 1L,
                            new AddWatchlistInstrumentRequest("AAPL")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");

            when(watchlistMapper.hasInstrument(1L, 101L)).thenReturn(false);
            when(watchlistMapper.countInstruments(1L)).thenReturn(25);
            assertThatThrownBy(() -> service.addInstrumentToList(7L, "uuid-7", 1L,
                            new AddWatchlistInstrumentRequest("AAPL")))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
        }

        @Test
        @DisplayName("adding to a list stores the instrument and returns that list")
        void addToListSucceeds() {
            clientUser();
            WatchlistRecord list = list(1L, "Tech");
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list);
            when(fauxnanceClient.quote("MSFT")).thenReturn(lookup(q("MSFT", big("430.10"), "USD", false)));
            when(instrumentMapper.findBySymbol("MSFT")).thenReturn(new InstrumentRecord(102L, "MSFT", "MSFT", "EQUITY", "USD", "ACTIVE", null));
            when(watchlistMapper.hasInstrument(1L, 102L)).thenReturn(false);
            when(watchlistMapper.countInstruments(1L)).thenReturn(2);
            when(watchlistMapper.findQuoteByInstrumentId(102L)).thenReturn(null);
            when(watchlistMapper.findInstrumentsByWatchlistId(1L)).thenReturn(List.of());

            WatchlistDto dto = service.addInstrumentToList(7L, "uuid-7", 1L, new AddWatchlistInstrumentRequest("MSFT"));

            verify(watchlistMapper).upsertQuote(eq(102L), eq("MSFT"), eq(big("430.10")), any(), any(), any(), any(), any(), any());
            verify(watchlistMapper).addInstrument(1L, 102L);
            verify(watchlistMapper).updateWatchlistTimestamp(1L);
            assertThat(dto.name()).isEqualTo("Tech");
        }

        @Test
        @DisplayName("an entry on another list, or owned elsewhere, cannot be removed from a list")
        void removeRejectsForeignEntry() {
            clientUser();
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list(1L, "Tech"));
            when(watchlistMapper.findInstrumentByIdAndAccount(9L, 7L))
                    .thenReturn(new WatchlistInstrumentRecord(9L, 2L, 101L, NOW));

            assertThatThrownBy(() -> service.removeInstrumentFromList(7L, "uuid-7", 1L, 9L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");
            verify(watchlistMapper, never()).removeInstrument(any());

            when(watchlistMapper.findInstrumentByIdAndAccount(10L, 7L)).thenReturn(null);
            assertThatThrownBy(() -> service.removeInstrumentFromList(7L, "uuid-7", 1L, 10L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");
        }

        @Test
        @DisplayName("removing an entry from its own list succeeds")
        void removeFromListSucceeds() {
            clientUser();
            WatchlistRecord list = list(1L, "Tech");
            when(watchlistMapper.findByIdAndAccountId(1L, 7L)).thenReturn(list);
            when(watchlistMapper.findInstrumentByIdAndAccount(9L, 7L))
                    .thenReturn(new WatchlistInstrumentRecord(9L, 1L, 101L, NOW));
            when(watchlistMapper.findInstrumentsByWatchlistId(1L)).thenReturn(List.of());

            WatchlistDto dto = service.removeInstrumentFromList(7L, "uuid-7", 1L, 9L);

            verify(watchlistMapper).removeInstrument(9L);
            verify(watchlistMapper).updateWatchlistTimestamp(1L);
            assertThat(dto.name()).isEqualTo("Tech");
        }
    }

    @Nested
    @DisplayName("Moving between lists")
    class MovingBetweenLists {

        @Test
        @DisplayName("an entry another account owns cannot be moved; neither can a foreign target")
        void moveRejectsForeignIds() {
            clientUser();
            assertThatThrownBy(() -> service.moveInstrument(7L, "uuid-7", 55L, 2L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");

            when(watchlistMapper.findInstrumentByIdAndAccount(3L, 7L))
                    .thenReturn(new WatchlistInstrumentRecord(3L, 1L, 101L, NOW));
            assertThatThrownBy(() -> service.moveInstrument(7L, "uuid-7", 3L, 99L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("ACC-404");
        }

        @Test
        @DisplayName("moving onto a list that already holds the symbol, or a full one, is VAL-422")
        void moveEnforcesCapAndUniqueness() {
            clientUser();
            when(watchlistMapper.findInstrumentByIdAndAccount(3L, 7L))
                    .thenReturn(new WatchlistInstrumentRecord(3L, 1L, 101L, NOW));
            when(watchlistMapper.findByIdAndAccountId(2L, 7L)).thenReturn(list(2L, "Holdings B"));

            when(watchlistMapper.hasInstrument(2L, 101L)).thenReturn(true);
            assertThatThrownBy(() -> service.moveInstrument(7L, "uuid-7", 3L, 2L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");

            when(watchlistMapper.hasInstrument(2L, 101L)).thenReturn(false);
            when(watchlistMapper.countInstruments(2L)).thenReturn(25);
            assertThatThrownBy(() -> service.moveInstrument(7L, "uuid-7", 3L, 2L))
                    .isInstanceOf(DomainException.class).extracting("code").isEqualTo("VAL-422");
        }

        @Test
        @DisplayName("a successful move removes from the old list and adds to the new")
        void moveSucceeds() {
            clientUser();
            when(watchlistMapper.findInstrumentByIdAndAccount(3L, 7L))
                    .thenReturn(new WatchlistInstrumentRecord(3L, 1L, 101L, NOW));
            when(watchlistMapper.findByIdAndAccountId(2L, 7L)).thenReturn(list(2L, "Holdings B"));
            when(watchlistMapper.hasInstrument(2L, 101L)).thenReturn(false);
            when(watchlistMapper.countInstruments(2L)).thenReturn(1);
            when(watchlistMapper.findInstrumentsByWatchlistId(2L)).thenReturn(List.of());

            WatchlistDto dto = service.moveInstrument(7L, "uuid-7", 3L, 2L);

            verify(watchlistMapper).removeInstrument(3L);
            verify(watchlistMapper).addInstrument(2L, 101L);
            verify(watchlistMapper).updateWatchlistTimestamp(1L);
            verify(watchlistMapper).updateWatchlistTimestamp(2L);
            assertThat(dto.name()).isEqualTo("Holdings B");
        }
    }

    @Nested
    @DisplayName("Single-list API")
    class SingleList {

        private final InstrumentRecord aapl = new InstrumentRecord(101L, "AAPL", "AAPL", "EQUITY", null, "ACTIVE", null);

        @BeforeEach
        void client() {
            org.mockito.Mockito.lenient().when(userMapper.findUserByUuid("uuid-7")).thenReturn(Map.of("user_type", "client"));
        }

        @Test
        @DisplayName("Creates the list on first read, and shows each instrument with its quote, or stale without one")
        void readCreatesAndBuilds() {
            when(watchlistMapper.findByAccountId(7L)).thenReturn(null, list(1L, "My Watchlist"));
            when(watchlistMapper.findInstrumentsByWatchlistId(1L)).thenReturn(List.of(
                    new WatchlistInstrumentRecord(9L, 1L, 101L, NOW), new WatchlistInstrumentRecord(10L, 1L, 102L, NOW)));
            when(instrumentMapper.findById(101L)).thenReturn(aapl);
            when(instrumentMapper.findById(102L)).thenReturn(new InstrumentRecord(102L, "INFY", "INFY.NS", "EQUITY", "INR", "ACTIVE", null));
            when(watchlistMapper.findQuoteByInstrumentId(101L)).thenReturn(new com.trading.tradeapi.entity.InstrumentQuoteRecord(
                    1L, 101L, "AAPL", big("190"), "USD", big("189"), big("191"), big("1"), big("0.5"), NOW, false));

            WatchlistDto dto = service.getWatchlist(7L, "uuid-7");

            verify(watchlistMapper).createWatchlist(7L);
            assertThat(dto.instruments()).hasSize(2);
            assertThat(dto.instruments().get(0).currentPrice()).isEqualByComparingTo("190");
            assertThat(dto.instruments().get(1).currentPrice()).isNull();
            assertThat(dto.instruments().get(1).priceCurrency()).isEqualTo("INR");
            assertThat(dto.instruments().get(1).stale()).isTrue();
        }

        @Test
        @DisplayName("Refuses an unknown user, an administrator, and an instrument row that lost its instrument")
        void refusals() {
            when(userMapper.findUserByUuid("ghost")).thenReturn(null);
            when(userMapper.findUserByUuid("admin")).thenReturn(Map.of("user_type", "admin"));
            assertThatThrownBy(() -> service.getWatchlist(7L, "ghost")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.getWatchlist(7L, "admin")).hasMessageContaining("client accounts");

            when(watchlistMapper.findByAccountId(7L)).thenReturn(list(1L, "My Watchlist"));
            when(watchlistMapper.findInstrumentsByWatchlistId(1L)).thenReturn(List.of(new WatchlistInstrumentRecord(9L, 1L, 404L, NOW)));
            assertThatThrownBy(() -> service.getWatchlist(7L, "uuid-7")).hasMessageContaining("Instrument not found");
        }

        @Test
        @DisplayName("Adding registers an unknown instrument from market data, copies its quote and checks alerts")
        void addRegistersAndQuotes() {
            when(watchlistMapper.findByAccountId(7L)).thenReturn(null, list(1L, "My Watchlist"));
            when(fauxnanceClient.quote("AAPL")).thenReturn(lookup(q("AAPL", big("190"), null, false)));
            when(instrumentMapper.findBySymbol("AAPL")).thenReturn(null, aapl);
            when(watchlistMapper.findInstrumentsByWatchlistId(1L)).thenReturn(List.of());

            service.addInstrument(7L, "uuid-7", new AddWatchlistInstrumentRequest(" aapl "));

            verify(instrumentMapper).upsertDiscoveredInstrument("AAPL", "AAPL", "USD", "ACTIVE");
            verify(watchlistMapper).upsertQuote(eq(101L), eq("AAPL"), any(), any(), any(), any(), any(), any(), eq(false));
            verify(alertService).evaluate(eq("AAPL"), eq(big("190")), any());
            verify(watchlistMapper).addInstrument(1L, 101L);
        }

        @Test
        @DisplayName("A symbol with no market data, a missing symbol, or one that cannot be registered is a bad request")
        void addRefusals() {
            when(watchlistMapper.findByAccountId(7L)).thenReturn(list(1L, "My Watchlist"));
            when(fauxnanceClient.quote("ZZZ")).thenReturn(missing());
            assertThatThrownBy(() -> service.addInstrument(7L, "uuid-7", new AddWatchlistInstrumentRequest("zzz")))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not found in market data");
            assertThatThrownBy(() -> service.addInstrument(7L, "uuid-7", new AddWatchlistInstrumentRequest("")))
                    .hasMessageContaining("Symbol is required");
            assertThatThrownBy(() -> service.addInstrument(7L, "uuid-7", null)).hasMessageContaining("Symbol is required");

            when(fauxnanceClient.quote("NEW")).thenReturn(lookup(q("NEW", big("5"), "USD", false)));
            when(instrumentMapper.findBySymbol("NEW")).thenReturn(null);
            assertThatThrownBy(() -> service.addInstrument(7L, "uuid-7", new AddWatchlistInstrumentRequest("NEW")))
                    .hasMessageContaining("Failed to register");
        }

        @Test
        @DisplayName("Removing needs a list and an entry on it")
        void remove() {
            when(watchlistMapper.findByAccountId(7L)).thenReturn(null, list(1L, "My Watchlist"));
            assertThatThrownBy(() -> service.removeInstrument(7L, "uuid-7", 9L)).hasMessageContaining("Watchlist not found");

            when(watchlistMapper.findInstrumentsByWatchlistId(1L))
                    .thenReturn(List.of(new WatchlistInstrumentRecord(9L, 1L, 101L, NOW)), List.of());
            org.mockito.Mockito.lenient().when(instrumentMapper.findById(101L)).thenReturn(aapl);
            assertThatThrownBy(() -> service.removeInstrument(7L, "uuid-7", 8L)).hasMessageContaining("not found in watchlist");

            when(watchlistMapper.findInstrumentsByWatchlistId(1L))
                    .thenReturn(List.of(new WatchlistInstrumentRecord(9L, 1L, 101L, NOW)), List.of());
            service.removeInstrument(7L, "uuid-7", 9L);
            verify(watchlistMapper).removeInstrument(9L);
        }
    }
}
