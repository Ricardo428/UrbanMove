package es.ulpgc.urbanmove.profiles.profile;

import es.ulpgc.urbanmove.common.security.AuthenticatedUser;
import es.ulpgc.urbanmove.profiles.profile.ProfileRepository.Saved;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Contrato en docs/apis/api-profiles.md. Todas las rutas son /me: el perfil es el del usuario del token. */
@RestController
@RequestMapping("/api/v1/profiles/me")
public class ProfileController {

    private final ProfileService profiles;

    public ProfileController(ProfileService profiles) {
        this.profiles = profiles;
    }

    @PutMapping
    public ResponseEntity<SavedResponse> save(@Valid @RequestBody SaveRequest request,
            @AuthenticationPrincipal AuthenticatedUser user) {
        Preferences preferences = request.preferences();
        boolean newsletter = preferences != null && Boolean.TRUE.equals(preferences.newsletter());
        String language = preferences == null ? null : preferences.language();
        Saved saved = profiles.save(user.userId(), request.firstName(), request.lastName(), request.phone(),
                newsletter, language);
        Profile profile = saved.profile();
        return ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(new SavedResponse(profile.userId(), profile.status(), profile.updatedAt()));
    }

    @GetMapping
    public ProfileView get(@AuthenticationPrincipal AuthenticatedUser user) {
        Profile profile = profiles.find(user.userId());
        return new ProfileView(profile.userId(), profile.firstName(), profile.lastName(), profile.phone(),
                new Preferences(profile.newsletter(), profile.language()), profile.status());
    }

    @DeleteMapping
    public MessageResponse deactivate(@AuthenticationPrincipal AuthenticatedUser user) {
        profiles.deactivate(user.userId());
        return new MessageResponse("Perfil desactivado correctamente. Sus datos se conservarán según la normativa.");
    }

    public record Preferences(Boolean newsletter, @Size(max = 8) String language) {
    }

    public record SaveRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @Pattern(regexp = "^\\+?[0-9]{7,15}$", message = "debe tener entre 7 y 15 dígitos, con un + inicial opcional")
            String phone,
            @Valid Preferences preferences) {
    }

    public record SavedResponse(String userId, ProfileStatus status, Instant updatedAt) {
    }

    public record ProfileView(String userId, String firstName, String lastName, String phone,
            Preferences preferences, ProfileStatus status) {
    }

    public record MessageResponse(String message) {
    }
}
