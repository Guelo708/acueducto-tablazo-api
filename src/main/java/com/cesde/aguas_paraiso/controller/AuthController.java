package com.cesde.aguas_paraiso.controller;

import com.cesde.aguas_paraiso.service.AdminTokenService;
import com.cesde.aguas_paraiso.service.AdminTokenService.AdminClaims;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@Tag(name = "0.1 Autenticacion", description = "Acceso exclusivo para Junta Directiva y Administracion")
public class AuthController {

    private final AdminTokenService adminTokenService;

    public AuthController(AdminTokenService adminTokenService) {
        this.adminTokenService = adminTokenService;
    }

    public static class LoginRequest {
        private String correo;
        private String contrasena;

        public String getCorreo() { return correo; }
        public void setCorreo(String correo) { this.correo = correo; }
        public String getContrasena() { return contrasena; }
        public void setContrasena(String contrasena) { this.contrasena = contrasena; }
    }

    public static class LoginResponse {
        private final String token;
        private final String correo;
        private final String nombre;
        private final String rol;

        public LoginResponse(String token, String correo, String nombre, String rol) {
            this.token = token;
            this.correo = correo;
            this.nombre = nombre;
            this.rol = rol;
        }

        public String getToken() { return token; }
        public String getCorreo() { return correo; }
        public String getNombre() { return nombre; }
        public String getRol() { return rol; }
    }

    public static class MensajeResponse {
        private final String mensaje;

        public MensajeResponse(String mensaje) {
            this.mensaje = mensaje;
        }

        public String getMensaje() { return mensaje; }
    }

    @PostMapping("/login")
    @Operation(summary = "Iniciar sesion como miembro autorizado de la Junta Directiva")
    public ResponseEntity<Object> login(@RequestBody LoginRequest credenciales) {
        String correo = credenciales != null ? credenciales.getCorreo() : null;
        String contrasena = credenciales != null ? credenciales.getContrasena() : null;

        AdminClaims admin = adminTokenService.autenticar(correo, contrasena);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    new MensajeResponse("Correo o contraseña incorrectos. Acceso exclusivo para miembros de la Junta Directiva.")
            );
        }

        String token = adminTokenService.generarToken(admin);
        return ResponseEntity.ok(new LoginResponse(token, admin.getCorreo(), admin.getNombre(), admin.getRol()));
    }

    @GetMapping("/me")
    @Operation(summary = "Validar sesion activa del administrador")
    public ResponseEntity<Object> me(HttpServletRequest request) {
        AdminClaims claims = (AdminClaims) request.getAttribute("adminUser");
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                    new MensajeResponse("Sesion no valida o expirada.")
            );
        }
        return ResponseEntity.ok(claims);
    }
}