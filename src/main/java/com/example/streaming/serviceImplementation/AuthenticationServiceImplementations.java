package com.example.streaming.serviceImplementation;

import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.example.streaming.enums.Role;
import com.example.streaming.enums.UserStatus;
import com.example.streaming.exceptions.UserNotFoundException;
import com.example.streaming.messageProducer.AuthenticationMessageProducer;
import com.example.streaming.model.AuthorizeUserVerification;
import com.example.streaming.model.UserRecord;
import com.example.streaming.model.Users;
import com.example.streaming.model.VerificationToken;
import com.example.streaming.payloads.UserSignInRequest;
import com.example.streaming.payloads.UserSignUpRequest;
import com.example.streaming.repository.AuthorizeUserVerificationRepository;
import com.example.streaming.repository.UserRecordRepository;
import com.example.streaming.repository.UsersRepository;
import com.example.streaming.repository.VerificationTokenRepository;
import com.example.streaming.responses.AuthResponse;
import com.example.streaming.responses.VerificationTokenResult;
import com.example.streaming.services.AuthenticationService;
import com.example.streaming.services.AuthorizeUserVerificationService;
import com.example.streaming.services.JwtService;
import com.example.streaming.utils.KeyWrapper;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;


@Service
@Transactional
@RequiredArgsConstructor
public class AuthenticationServiceImplementations implements AuthenticationService {

    private static final int EXPIRATION_MINUTES = 10;
    private Date expirationTime;
    private final UsersRepository userRepository;
    private final UserRecordRepository userRecordRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthenticationMessageProducer notificationServiceClient;
    private final VerificationTokenRepository verificationTokenRepository;
    private final AuthenticationManager authenticationManager;
    private final KeyWrapper keysWrapper;
    private final AuthorizeUserVerificationService authorizeUserVerificationService;
    private final AuthorizeUserVerificationRepository authorizeUserVerificationRepository;
   

    @Transactional
    public ResponseEntity<String> createAccount(UserSignUpRequest request) {
        Long nextUserId = getNextUserId();

        Users user = Users.builder()
            .id(nextUserId)
            .email(request.getEmail())
            .username(request.getUsername())
            .twoFactorAuth(false)
            .role(Role.USER)
            .password(passwordEncoder.encode(request.getPassword()))
            .build();
        userRepository.save(user);

        // Retrieve the saved user to get the user ID
        Users savedUser = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new UserNotFoundException("User not found"));

        UserRecord userRecord = UserRecord.builder()
            .user(savedUser)
            .firstName(request.getFirstName())
            .lastName(request.getLastName())
            .gender(request.getGender())
            .telephone(request.getTelephone())
            .locked(false)
            .lockedAt(null)
            .isBlocked(false)
            .build();
        userRecordRepository.save(userRecord);

        // Create a verification token
        UUID verificationToken = UUID.randomUUID();
        expirationTime = calculateExpirationDate(EXPIRATION_MINUTES);
        VerificationToken tokenEntity = VerificationToken.builder()
                .userId(nextUserId)
                .token(String.valueOf(verificationToken))
                .expirationTime(expirationTime)
                .build();
        verificationTokenRepository.save(tokenEntity);
        Long unverifiedUserId = KeyWrapper.generateUniqueAuthorizeUserId();
        authorizeUserVerificationService.save(nextUserId, unverifiedUserId);
        // Generate the verification link using keysWrapper.getUrl()
        String verificationLink = keysWrapper.getUrl() + "/auth/verifyRegistration?token=" + verificationToken + "&id="
                + unverifiedUserId;

        String content = "Dear " + request.getUsername() + ",\n\n"
                + "Thank you for signing up for pesco! We're excited to have you on board.\n\n"
                + "Please verify your email address to complete your registration and activate your account.";

        CompletableFuture<Void>sendVerificationMessage = CompletableFuture.runAsync(() -> notificationServiceClient.sendVerificationEmail(request.getEmail(), content, verificationLink, request.getUsername()));
        sendVerificationMessage.join();

