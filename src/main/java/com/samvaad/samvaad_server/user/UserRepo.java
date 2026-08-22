package com.samvaad.samvaad_server.user;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface UserRepo extends JpaRepository<User, UUID> {

    boolean existsByUsername(String username);

}