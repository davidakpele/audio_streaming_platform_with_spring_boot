package com.example.streaming.domain;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import com.example.streaming.enums.EventStatus;
import com.example.streaming.enums.StreamType;
import com.example.streaming.model.Event;
import com.example.streaming.model.Participants;
import com.example.streaming.model.Users;
import com.example.streaming.repository.EventRepository;
import com.example.streaming.repository.ParticipantRepository;
import com.example.streaming.repository.UsersRepository;
import com.example.streaming.responses.StreamLinkMessage;
import com.example.streaming.sessions.WebSocketSessionManager;
import com.example.streaming.utils.JwtTokenProvider;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.web.socket.*;

@Component
public class AudioStreamHandlerConsumer extends AbstractWebSocketHandler {

    private final ConcurrentMap<String, String> sessionToRoomMap = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();
    // Active connections tracking
    private final Map<String, Event> activeEvents = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private static final Map<String, Set<WebSocketSession>> eventSessions = new HashMap<>();
    private final Map<String, String> sessionToEventMap = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Autowired
    private EventRepository eventRepository;

    @Autowired
    private ParticipantRepository participantRepository;

    @Autowired
    private UsersRepository userRepository;

    @Autowired
    private WebSocketSessionManager sessionManager;
    
    @Autowired
    private RedisTemplate<String, String> stringRedisTemplate;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        URI uri = session.getUri();
        if (uri == null) {
            session.close();
            return;
        }
        String path = uri.getPath();
        String[] segments = path.split("/");
        // Handle active-streams request
        if (path.endsWith("/ws/list/active-streams/")) {
            //handleActiveStreamsRequest(session);
            return;
        }
        
        Map<String, String> pathVariables = extractPathVariables(uri);

        String userId = pathVariables.get("userId");

        String token = extractTokenFromQuery(uri.getQuery());

