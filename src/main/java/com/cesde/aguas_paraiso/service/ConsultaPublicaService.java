package com.cesde.aguas_paraiso.service;

import com.cesde.aguas_paraiso.dto.ConsultaPublicaRequestDTO;
import com.cesde.aguas_paraiso.dto.EstadoCuentaPublicoDTO;
import com.cesde.aguas_paraiso.dto.EstadoCuentaPublicoDTO.FacturaPublicaDTO;
import com.cesde.aguas_paraiso.dto.EstadoCuentaPublicoDTO.PagoPublicoDTO;
import com.cesde.aguas_paraiso.model.entity.Factura;
import com.cesde.aguas_paraiso.model.entity.Pago;
import com.cesde.aguas_paraiso.model.entity.Predio;
import com.cesde.aguas_paraiso.model.entity.Usuario;
import com.cesde.aguas_paraiso.model.enums.EstadoFactura;
import com.cesde.aguas_paraiso.repository.FacturaRepository;
import com.cesde.aguas_paraiso.repository.PagoRepository;
import com.cesde.aguas_paraiso.repository.PredioRepository;
import com.cesde.aguas_paraiso.repository.UsuarioRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

@Service
@Transactional(readOnly = true)
public class ConsultaPublicaService {

    private final UsuarioRepository usuarioRepository;
    private final PredioRepository predioRepository;
    private final FacturaRepository facturaRepository;
    private final PagoRepository pagoRepository;

    public ConsultaPublicaService(
            UsuarioRepository usuarioRepository,
            PredioRepository predioRepository,
            FacturaRepository facturaRepository,
            PagoRepository pagoRepository
    ) {
        this.usuarioRepository = usuarioRepository;
        this.predioRepository = predioRepository;
        this.facturaRepository = facturaRepository;
        this.pagoRepository = pagoRepository;
    }

