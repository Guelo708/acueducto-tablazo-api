package com.cesde.aguas_paraiso.repository;

import com.cesde.aguas_paraiso.model.entity.Usuario;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UsuarioRepository extends JpaRepository<Usuario, Long> {

    Optional<Usuario> findByCedula(String cedula);

    boolean existsByCedula(String cedula);

    Optional<Usuario> findByCorreoElectronico(String correoElectronico);

    boolean existsByCorreoElectronico(String correoElectronico);

    @Query("""
        SELECT u FROM Usuario u
        WHERE LOWER(CONCAT(
            COALESCE(u.primerNombre, ''), ' ',
            COALESCE(u.segundoNombre, ''), ' ',
            COALESCE(u.primerApellido, ''), ' ',
            COALESCE(u.segundoApellido, '')
        )) LIKE LOWER(CONCAT('%', :texto, '%'))
    """)
    List<Usuario> buscarPorNombreCompletoParcial(@Param("texto") String texto);
}
