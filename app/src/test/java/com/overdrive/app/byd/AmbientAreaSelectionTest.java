package com.overdrive.app.byd;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AmbientAreaSelectionTest {
    @Test
    public void usesTheTwoArgumentAreaSelector() {
        TwoArgumentDevice device = new TwoArgumentDevice();
        assertTrue(BydDataCollector.selectIalArea(device, 2));
        assertEquals(2, device.area);
        assertEquals(0, device.flags);
    }

    public static final class TwoArgumentDevice {
        int area;
        int flags = -1;

        public int setIALArea(int area, int flags) {
            this.area = area;
            this.flags = flags;
            return 0;
        }
    }
}
