package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

public class AmbientApplyResultTest {
    @Test
    public void requiresAtLeastOneSuccessfulWriteAndNoFailedWrites() throws Exception {
        assertFalse(AmbientProbe.allAttemptedStepsSucceeded(new JSONObject()));
        assertTrue(AmbientProbe.allAttemptedStepsSucceeded(
                new JSONObject().put("front",
                        new JSONObject().put("colour", true).put("brightness", true))));
        assertFalse(AmbientProbe.allAttemptedStepsSucceeded(
                new JSONObject().put("front",
                        new JSONObject().put("colour", true).put("brightness", false))));
    }
}
