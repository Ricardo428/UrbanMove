package es.ulpgc.urbanmove.common.security;

/** Roles del sistema. Solo un ADMIN puede crear usuarios MAINTENANCE o ADMIN. */
public enum Role {
    USER,
    MAINTENANCE,
    ADMIN
}
