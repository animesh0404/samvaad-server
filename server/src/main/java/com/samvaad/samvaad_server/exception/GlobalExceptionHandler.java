package com.samvaad.samvaad_server.exception;

import com.samvaad.samvaad_server.e2ee.dto.E2eeErrorResponse;
import com.samvaad.samvaad_server.e2ee.exception.DeviceAlreadyExistsException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceApprovalDeniedException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceLimitExceededException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotActiveException;
import com.samvaad.samvaad_server.e2ee.exception.DeviceNotFoundException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidKeyMaterialException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidPrekeyBatchException;
import com.samvaad.samvaad_server.e2ee.exception.InvalidRecoveryCodeException;
import com.samvaad.samvaad_server.e2ee.exception.PrekeyClaimConflictException;
import com.samvaad.samvaad_server.e2ee.exception.RecoveryRequiredException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestConflictException;
import com.samvaad.samvaad_server.friendrequest.FriendRequestNotFoundException;
import com.samvaad.samvaad_server.messaging.ConversationNotFoundException;
import com.samvaad.samvaad_server.messaging.InvalidPaginationException;
import com.samvaad.samvaad_server.messaging.MessageConflictException;
import com.samvaad.samvaad_server.user.EmailAlreadyExistsException;
import com.samvaad.samvaad_server.user.InvalidUsernameException;
import com.samvaad.samvaad_server.user.UserAlreadyExistsException;
import com.samvaad.samvaad_server.user.UserDeletionConflictException;
import com.samvaad.samvaad_server.user.UserNotFoundException;
import com.samvaad.samvaad_server.user.userprofile.UserProfileNotFoundException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleUserAlreadyExists(UserAlreadyExistsException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(EmailAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleEmailAlreadyExists(EmailAlreadyExistsException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(InvalidUsernameException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidUsername(InvalidUsernameException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(FriendRequestNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleFriendRequestNotFound(FriendRequestNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(FriendRequestConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleFriendRequestConflict(FriendRequestConflictException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(MessageConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleMessageConflict(MessageConflictException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(ConversationNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleConversationNotFound(ConversationNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(InvalidPaginationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidPagination(InvalidPaginationException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .findFirst()
                .orElse("Invalid request");

        return new ErrorResponse(message);
    }

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleUserNotFound(UserNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(UserProfileNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleUserProfileNotFound(UserProfileNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(UserDeletionConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleUserDeletionConflict(UserDeletionConflictException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(com.samvaad.samvaad_server.auth.exception.BadCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleBadCredentials(com.samvaad.samvaad_server.auth.exception.BadCredentialsException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleIncorrectPassword(com.samvaad.samvaad_server.auth.exception.IncorrectPasswordException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleSessionLimitExceeded(com.samvaad.samvaad_server.auth.exception.SessionLimitExceededException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleInvalidRefreshToken(com.samvaad.samvaad_server.auth.exception.InvalidRefreshTokenException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleForbidden(ForbiddenOperationException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(DeviceNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleDeviceNotFound(DeviceNotFoundException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(DeviceAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDeviceAlreadyExists(DeviceAlreadyExistsException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(DeviceLimitExceededException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDeviceLimitExceeded(DeviceLimitExceededException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(DeviceNotActiveException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleDeviceNotActive(DeviceNotActiveException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(DeviceApprovalDeniedException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleDeviceApprovalDenied(DeviceApprovalDeniedException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(InvalidKeyMaterialException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidKeyMaterial(InvalidKeyMaterialException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(InvalidPrekeyBatchException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidPrekeyBatch(InvalidPrekeyBatchException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(InvalidRecoveryCodeException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public ErrorResponse handleInvalidRecoveryCode(InvalidRecoveryCodeException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(PrekeyClaimConflictException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handlePrekeyClaimConflict(PrekeyClaimConflictException ex) {
        return new ErrorResponse(ex.getMessage());
    }

    @ExceptionHandler(RecoveryRequiredException.class)
    @ResponseStatus(HttpStatus.FORBIDDEN)
    public E2eeErrorResponse handleRecoveryRequired(RecoveryRequiredException ex) {
        return new E2eeErrorResponse(ex.getMessage(), RecoveryRequiredException.REASON);
    }

    public record ErrorResponse(String message) {
    }
}