package com.trading.tradeapi.preferences;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.trading.tradeapi.TestTokenFactory;
import com.trading.tradeapi.preferences.api.AlertChannel;
import com.trading.tradeapi.preferences.api.AlertChannelResolver;
import com.trading.tradeapi.preferences.api.ResolvedChannel;
import com.trading.tradeapi.preferences.dto.PreferenceResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The preferences module over HTTP with the real filter, the real service and the real
 * mapper. Seed data: account 1 belongs to user 1, account 2 to user 2, so those two accounts
 * are never the same customer.
 *
 * <p>Its own database. Another {@code @SpringBootTest} already owns {@code mem:testdb}, and
 * re-running {@code test-data.sql} against it would fail on the unique username rather than
 * hand this class a fixture.
 *
 * <p>Field values are asserted on the deserialized response rather than through jsonPath.
 * An unset preference answers {@code null} for several fields, and whether a null is "no value"
 * or "a value that is null" is a jsonPath question, not a question about this module.
 */
@SpringBootTest(properties =
        "spring.datasource.url=jdbc:h2:mem:preferences-test;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;NON_KEYWORDS=VALUE")
@AutoConfigureMockMvc
@DisplayName("Customer preferences module")
class PreferencesIntegrationTest {

    private static final String BASE = "/api/v1/preferences";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AlertChannelResolver alertChannelResolver;

    @Value("${jwt.secret}")
    private String jwtSecret;

    /** Each test states the preference it needs, so no test depends on another's order. */
    @BeforeEach
    void clearPreferences() {
        jdbc.update("DELETE FROM customer_preference");
    }

    // â”€â”€â”€â”€â”€ The three probes every route in this sprint has to survive â”€â”€â”€â”€â”€

