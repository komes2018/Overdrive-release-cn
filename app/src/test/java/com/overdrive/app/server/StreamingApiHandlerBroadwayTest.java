package com.overdrive.app.server;

import static org.junit.Assert.assertSame;

import com.overdrive.app.surveillance.GpuPipelineConfig;

import org.junit.Test;

public class StreamingApiHandlerBroadwayTest {

    @Test
    public void capsOnlyDilink5BroadwayProfilesAboveFifteenFps() {
        assertSame(
                GpuPipelineConfig.StreamingQuality.ULTRA_HIGH,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.MAX, true, true));
        assertSame(
                GpuPipelineConfig.StreamingQuality.MAX,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.MAX, false, true));
        assertSame(
                GpuPipelineConfig.StreamingQuality.MAX,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.MAX, true, false));
        assertSame(
                GpuPipelineConfig.StreamingQuality.HIGH,
                StreamingApiHandler.capBroadwayQuality(
                        GpuPipelineConfig.StreamingQuality.HIGH, true, true));
    }
}
