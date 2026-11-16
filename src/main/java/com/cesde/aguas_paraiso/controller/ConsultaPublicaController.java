package com.cesde.aguas_paraiso.controller;

import com.cesde.aguas_paraiso.dto.ConsultaPublicaRequestDTO;
import com.cesde.aguas_paraiso.dto.EstadoCuentaPublicoDTO;
import com.cesde.aguas_paraiso.service.ConsultaPublicaService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/public")
public class ConsultaPublicaController {

    private final ConsultaPublicaService consultaPublicaService;

    public ConsultaPublicaController(ConsultaPublicaService consultaPublicaService) {
        this.consultaPublicaService = consultaPublicaService;
    }

    @PostMapping("/estado-cuenta")
    public ResponseEntity<EstadoCuentaPublicoDTO> consultarEstadoCuenta(
            @Valid @RequestBody ConsultaPublicaRequestDTO request
    ) {
        EstadoCuentaPublicoDTO respuesta = consultaPublicaService.consultarEstadoCuenta(request);
        return ResponseEntity.ok(respuesta);
    }
}
