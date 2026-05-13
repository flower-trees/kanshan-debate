package com.zhihu.kanshan.debate.repository;

import com.zhihu.kanshan.debate.model.DebateSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebateSessionRepository extends JpaRepository<DebateSession, String> {
    Optional<DebateSession> findByTopicHashAndStatus(String topicHash, DebateSession.Status status);
}
