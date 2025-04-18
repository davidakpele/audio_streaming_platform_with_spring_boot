package com.example.streaming.model;

import java.time.Instant;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity
public class Participants {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "event_id")
    private Event event;
    
    @Column(name = "user_id")
    private String userId;
    
    @Column(name = "username")
    private String username;
    
    @Column(name = "joined_at")
    private Instant joinedAt = Instant.now();

    public String getRoomId() {
        return event != null ? event.getRoomId() : null;
    }
}
