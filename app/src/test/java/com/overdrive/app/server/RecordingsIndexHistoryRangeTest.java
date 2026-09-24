package com.overdrive.app.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class RecordingsIndexHistoryRangeTest {

    @Test
    public void epochRangeUsesPreparedInclusivePredicates()
            throws Exception {
        RecordingsIndex.Filter filter =
                new RecordingsIndex.Filter();
        filter.fromMs = 100L;
        filter.toMs = 200L;
        filter.type = "sentry";

        StringBuilder where = new StringBuilder();
        List<Object> args = new ArrayList<>();
        Method buildWhere = RecordingsIndex.class.getDeclaredMethod(
                "buildWhere", RecordingsIndex.Filter.class,
                StringBuilder.class, List.class);
        buildWhere.setAccessible(true);
        buildWhere.invoke(null, filter, where, args);

        String sql = where.toString();
        assertTrue(sql.contains("ts_ms >= ?"));
        assertTrue(sql.contains("ts_ms <= ?"));
        assertFalse(sql.contains("100"));
        assertFalse(sql.contains("200"));
        assertEquals(Long.valueOf(100L), args.get(0));
        assertEquals(Long.valueOf(200L), args.get(1));
        assertEquals("sentry", args.get(2));
    }
}