    public EstadoCuentaPublicoDTO consultarEstadoCuenta(ConsultaPublicaRequestDTO request) {
        String criterio = request.identificador() != null ? request.identificador().trim() : "";
        String ultimos4Ingresados = request.ultimos4Celular() != null
                ? request.ultimos4Celular().replaceAll("\\D", "")
                : "";

        Usuario usuario = resolverUsuario(criterio, ultimos4Ingresados);

        String celularRegistrado = usuario.getCelular() != null
                ? usuario.getCelular().replaceAll("\\D", "")
                : "";

        boolean tieneCelularValido = celularRegistrado.length() >= 4;

        if (tieneCelularValido) {
            String ultimos4Reales = celularRegistrado.substring(celularRegistrado.length() - 4);
            if (!ultimos4Reales.equals(ultimos4Ingresados)) {
                throw new IllegalArgumentException(
                        "Los datos ingresados no coinciden. Verifica tu cédula/predio y los últimos 4 dígitos de tu celular."
                );
            }
        }

        List<Factura> facturasUsuario = facturaRepository
                .findByPredioPropietarioIdOrderByAnioDescMesDesc(usuario.getId());

        List<FacturaPublicaDTO> facturasPendientes = facturasUsuario.stream()
                .filter(f -> f.getEstado() != EstadoFactura.PAGADA)
                .map(this::mapearFacturaPublica)
                .filter(f -> f.saldoPendiente().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        BigDecimal totalAdeudado = facturasPendientes.stream()
                .map(FacturaPublicaDTO::saldoPendiente)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        List<PagoPublicoDTO> ultimosPagos = pagoRepository
                .findByFacturaPredioPropietarioIdOrderByFechaPagoDesc(usuario.getId())
                .stream()
                .limit(6)
                .map(this::mapearPagoPublico)
                .toList();

        boolean esRegistroAntiguoSinCedula = usuario.getCedula() == null
                || usuario.getCedula().isBlank()
                || usuario.getCedula().toUpperCase().startsWith("SC")
                || !usuario.getCedula().matches("\\d+");

        boolean requiereActualizarDatos = !tieneCelularValido || esRegistroAntiguoSinCedula;
        String nombreTitular = construirNombre(usuario, !tieneCelularValido);
        String cedulaVisible = esRegistroAntiguoSinCedula ? "Sin cédula registrada" : usuario.getCedula();

        return new EstadoCuentaPublicoDTO(
                nombreTitular,
                cedulaVisible,
                facturasPendientes.isEmpty(),
                requiereActualizarDatos,
                totalAdeudado,
                facturasPendientes.size(),
                facturasPendientes,
                ultimosPagos
        );
    }

    private Usuario resolverUsuario(String criterio, String ultimos4Ingresados) {
        // 1. Intento directo por cédula o identificador exacto
        Optional<Usuario> porCedula = usuarioRepository.findByCedula(criterio);
        if (porCedula.isPresent()) {
            return porCedula.get();
        }

        // 2. Búsqueda para registros antiguos por nombre de predio o nombre de titular
        List<Usuario> candidatosPorPredio = predioRepository
                .findByUbicacionNombrePredioContainingIgnoreCase(criterio)
                .stream()
                .map(Predio::getPropietario)
                .distinct()
                .toList();

        List<Usuario> candidatos = !candidatosPorPredio.isEmpty()
                ? candidatosPorPredio
                : usuarioRepository.buscarPorNombreCompletoParcial(criterio);

        if (candidatos.isEmpty()) {
            throw new IllegalArgumentException(
                    "No encontramos registros con ese dato. Verifica tu cédula o el nombre exacto de tu predio."
            );
        }

        // Si hay varios homónimos o predios similares, filtramos por los últimos 4 dígitos del celular
        if (candidatos.size() > 1) {
            if (ultimos4Ingresados.length() == 4) {
                List<Usuario> filtradosPorCelular = candidatos.stream()
                        .filter(u -> {
                            String cel = u.getCelular() != null ? u.getCelular().replaceAll("\\D", "") : "";
                            return cel.length() >= 4 && cel.endsWith(ultimos4Ingresados);
                        })
                        .toList();

                if (filtradosPorCelular.size() == 1) {
                    return filtradosPorCelular.get(0);
                }
            }
            throw new IllegalArgumentException(
                    "Encontramos varios registros con un nombre similar. Ingresa el nombre completo o los últimos 4 dígitos de tu celular."
            );
        }

        return candidatos.get(0);
    }

    private FacturaPublicaDTO mapearFacturaPublica(Factura factura) {
        BigDecimal totalAbonado = pagoRepository.sumarPagosPorFactura(factura.getId());
        if (totalAbonado == null) {
            totalAbonado = BigDecimal.ZERO;
        }
        BigDecimal saldo = factura.getValorFactura().subtract(totalAbonado).max(BigDecimal.ZERO);

        String nombrePredio = (factura.getPredio() != null && factura.getPredio().getUbicacion() != null
                && factura.getPredio().getUbicacion().getNombrePredio() != null)
                ? factura.getPredio().getUbicacion().getNombrePredio()
                : "Predio registrado";

        String sector = (factura.getPredio() != null && factura.getPredio().getSector() != null)
                ? factura.getPredio().getSector().getNombre()
                : "Vereda El Tablazo";

        return new FacturaPublicaDTO(
                factura.getId(),
                nombrePredio,
                sector,
                factura.getMes(),
                factura.getAnio(),
                factura.getValorFactura(),
                totalAbonado,
                saldo,
                factura.getFechaVencimiento(),
                factura.getEstado().name()
        );
    }

    private PagoPublicoDTO mapearPagoPublico(Pago pago) {
        return new PagoPublicoDTO(
                pago.getId(),
                pago.getFactura() != null ? pago.getFactura().getMes() : null,
                pago.getFactura() != null ? pago.getFactura().getAnio() : null,
                pago.getValorPagado(),
                pago.getFechaPago(),
                pago.getMetodoPago() != null ? pago.getMetodoPago().name() : ""
        );
    }

    private String construirNombre(Usuario u, boolean enmascarar) {
        StringBuilder sb = new StringBuilder();
        agregarParte(sb, u.getPrimerNombre(), false);
        agregarParte(sb, u.getSegundoNombre(), enmascarar);
        agregarParte(sb, u.getPrimerApellido(), enmascarar);
        agregarParte(sb, u.getSegundoApellido(), enmascarar);
        return sb.toString();
    }

    private void agregarParte(StringBuilder sb, String parte, boolean enmascarar) {
        if (parte == null || parte.isBlank()) return;
        String limpia = parte.trim();
        if (!sb.isEmpty()) sb.append(" ");
        if (enmascarar && limpia.length() > 1) {
            sb.append(limpia.charAt(0)).append("****");
        } else {
            sb.append(limpia);
        }
    }
}
