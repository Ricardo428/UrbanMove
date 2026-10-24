package es.ulpgc.urbanmove.identity.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Con valid=true lleva userId y role; con valid=false, solo error. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ValidateResponse(boolean valid, String userId, String role, String error) {

    public static ValidateResponse ok(String userId, String role) {
        return new ValidateResponse(true, userId, role, null);
    }

    public static ValidateResponse invalid(String error) {
        return new ValidateResponse(false, null, null, error);
    }
}
