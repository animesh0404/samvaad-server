package com.samvaad.samvaad_server.user.userprofile;

import jakarta.validation.constraints.Size;

import java.util.HashSet;
import java.util.Set;

public class UserProfileUpdateDto {

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String middleName;

    @Size(max = 100)
    private String lastName;

    @Size(max = 255)
    private String displayName;

    @Size(max = 2000)
    private String bio;

    @Size(max = 2048)
    private String avatarUrl;

    @Size(max = 255)
    private String statusMessage;

    private final Set<String> presentFields = new HashSet<>();

    public UserProfileUpdateDto() {
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        presentFields.add("firstName");
        this.firstName = firstName;
    }

    public boolean hasFirstName() {
        return presentFields.contains("firstName");
    }

    public String getMiddleName() {
        return middleName;
    }

    public void setMiddleName(String middleName) {
        presentFields.add("middleName");
        this.middleName = middleName;
    }

    public boolean hasMiddleName() {
        return presentFields.contains("middleName");
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        presentFields.add("lastName");
        this.lastName = lastName;
    }

    public boolean hasLastName() {
        return presentFields.contains("lastName");
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        presentFields.add("displayName");
        this.displayName = displayName;
    }

    public boolean hasDisplayName() {
        return presentFields.contains("displayName");
    }

    public String getBio() {
        return bio;
    }

    public void setBio(String bio) {
        presentFields.add("bio");
        this.bio = bio;
    }

    public boolean hasBio() {
        return presentFields.contains("bio");
    }

    public String getAvatarUrl() {
        return avatarUrl;
    }

    public void setAvatarUrl(String avatarUrl) {
        presentFields.add("avatarUrl");
        this.avatarUrl = avatarUrl;
    }

    public boolean hasAvatarUrl() {
        return presentFields.contains("avatarUrl");
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public void setStatusMessage(String statusMessage) {
        presentFields.add("statusMessage");
        this.statusMessage = statusMessage;
    }

    public boolean hasStatusMessage() {
        return presentFields.contains("statusMessage");
    }
}