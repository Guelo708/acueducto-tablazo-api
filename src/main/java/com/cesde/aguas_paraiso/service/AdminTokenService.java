package com.cesde.aguas_paraiso.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.PropertySource;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
@PropertySource(value = "classpath:application-local.properties", ignoreResourceNotFound = true)
public class AdminTokenService {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final long EXPIRACION_SEGUNDOS = 12 * 60 * 60;

    @Value("${ADMIN_EMAIL:${app.admin.email:}}")
    private String adminEmail;

    @Value("${ADMIN_PASSWORD:${app.admin.password:}}")
    private String adminPassword;

    @Value("${ADMIN_NOMBRE:${app.admin.nombre:Tesoreria Junta Directiva}}")
    private String adminNombre;

    @Value("${ADMIN_EXTRA_USERS:${app.admin.extra-users:}}")
    private String extraUsers;

    @Value("${JWT_SECRET:${app.security.jwt-secret:}}")
    private String secretKey;

    public static class AdminClaims {
        private final String correo;
        private final String nombre;
        private final String rol;
        private final long expiraEn;

        public AdminClaims(String correo, String nombre, String rol, long expiraEn) {
            this.correo = correo;
            this.nombre = nombre;
            this.rol = rol;
            this.expiraEn = expiraEn;
        }

        public String getCorreo() { return correo; }
        public String getNombre() { return nombre; }
        public String getRol() { return rol; }
        public long getExpiraEn() { return expiraEn; }
    }

    public AdminClaims autenticar(String correo, String contrasena) {
        if (correo == null || contrasena == null || secretKey == null || secretKey.isBlank()) {
            return null;
        }
        String correoNorm = correo.trim().toLowerCase();

        if (adminEmail != null && !adminEmail.isBlank()
                && adminPassword != null && !adminPassword.isBlank()
                && correoNorm.equals(adminEmail.trim().toLowerCase())
                && adminPassword.equals(contrasena)) {
            return new AdminClaims(adminEmail.trim(), adminNombre.trim(), "JUNTA_DIRECTIVA", 0L);
        }

        if (extraUsers != null && !extraUsers.isBlank()) {
            for (String entry : extraUsers.split(";")) {
                String[] parts = entry.split(":", 3);
                if (parts.length >= 2) {
                    String uEmail = parts[0].trim().toLowerCase();
                    String uPass = parts[1];
                    String uName = parts.length == 3 ? parts[2].trim() : "Directivo Junta";
                    if (correoNorm.equals(uEmail) && !uPass.isBlank() && uPass.equals(contrasena)) {
                        return new AdminClaims(parts[0].trim(), uName, "JUNTA_DIRECTIVA", 0L);
                    }
                }
            }
        }

        return null;
    }

    public String generarToken(AdminClaims admin) {
        long expiraEn = Instant.now().getEpochSecond() + EXPIRACION_SEGUNDOS;
        String payload = admin.getCorreo() + "|" + admin.getNombre().replace("|", " ") + "|" + admin.getRol() + "|" + expiraEn;
        String payloadB64 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String firmaB64 = firmar(payloadB64);
        return payloadB64 + "." + firmaB64;
    }

    public AdminClaims validarToken(String token) {
        try {
            if (token == null || !token.contains(".") || secretKey == null || secretKey.isBlank()) {
                return null;
            }
            String[] parts = token.split("\\.");
            if (parts.length != 2) {
                return null;
            }

            String payloadB64 = parts[0];
            String firmaRecibida = parts[1];
            String firmaEsperada = firmar(payloadB64);

            if (!MessageDigest.isEqual(
                    firmaEsperada.getBytes(StandardCharsets.UTF_8),
                    firmaRecibida.getBytes(StandardCharsets.UTF_8))) {
                return null;
            }

            String payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
            String[] campos = payload.split("\\|");
            if (campos.length != 4) {
                return null;
            }

            long expiraEn = Long.parseLong(campos[3]);
            if (Instant.now().getEpochSecond() > expiraEn) {
                return null;
            }

            return new AdminClaims(campos[0], campos[1], campos[2], expiraEn);
        } catch (Exception e) {
            return null;
        }
    }

    private String firmar(String data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            SecretKeySpec keySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC_ALGO);
            mac.init(keySpec);
            byte[] rawHmac = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(rawHmac);
        } catch (Exception e) {
            throw new IllegalStateException("Error generando firma de sesion", e);
        }
    }
}