package com.example.streaming.controllers;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import com.example.streaming.config.FileStorageConfig;
import com.example.streaming.dtos.UserDTO;
import com.example.streaming.exceptions.Error;
import com.example.streaming.model.Users;
import com.example.streaming.payloads.UserSignUpRequest;
import com.example.streaming.services.UserRecordService;
import com.example.streaming.services.UserService;
import com.example.streaming.utils.KeyWrapper;
import org.springframework.util.StringUtils;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/user")
public class UserController {

    private final UserService userServices;
    private final UserRecordService userRecordService;
    private final FileStorageConfig fileStorageConfig;
    private final KeyWrapper keysWrapper;
 
    @PreAuthorize("hasRole('USER')")
    @GetMapping("/personal-details/{id}")
    public ResponseEntity<?> getUserByUserId(@PathVariable Long id, HttpServletResponse response,
            Authentication authentication) {
        if (id == null || id <= 0) {
            return Error.createResponse("Invalid request sent.", HttpStatus.BAD_REQUEST, "User Id is missing");
        } else if (userServices.getUserById(id) == null) {
            return Error.createResponse("User with ID " + id + " does not exist.", HttpStatus.BAD_REQUEST,
                    "User does not exist");
        }
        UserDTO userDTO = userRecordService.getUserDetailsById(id, authentication);

        return ResponseEntity.ok(userDTO);
    }

    @PreAuthorize("hasRole('USER')")
    @GetMapping("/by/username/{username}")
    public ResponseEntity<?> getUserByUsername(@PathVariable String username, HttpServletResponse response,
            Authentication authentication) {
        if (username == null || username.isEmpty()) {
            return Error.createResponse("Username is require.*", HttpStatus.BAD_REQUEST, "Username is require.*");
        } else if (userServices.getUserByUsername(username) == null) {
            return Error.createResponse("User with username " + username + " does not exist.", HttpStatus.BAD_REQUEST,
                    "User does not exist");
        }
        UserDTO userDTO = userRecordService.getUserByUsername(username, authentication);

        return ResponseEntity.ok(userDTO);
    }

    @PreAuthorize("hasRole('USER')")
    @PutMapping("/settings/reset-password/{id}")
    public ResponseEntity<?> resetPassword(@PathVariable Long id, @RequestBody UserSignUpRequest request,
            Authentication authentication) {
        return ResponseEntity.ok(userServices.resetPassword(id, request.getPassword(), authentication));
    }

    @PreAuthorize("hasAnyRole('USER', 'ADMIN')")
    @PutMapping("/settings/deactivate-account/{id}")
    public ResponseEntity<?> deactivateAccount(@PathVariable Long id, Authentication authentication) {
        // deactivate account
        return ResponseEntity.ok(userServices.deactivateAccount(id, authentication));
    }


    @GetMapping("/profile")
    public ResponseEntity<?> getUserProfile(Authentication authentication) {
        String username = authentication.getName();
        Users user = userServices.getUserByUsername(username);
        if (!username.equals(user.getUsername())) {
            return Error.createResponse(
                    "UNAUTHORIZE ACCESS", HttpStatus.FORBIDDEN,
                    "You dont have access to the endpoints");
        }
        return ResponseEntity.ok(user);
    }


    @PostMapping("/settings/update/user")
    public ResponseEntity<?> updateUser(
            @RequestParam(value = "profile", required = false) MultipartFile profile,
            @RequestParam("username") String username,
            @RequestParam("email") String email,
            @RequestParam("gender") String gender, Authentication authentication) {
        String requestUsername = authentication.getName();
        Users user = userServices.getUserByUsername(requestUsername);
        if (!username.equals(user.getUsername())) {
            return Error.createResponse(
                    "UNAUTHORIZE ACCESS", HttpStatus.FORBIDDEN,
                    "You dont have access to the endpoints");
        }
        try {
            String profilePath = null;

            // Handle file upload if present
            if (profile != null && !profile.isEmpty()) {
                // Ensure the upload directory exists
                Path uploadDir = Paths.get(fileStorageConfig.getUploadDir());
                if (Files.notExists(uploadDir)) {
                    Files.createDirectories(uploadDir);
                }

                // Replace the original file name with the user's ID
                String userId = user.getId().toString();
                String extension = StringUtils.getFilenameExtension(profile.getOriginalFilename());
                String newFileName = userId + (extension != null ? "." + extension : "");

                // Define the file path and save the file
                Path filePath = uploadDir.resolve(newFileName);

                // Check if the file already exists, and delete it if it does
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                }

                // Copy the file to the destination
                Files.copy(profile.getInputStream(), filePath);

                // Set the profile path to be saved in the database
                String baseUrl = keysWrapper.getAssetUrl();
                profilePath = baseUrl + "/image/" + newFileName;
            }

            return ResponseEntity.ok(userServices.updateUserProfile(username, email, gender, profilePath));
        } catch (IOException e) {
            return new ResponseEntity<>("Failed to upload file: " + e.getMessage(), HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }


    @PutMapping("/{id}/account/lock")
    public ResponseEntity<?> lockUserAccount(@PathVariable Long userId, HttpServletResponse response) {
        if (userId == null) {
            return Error.createResponse("User Id is require.*", HttpStatus.BAD_REQUEST, "Username is require.*");
        } else if (userServices.getUserById(userId) == null) {
            return Error.createResponse("User with user Id " + userId + " does not exist.", HttpStatus.BAD_REQUEST,
                    "User does not exist");
        }

        return userRecordService.lockUserAccount(userId);
    }

    @PutMapping("/{id}/account/block")
    public ResponseEntity<?> blockUserAccount(@PathVariable Long userId, HttpServletResponse response) {
        if (userId == null) {
            return Error.createResponse("User Id is require.*", HttpStatus.BAD_REQUEST, "Username is require.*");
        } else if (userServices.getUserById(userId) == null) {
            return Error.createResponse("User with user Id " + userId + " does not exist.", HttpStatus.BAD_REQUEST,
                    "User does not exist");
        }

        return userRecordService.blockUserAccount(userId);
    }


}
