package ru.tubetv.app;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class VkWebClientTest {
    @Test
    public void recognizesAuthorizationErrorCode() throws Exception {
        JSONObject response = new JSONObject(
                "{\"error\":{\"error_code\":5,\"error_msg\":\"User authorization failed\"}}");

        assertTrue(VkWebClient.isAuthorizationError(response));
    }

    @Test
    public void recognizesAuthorizationErrorMessage() throws Exception {
        JSONObject response = new JSONObject(
                "{\"error\":{\"error_code\":0,\"error_msg\":\"Authorization failed\"}}");

        assertTrue(VkWebClient.isAuthorizationError(response));
    }

    @Test
    public void ignoresUnrelatedApiErrors() throws Exception {
        JSONObject response = new JSONObject(
                "{\"error\":{\"error_code\":6,\"error_msg\":\"Too many requests\"}}");

        assertFalse(VkWebClient.isAuthorizationError(response));
    }
}
