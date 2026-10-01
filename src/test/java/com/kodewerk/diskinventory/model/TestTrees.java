package com.kodewerk.diskinventory.model;

import java.io.IOException;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class TestTrees {

    private TestTrees() {
    }

    /** Every directory in result has the same logical and allocated totals as a fresh scan of the disk. */
    static void assertMatchesFreshScan(DiskUsageModel model, ScanResult result) throws IOException {
        assertSameTotals(model.scan(result.root().path()).root(), result.root());
    }

    private static void assertSameTotals(DirectoryNode expected, DirectoryNode actual) {
        String at = actual.path().toString();
        assertEquals(expected.path(), actual.path());
        assertEquals(expected.totalSize(), actual.totalSize(), "total at " + at);
        assertEquals(expected.totalSize(SizeMode.ALLOCATED), actual.totalSize(SizeMode.ALLOCATED),
                "allocated total at " + at);
        assertEquals(expected.directFileSize(), actual.directFileSize(), "direct size at " + at);
        assertEquals(expected.errorCount(), actual.errorCount(), "unreadable entries at " + at);
        assertEquals(names(expected), names(actual), "children at " + at);
        for (DirectoryNode child : expected.children()) {
            assertSameTotals(child, actual.child(child.name()).orElseThrow());
        }
    }

    private static Set<String> names(DirectoryNode node) {
        return node.children().stream().map(DirectoryNode::name).collect(Collectors.toSet());
    }
}
