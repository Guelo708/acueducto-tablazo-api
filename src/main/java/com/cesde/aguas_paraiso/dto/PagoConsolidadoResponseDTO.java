package com.cesde.aguas_paraiso.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

@Schema(description = "Resumen de distribución FIFO de un pago consolidado")
public record PagoConsolidadoResponseDTO(
        @Schema(description = "Cédula del suscriptor", example = "1037654321")
        String cedula,

        @Schema(description = "Nombre del propietario", example = "Ana María Gómez López")
        String nombrePropietario,

        @Schema(description = "Valor total consignado y aplicado", example = "85000.00")
        BigDecimal valorTotalConsignado,

        @Schema(description = "Deuda total pendiente antes de aplicar el pago", example = "120000.00")
        BigDecimal deudaTotalAnterior,

        @Schema(description = "Deuda total pendiente después de aplicar el pago", example = "35000.00")
        BigDecimal deudaTotalRestante,

        @Schema(description = "Cantidad de facturas que quedaron completamente PAGADAS", example = "3")
        int facturasSaldadasCount,

        @Schema(description = "Cantidad de facturas que quedaron con abono PARCIAL", example = "1")
        int facturasAbonadasParcialCount,

        @Schema(description = "Cantidad de facturas que aún conservan saldo pendiente", example = "2")
        int facturasPendientesRestantesCount,

        @Schema(description = "Detalle individual de cada pago generado en la transacción")
        List<PagoResponseDTO> pagosGenerados
) {
}