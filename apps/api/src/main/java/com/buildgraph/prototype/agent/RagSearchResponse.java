package com.buildgraph.prototype.agent;

import java.util.List;

public record RagSearchResponse(
        List<RagEvidence> items,
        int page,
        int size,
        int total
) {
}