        if (segments.length == 6 && segments[5].matches("\\d+") && segments.length <= 6) {
            String hosterId = segments[5];
            if (userId != null && !userId.isEmpty()) {
                handleHostConnection(session, hosterId, token);
                session.getAttributes().put("userId", userId);
                session.getAttributes().put("eventId", "event-" + userId);
            }
        } else if (segments.length >= 7 && "join".equals(segments[4]) && segments[8].matches("\\d+")) {
            String eventId = segments[6];
            String username = segments[7];
            String participantId = segments[8];
            handleParticipantConnection(session, eventId, participantId, username);
            session.getAttributes().put("eventId", eventId);
            session.getAttributes().put("username", username);
            session.getAttributes().put("participantId", participantId);
            eventSessions.computeIfAbsent(eventId, k -> new HashSet<>()).add(session);
        }
        sessions.put(session.getId(), session);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession senderSession, BinaryMessage message) throws Exception {
        String senderSessionId = senderSession.getId();
        byte[] audioData = message.getPayload().array();

    
        int MAX_FRAME_SIZE = 4096 * 2; 
        if (audioData.length > MAX_FRAME_SIZE) {
            System.out.println("❌ Frame too large. Skipping...");
            return;
        }

        // Silence detection - only if you want server-side filtering
        boolean isSilent = true;
        for (int i = 0; i < audioData.length; i += 2) {
            short sample = (short) ((audioData[i + 1] << 8) | (audioData[i] & 0xFF));
            // Adjust threshold as needed
            if (Math.abs(sample) > 500) { 
                isSilent = false;
                break;
            }
        }
        if (isSilent) {
            System.out.println("🔇 Silent audio skipped.");
            return;
        }
       
        // Find which room the sender belongs to
        String roomId = findRoomForSession(senderSessionId);
        if (roomId == null) {
            System.out.println("❌ No room found for session: " + senderSessionId);
            return;
        }

        // Get all active participants in the room
        List<WebSocketSession> recipients = getRoomParticipants(roomId, senderSessionId);
        if (recipients.isEmpty()) {
            System.out.println("⚠️ No active participants in room: " + roomId);
            return;
        }

        //  Broadcast audio to all participants 
        BinaryMessage audioMessage = new BinaryMessage(audioData);
        for (WebSocketSession recipient : recipients) {
            try {
                if (recipient.isOpen()) {
                    recipient.sendMessage(audioMessage);
                }
            } catch (IOException e) {
                System.out.println("❌ Failed to send to session " + recipient.getId() + ": " + e.getMessage());
                cleanupDisconnectedSession(recipient.getId());
            }
        }

    }

    // Helper method to find room for a session
    private String findRoomForSession(String sessionId) {
        Set<String> roomKeys = stringRedisTemplate.keys("event_room:*");
        if (roomKeys == null)
            return null;
        ObjectMapper mapper = new ObjectMapper();
        for (String roomKey : roomKeys) {
            String roomJson = stringRedisTemplate.opsForValue().get(roomKey);
            if (roomJson == null)
                continue;

            try {
                Map<String, Object> roomData = mapper.readValue(roomJson, new TypeReference<>() {
                });

                // Check host session
                String hostSessionId = (String) roomData.get("hostSessionId");
                if (sessionId.equals(hostSessionId)) {
                    return (String) roomData.get("roomId");
                }

                // Check participants
                Map<String, Object> participants = (Map<String, Object>) roomData.get("participants");
                if (participants != null) {
                    for (Object participantObj : participants.values()) {
                        Map<String, Object> participant = (Map<String, Object>) participantObj;
                        String participantSessionId = (String) participant.get("sessionId");
                        if (sessionId.equals(participantSessionId)) {
                            return (String) roomData.get("roomId");
                        }
                    }
                }
            } catch (Exception e) {
                System.out.println("⚠️ Error processing room data: " + e.getMessage());
            }
        }
        return null;
    }


    // Helper method to get all active participants in a room
    private List<WebSocketSession> getRoomParticipants(String roomId, String excludeSessionId) {
        List<WebSocketSession> participants = new ArrayList<>();
        String roomKey = "event_room:" + roomId;
        String roomJson = stringRedisTemplate.opsForValue().get(roomKey);
        if (roomJson == null)
            return participants;

        try {
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(roomJson, new TypeReference<>() {
            });

            // Add host if different from excluded session
            String hostSessionId = (String) roomData.get("sessionId");
            if (hostSessionId != null && !hostSessionId.equals(excludeSessionId)) {
                WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                if (hostSession != null && hostSession.isOpen()) {
                    participants.add(hostSession);
                }
            }

            // Add all other participants
            Map<String, Object> participantMap = (Map<String, Object>) roomData.get("participants");
            if (participantMap != null) {
                for (Object participantObj : participantMap.values()) {
                    Map<String, Object> participant = (Map<String, Object>) participantObj;
                    String participantSessionId = (String) participant.get("sessionId");
                    if (participantSessionId != null && !participantSessionId.equals(excludeSessionId)) {
                        WebSocketSession participantSession = sessionManager.getSession(participantSessionId);
                        if (participantSession != null && participantSession.isOpen()) {
                            participants.add(participantSession);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("⚠️ Error getting room participants: " + e.getMessage());
        }

        return participants;
    }

    
    // Helper method to clean up disconnected sessions
    private void cleanupDisconnectedSession(String sessionId) {
        sessionManager.removeSession(sessionId);
    }
 

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    message.getPayload(),
                    new TypeReference<Map<String, Object>>() {
                    });
            
            String type = (String) payload.get("type");
            String eventId = (String) payload.get("event_id");
            Optional<Event> event = eventRepository.findByRoomId(eventId);
            
            if (!event.isPresent()) {
                sendErrorAndClose(session, "Event not found",
                        "This event id you're trying to join is not found in the system.");
                return;
            }

            if (type.equals("stream_ended")) {
                // Redis key
                String redisKey = "event_room:" + eventId;
                String eventJson = stringRedisTemplate.opsForValue().get(redisKey);
                if (eventJson == null || eventJson.isEmpty()) {
                    sendErrorAndClose(session, "Room not found", "This event room doesn't exist or has no host details.");
                    return;
                }
                
                Event eventOpt = event.get();
                endEvent(eventOpt);

                stringRedisTemplate.delete(redisKey);
            }

            if (type.equals("invite_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleUserInviteCohost(eventId, participantId);
            }

            if (type.equals("accept_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleUserAcceptCohost(eventId, participantId);
            }

            if (type.equals("leave_room")) {
                String participantId = (String) payload.get("user_id");
                handleUserExistRoom(eventId, participantId);
            }

            if (type.equals("remove_user_in_room")) {
                String participantId = (String) payload.get("user_id");
                handleRemoveUserFromRoomByHost(eventId, participantId);
            }

            if (type.equals("remove_cohost")) {
                String participantId = (String) payload.get("user_id");
                handleRemoveCohost(eventId, participantId);
            }
            
            if ("chat_message".equals(type) || type.equals("text_message") || type.equals("chat") || "broadcast_message".equals(type)) {
                String textMessage = (String) payload.get("message");
                String participantId = (String) payload.get("participant_id");
                if (textMessage != null && !textMessage.trim().isEmpty()) {
                    handleChatMessage(eventId, textMessage.trim(), participantId);
                }
            }

        } catch (Exception e) {
            sendErrorAndClose(session, "Error", "Invalid message format");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        System.out.println("Disconnected: " + session.getId());
        sessions.remove(session.getId());
    }

   
    private void handleHostConnection(WebSocketSession session, String userId, String token) throws IOException {
        // JWT validation
        if (token == null || token.isEmpty()) {
            sendErrorAndClose(session, "JWT token is missing", "Authentication required");
            return;
        }

        if (!jwtTokenProvider.validateToken(token)) {
            sendErrorAndClose(session, "Invalid token", "Invalid or expired token");
            return;
        }
        
        String extractedUserId = jwtTokenProvider.getUserIdFromJWT(token);

        if (!extractedUserId.equals(userId)) {
            sendErrorAndClose(session, "Unauthorized", "User ID mismatch");
            return;
        }

        // Create new event
        Event event = new Event();
        event.setRoomId(UUID.randomUUID().toString());
        event.setHostId(userId);
        event.setStatus(EventStatus.active);
        event.setStreamType(StreamType.AUDIO);
        eventRepository.save(event);

        activeEvents.put(event.getRoomId(), event);

        // Prepare session data
        String sessionId = session.getId();
        session.getAttributes().put("userId", userId);
        session.getAttributes().put("isHost", true);
        session.getAttributes().put("eventId", event.getRoomId());

        long userIdLong = Long.parseLong(userId);

        Optional<Users> users = userRepository.findById(userIdLong);
        if (!users.isPresent()) {
            sendErrorAndClose(session, "User not found",
                    "User not found");
            return;
        }
        // Store host details
        Map<String, Object> hostDetails = new HashMap<>();
        hostDetails.put("userId", userId);
        hostDetails.put("username", users.get().getUsername());
        hostDetails.put("startedAt", Instant.now().toString());
        // Store event data
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("hostSessionId", sessionId); // Explicit host session ID
        eventData.put("roomId", event.getRoomId());
        eventData.put("hostId", userId); // Store host ID separately
        eventData.put("hostDetails", hostDetails);
        eventData.put("participants", new HashMap<>());
        eventData.put("total_participants", 0);

        // Store in Redis
        String redisKey = "event_room:" + event.getRoomId();
        stringRedisTemplate.opsForValue().set(redisKey, new ObjectMapper().writeValueAsString(eventData));

        // Store host session separately
        stringRedisTemplate.opsForValue().set("event_host_session:" + userId, sessionId);
        sessionManager.addSession(sessionId, session);

        // Store in memory
        sessions.put(sessionId, session);
        sessionToEventMap.put(sessionId, event.getRoomId());

        // Send stream link
        String joinUrl = generateStreamingLink(event.getRoomId(), userId);
        sendMessage(session, new StreamLinkMessage("stream_link", event.getRoomId(), joinUrl));
    }

    
    private void handleParticipantConnection(WebSocketSession session, String eventId, String participantId,
            String username) throws IOException {
        // 1. Validate event exists and is active
        Optional<Event> event = eventRepository.findByRoomId(eventId);
        if (!event.isPresent()) {
            sendErrorAndClose(session, "Event not found",
                    "This event id you're trying to join is not found in the system.");
            return;
        }

        if (event.get().getStatus() != EventStatus.active) {
            sendErrorAndClose(session, "Event ended", "This event is no longer active");
            return;
        }

        // 2. Prepare participant data
        String displayName = (username != null) ? username : "Guest";
        Event save_event = event.get();
        String redisKey = "event_room:" + eventId;
        ObjectMapper mapper = new ObjectMapper();

        // 3. Get or create room data in Redis
        String eventJson = stringRedisTemplate.opsForValue().get(redisKey);
        if (eventJson == null || eventJson.isEmpty()) {
            sendErrorAndClose(session, "Room not found", "This event room doesn't exist or has no host details.");
            return;
        }

        try {
            // 4. Parse and update room data
            Map<String, Object> eventMap = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) eventMap
                    .get("participants");
            if (participantsMap == null) {
                participantsMap = new LinkedHashMap<>();
            }

            // 5. Check if participant exists 
            boolean isReconnecting = false;
            for (Map<String, Object> participant : participantsMap.values()) {
                if (participantId.equals(participant.get("participantId"))) {
                    isReconnecting = true;
                    participant.put("isReconnect", true);
                    break;
                }
            }

            // 6. Add new participant if not reconnecting
            if (!isReconnecting) {
                int nextIndex = participantsMap.size();
                Map<String, Object> participantData = new HashMap<>();
                participantData.put("participantId", participantId);
                participantData.put("username", displayName);
                participantData.put("joinedAt", Instant.now().toString());
                participantData.put("sessionId", session.getId());
                participantData.put("isReconnect", false);
                participantData.put("is_cohost", false);
                participantsMap.put(nextIndex, participantData);

                // Update participant count
                eventMap.put("total_participants",
                        ((Number) eventMap.getOrDefault("total_participants", 0)).intValue() + 1);
            }

            // 7. Update Redis data
            eventMap.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(eventMap));

            // 8. Store session mapping (CRITICAL FIX)
            session.getAttributes().put("participantId", participantId);
            session.getAttributes().put("eventId", eventId);

            String sessionRedisKey = "event_participant_session:" + participantId;
            stringRedisTemplate.opsForValue().set(sessionRedisKey, session.getId());
            sessionManager.addSession(session.getId(), session);

            // Use sessionId from Redis if it exists, otherwise fallback to current
            // session's id
            String redisSessionId = (String) eventMap.get("sessionId");
            String finalSessionId = (redisSessionId != null && !redisSessionId.isEmpty())
                    ? redisSessionId
                    : session.getId();
            sessionManager.addSession(finalSessionId, session);
            activeSessions.put(finalSessionId, session);
            sessionToRoomMap.put(finalSessionId, eventId);
            // 9. Update database async
            int updatedCount = ((Number) eventMap.get("total_participants")).intValue();
            if (!isReconnecting && !participantRepository.existsByEventRoomIdAndUserId(eventId, participantId)) {
                CompletableFuture.runAsync(() -> {
                    Participants participant = new Participants();
                    participant.setEvent(save_event);
                    participant.setUserId(participantId);
                    participant.setUsername(displayName);
                    participantRepository.save(participant);

                    save_event.setTotalParticipants((long) updatedCount);
                    eventRepository.save(save_event);
                });
            }

            // 10. Notify all participants
            String joinMessage = displayName + (isReconnecting ? " reconnected" : " joined") + " the live event";
            broadcastMessage(eventId, joinMessage, displayName, participantId);
            broadcastParticipantList(eventId);
            broadcastParticipantCount(eventId);
            sendChatHistory(eventId, participantId);

        } catch (Exception e) {
            sendErrorAndClose(session, "Connection error", "Failed to establish connection");
        }
    }

    
    public void broadcastMessage(String roomId, String message, String username, String userId) {
        // Create the message payload
        Map<String, Object> payload = new HashMap<>();
        payload.put("type", "broadcast_message");
        payload.put("message", message);
        payload.put("username", username);
        payload.put("user_id", userId);
        payload.put("timestamp", Instant.now().toString());
        try {
            // 1. Get room data from Redis
            String redisKey = "event_room:" + roomId;
            String redisData = stringRedisTemplate.opsForValue().get(redisKey);

            if (redisData == null || redisData.isEmpty()) {
                System.out.println("❌ No data found in Redis for roomId: " + roomId);
                return;
            }

            // 2. Parse the room data
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(redisData, new TypeReference<Map<String, Object>>() {
            });
            Map<String, Object> participants = (Map<String, Object>) roomData.get("participants");

            if (participants == null || participants.isEmpty()) {
                System.out.println("⚠️ No participants found in room: " + roomId);
                return;
            }

            // 3. Convert payload to JSON
            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);
    
            // 4. Broadcast to all participants
            for (Object participantObj : participants.values()) {
                Map<String, Object> participant = (Map<String, Object>) participantObj;
                String participantId = (String) participant.get("participantId");

                // Get the WebSocket session for this participant
                String sessionKey = "event_participant_session:" + participantId;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);
                // 2. Get the actual WebSocket session
                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    session.sendMessage(textMessage);
                   
                }
            }
        } catch (JsonProcessingException e) {
            System.out.println("❌ JSON processing error: " + e.getMessage());
        } catch (Exception e) {
            System.out.println("❌ Unexpected error during broadcast: " + e.getMessage());
        }
    }

    
    private void broadcastParticipantList(String eventId) {
        try {
            // 1. Get room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);
            
            if (eventJson == null || eventJson.isEmpty()) {
                return;
            }
            
            // 2. Parse the room data
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<Map<String, Object>>() {
            });
           
    
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData.get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                return;
            }

            // 3. Prepare participant list payload
            List<Map<String, Object>> participantList = participantsMap.values().stream()
                    .map(participant -> {
                        Map<String, Object> simplified = new HashMap<>();
                        simplified.put("id", participant.get("participantId"));
                        simplified.put("username", participant.get("username"));
                        simplified.put("is_cohost", participant.get("is_cohost"));
                        simplified.put("joinedAt", participant.get("joinedAt"));
                        return simplified;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "participant_list");
            payload.put("participants", participantList);

            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 4. Send to all participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String participantId = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + participantId;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);
              
                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                        }
                    }
                }
            }

            // 5. Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
     
            if (hostDetails != null) {
                String hostSessionKey = "event_host_session:" + roomData.get("hostId");
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);
                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    // Get session from memory
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            // Clean up on failure
                            System.out.println("Failed to send to host participants list");
                        }
                    } else {
                        System.out.println("Host WebSocketSession is null or closed");
                    }
                } else {
                    System.out.println("Session ID not found in Redis for host user ID: " + hostSessionId);
                }
            }

        } catch (JsonProcessingException e) {
            // log.error("JSON processing error while broadcasting participant list: {}", e.getMessage());
        } catch (Exception e) {
            // log.error("Unexpected error broadcasting participant list: {}", e.getMessage());
        }
    }

    
    private void broadcastParticipantCount(String eventId) {
        try {
            // 1. Get room data
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);
            if (eventJson == null || eventJson.isEmpty()) {
                return;
            }

            // 2. Parse data
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            int participantCount = ((Number) roomData.getOrDefault("total_participants", 0)).intValue();

            // 3. Prepare message
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "participant_count");
            payload.put("count", participantCount);
            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            // 4. Send to host first (same approach as broadcastParticipantList)
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                if (hostId != null) {
                    String hostSessionKey = "event_host_session:" + hostId;
                    String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);
                    if (hostSessionId != null) {
                        WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                        if (hostSession != null && hostSession.isOpen()) {
                            try {
                                hostSession.sendMessage(textMessage);
                            } catch (IOException e) {
                                System.out.println("Failed to send to host: " + e.getMessage());
                                stringRedisTemplate.delete(hostSessionKey);
                                sessionManager.removeSession(hostSessionId);
                            }
                        }
                    }
                }
            }

            // 5. Send to participants
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");
            if (participantsMap != null) {
                for (Map<String, Object> participant : participantsMap.values()) {
                    String participantId = (String) participant.get("participantId");
                    String sessionKey = "event_participant_session:" + participantId;
                    String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                    if (sessionId != null) {
                        WebSocketSession session = sessionManager.getSession(sessionId);
                        if (session != null && session.isOpen()) {
                            try {
                                session.sendMessage(textMessage);
                            } catch (IOException e) {
                                stringRedisTemplate.delete(sessionKey);
                                sessionManager.removeSession(sessionId);
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Broadcast error: " + e.getMessage());
        }
    }
    
    
    // Updated implementation that gets user details internally
    private String generateStreamingLink(String eventId, String userId) {
        long userIdLong = Long.parseLong(userId);

        Users currentUser = userRepository.findById(userIdLong)
                .orElseThrow(() -> new UsernameNotFoundException("User not found"));

        return String.format("ws://localhost:8011/ws/stream/live/join/event/%s/%s/%s/",
                eventId,
                currentUser.getUsername(),
                currentUser.getId());
    }
    
    
    // Utility methods
    private Map<String, String> extractPathVariables(URI uri) {
        Map<String, String> pathVariables = new HashMap<>();
        String path = uri.getPath();
        String[] parts = path.split("/");
        if (parts.length > 3)
            pathVariables.put("userId", parts[3]);
        if (parts.length > 4)
            pathVariables.put("eventId", parts[4]);
        if (parts.length > 5)
            pathVariables.put("participantId", parts[5]);
        if (parts.length > 6)
            pathVariables.put("username", parts[6]);

        return pathVariables;
    }

    
    private String extractTokenFromQuery(String query) {
        if (query == null || query.isEmpty()) {
            return null;
        }

        String[] params = query.split("&");
        for (String param : params) {
            if (param.startsWith("token=")) {
                return param.substring(6);
            }
        }
        return null;
    }

    
    private void sendErrorAndClose(WebSocketSession session, String message, String details) {
        try {
            Map<String, Object> error = new HashMap<>();
            error.put("status", "error");
            error.put("message", message);
            error.put("details", details);

            String errorJson = new ObjectMapper().writeValueAsString(error);
            session.sendMessage(new TextMessage(errorJson));

            // Small delay to ensure message delivery before closing
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }

            if (session.isOpen()) {
                session.close(CloseStatus.NOT_ACCEPTABLE.withReason(message));
            }
        } catch (Exception e) {
            try {
                if (session.isOpen()) {
                    session.close(CloseStatus.SERVER_ERROR.withReason("Internal server error"));
                }
            } catch (IOException ignored) {
            }
        }
    }

    
    private void sendMessage(WebSocketSession session, Object message) throws IOException {
        try {
            ObjectMapper mapper = new ObjectMapper();
            String json = mapper.writeValueAsString(message);
            session.sendMessage(new TextMessage(json));
        } catch (JsonProcessingException e) {
            throw new IOException("Failed to send message", e);
        }
    }

    
    public String getEventHost(String eventId) {
        String redisKey = "event:" + eventId;
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json == null)
            return null;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);
            JsonNode hostDetails = root.path("hostDetails");
            return hostDetails.path("userId").asText(); // returns string "1001" etc.
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    
    public boolean isCohost(String eventId, String participantId) {
        String redisKey = "event:" + eventId;
        String json = stringRedisTemplate.opsForValue().get(redisKey);
        if (json == null) return false;

        try {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(json);

            JsonNode participantsNode = root.path("participants");
            String hostId = getEventHost(eventId);

            for (JsonNode participant : participantsNode) {
                String id = participant.path("participantId").asText();
                if (participantId.equals(id) && !participantId.equals(hostId)) {
                    return true;
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    
    private void endEvent(Event event) {
        String eventId = event.getRoomId();

        // 1. First broadcast the event ending
        Map<String, Object> endPayload = new HashMap<>();
        endPayload.put("type", "event_ended");
        endPayload.put("message", "Host has ended the event");
        endPayload.put("timestamp", Instant.now().toString());

        // Broadcast to all active sessions
        broadcastToAllSessions(eventId, endPayload);

        // 2. Then update database
        event.setStatus(EventStatus.ended);
        event.setEndTimestamp(Instant.now());
        eventRepository.save(event);
    }

    
    // Enhanced broadcast method
    private void broadcastToAllSessions(String eventId, Object payload) {
        try {
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("⚠️ No room found for event: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<Map<String, Object>>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("⚠️ No participants in room: " + eventId);
                return;
            }

            String jsonMessage = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonMessage);

            for (Map<String, Object> participant : participantsMap.values()) {
                String participantId = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + participantId;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            System.out.println("❌ Failed to send to session " + sessionId + ": " + e.getMessage());
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("❌ Error in broadcastToAllSessions: " + e.getMessage());
        }
    }

    
    private void handleUserInviteCohost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participants = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participants == null || participants.isEmpty())
                return;

            // 2. Check participant exists
            boolean participantExists = participants.values().stream()
                    .anyMatch(p -> participantId.equals(p.get("participantId")));

            if (!participantExists) {
                System.out.println("Participant not found: " + participantId);
                return;
            }

            // 3. Get participant's session from Redis
            String sessionKey = "event_participant_session:" + participantId;
            String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

            if (sessionId == null) {
                System.out.println("Session ID not found for participant: " + participantId);
                return;
            }

            WebSocketSession participantSession = sessionManager.getSession(sessionId);
            if (participantSession != null && participantSession.isOpen()) {
                // 4. Prepare the co-host invitation payload
                Map<String, Object> payload = new HashMap<>();
                payload.put("type", "cohost_invite");
                payload.put("message", "You've been invited to be a co-host on this event.");
                payload.put("timestamp", Instant.now().toString());

                String json = mapper.writeValueAsString(payload);
                participantSession.sendMessage(new TextMessage(json));
            } else {
                System.out.println("❌ WebSocket session is closed or missing for " + participantId);
            }
        } catch (Exception e) {
            System.out.println("Error inviting co-host: " + e.getMessage());
        }
    }


    private void handleUserAcceptCohost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and update the participant's co-host status
            boolean participantFound = false;
            for (Map.Entry<Integer, Map<String, Object>> entry : participantsMap.entrySet()) {
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    participant.put("is_cohost", true);
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update Redis with the modified participant data
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 4. Get the participant's username for notification
            String username = (String) participantsMap.values().stream()
                    .filter(p -> participantId.equals(p.get("participantId")))
                    .findFirst()
                    .map(p -> p.get("username"))
                    .orElse("A participant");

            // 5. Prepare notification message
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_joined");
            notificationPayload.put("message", username + " is now a co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", true);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // 6. Broadcast notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + pid;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected participant session: " + pid);
                        }
                    }
                }
            }

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                            stringRedisTemplate.delete(hostSessionKey);
                            sessionManager.removeSession(hostSessionId);
                        }
                    }
                }
            }

            // 7. Broadcast updated participant list to everyone
            broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error handling co-host acceptance: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleUserExistRoom(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and remove the participant
            String username = null;
            Iterator<Map.Entry<Integer, Map<String, Object>>> iterator = participantsMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Map<String, Object>> entry = iterator.next();
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    username = (String) participant.get("username");
                    iterator.remove();
                    break;
                }
            }

            if (username == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update participant count
            int currentCount = ((Number) roomData.getOrDefault("total_participants", 0)).intValue();
            roomData.put("total_participants", Math.max(0, currentCount - 1));

            // 4. Update Redis
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 5. Clean up participant session
            String sessionKey = "event_participant_session:" + participantId;
            stringRedisTemplate.delete(sessionKey);

            // 6. Prepare leave notification
            Map<String, Object> leavePayload = new HashMap<>();
            leavePayload.put("type", "participant_left");
            leavePayload.put("message", username + " left the room");
            leavePayload.put("participantId", participantId);
            leavePayload.put("timestamp", Instant.now().toString());

            String leaveMessage = mapper.writeValueAsString(leavePayload);
            TextMessage leaveTextMessage = new TextMessage(leaveMessage);

            // 7. Broadcast leave notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String pSessionKey = "event_participant_session:" + pid;
                String pSessionId = stringRedisTemplate.opsForValue().get(pSessionKey);

                if (pSessionId != null) {
                    WebSocketSession session = sessionManager.getSession(pSessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(pSessionKey);
                            sessionManager.removeSession(pSessionId);
                        }
                    }
                }
            }

            // 8. Broadcast updated participant list and count
            broadcastParticipantList(eventId);
            broadcastParticipantCount(eventId);

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling user exit: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleRemoveUserFromRoomByHost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and remove the participant
            String username = null;
            Iterator<Map.Entry<Integer, Map<String, Object>>> iterator = participantsMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Map<String, Object>> entry = iterator.next();
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    username = (String) participant.get("username");
                    iterator.remove();
                    break;
                }
            }

            if (username == null) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update participant count
            int currentCount = ((Number) roomData.getOrDefault("total_participants", 0)).intValue();
            roomData.put("total_participants", Math.max(0, currentCount - 1));

            // 4. Update Redis
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 5. Clean up participant session
            String sessionKey = "event_participant_session:" + participantId;
            stringRedisTemplate.delete(sessionKey);

            // 6. Prepare leave notification
            Map<String, Object> leavePayload = new HashMap<>();
            leavePayload.put("type", "participant_removed");
            leavePayload.put("message", username + " has been removed from the room.");
            leavePayload.put("participantId", participantId);
            leavePayload.put("timestamp", Instant.now().toString());

            String leaveMessage = mapper.writeValueAsString(leavePayload);
            TextMessage leaveTextMessage = new TextMessage(leaveMessage);

            // 7. Broadcast leave notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String pSessionKey = "event_participant_session:" + pid;
                String pSessionId = stringRedisTemplate.opsForValue().get(pSessionKey);

                if (pSessionId != null) {
                    WebSocketSession session = sessionManager.getSession(pSessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(pSessionKey);
                            sessionManager.removeSession(pSessionId);
                        }
                    }
                }
            }

            // 8. Broadcast updated participant list and count
            broadcastParticipantList(eventId);
            broadcastParticipantCount(eventId);

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(leaveTextMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                        }
                    }
                }
            }

        } catch (Exception e) {
            System.out.println("Error handling user exit: " + e.getMessage());
            e.printStackTrace();
        }
    }

    
    private void handleRemoveCohost(String eventId, String participantId) {
        try {
            // 1. Fetch room data from Redis
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);

            if (eventJson == null || eventJson.isEmpty()) {
                System.out.println("Room not found for eventId: " + eventId);
                return;
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {
            });
            Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData
                    .get("participants");

            if (participantsMap == null || participantsMap.isEmpty()) {
                System.out.println("No participants found in room");
                return;
            }

            // 2. Find and update the participant's co-host status to false
            boolean participantFound = false;
            String username = null;
            for (Map.Entry<Integer, Map<String, Object>> entry : participantsMap.entrySet()) {
                Map<String, Object> participant = entry.getValue();
                if (participantId.equals(participant.get("participantId"))) {
                    participant.put("is_cohost", false);
                    username = (String) participant.get("username");
                    participantFound = true;
                    break;
                }
            }

            if (!participantFound) {
                System.out.println("Participant not found in room: " + participantId);
                return;
            }

            // 3. Update Redis with the modified participant data
            roomData.put("participants", participantsMap);
            stringRedisTemplate.opsForValue().set(redisKey, mapper.writeValueAsString(roomData));

            // 4. Prepare removal notification
            Map<String, Object> notificationPayload = new HashMap<>();
            notificationPayload.put("type", "cohost_removed");
            notificationPayload.put("message", username + " has been removed as co-host");
            notificationPayload.put("participant_id", participantId);
            notificationPayload.put("isCoHost", false);
            notificationPayload.put("username", username);
            notificationPayload.put("timestamp", Instant.now().toString());

            String notificationMessage = mapper.writeValueAsString(notificationPayload);
            TextMessage textMessage = new TextMessage(notificationMessage);

            // 5. Broadcast notification to all participants and host
            // Send to participants
            for (Map<String, Object> participant : participantsMap.values()) {
                String pid = (String) participant.get("participantId");
                String sessionKey = "event_participant_session:" + pid;
                String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                if (sessionId != null) {
                    WebSocketSession session = sessionManager.getSession(sessionId);
                    if (session != null && session.isOpen()) {
                        try {
                            session.sendMessage(textMessage);
                        } catch (IOException e) {
                            stringRedisTemplate.delete(sessionKey);
                            sessionManager.removeSession(sessionId);
                            System.out.println("Cleaned up disconnected participant session: " + pid);
                        }
                    }
                }
            }

            // Send to host
            Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
            if (hostDetails != null) {
                String hostId = (String) roomData.get("hostId");
                String hostSessionKey = "event_host_session:" + hostId;
                String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                if (hostSessionId != null) {
                    WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                    if (hostSession != null && hostSession.isOpen()) {
                        try {
                            hostSession.sendMessage(textMessage);
                        } catch (IOException e) {
                            System.out.println("Failed to send to host: " + e.getMessage());
                            stringRedisTemplate.delete(hostSessionKey);
                            sessionManager.removeSession(hostSessionId);
                        }
                    }
                }
            }

            // 6. Broadcast updated participant list to everyone
            broadcastParticipantList(eventId);

        } catch (Exception e) {
            System.out.println("Error removing co-host: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void handleChatMessage(String eventId, String message, String participantId) {
        try {
            long userIdLong = Long.parseLong(participantId);
            Optional<Users> userdetails = userRepository.findById(userIdLong);

            // 1. Prepare chat message payload
            Map<String, Object> chatPayload = new HashMap<>();
            chatPayload.put("type", "chat_message");
            chatPayload.put("message", message);
            chatPayload.put("username", userdetails.get().getUsername());
            chatPayload.put("user_id", participantId);
            chatPayload.put("timestamp", Instant.now().toString());

            // 2. Store message in Redis chat history
            String chatHistoryKey = "event_chat_history:" + eventId;
            ObjectMapper mapper = new ObjectMapper();
            stringRedisTemplate.opsForList().rightPush(chatHistoryKey, mapper.writeValueAsString(chatPayload));
            
            // Keep only the last 100 messages (optional)
            stringRedisTemplate.opsForList().trim(chatHistoryKey, -100, -1);

            // 3. Broadcast message to all participants and host
            String redisKey = "event_room:" + eventId;
            String eventJson = stringRedisTemplate.opsForValue().get(redisKey);
            
            if (eventJson != null && !eventJson.isEmpty()) {
                Map<String, Object> roomData = mapper.readValue(eventJson, new TypeReference<>() {});
                Map<Integer, Map<String, Object>> participantsMap = (Map<Integer, Map<String, Object>>) roomData.get("participants");

                TextMessage textMessage = new TextMessage(mapper.writeValueAsString(chatPayload));

                // Send to participants
                if (participantsMap != null) {
                    for (Map<String, Object> participant : participantsMap.values()) {
                        String pid = (String) participant.get("participantId");
                        String sessionKey = "event_participant_session:" + pid;
                        String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

                        if (sessionId != null) {
                            WebSocketSession session = sessionManager.getSession(sessionId);
                            if (session != null && session.isOpen()) {
                                try {
                                    session.sendMessage(textMessage);
                                } catch (IOException e) {
                                    stringRedisTemplate.delete(sessionKey);
                                    sessionManager.removeSession(sessionId);
                                }
                            }
                        }
                    }
                }

                // Send to host
                Map<String, Object> hostDetails = (Map<String, Object>) roomData.get("hostDetails");
                if (hostDetails != null) {
                    String hostId = (String) roomData.get("hostId");
                    String hostSessionKey = "event_host_session:" + hostId;
                    String hostSessionId = stringRedisTemplate.opsForValue().get(hostSessionKey);

                    if (hostSessionId != null) {
                        WebSocketSession hostSession = sessionManager.getSession(hostSessionId);
                        if (hostSession != null && hostSession.isOpen()) {
                            try {
                                hostSession.sendMessage(textMessage);
                            } catch (IOException e) {
                                System.out.println("Failed to send chat to host: " + e.getMessage());
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error handling chat message: " + e.getMessage());
            e.printStackTrace();
        }
    }


    private void sendChatHistory(String eventId, String targetParticipantId) {
        try {
            // 1. Get chat history from Redis
            String chatHistoryKey = "event_chat_history:" + eventId;
            List<String> chatMessages = stringRedisTemplate.opsForList().range(chatHistoryKey, 0, -1);

            if (chatMessages == null || chatMessages.isEmpty()) {
                return;
            }

            // 2. Parse messages and prepare payload
            ObjectMapper mapper = new ObjectMapper();
            List<Map<String, Object>> messages = chatMessages.stream()
                    .map(msg -> {
                        try {
                            return mapper.readValue(msg, new TypeReference<Map<String, Object>>() {
                            });
                        } catch (JsonProcessingException e) {
                            return null;
                        }
                    })
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());

            // 3. Prepare chat history payload
            Map<String, Object> payload = new HashMap<>();
            payload.put("type", "chat_history");
            payload.put("messages", messages);
            String jsonPayload = mapper.writeValueAsString(payload);
            TextMessage textMessage = new TextMessage(jsonPayload);

            // 4. Get the target participant's session
            String sessionKey = "event_participant_session:" + targetParticipantId;
            String sessionId = stringRedisTemplate.opsForValue().get(sessionKey);

            if (sessionId != null) {
                WebSocketSession session = sessionManager.getSession(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        session.sendMessage(textMessage);
                    } catch (IOException e) {
                        stringRedisTemplate.delete(sessionKey);
                        sessionManager.removeSession(sessionId);
                        System.out.println("Failed to send chat history to participant " + targetParticipantId);
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("Error sending chat history: " + e.getMessage());
            e.printStackTrace();
        }
    }
    
}
