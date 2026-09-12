package com.samvaad.samvaad_server.exception;

import com.samvaad.samvaad_server.user.UserAlreadyExistsException;
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

    public record ErrorResponse(String message) {
    }
}