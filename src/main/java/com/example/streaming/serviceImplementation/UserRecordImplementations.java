package com.example.streaming.serviceImplementation;

import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import com.example.streaming.dtos.UserDTO;
import com.example.streaming.model.UserRecord;
import com.example.streaming.model.Users;
import com.example.streaming.repository.UserRecordRepository;
import com.example.streaming.repository.UsersRepository;
import com.example.streaming.services.UserRecordService;


@Service
public class UserRecordImplementations implements UserRecordService {

    private final UsersRepository userRepository;
    private final UserRecordRepository userRecordRepository;

    public UserRecordImplementations(UsersRepository userRepository, UserRecordRepository userRecordRepository) {
        this.userRepository = userRepository;
        this.userRecordRepository = userRecordRepository;
    }

    @Override
    public UserDTO getUserDetailsById(Long id, Authentication authentication) {
        Users user = userRepository.findUserWithRecordById(id);
        // Get the username from authentication
        String username = authentication.getName();
        if (user == null || !user.getUsername().equals(username)) {
            return null;
        }
        return UserDTO.fromEntity(user);
    }

    @Override
    public UserDTO getUserByUsername(String username, Authentication authentication) {
        Optional<Users> GetUser = userRepository.findByUsername(username);
        // Get the username from authentication
        String NewUsername = authentication.getName();
        if (GetUser.isPresent() && NewUsername.equals(GetUser.get().getUsername())) {
            Users user = userRepository.findUserWithRecordById(GetUser.get().getId());
            return UserDTO.fromEntity(user);
        }

        return null;
    }

    @Override
    public boolean isLockedAccount(Long userId) {
        return userRecordRepository.isUserAccountLocked(userId);
    }

    @Override
    public boolean isBlockedAccount(Long userId) {
        return userRecordRepository.isUserAccountBlocked(userId);
    }

    @Override
    public Optional<UserRecord> getUserNames(Long userId) {
        return userRecordRepository.findByUserId(userId);
    }

    @Override
    public ResponseEntity<?> lockUserAccount(Long userId) {
        Optional<UserRecord> user = userRecordRepository.findByUserId(userId);
        if (user != null && user.isPresent()) {
            UserRecord updateUserAccount = user.get();

            updateUserAccount.setLocked(true);
            userRecordRepository.save(updateUserAccount);
            return ResponseEntity.ok("User account successfully lock.");
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
    }

    @Override
    public ResponseEntity<?> blockUserAccount(Long userId) {
        Optional<UserRecord> user = userRecordRepository.findByUserId(userId);
        if (user != null && user.isPresent()) {
            UserRecord updateUserAccount = user.get();
            updateUserAccount.setBlocked(true);
            userRecordRepository.save(updateUserAccount);
            return ResponseEntity.ok("User account successfully block.");
        }
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body("User not found.");
    }

}
