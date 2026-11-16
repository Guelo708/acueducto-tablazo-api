package com.cesde.aguas_paraiso.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EstadoCuentaPublicoDTO(
        String titular,
        String cedula,
        boolean alDia,
        boolean requiereActualizarCelular,
        BigDecimal totalAdeudado,
        int cantidadFacturasPendientes,
        List<FacturaPublicaDTO> facturasPendientes,
        List<PagoPublicoDTO> ultimosPagos
) {
    public record FacturaPublicaDTO(
            Long id,
            String nombrePredio,
            String sector,
            Integer mes,
            Integer anio,
            BigDecimal valorFactura,
            BigDecimal totalAbonado,
            BigDecimal saldoPendiente,
            LocalDate fechaVencimiento,
            String estado
    ) {}

    public record PagoPublicoDTO(
            Long id,
            Integer mesFactura,
            Integer anioFactura,
            BigDecimal valorPagado,
            LocalDate fechaPago,
            String metodoPago
    ) {}
}
