package com.smartkpi.core;

import com.smartkpi.model.*;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BranchAggregatorTest {
    @Test
    void shouldFollowDeviceTotalRule() {
        TerminalConfig c1 = new TerminalConfig("营业部", "Q1", true, true, "", "营业部", null, null);
        TerminalConfig c2 = new TerminalConfig("营业部", "Q2", true, true, "", "营业部", null, null);
        TerminalConfig c3 = new TerminalConfig("营业部", "Q3", true, true, "", "营业部", null, null);

        DeviceRecord d1 = new DeviceRecord("营业部", "Q1", 0.99, 0.01, 100.0, 1000.0);
        DeviceRecord d2 = new DeviceRecord("营业部", "Q2", 0.99, 0.01, 200.0, 1000.0);

        List<TerminalUnified> unified = List.of(
                new TerminalUnified(c1, d1, d1.bootRate(), d1.resourceRate()),
                new TerminalUnified(c2, d2, d2.bootRate(), d2.resourceRate()),
                new TerminalUnified(c3, null, null, null)
        );

        BranchAggregator aggregator = new BranchAggregator();
        List<BranchMetric> out = aggregator.aggregateResource(unified, new ProcessingLogger());

        assertEquals(1, out.size());
        BranchMetric metric = out.get(0);
        assertEquals(2, metric.inScopeCount());
        assertEquals(1, metric.offsiteOnly());
        assertEquals(3, metric.deviceTotal());
        assertEquals("15.00%", metric.computedRate());
    }

    @Test
    void shouldUseDashWhenNoAverageDevices() {
        TerminalConfig c1 = new TerminalConfig("营业部", "Q1", true, false, "", "营业部", null, null);
        TerminalUnified u1 = new TerminalUnified(c1, new DeviceRecord("营业部", "Q1", 0.0, 0.0, 100.0, 1000.0), 0.0, 0.0);
        BranchMetric metric = new BranchAggregator().aggregateResource(List.of(u1), new ProcessingLogger()).get(0);
        assertEquals("-", metric.computedRate());
    }
}