package com.cesde.aguas_paraiso.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ReciboVerificacionDTO(
        boolean valido,
        String mensaje,
        Long numeroRecibo,
        String codigoVerificacion,
        String titular,
        String cedulaEnmascarada,
        String codigoPredio,
        String nombrePredio,
        Integer mesFactura,
        Integer anioFactura,
        BigDecimal valorPagado,
        BigDecimal valorFactura,
        LocalDate fechaPago,
        String metodoPago,
        String estadoFactura
) {}