    @Test
    @DisplayName("Refused with no token")
    void noTokenIsRefused() throws Exception {
        mockMvc.perform(get(BASE + "/1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    @DisplayName("Refused with a well-formed token signed by a key nobody holds")
    void tokenSignedByTheWrongKeyIsRefused() throws Exception {
        mockMvc.perform(get(BASE + "/1")
                        .header("Authorization", "Bearer "
                                + TestTokenFactory.createToken(1L, "a-different-secret-that-is-at-least-32-bytes", 60_000L)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("AUTH-401"));
    }

    @Test
    @DisplayName("Refused when the token's account is not the account in the path")
    void crossAccountAccessIsForbiddenNotNotFound() throws Exception {
        mockMvc.perform(get(BASE + "/1")
                        .header("Authorization", "Bearer " + tokenFor(2L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("ACC-403"));
    }

    // â”€â”€â”€â”€â”€ Read and write â”€â”€â”€â”€â”€

    @Test
    @DisplayName("An account that never saved a preference answers 200, not 404")
    void unsetPreferenceIsAValueNotAnError() throws Exception {
        PreferenceResponse body = getPreference(2L, tokenFor(2L));

        assertThat(body.accountId()).isEqualTo(2L);
        assertThat(body.alertChannel()).isEqualTo(AlertChannel.NONE);
        assertThat(body.defaultAccountId()).isNull();
        assertThat(body.alertContact()).isNull();
        assertThat(body.displayCurrency()).isNull();
        assertThat(body.updatedAt()).isNull();
    }

    @Test
    @DisplayName("A preference written through the API reads back unchanged")
    void writeThenReadRoundTrips() throws Exception {
        PreferenceResponse written = putPreference(1L, tokenFor(1L), Map.of(
                "defaultAccountId", 1,
                "alertChannel", "EMAIL",
                "alertContact", "alex@example.com",
                "displayCurrency", "USD"));
        assertThat(written.alertChannel()).isEqualTo(AlertChannel.EMAIL);
        assertThat(written.displayCurrency()).isEqualTo("USD");
        assertThat(written.updatedAt()).isNotNull();

        PreferenceResponse read = getPreference(1L, tokenFor(1L));
        assertThat(read).usingRecursiveComparison().isEqualTo(written);
    }

    @Test
    @DisplayName("Switching notifications off leaves no stale destination behind")
    void switchingToNoneDropsTheContact() throws Exception {
        putEmailPreference();

        PreferenceResponse off = putPreference(1L, tokenFor(1L), Map.of("alertChannel", "NONE"));
        assertThat(off.alertChannel()).isEqualTo(AlertChannel.NONE);
        assertThat(off.alertContact()).isNull();

        PreferenceResponse reread = getPreference(1L, tokenFor(1L));
        assertThat(reread.alertContact()).isNull();
        assertThat(reread.defaultAccountId()).isNull();
    }

    // â”€â”€â”€â”€â”€ Validation â”€â”€â”€â”€â”€

    @Test
    @DisplayName("A channel with no destination is refused with VAL-422")
    void emailWithoutAContactIsRefused() throws Exception {
        putExpecting(422, "VAL-422", 1L, Map.of("alertChannel", "EMAIL"));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("A contact that is not an email address is refused with VAL-422")
    void malformedEmailIsRefused() throws Exception {
        putExpecting(422, "VAL-422", 1L, Map.of(
                "alertChannel", "EMAIL",
                "alertContact", "not-an-email"));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("A missing channel is refused with VAL-422")
    void missingChannelIsRefused() throws Exception {
        putExpecting(422, "VAL-422", 1L,
                Map.of("alertContact", "alex@example.com"));
        assertThat(count()).isZero();
    }

    @Test
    @DisplayName("An unknown account is 404, and the token has to be for that same account")
    void unknownAccountIsNotFound() throws Exception {
        mockMvc.perform(get(BASE + "/999")
                        .header("Authorization", "Bearer " + tokenFor(999L)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("ACC-404"));
    }

    @Test
    @DisplayName("A default account belonging to somebody else is refused with ACC-403")
    void defaultAccountOfAnotherCustomerIsForbidden() throws Exception {
        putExpecting(403, "ACC-403", 1L, Map.of(
                "defaultAccountId", 2,
                "alertChannel", "NONE"));

        // Refused before anything was written: the probe changed no state.
        assertThat(count()).isZero();
    }

    // â”€â”€â”€â”€â”€ The seam the notifications module will call â”€â”€â”€â”€â”€

    @Test
    @DisplayName("The published resolver answers from the stored preference")
    void resolverAnswersThroughTheInterface() throws Exception {
        putEmailPreference();

        ResolvedChannel resolved = alertChannelResolver.resolveChannelForAccount(1L);
        assertThat(resolved.deliverable()).isTrue();
        assertThat(resolved.channel()).isEqualTo(AlertChannel.EMAIL);
        assertThat(resolved.contact()).isEqualTo("alex@example.com");

        // No preference stored: a value, never null, so the caller has to handle it.
        ResolvedChannel unset = alertChannelResolver.resolveChannelForAccount(2L);
        assertThat(unset).isNotNull();
        assertThat(unset.deliverable()).isFalse();
        assertThat(unset.channel()).isEqualTo(AlertChannel.NONE);
        assertThat(unset.contact()).isNull();
    }

    @Test
    @DisplayName("The resolver has no route: it is a Java interface, not an endpoint")
    void resolutionIsNotPublishedAsARoute() throws Exception {
        mockMvc.perform(get(BASE + "/1/channel")
                        .header("Authorization", "Bearer " + tokenFor(1L)))
                .andExpect(status().isNotFound());
    }

    // â”€â”€â”€â”€â”€ helpers â”€â”€â”€â”€â”€

    private String tokenFor(long accountId) {
        return TestTokenFactory.createToken(accountId, jwtSecret, 60_000L);
    }

    private void putEmailPreference() throws Exception {
        putPreference(1L, tokenFor(1L), Map.of(
                "defaultAccountId", 1,
                "alertChannel", "EMAIL",
                "alertContact", "alex@example.com"));
    }

    private PreferenceResponse getPreference(long accountId, String token) throws Exception {
        MvcResult result = mockMvc.perform(get(BASE + "/" + accountId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private PreferenceResponse putPreference(long accountId, String token, Map<String, Object> fields)
            throws Exception {
        MvcResult result = mockMvc.perform(put(BASE + "/" + accountId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(fields)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result);
    }

    private void putExpecting(int status, String errorCode, long accountId, Map<String, Object> fields)
            throws Exception {
        mockMvc.perform(put(BASE + "/" + accountId)
                        .header("Authorization", "Bearer " + tokenFor(accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(fields)))
                .andExpect(status().is(status))
                .andExpect(jsonPath("$.errorCode").value(errorCode));
    }

    private PreferenceResponse read(MvcResult result) throws Exception {
        return objectMapper.readValue(result.getResponse().getContentAsString(), PreferenceResponse.class);
    }

    /** {@code LinkedHashMap} so the JSON body is readable when a test fails. */
    private String json(Map<String, Object> fields) throws Exception {
        return objectMapper.writeValueAsString(new LinkedHashMap<>(fields));
    }

    private int count() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM customer_preference", Integer.class);
        return n == null ? 0 : n;
    }
}
