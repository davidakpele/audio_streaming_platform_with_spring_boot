package com.example.streaming.poto;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Service;
import com.example.streaming.model.Participants;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;

@Service
public class RedisService {

    private final RedisTemplate<String, Object> redisTemplate;

    @SuppressWarnings("unused")
    private final RedisTemplate<String, String> customStringRedisTemplate;

    @Autowired
    private RedisMessageListenerContainer redisMessageListenerContainer;
    // Redis Keys
    private static final String AUDIO_CHANNEL_PREFIX = "audio_channel:";

    // Constructor injection for better testability
    @Autowired
    public RedisService(RedisTemplate<String, Object> redisTemplate,
            RedisTemplate<String, String> customStringRedisTemplate) {
        this.redisTemplate = redisTemplate;
        this.customStringRedisTemplate = customStringRedisTemplate;
    }

    // Add participant to an event
    public void addParticipantToEvent(String eventId, String participantId, Map<String, String> participantMap)
            throws JsonProcessingException {
        String redisKey = "event:" + eventId + ":participants";
        String participantJson = new ObjectMapper().writeValueAsString(participantMap);
        redisTemplate.opsForHash().put(redisKey, participantId, participantJson);
    }

    // Remove participant from an event
    public void removeParticipantFromEvent(String eventId, String participantId) {
        redisTemplate.opsForHash().delete("event:" + eventId + ":participants", participantId);
    }

    // Get all participants for an event
    public Map<String, Map<String, String>> getEventParticipants(String eventId) {
        Map<Object, Object> redisEntries = redisTemplate.opsForHash().entries("event:" + eventId + ":participants");

        Map<String, Map<String, String>> participants = new HashMap<>();
        ObjectMapper mapper = new ObjectMapper();

        for (Map.Entry<Object, Object> entry : redisEntries.entrySet()) {
            try {
                String participantId = (String) entry.getKey();
                String json = (String) entry.getValue();
                Map<String, String> participantDetails = mapper.readValue(json,
                        new TypeReference<Map<String, String>>() {
                        });
                participants.put(participantId, participantDetails);
            } catch (JsonProcessingException e) {
                // Optional: log error or skip invalid entry
                e.printStackTrace();
            }
        }

        return participants;
    }

    // Set host data
    public void setHost(String hostId, String hostname, String roomId) {
        String redisKey = "host:" + hostId;
        Map<String, String> hostData = new HashMap<>();
        hostData.put("roomId", roomId);
        hostData.put("hostname", hostname);
        redisTemplate.opsForHash().putAll(redisKey, hostData);
    }

    // Check if a participant exists in an event
    public boolean participantExists(String eventId, String participantId) {
        String redisKey = "event:" + eventId + ":participants";
        return Boolean.TRUE.equals(redisTemplate.opsForHash().hasKey(redisKey, participantId));
    }

    // Get a participant's details from Redis
    public Participants getParticipant(String eventId, String participantId) throws JsonProcessingException {
        String redisKey = "event:" + eventId + ":participants";
        String participantJson = (String) redisTemplate.opsForHash().get(redisKey, participantId);
        if (participantJson == null)
            return null;
        return new ObjectMapper().readValue(participantJson, Participants.class);
    }

    // Remove all data related to an event from Redis
    public void removeEventFromRedis(String eventId) {
        // Define the pattern for all keys related to the event
        String eventKeyPattern = "event:" + eventId + ":*";
        String hostKeyPattern = "host:" + eventId + "*";

        // Scan and remove all event-related keys
        Set<String> keysToDelete = redisTemplate.keys(eventKeyPattern);
        if (keysToDelete != null) {
            redisTemplate.delete(keysToDelete);
        }

        // Optionally, remove the host data as well
        Set<String> hostKeysToDelete = redisTemplate.keys(hostKeyPattern);
        if (hostKeysToDelete != null) {
            redisTemplate.delete(hostKeysToDelete);
        }
    }

    // Method to publish the audio message to the event's Redis channel
    public void publishAudioData(String eventId, byte[] audioData) {
        String redisChannel = AUDIO_CHANNEL_PREFIX + eventId;
        redisTemplate.convertAndSend(redisChannel, audioData);
    }

    // Subscribe a WebSocket session to a Redis channel
    public void subscribeToEventChannel(String eventId, MessageListener listener) {
        String redisChannel = AUDIO_CHANNEL_PREFIX + eventId;
        redisMessageListenerContainer.addMessageListener(listener, new ChannelTopic(redisChannel));
    }

    // Unsubscribe a WebSocket session from the Redis channel
    public void unsubscribeFromEventChannel(String eventId, MessageListener listener) {
        String redisChannel = AUDIO_CHANNEL_PREFIX + eventId;
        redisMessageListenerContainer.removeMessageListener(listener, new ChannelTopic(redisChannel));
    }

}
