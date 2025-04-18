package com.example.streaming.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import com.example.streaming.model.Event;

@Repository
public interface EventRepository extends JpaRepository<Event, Long> {
    List<Event> findByStatus(String status);

    @Query("SELECT e FROM Event e WHERE e.roomId = :roomId")
    Optional<Event> findByRoomId(@Param("roomId") String roomId);
    
    Optional<Event> findByRoomIdAndStatus(String roomId, String status);
}
