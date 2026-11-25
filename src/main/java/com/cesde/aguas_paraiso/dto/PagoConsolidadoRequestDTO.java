package com.cesde.aguas_paraiso.dto;

import com.cesde.aguas_paraiso.model.enums.MetodoPago;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;

@Schema(description = "Datos necesarios para registrar una consignación consolidada y aplicarla en orden FIFO")
public record PagoConsolidadoRequestDTO(
        @Schema(description = "Cédula del propietario cuyas facturas pendientes se van a abonar", example = "1037654321")
        String cedula,

        @Schema(description = "Código de predio opcional para restringir el abono a un solo inmueble", example = "AP-0001")
        String codigoPredio,

        @Schema(description = "Monto total consignado por el suscriptor", example = "85000.00")
        BigDecimal valorTotalPagado,

        @Schema(description = "Fecha en que se realizó el pago", example = "2026-11-24")
        LocalDate fechaPago,

        @Schema(description = "Método utilizado para realizar el pago", example = "NEQUI")
        MetodoPago metodoPago,

        @Schema(description = "Observación o referencia de comprobante opcional", example = "Consignación Nequi ref #98421")
        String observacion
) {
}