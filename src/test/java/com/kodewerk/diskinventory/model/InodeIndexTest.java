package com.kodewerk.diskinventory.model;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class InodeIndexTest {

    private static final Path A = Path.of("/a");

    @Test
    void removeLinksUnderKeepsOwnerEqualToPathAndForgetsOwnersBelowIt() {
        InodeIndex index = new InodeIndex();
        index.addLink("atP", A.resolve("b"), 2, 10, 10);
        index.addLink("atP", A.resolve("z"), 2, 10, 10);
        index.addLink("below", A.resolve("b/f"), 2, 20, 20);
        index.addLink("below", A.resolve("z/f"), 2, 20, 20);
        index.addLink("sibling", A.resolve("bc"), 2, 30, 30);
        index.addLink("sibling", A.resolve("z/g"), 2, 30, 30);
        index.settle(Set.of("atP", "below", "sibling"));

        Set<Object> lost = index.removeLinksUnder(A.resolve("b"));

        assertEquals(Set.of("atP", "below"), lost);
        assertEquals(Optional.of(A.resolve("b")), index.owner("atP"));
        assertEquals(Optional.empty(), index.owner("below"));
        assertEquals(Optional.of(A.resolve("bc")), index.owner("sibling"));
        assertEquals(List.of(A.resolve("bc"), A.resolve("z/g")), index.linksOf("sibling"));
    }

    @Test
    void settleUnchargesWhatWasChargedNotTheNewSize() {
        InodeIndex index = new InodeIndex();
        index.addLink("k", A.resolve("m/f"), 2, 10, 10);
        assertEquals(Map.of(A.resolve("m"), new TreeEdit.Charge(10, 10)), index.settle(Set.of("k")));

        index.addLink("k", A.resolve("b/f"), 2, 25, 30);    // grew; owner moves to b/f

        assertEquals(Map.of(A.resolve("m"), new TreeEdit.Charge(-10, -10),
                A.resolve("b"), new TreeEdit.Charge(25, 30)), index.settle(Set.of("k")));
    }

    @Test
    void settleRechargesOwnerWhenSizeChanged() {
        InodeIndex index = new InodeIndex();
        index.addLink("k", A.resolve("m/f"), 2, 10, 10);
        index.settle(Set.of("k"));

        index.addLink("k", A.resolve("m/f"), 2, 25, 30);

        assertEquals(Map.of(A.resolve("m"), new TreeEdit.Charge(15, 20)), index.settle(Set.of("k")));
    }
}
