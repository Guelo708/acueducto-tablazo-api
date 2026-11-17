package com.cesde.aguas_paraiso.controller;

import com.cesde.aguas_paraiso.dto.ReciboVerificacionDTO;
import com.cesde.aguas_paraiso.model.entity.Factura;
import com.cesde.aguas_paraiso.model.entity.Pago;
import com.cesde.aguas_paraiso.model.entity.Predio;
import com.cesde.aguas_paraiso.repository.PagoRepository;
import com.cesde.aguas_paraiso.service.ReciboFirmaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Optional;

@RestController
@RequestMapping("/api/public/recibos")
@Tag(name = "0. Portal Público", description = "Verificación criptográfica de recibos de pago con QR")
public class PublicReciboController {

    private final PagoRepository pagoRepository;
    private final ReciboFirmaService reciboFirmaService;

    public PublicReciboController(PagoRepository pagoRepository, ReciboFirmaService reciboFirmaService) {
        this.pagoRepository = pagoRepository;
        this.reciboFirmaService = reciboFirmaService;
    }

    @GetMapping("/verificar")
    @Transactional(readOnly = true)
    @Operation(summary = "Verificar autenticidad de un recibo de pago mediante ID y firma HMAC-SHA256")
    public ResponseEntity<ReciboVerificacionDTO> verificarRecibo(
            @RequestParam("id") Long id,
            @RequestParam("firma") String firma
    ) {
        try {
            Optional<Pago> pagoOpt = pagoRepository.findById(id);

            if (pagoOpt.isEmpty()) {
                return ResponseEntity.ok(new ReciboVerificacionDTO(
                        false,
                        "El recibo consultado no existe en los registros oficiales del Acueducto Vereda El Tablazo.",
                        id, firma, null, null, null, null, null, null, null, null, null, null, null
                ));
            }

            Pago pago = pagoOpt.get();
            if (!reciboFirmaService.verificarCodigo(pago, firma)) {
                return ResponseEntity.ok(new ReciboVerificacionDTO(
                        false,
                        "ALERTA DE SEGURIDAD: La firma digital del código QR no coincide con los datos del pago. Este comprobante pudo haber sido alterado.",
                        id, firma, null, null, null, null, null, null, null, null, null, null, null
                ));
            }

            Factura factura = pago.getFactura();
            Predio predio = (factura != null) ? factura.getPredio() : null;
            String cedula = (factura != null && factura.getCedulaPropietario() != null)
                    ? factura.getCedulaPropietario()
                    : "";

            String nombrePredio = "Predio registrado";
            try {
                if (predio != null && predio.getUbicacion() != null && predio.getUbicacion().getNombrePredio() != null) {
                    nombrePredio = predio.getUbicacion().getNombrePredio();
                }
            } catch (Exception ignored) {
                nombrePredio = "Predio registrado";
            }

            return ResponseEntity.ok(new ReciboVerificacionDTO(
                    true,
                    "Comprobante auténtico emitido y firmado digitalmente por el Acueducto Vereda El Tablazo.",
                    pago.getId(),
                    reciboFirmaService.generarCodigoVerificacion(pago),
                    (factura != null && factura.getNombrePropietario() != null) ? factura.getNombrePropietario() : "Suscriptor",
                    enmascararCedula(cedula),
                    (predio != null && predio.getCodigoPredio() != null) ? predio.getCodigoPredio() : "N/A",
                    nombrePredio,
                    (factura != null) ? factura.getMes() : null,
                    (factura != null) ? factura.getAnio() : null,
                    pago.getValorPagado(),
                    (factura != null) ? factura.getValorFactura() : null,
                    pago.getFechaPago(),
                    (pago.getMetodoPago() != null) ? pago.getMetodoPago().name() : "",
                    (factura != null && factura.getEstado() != null) ? factura.getEstado().name() : ""
            ));
        } catch (Exception ex) {
            ex.printStackTrace();
            return ResponseEntity.ok(new ReciboVerificacionDTO(
                    false,
                    "Error técnico verificando recibo (" + ex.getClass().getSimpleName() + "): " + ex.getMessage(),
                    id, firma, null, null, null, null, null, null, null, null, null, null, null
            ));
        }
    }

    private String enmascararCedula(String cedula) {
        if (cedula == null || cedula.length() <= 4) {
            return "****";
        }
        return "*".repeat(cedula.length() - 4) + cedula.substring(cedula.length() - 4);
    }
}