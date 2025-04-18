package com.example.streaming.dtos;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;
import com.example.streaming.model.UserRecord;

@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class UserRecordDTO {
    private Long id;
    private String firstName;
    private String lastName;
    private String telephone;
    private String gender;
    private boolean locked;
    private LocalDateTime lockedAt;
    private boolean isBlocked;
    private Long blockedDuration;
    private String blockedUntil;
    private String blockedReason;
    private UserDTO userDTO;
    private String photo;

    public static UserRecordDTO fromEntity(UserRecord record) {
        return UserRecordDTO.builder()
            .id(record.getId())
            .firstName(record.getFirstName())
            .lastName(record.getLastName())
            .gender(record.getGender())
            .locked(record.isLocked())
            .lockedAt(record.getLockedAt())
            .isBlocked(record.isBlocked())
            .blockedDuration(record.getBlockedDuration())
            .blockedUntil(record.getBlockedUntil())
            .blockedReason(record.getBlockedReason())
            .photo(record.getPhoto())
            .build();
    }
}
