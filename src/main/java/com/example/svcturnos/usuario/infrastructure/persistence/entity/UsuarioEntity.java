package com.example.svcturnos.usuario.infrastructure.persistence.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import com.example.svcturnos.usuario.domain.model.Usuario;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import java.time.ZoneOffset;

import java.time.OffsetDateTime;

@Entity
@Table(name = "usuarios")
public class UsuarioEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String login;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "first_name", nullable = false, length = 50)
    private String firstName;

    @Column(name = "last_name", nullable = false, length = 50)
    private String lastName;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "image_url", length = 256)
    private String imageUrl;

    @Column(name = "lang_key", nullable = false, length = 10)
    private String langKey;

    @Column(nullable = false)
    private boolean activated;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected UsuarioEntity() {
        // Constructor requerido por JPA.
    }

    public static UsuarioEntity fromDomain(Usuario usuario) {
        UsuarioEntity entity = new UsuarioEntity();

        entity.id = usuario.id();
        entity.login = usuario.login();
        entity.passwordHash = usuario.passwordHash();
        entity.firstName = usuario.firstName();
        entity.lastName = usuario.lastName();
        entity.email = usuario.email();
        entity.imageUrl = usuario.imageUrl();
        entity.langKey = usuario.langKey();
        entity.activated = usuario.activated();

        return entity;
    }

    public Usuario toDomain() {
        return new Usuario(
                id,
                login,
                passwordHash,
                firstName,
                lastName,
                email,
                imageUrl,
                langKey,
                activated
        );
    }

    @PrePersist
    void antesDeInsertar() {
        OffsetDateTime ahora = OffsetDateTime.now(ZoneOffset.UTC);
        createdAt = ahora;
        updatedAt = ahora;
    }

    @PreUpdate
    void antesDeActualizar() {
        updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
