package com.overdrive.app.byd;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class PositionStoreModelConfirmationTest {
    @Test
    public void sealion7ConfirmationIsDiLink5Only() {
        assertTrue(PositionStore.isModelConfirmed("seal", false));
        assertFalse(PositionStore.isModelConfirmed("sealion7", false));
        assertTrue(PositionStore.isModelConfirmed("sealion7", true));
    }
}
