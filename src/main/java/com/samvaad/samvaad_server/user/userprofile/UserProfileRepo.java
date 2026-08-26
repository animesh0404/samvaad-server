package com.samvaad.samvaad_server.user.userprofile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserProfileRepo extends JpaRepository<UserProfile, UUID> {
}