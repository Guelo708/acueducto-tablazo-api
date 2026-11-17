package com.cesde.aguas_paraiso.service;

import com.cesde.aguas_paraiso.model.entity.Factura;
import com.cesde.aguas_paraiso.model.entity.Pago;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;

@Service
public class ReciboFirmaService {

    private final byte[] secretKeyBytes;

    public ReciboFirmaService(@Value("${app.recibo.secret}") String secret) {
        this.secretKeyBytes = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String generarCodigoVerificacion(Pago pago) {
        if (pago == null || pago.getId() == null) {
            return "";
        }
        Factura factura = pago.getFactura();
        String facturaId = factura != null && factura.getId() != null ? factura.getId().toString() : "0";
        String cedula = factura != null && factura.getCedulaPropietario() != null ? factura.getCedulaPropietario().trim() : "";
        String valor = pago.getValorPagado() != null
                ? pago.getValorPagado().setScale(2, RoundingMode.HALF_UP).toPlainString()
                : "0.00";
        String fecha = pago.getFechaPago() != null ? pago.getFechaPago().toString() : "";

        String payload = String.join("|",
                pago.getId().toString(),
                facturaId,
                cedula,
                valor,
                fecha
        );

        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretKeyBytes, "HmacSHA256"));
            byte[] rawHmac = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            String hex = HexFormat.of().withUpperCase().formatHex(rawHmac);
            // Tomamos 16 caracteres hexadecimales (64 bits de entropia HMAC) formateados en bloques de 4
            return hex.substring(0, 4) + "-" +
                   hex.substring(4, 8) + "-" +
                   hex.substring(8, 12) + "-" +
                   hex.substring(12, 16);
        } catch (Exception e) {
            throw new IllegalStateException("Error generando firma criptografica del recibo", e);
        }
    }

    public boolean verificarCodigo(Pago pago, String codigoRecibido) {
        if (pago == null || codigoRecibido == null || codigoRecibido.isBlank()) {
            return false;
        }
        String esperado = generarCodigoVerificacion(pago);
        String normalizado = codigoRecibido.trim().toUpperCase();
        return MessageDigest.isEqual(
                esperado.getBytes(StandardCharsets.UTF_8),
                normalizado.getBytes(StandardCharsets.UTF_8)
        );
    }
}