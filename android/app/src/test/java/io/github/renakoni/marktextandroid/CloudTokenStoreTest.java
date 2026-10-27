package io.github.renakoni.marktextandroid;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class CloudTokenStoreTest {

    @Test
    public void serializationRoundTrips() {
        CloudTokenStore tokens = new CloudTokenStore("AT", "RT", 123_456L, "user@example.com");

        CloudTokenStore parsed = CloudTokenStore.parse(tokens.serialize());

        assertEquals("AT", parsed.accessToken);
        assertEquals("RT", parsed.refreshToken);
        assertEquals(123_456L, parsed.expiresAtMillis);
        assertEquals("user@example.com", parsed.accountName);
    }

    @Test
    public void nullAccountNameSurvivesTheRoundTrip() {
        CloudTokenStore parsed =
            CloudTokenStore.parse(new CloudTokenStore("AT", "RT", 1L, null).serialize());

        assertNull(parsed.accountName);
    }

    @Test
    public void parsingRejectsGarbageAndTokenlessRecords() {
        assertNull(CloudTokenStore.parse(null));
        assertNull(CloudTokenStore.parse(""));
        assertNull(CloudTokenStore.parse("not json"));
        assertNull(CloudTokenStore.parse("{\"accessToken\":\"AT\"}"));
    }

    @Test
    public void freshnessRespectsTheExpiryMargin() {
        CloudTokenStore tokens = new CloudTokenStore("AT", "RT", 100_000L, null);

        assertTrue(tokens.isAccessTokenFresh(100_000L - CloudTokenStore.EXPIRY_MARGIN_MILLIS - 1));
        assertFalse(tokens.isAccessTokenFresh(100_000L - CloudTokenStore.EXPIRY_MARGIN_MILLIS));
        assertFalse(new CloudTokenStore("", "RT", 100_000L, null).isAccessTokenFresh(0));
    }

    @Test
    public void neverExpiringTokensStandWithoutARefreshToken() {
        CloudTokenStore tokens =
            new CloudTokenStore("AT", "", CloudTokenStore.NEVER_EXPIRES, "octocat");

        CloudTokenStore parsed = CloudTokenStore.parse(tokens.serialize());

        assertEquals("AT", parsed.accessToken);
        assertEquals("", parsed.refreshToken);
        assertEquals(CloudTokenStore.NEVER_EXPIRES, parsed.expiresAtMillis);
        assertTrue(parsed.isAccessTokenFresh(System.currentTimeMillis()));
    }

    @Test
    public void expiringTokensStillRequireARefreshToken() {
        assertNull(CloudTokenStore.parse(new CloudTokenStore("AT", "", 100_000L, null).serialize()));
        assertNull(CloudTokenStore.parse(
            new CloudTokenStore("", "", CloudTokenStore.NEVER_EXPIRES, null).serialize()));
    }
}
