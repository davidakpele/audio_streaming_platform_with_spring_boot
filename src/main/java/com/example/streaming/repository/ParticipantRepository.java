package com.example.streaming.repository;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.streaming.model.Participants;

@Repository
public interface ParticipantRepository extends JpaRepository<Participants, Long> {

    // Query by event's roomId
    @Query("SELECT p FROM Participants p WHERE p.event.roomId = :roomId")
    List<Participants> findByEventRoomId(@Param("roomId") String roomId);

    @Query("SELECT CASE WHEN COUNT(p) > 0 THEN true ELSE false END " +
            "FROM Participants p WHERE p.event.roomId = :roomId AND p.userId = :userId")
    boolean existsByEventRoomIdAndUserId(@Param("roomId") String roomId,
            @Param("userId") String userId);

    @Query("SELECT COUNT(p) FROM Participants p WHERE p.event.roomId = :roomId")
    int countByEventRoomId(@Param("roomId") String roomId);

    @Modifying
    @Query("DELETE FROM Participants p WHERE p.event.roomId = :roomId AND p.userId = :participantId")
    void deleteByEventRoomIdAndUserId(@Param("roomId") String roomId,
            @Param("participantId") String participantId);
}
