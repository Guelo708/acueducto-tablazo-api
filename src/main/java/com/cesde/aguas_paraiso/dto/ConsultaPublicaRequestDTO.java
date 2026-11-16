package com.cesde.aguas_paraiso.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ConsultaPublicaRequestDTO(
        @NotBlank(message = "Ingresa tu número de cédula o el nombre de tu predio/suscriptor")
        @Size(min = 4, max = 100, message = "El criterio de búsqueda debe tener entre 4 y 100 caracteres")
        String identificador,

        @Size(max = 4, message = "Debe ingresar máximo los últimos 4 dígitos del celular")
        String ultimos4Celular
) {
}
