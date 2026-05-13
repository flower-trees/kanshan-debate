package com.zhihu.kanshan.debate.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "debate_session")
@Data
@NoArgsConstructor
public class DebateSession {

    public enum Status { PENDING, CLUSTERING, DEBATING, COMPLETED, ERROR }

    @Id
    private String id;

    @Column(nullable = false)
    private String topicHash;

    @Column(nullable = false, length = 500)
    private String topic;

    @Enumerated(EnumType.STRING)
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String stancesJson;   // JSON array of StanceGroup

    @Column(columnDefinition = "TEXT")
    private String turnsJson;     // JSON array of DebateTurn

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(nullable = false)
    private long createdAt = System.currentTimeMillis();
}
