package com.yuforge.memory;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ToolResultArtifactStoreTest {

    @Test
    void deduplicatesAndRestoresOriginalResult() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(4, 10_000);

        ToolResultArtifactStore.Artifact first = store.archive("read_file", "call-1", "exact content");
        ToolResultArtifactStore.Artifact second = store.archive("read_file", "call-1", "exact content");

        assertEquals(first.id(), second.id());
        assertEquals(1, store.size());
        assertTrue(store.get(first.id()).orElseThrow().formatForTool().contains("exact content"));
    }

    @Test
    void evictsOldestArtifactWhenEntryBoundIsExceeded() {
        ToolResultArtifactStore store = new ToolResultArtifactStore(2, 10_000);
        String first = store.archive("read_file", "1", "one").id();
        store.archive("read_file", "2", "two");
        String third = store.archive("read_file", "3", "three").id();

        assertTrue(store.get(first).isEmpty());
        assertTrue(store.get(third).isPresent());
        assertEquals(2, store.size());
    }
}
