package com.buildgraph.prototype.ticket;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class SupportChatRoomProvisioner {
    private final JdbcTemplate jdbcTemplate;

    public SupportChatRoomProvisioner(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String ensureRoom(Long userInternalId, Long ticketInternalId) {
        return SupportChatRoomCreator.ensureRoom(jdbcTemplate, userInternalId, ticketInternalId).publicId();
    }
}
