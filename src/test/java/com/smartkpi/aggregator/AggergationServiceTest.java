package com.smartkpi.aggregator;

import com.smartkpi.aggregator.Domain.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AggregationServiceTest {

    @Test
    void shouldAggregateAndSort() {
        AggregationService service = new AggregationService();
        List<DeviceRate> devices = List.of(
                new DeviceRate("T1", "网点A", 90.0),
                new DeviceRate("T2", "网点A", 100.0),
                new DeviceRate("T3", "网点B", 80.0)
        );
        Map<String, DepartConfig> cfg = Map.of(
                "T2", new DepartConfig("T2", "网点A", true, false, "网点A离行", 100.0, 0.0)
        );
        ProcessingLog log = new ProcessingLog();
        List<OutputRow> rows = service.buildRows(devices, cfg, Set.of(), MetricType.BOOT, log);

        assertEquals(3, rows.size());
        assertEquals("网点A离行", rows.get(0).name());
        assertEquals(100.0, rows.get(0).rate());
        assertEquals("网点A", rows.get(1).name());
        assertEquals(90.0, rows.get(1).rate());
    }

    @Test
    void shouldParseRate() {
        assertEquals(99.94, RateParser.parseString("99.94%").orElseThrow());
        assertEquals(99.94, RateParser.parseString("0.9994").orElseThrow(), 0.001);
        assertTrue(RateParser.parseString("-").isEmpty());
    }
}