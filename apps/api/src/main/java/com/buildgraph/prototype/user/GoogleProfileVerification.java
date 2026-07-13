package com.buildgraph.prototype.user;

public record GoogleProfileVerification(
        String userId,
        String providerUserId
) {
}
