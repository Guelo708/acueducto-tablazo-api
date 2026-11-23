import argparse
import datetime as dt
import os
import re
import sys
import unicodedata
from decimal import Decimal, InvalidOperation

import openpyxl
import psycopg2
from psycopg2.extras import RealDictCursor

MESES_ES = {
    "enero": 1, "febrero": 2, "marzo": 3, "abril": 4,
    "mayo": 5, "junio": 6, "julio": 7, "agosto": 8,
    "septiembre": 9, "setiembre": 9, "octubre": 10,
    "noviembre": 11, "diciembre": 12,
}

PREFIJOS_SECTOR = {
    "TPB": "baja",
    "TPA": "alta",
    "TC": "centro",
    "EH": "hoyo",
    "SC": "colegio",
}


def norm_text(val):
    if val is None:
        return ""
    s = str(val).strip().lower()
    s = "".join(c for c in unicodedata.normalize("NFD", s) if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", s)


def clean_str(val):
    if val is None:
        return None
    s = re.sub(r"\s+", " ", str(val).strip())
    return s if s else None


def parse_money(val):
    if val is None or str(val).strip() in ("", "-", "$", "$ -"):
        return Decimal("0.00")
    if isinstance(val, (int, float, Decimal)):
        return Decimal(str(val)).quantize(Decimal("0.01"))
    s = str(val).strip()
    s = s.replace("$", "").replace(" ", "")
    # Formato colombiano: 80.000 o 80.000,00
    if "." in s and "," in s:
        s = s.replace(".", "").replace(",", ".")
    elif "." in s:
        parts = s.split(".")
        if len(parts[-1]) == 3:
            s = "".join(parts)
    elif "," in s:
        parts = s.split(",")
        if len(parts[-1]) == 3:
            s = "".join(parts)
        else:
            s = s.replace(",", ".")
    try:
        return Decimal(s).quantize(Decimal("0.01"))
    except InvalidOperation:
        return Decimal("0.00")


def parse_mes(val):
    if val is None:
        return None
    if isinstance(val, (int, float)):
        m = int(val)
        return m if 1 <= m <= 12 else None
    key = norm_text(val)
    if key.isdigit():
        m = int(key)
        return m if 1 <= m <= 12 else None
    return MESES_ES.get(key)


def parse_fecha_pago(val, anio_fallback, mes_fallback):
    if val is None or str(val).strip() == "":
        return dt.date(anio_fallback, mes_fallback, 15)
    if isinstance(val, dt.datetime):
        return val.date()
    if isinstance(val, dt.date):
        return val
    s = norm_text(val)
    # Ej: "domingo, 6 de septiembre de 2026"
    m = re.search(r"(\d{1,2})\s+de\s+([a-z]+)\s+de\s+(\d{4})", s)
    if m:
        dia = int(m.group(1))
        mes = MESES_ES.get(m.group(2), mes_fallback)
        anio = int(m.group(3))
        try:
            return dt.date(anio, mes, dia)
        except ValueError:
            pass
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y"):
        try:
            return dt.datetime.strptime(str(val).strip(), fmt).date()
        except ValueError:
            continue
    return dt.date(anio_fallback, mes_fallback, 15)


def parse_contacto(val):
    s = clean_str(val)
    if not s:
        return None, None
    celular = None
    correo = None
    for part in re.split(r"[/,;|]+", s):
        token = part.strip()
        if "@" in token:
            correo = token.lower()[:100]
        else:
            digits = re.sub(r"\D", "", token)
            if len(digits) >= 7:
                celular = digits[:20]
    return celular, correo


def load_env(env_path):
    env = {}
    if not os.path.exists(env_path):
        return env
    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, v = line.split("=", 1)
            env[k.strip()] = v.strip().strip('"').strip("'")
    return env


def connect_neon(base_dir):
    env = load_env(os.path.join(base_dir, ".env"))
    db_url = os.environ.get("DB_URL") or env.get("DB_URL")
    db_user = os.environ.get("DB_USERNAME") or env.get("DB_USERNAME")
    db_pass = os.environ.get("DB_PASSWORD") or env.get("DB_PASSWORD")

    if not db_url:
        raise RuntimeError("No se encontró DB_URL en .env ni en variables de entorno.")

    pg_dsn = db_url.replace("jdbc:", "", 1)
    return psycopg2.connect(pg_dsn, user=db_user, password=db_pass)


def map_headers(raw_headers):
    col_map = {}
    for idx, h in enumerate(raw_headers):
        nh = norm_text(h)
        if nh in ("cc", "cedula", "documento"):
            col_map["cc"] = idx
        elif "primer nombre" in nh:
            col_map["primer_nombre"] = idx
        elif "segundo nombre" in nh:
            col_map["segundo_nombre"] = idx
        elif "primer apellido" in nh:
            col_map["primer_apellido"] = idx
        elif "segundo apellido" in nh:
            col_map["segundo_apellido"] = idx
        elif "nombre predio" in nh:
            col_map["nombre_predio"] = idx
        elif nh == "mes":
            col_map["mes"] = idx
        elif nh in ("ano", "año"):
            col_map["anio"] = idx
        elif nh == "tarifa":
            col_map["tarifa"] = idx
        elif nh == "pago":
            col_map["pago"] = idx
        elif "fecha de pago" in nh or "fecha pago" in nh:
            col_map["fecha_pago"] = idx
        elif "telefono" in nh or "correo" in nh or "celular" in nh:
            col_map["contacto"] = idx
        elif "codigo predio" in nh:
            col_map["codigo_predio"] = idx
        elif "tipo de predio" in nh or "tipo predio" in nh:
            col_map["tipo_predio"] = idx
        elif "sector" in nh:
            col_map["sector"] = idx
    return col_map


def resolver_sector(codigo_predio, sector_excel, sectores_db):
    if not sectores_db:
        return None
    if sector_excel is not None:
        s_str = str(sector_excel).strip()
        if s_str.isdigit():
            sid = int(s_str)
            for s in sectores_db:
                if s["id"] == sid:
                    return s
        s_norm = norm_text(s_str)
        for s in sectores_db:
            if s_norm in norm_text(s["nombre"]) or norm_text(s["nombre"]) in s_norm:
                return s

    if codigo_predio and "-" in codigo_predio:
        prefijo = codigo_predio.split("-", 1)[0].strip().upper()
        clave = PREFIJOS_SECTOR.get(prefijo)
        if clave:
            for s in sectores_db:
                if clave in norm_text(s["nombre"]):
                    return s

    return sectores_db[0]


def resolver_tipo_predio(tipo_excel, tipos_db):
    if not tipos_db:
        return None
    if tipo_excel is not None:
        t_str = str(tipo_excel).strip()
        if t_str.isdigit():
            tid = int(t_str)
            for t in tipos_db:
                if t["id"] == tid:
                    return t
        t_norm = norm_text(t_str)
        for t in tipos_db:
            if t_norm in norm_text(t["nombre"]) or norm_text(t["nombre"]) in t_norm:
                return t
    return tipos_db[0]


def main():
    parser = argparse.ArgumentParser(description="Migración de Excel a Neon PostgreSQL")
    parser.add_argument("--archivo", required=True, help="Ruta completa del archivo .xlsx")
    parser.add_argument("--hoja", default=None, help="Nombre de la hoja (por defecto la primera)")
    parser.add_argument("--ejecutar", action="store_true", help="Guarda realmente en la base de datos")
    args = parser.parse_args()

    if not os.path.exists(args.archivo):
        print(f"ERROR: No existe el archivo {args.archivo}")
        sys.exit(1)

    base_dir = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
    conn = connect_neon(base_dir)
    cur = conn.cursor(cursor_factory=RealDictCursor)

    cur.execute("SELECT id, nombre, descripcion FROM sectores ORDER BY id")
    sectores_db = cur.fetchall()

    cur.execute("SELECT id, nombre, descripcion FROM tipos_predios ORDER BY id")
    tipos_db = cur.fetchall()

    cur.execute("SELECT id, tipo_predio_id, valor, fecha_inicio, fecha_fin FROM tarifas ORDER BY id")
    tarifas_db = cur.fetchall()

    print("\n=== CATÁLOGOS ACTUALES EN NEON POSTGRESQL ===")
    print("Sectores:", [(s["id"], s["nombre"]) for s in sectores_db])
    print("Tipos de predio:", [(t["id"], t["nombre"]) for t in tipos_db])
    print("Tarifas:", [(t["id"], f"tipo={t['tipo_predio_id']}", str(t["valor"])) for t in tarifas_db])

    if not sectores_db or not tipos_db:
        print("\nAVISO: Debes tener al menos 1 sector y 1 tipo de predio creados en la plataforma antes de migrar.")
        sys.exit(1)

    wb = openpyxl.load_workbook(args.archivo, data_only=True)
    sheet_name = args.hoja if args.hoja else wb.sheetnames[0]
    ws = wb[sheet_name]

    rows = list(ws.iter_rows(values_only=True))
    if len(rows) < 2:
        print("El archivo Excel no tiene filas de datos.")
        sys.exit(1)

    col_map = map_headers(rows[0])
    print(f"\nHoja leída: '{sheet_name}' | Columnas detectadas: {col_map}")

    hoy = dt.date.today()
    now_ts = dt.datetime.now()

    usuarios_map = {}
    predios_map = {}
    facturas_list = []

    for row_num, row in enumerate(rows[1:], start=2):
        cc_raw = row[col_map["cc"]] if "cc" in col_map else None
        if cc_raw is None or str(cc_raw).strip() == "":
            continue

        cedula = re.sub(r"\D", "", str(cc_raw).split(".")[0])[:15]
        p_nombre = clean_str(row[col_map["primer_nombre"]]) if "primer_nombre" in col_map else "SinNombre"
        s_nombre = clean_str(row[col_map["segundo_nombre"]]) if "segundo_nombre" in col_map else None
        p_apellido = clean_str(row[col_map["primer_apellido"]]) if "primer_apellido" in col_map else "SinApellido"
        s_apellido = clean_str(row[col_map["segundo_apellido"]]) if "segundo_apellido" in col_map else None
        contacto_raw = row[col_map["contacto"]] if "contacto" in col_map else None
        celular, correo = parse_contacto(contacto_raw)

        if cedula not in usuarios_map:
            usuarios_map[cedula] = {
                "cedula": cedula,
                "primer_nombre": p_nombre[:50],
                "segundo_nombre": s_nombre[:50] if s_nombre else None,
                "primer_apellido": p_apellido[:50],
                "segundo_apellido": s_apellido[:50] if s_apellido else None,
                "celular": celular,
                "correo_electronico": correo,
            }
        else:
            if celular and not usuarios_map[cedula]["celular"]:
                usuarios_map[cedula]["celular"] = celular
            if correo and not usuarios_map[cedula]["correo_electronico"]:
                usuarios_map[cedula]["correo_electronico"] = correo

        nombre_predio = clean_str(row[col_map["nombre_predio"]]) if "nombre_predio" in col_map else "Predio Principal"
        codigo_raw = clean_str(row[col_map["codigo_predio"]]) if "codigo_predio" in col_map else None
        codigo_predio = (codigo_raw if codigo_raw else f"AP-{cedula}")[:20]

        tipo_excel = row[col_map["tipo_predio"]] if "tipo_predio" in col_map else None
        sector_excel = row[col_map["sector"]] if "sector" in col_map else None

        tipo_obj = resolver_tipo_predio(tipo_excel, tipos_db)
        sector_obj = resolver_sector(codigo_predio, sector_excel, sectores_db)

        if codigo_predio not in predios_map:
            predios_map[codigo_predio] = {
                "codigo_predio": codigo_predio,
                "nombre_predio": nombre_predio,
                "cedula_propietario": cedula,
                "sector": sector_obj,
                "tipo_predio": tipo_obj,
            }

        mes = parse_mes(row[col_map["mes"]]) if "mes" in col_map else None
        anio_raw = row[col_map["anio"]] if "anio" in col_map else None
        anio = int(float(anio_raw)) if anio_raw is not None else None

        if not mes or not anio:
            print(f"Fila {row_num} omitida: Mes o Año inválido ({row[col_map.get('mes', 0)]}, {anio_raw})")
            continue

        valor_tarifa = parse_money(row[col_map["tarifa"]]) if "tarifa" in col_map else Decimal("0.00")
        valor_pago = parse_money(row[col_map["pago"]]) if "pago" in col_map else Decimal("0.00")
        fecha_pago_raw = row[col_map["fecha_pago"]] if "fecha_pago" in col_map else None

        f_gen = dt.date(anio, mes, 1)
        f_venc = dt.date(anio, mes, 15)

        if valor_pago >= valor_tarifa and valor_tarifa > 0:
            estado = "PAGADA"
        elif valor_pago > 0:
            estado = "PARCIAL"
        else:
            estado = "VENCIDA" if f_venc < hoy else "PENDIENTE"

        u_info = usuarios_map[cedula]
        nombre_completo = " ".join(filter(None, [
            u_info["primer_nombre"], u_info["segundo_nombre"],
            u_info["primer_apellido"], u_info["segundo_apellido"]
        ]))[:200]

        facturas_list.append({
            "codigo_predio": codigo_predio,
            "cedula": cedula,
            "nombre_completo": nombre_completo,
            "tipo_predio_id": tipo_obj["id"],
            "mes": mes,
            "anio": anio,
            "valor_tarifa": valor_tarifa,
            "valor_pago": valor_pago,
            "fecha_gen": f_gen,
            "fecha_venc": f_venc,
            "fecha_pago": parse_fecha_pago(fecha_pago_raw, anio, mes) if valor_pago > 0 else None,
            "estado": estado,
        })

    print(f"\n=== RESUMEN DETECTADO EN EL EXCEL ===")
    print(f"- Suscriptores únicos: {len(usuarios_map)}")
    for u in usuarios_map.values():
        print(f"   * CC {u['cedula']}: {u['primer_nombre']} {u['segundo_nombre'] or ''} {u['primer_apellido']} {u['segundo_apellido'] or ''} | Cel: {u['celular']} | Correo: {u['correo_electronico']}")

    print(f"- Predios únicos: {len(predios_map)}")
    for p in predios_map.values():
        print(f"   * {p['codigo_predio']} ('{p['nombre_predio']}') -> Sector: [{p['sector']['id']}] {p['sector']['nombre']} | Tipo: [{p['tipo_predio']['id']}] {p['tipo_predio']['nombre']}")

    print(f"- Facturas a procesar: {len(facturas_list)}")
    for f in facturas_list[:5]:
        print(f"   * {f['codigo_predio']} | Periodo {f['mes']:02d}/{f['anio']} | Valor: ${f['valor_tarifa']} | Pagado: ${f['valor_pago']} | Estado: {f['estado']}")
    if len(facturas_list) > 5:
        print(f"   ... y {len(facturas_list) - 5} facturas más.")

    if not args.ejecutar:
        print("\n[MODO SIMULACIÓN] No se escribió nada en Neon. Si todo está correcto, ejecuta con --ejecutar")
        conn.close()
        return

    try:
        # 1. Insertar o recuperar Usuarios
        usuario_id_por_cc = {}
        for cc, u in usuarios_map.items():
            cur.execute("SELECT id FROM usuarios WHERE cedula = %s", (cc,))
            existing = cur.fetchone()
            if existing:
                usuario_id_por_cc[cc] = existing["id"]
            else:
                cur.execute(
                    """
                    INSERT INTO usuarios (
                        fecha_creacion, fecha_actualizacion, cedula,
                        primer_nombre, segundo_nombre, primer_apellido, segundo_apellido,
                        celular, correo_electronico
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    RETURNING id
                    """,
                    (
                        now_ts, now_ts, u["cedula"],
                        u["primer_nombre"], u["segundo_nombre"],
                        u["primer_apellido"], u["segundo_apellido"],
                        u["celular"], u["correo_electronico"]
                    )
                )
                usuario_id_por_cc[cc] = cur.fetchone()["id"]

        # 2. Insertar o recuperar Predios
        predio_id_por_codigo = {}
        for cod, p in predios_map.items():
            cur.execute("SELECT id FROM predios WHERE codigo_predio = %s", (cod,))
            existing = cur.fetchone()
            if existing:
                predio_id_por_codigo[cod] = existing["id"]
            else:
                cur.execute(
                    """
                    INSERT INTO predios (
                        fecha_creacion, fecha_actualizacion, codigo_predio,
                        nombre_predio, vereda, municipio,
                        usuario_id, sector_id, tipo_predio_id
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    RETURNING id
                    """,
                    (
                        now_ts, now_ts, cod,
                        p["nombre_predio"], "El Tablazo", "Rionegro",
                        usuario_id_por_cc[p["cedula_propietario"]],
                        p["sector"]["id"],
                        p["tipo_predio"]["id"]
                    )
                )
                predio_id_por_codigo[cod] = cur.fetchone()["id"]

        # 3. Resolver o crear Tarifas según (tipo_predio_id, valor)
        def get_or_create_tarifa(tipo_id, valor):
            for t in tarifas_db:
                if t["tipo_predio_id"] == tipo_id and Decimal(str(t["valor"])) == valor:
                    return t["id"]
            cur.execute(
                """
                INSERT INTO tarifas (fecha_creacion, fecha_actualizacion, tipo_predio_id, valor, fecha_inicio)
                VALUES (%s, %s, %s, %s, %s)
                RETURNING id
                """,
                (now_ts, now_ts, tipo_id, valor, dt.date(2025, 1, 1))
            )
            new_id = cur.fetchone()["id"]
            tarifas_db.append({"id": new_id, "tipo_predio_id": tipo_id, "valor": valor})
            return new_id

        # 4. Insertar Facturas y Pagos
        facturas_creadas = 0
        pagos_creados = 0
        for f in facturas_list:
            predio_id = predio_id_por_codigo[f["codigo_predio"]]
            cur.execute(
                "SELECT id FROM facturas WHERE predio_id = %s AND mes = %s AND anio = %s",
                (predio_id, f["mes"], f["anio"])
            )
            existing_f = cur.fetchone()
            if existing_f:
                continue

            tarifa_id = get_or_create_tarifa(f["tipo_predio_id"], f["valor_tarifa"])
            cur.execute(
                """
                INSERT INTO facturas (
                    fecha_creacion, fecha_actualizacion, predio_id, tarifa_id,
                    cedula_propietario, nombre_propietario, mes, anio,
                    valor_factura, fecha_generacion, fecha_vencimiento, estado
                ) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)
                RETURNING id
                """,
                (
                    now_ts, now_ts, predio_id, tarifa_id,
                    f["cedula"], f["nombre_completo"], f["mes"], f["anio"],
                    f["valor_tarifa"], f["fecha_gen"], f["fecha_venc"], f["estado"]
                )
            )
            factura_id = cur.fetchone()["id"]
            facturas_creadas += 1

            if f["valor_pago"] > 0:
                cur.execute(
                    """
                    INSERT INTO pagos (
                        fecha_creacion, fecha_actualizacion, factura_id,
                        valor_pagado, fecha_pago, metodo_pago, observacion
                    ) VALUES (%s, %s, %s, %s, %s, %s, %s)
                    """,
                    (
                        now_ts, now_ts, factura_id,
                        f["valor_pago"], f["fecha_pago"], "EFECTIVO",
                        "Migrado desde histórico Excel"
                    )
                )
                pagos_creados += 1

        conn.commit()
        print(f"\n[MIGRACIÓN EXITOSA] Usuarios: {len(usuario_id_por_cc)} | Predios: {len(predio_id_por_codigo)} | Facturas nuevas: {facturas_creadas} | Pagos registrados: {pagos_creados}")
    except Exception as e:
        conn.rollback()
        print(f"\nERROR durante la migración (se revirtieron todos los cambios): {e}")
        raise
    finally:
        conn.close()


if __name__ == "__main__":
    main()