        String message = "Thanks for your interest in joining Artex network! To complete account verification, email has been sent to email address you provided.";
        return new ResponseEntity<>(message, HttpStatus.CREATED);
    }

    @Override
    public ResponseEntity<?> login(UserSignInRequest request, HttpServletResponse response) {
        Map<String, Object> Authresponse = new HashMap<>();

        Optional<Users> userInfo = userRepository.findByUsername(request.getUsername());
        if (userInfo.isPresent()) {
            if (userInfo.get().isEnabled()) {
                try {
                    authenticationManager.authenticate(
                            new UsernamePasswordAuthenticationToken(
                                    request.getUsername(),
                                    request.getPassword()));

                  
                    Optional<UserRecord> checkAccountStatus = userRecordRepository.findByUserId(userInfo.get().getId());
                    if (checkAccountStatus.isPresent()) {
                        if (checkAccountStatus.get().isLocked()) {
                            Authresponse.put("status", HttpStatus.UNAUTHORIZED.value());
                            Authresponse.put("success", false);
                            Authresponse.put("message",
                                    "Sorry, this account is currently locked. Please contact customer service.");
                            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Authresponse);
                        } else if (checkAccountStatus.get().isBlocked()) {
                            Authresponse.put("status", HttpStatus.UNAUTHORIZED.value());
                            Authresponse.put("success", false);
                            Authresponse.put("message",
                                    "Sorry, this account is currently blocked. Please contact customer service.");
                            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Authresponse);
                        }
                    }

                    String jwtToken = jwtService.generateToken(userInfo.get(), userInfo.get().getId());
                    boolean isTwoFactorAuthEnabled = userInfo.get().isTwoFactorAuth();

                    if (isTwoFactorAuthEnabled) {
                        Authresponse.put("message", "Two-factor authentication is enabled.");
                        Authresponse.put("twoFactorAuthEnabled", true);
                        Authresponse.put("status", HttpStatus.BAD_REQUEST.value());

                       // String otp = keysWrapper.generateOTP();

                        // TwoFactorAuthentication existingOtp = twoFactorAuthenticationServiceImplementation
                        //         .findByUser(userInfo.get().getId());
                        // if (existingOtp != null) {
                        //     twoFactorAuthenticationServiceImplementation.deleteTwoFactorOtp(existingOtp);
                        // }

                        // TwoFactorAuthentication newOtp = twoFactorAuthenticationServiceImplementation
                        //         .createTwoFactorOtp(userInfo.get(), otp, jwt);
                        // Authresponse.put("otpId", newOtp.getId().toString());
                        // Authresponse.put("jwt", newOtp.getToken());

                        // CompletableFuture.runAsync(() -> notificationServiceClient.sendOptEmail(
                        //         userInfo.get().getEmail(), otp,
                        //         keysWrapper.getUrl() + "/auth/security/password",
                        //         keysWrapper.getUrl() + "/auth/security/configuring-two-factor-authentication",
                        //         keysWrapper.getUrl()
                        //                 + "/auth/security/configuring-two-factor-authentication-recovery-methods"))
                        //         .join();
                        return ResponseEntity.ok(Authresponse);
                    }

                    Authresponse.put("jwt", jwtToken);
                    Authresponse.put("email", userInfo.get().getEmail());
                    Authresponse.put("userId", userInfo.get().getId());
                    Authresponse.put("status", HttpStatus.OK.value());
                    Authresponse.put("success", true);
                    Authresponse.put("session", true);
                    Authresponse.put("twoFactorAuthEnabled", userInfo.get().isTwoFactorAuth());
                    Authresponse.put("username", userInfo.get().getUsername());

                    Cookie cookie = new Cookie("authToken", jwtToken);
                    cookie.setMaxAge(24 * 60 * 60);
                    cookie.setPath("/");
                    response.addCookie(cookie);

                    return ResponseEntity.ok(Authresponse);

                } catch (BadCredentialsException e) {
                    // Handle invalid credentials explicitly
                    Authresponse.put("status", HttpStatus.BAD_REQUEST.value());
                    Authresponse.put("success", false);
                    Authresponse.put("message", "Invalid user creditials.");

                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Authresponse);
                } catch (Exception e) {
                    // Handle general authentication failure
                    Authresponse.put("status", HttpStatus.BAD_REQUEST.value());
                    Authresponse.put("success", false);
                    Authresponse.put("message", "Authentication failed. Please try again.");

                    return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Authresponse);
                }

            } else {
                Authresponse.put("status", HttpStatus.UNAUTHORIZED.value());
                Authresponse.put("success", false);
                Authresponse.put("message", "This account has not been verified.");

                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Authresponse); 
            }
            
        } else {
            // Handle invalid credentials explicitly
            Authresponse.put("status", HttpStatus.BAD_REQUEST.value());
            Authresponse.put("success", false);
            Authresponse.put("message", "Invalid user creditials.");

            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Authresponse);
        }
    }

    
    
    @Override
    public Optional<Users> findByEmail(String email) {
        return userRepository.findByEmail(email);
    }

    @Override
    public Optional<Users> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public VerificationTokenResult generateVerificationToken(String oldToken) {
        VerificationToken verificationToken = verificationTokenRepository.findByToken(oldToken);
        if (verificationToken == null) {
            return new VerificationTokenResult(false, "Token not found");
        }

        // Update expirationTime (adjust this based on your requirements)
        Date newExpirationTime = calculateExpirationDate(EXPIRATION_MINUTES);

        Optional<Users> user = userRepository.findById(verificationToken.getUserId());
        verificationToken.setExpirationTime(newExpirationTime);

        String newToken = UUID.randomUUID().toString();
        // Generate a new token
        verificationToken.setToken(newToken);

        // Save the updated verification token
        verificationTokenRepository.save(verificationToken);

        Optional<AuthorizeUserVerification> optionAuthUser = authorizeUserVerificationRepository
                .findUserByIdOptional(user.get().getId());

        String verificationLink = keysWrapper.getUrl() + "/auth/verifyRegistration?token=" + newToken + "&id="
                + optionAuthUser.get().getId();

        String content = "Dear " + user.get().getUsername() + ",\n\n"
                + "Thank you for registering with Pesco! We're thrilled to have you join us.\n\n"
                + "To complete your registration and activate your account";

        CompletableFuture<Void>sendVerificationLinkMessage = CompletableFuture.runAsync(() -> notificationServiceClient.sendVerificationEmail(user.get().getEmail(), content, verificationLink, user.get().getUsername()));
        sendVerificationLinkMessage.join();
        
        return new VerificationTokenResult(true, verificationToken);
    }

    private Date calculateExpirationDate(int expirationMinutes) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(new Date().getTime());
        calendar.add(Calendar.MINUTE, expirationMinutes);
        return new Date(calendar.getTime().getTime());
    }

    @Override
    @Transactional
    public ResponseEntity<?> verifyUser(String token, Long id) {
        AuthResponse verifyResponse = new AuthResponse();

        // Check if the user is already verified
        boolean checkVerifyUser = authorizeUserVerificationRepository.findUserById(id);
        if (checkVerifyUser) {
            verifyResponse.setMessage("This account has already been verified.");
            verifyResponse.setStatus(true);
            return new ResponseEntity<>(verifyResponse, HttpStatus.OK);
        }

        // Find the verification token
        VerificationToken verificationToken = verificationTokenRepository.findByToken(token);

        if (verificationToken == null) {
            verifyResponse.setMessage("Invalid verification token");
            verifyResponse.setStatus(false);
            return new ResponseEntity<>(verifyResponse, HttpStatus.BAD_REQUEST);
        }

        // Retrieve the user associated with the verification token
        Optional<Users> optionalUser = userRepository.findById(verificationToken.getUserId());
        if (optionalUser.isEmpty()) {
            verifyResponse.setMessage("User not found.");
            verifyResponse.setStatus(false);
            return new ResponseEntity<>(verifyResponse, HttpStatus.BAD_REQUEST);
        }

        Users user = optionalUser.get();

        // Check if the user account is already verified
        if (user.isEnabled()) {
            verifyResponse.setMessage("Hi " + user.getUsername() + ", Your account has already been verified.");
            verifyResponse.setStatus(true);
            return new ResponseEntity<>(verifyResponse, HttpStatus.OK);
        }

        // Check if the token has expired
        Calendar cal = Calendar.getInstance();
        if (verificationToken.getExpirationTime().getTime() - cal.getTime().getTime() < 0) {
            verifyResponse.setMessage("Verification token has expired. Click the resend button to get a new token.");
            verifyResponse.setStatus(false);
            return new ResponseEntity<>(verifyResponse, HttpStatus.CONFLICT);
        }

        try {
            // Update user record status
            Optional<UserRecord> optionalRecord = userRecordRepository.findByUserId(user.getId());
            optionalRecord.ifPresent(userRecord -> {
                userRecord.setStatus(UserStatus.ACTIVE);
                userRecord.setLocked(false);
                userRecord.setBlocked(false);
                userRecordRepository.save(userRecord);
            });

            // Enable user account
            user.setEnabled(true);
            userRepository.save(user);

            // Delete the verification token
            verificationTokenRepository.delete(verificationToken);

            verifyResponse.setMessage("User registration verified successfully.");
            verifyResponse.setStatus(true);
            return new ResponseEntity<>(verifyResponse, HttpStatus.OK);

        } catch (Exception e) {
            // Handle wallet creation failure
            verifyResponse.setMessage(
                    "Failed to create wallet for user. Skipping user verification. Error: " + e.getMessage());
            verifyResponse.setStatus(false);
            return new ResponseEntity<>(verifyResponse, HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private Long getNextUserId() {
        List<Users> existingUsers = userRepository.findAll();
        Users newUser = new Users();
        if (existingUsers.isEmpty()) {
            newUser.setId(1001L);
        } else {
            // Find the maximum existing user ID
            Long maxId = existingUsers.stream()
                    .map(Users::getId)
                    .max(Long::compare)
                    .orElse(0L);
            // Set the new user ID
            newUser.setId(maxId + 1);
        }
        return newUser.getId();
    }

    
}
