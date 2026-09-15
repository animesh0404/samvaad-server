package com.samvaad.samvaad_server.user;

import com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException;
import com.samvaad.samvaad_server.common.logging.OperationalLog;
import com.samvaad.samvaad_server.friendrequest.FriendRequestRepo;
import com.samvaad.samvaad_server.messaging.Conversation;
import com.samvaad.samvaad_server.messaging.ConversationRepo;
import com.samvaad.samvaad_server.messaging.MessageRepo;
import com.samvaad.samvaad_server.session.SessionRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileRepo;
import com.samvaad.samvaad_server.user.userprofile.UserProfileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.UUID;

@Service
public class UserService {

    private static final Logger log = LoggerFactory.getLogger(UserService.class);

    private final UserRepo userRepo;
    private final UserProfileService userProfileService;
    private final UserProfileRepo userProfileRepo;
    private final SessionRepo sessionRepo;
    private final FriendRequestRepo friendRequestRepo;
    private final MessageRepo messageRepo;
    private final ConversationRepo conversationRepo;
    private final PasswordEncoder passwordEncoder;

    public UserService(
            UserRepo userRepo,
            UserProfileService userProfileService,
            UserProfileRepo userProfileRepo,
            SessionRepo sessionRepo,
            FriendRequestRepo friendRequestRepo,
            MessageRepo messageRepo,
            ConversationRepo conversationRepo,
            PasswordEncoder passwordEncoder) {
        this.userRepo = userRepo;
        this.userProfileService = userProfileService;
        this.userProfileRepo = userProfileRepo;
        this.sessionRepo = sessionRepo;
        this.friendRequestRepo = friendRequestRepo;
        this.messageRepo = messageRepo;
        this.conversationRepo = conversationRepo;
        this.passwordEncoder = passwordEncoder;
    }

    @OperationalLog("user.create")
    @Transactional
    public UserDto createUser(CreateUserRequestDto request) {

        if (userRepo.existsByUsername(request.getUsername())) {
            log.warn("User creation conflict username={}", request.getUsername());
            throw new UserAlreadyExistsException(request.getUsername());
        }

        User user = UserMapper.toEntity(request);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(UserRole.USER);

        User savedUser = userRepo.save(user);

        userProfileService.createProfile(savedUser);

        log.info("User created userId={} username={}", savedUser.getUserId(), savedUser.getUsername());
        return UserMapper.toDto(savedUser);
    }

    public UserDto getUser(UUID userId) {
        User user = userRepo.findById(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        return UserMapper.toDto(user);
    }

    public List<UserDto> listUsers() {
        return userRepo.findAll().stream()
                .map(UserMapper::toDto)
                .toList();
    }

    public UserLookupDto lookupByUsername(String username) {
        User user = userRepo.findByUsernameIgnoreCase(username.trim())
                .orElseThrow(() -> new UserNotFoundException(username));

        return UserMapper.toLookupDto(user);
    }

    @OperationalLog("user.delete")
    @Transactional
    public void deleteUser(UUID userId) {
        User user = userRepo.findByIdWithLock(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));
        String username = user.getUsername();

        // Explicit dependent cleanup in foreign-key-safe order. The schema
        // keeps RESTRICT foreign keys as a backstop; no JPA cascades are used.
        sessionRepo.deleteByUserId(userId);
        userProfileRepo.deleteById(userId);
        friendRequestRepo.deleteByParticipantUserId(userId);
        messageRepo.deleteBySenderUserId(userId);
        List<Conversation> conversations =
                conversationRepo.findByParticipantAOrParticipantB(userId, userId, Pageable.unpaged());
        for (Conversation conversation : conversations) {
            // Messages reference their conversation; delete them first.
            messageRepo.deleteByConversationConversationId(conversation.getConversationId());
            conversationRepo.deleteById(conversation.getConversationId());
        }
        userRepo.deleteById(userId);
        try {
            userRepo.flush();
        } catch (DataIntegrityViolationException e) {
            log.warn("User deletion conflict userId={}", userId);
            throw new UserDeletionConflictException(userId);
        }
        logAfterCommit(userId, username);
    }

    /**
     * Logs deletion success only after the transaction commits. Logging
     * inside the method body would claim success even when the commit
     * subsequently rolls back.
     */
    private void logAfterCommit(UUID userId, String username) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    log.info("User deleted userId={} username={}", userId, username);
                }
            });
        } else {
            log.info("User deleted userId={} username={}", userId, username);
        }
    }

    @OperationalLog("user.changeEmail")
    @Transactional
    public UserDto changeEmail(UUID userId, String email) {
        User user = userRepo.findByIdWithLock(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (user.getEmail() != null && user.getEmail().equalsIgnoreCase(email)) {
            log.debug("Email change no-op userId={}", userId);
            return UserMapper.toDto(user);
        }

        boolean takenByAnother = userRepo.findByEmailIgnoreCase(email).stream()
                .anyMatch(holder -> !holder.getUserId().equals(userId));
        if (takenByAnother) {
            log.warn("Email change conflict userId={}", userId);
            throw new EmailAlreadyExistsException(email);
        }

        user.setEmail(email);
        try {
            User savedUser = userRepo.saveAndFlush(user);
            log.info("Email changed userId={}", userId);
            return UserMapper.toDto(savedUser);
        } catch (DataIntegrityViolationException e) {
            log.warn("Email change conflict userId={}", userId);
            throw new EmailAlreadyExistsException(email);
        }
    }

    @OperationalLog("user.changePassword")
    @Transactional
    public UserDto changePassword(UUID userId, String currentPassword, String newPassword) {
        User user = userRepo.findByIdWithLock(userId)
                .orElseThrow(() -> new UserNotFoundException(userId));

        if (!passwordEncoder.matches(currentPassword, user.getPasswordHash())) {
            log.warn("Password change failed: incorrect password userId={}", userId);
            throw new IncorrectPasswordException("Incorrect password");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        User savedUser = userRepo.save(user);
        log.info("Password changed userId={}", userId);
        return UserMapper.toDto(savedUser);
    }
}
