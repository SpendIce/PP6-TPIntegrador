package io.github.spendice.linkshortener;

import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Dónde ve el test al host que publica los puertos de Testcontainers.
 *
 * <p>En el host es {@code localhost}. En el sandbox de stinter la JVM corre
 * dentro de un contenedor y el daemon de Docker es el del host, así que el
 * puerto mapeado queda publicado en el host y no en el {@code localhost} del
 * sandbox: ahí el camino es el gateway del bridge, que es el host visto desde
 * el contenedor.</p>
 *
 * <p>Sin esto, toda suite con Testcontainers falla por conexión rechazada en
 * el sandbox aunque en el host pase: es el mismo puerto, visto desde otra
 * red.</p>
 */
final class TestHost {

    private static String cache;

    private TestHost() {
    }

    /** Host que publica los puertos mapeados: {@code localhost} o el gateway. */
    static synchronized String host() {
        if (cache != null) {
            return cache;
        }
        if (!Files.exists(Path.of("/.dockerenv"))) {
            return cache = "localhost";
        }
        try {
            List<String> lines = Files.readAllLines(Path.of("/proc/net/route"));
            for (String line : lines) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 3 || !"00000000".equals(parts[1])) {
                    continue;
                }
                // La ruta se publica en hexadecimal y little-endian.
                long gateway = Long.parseLong(parts[2], 16);
                byte[] octets = {
                        (byte) (gateway & 0xff), (byte) ((gateway >> 8) & 0xff),
                        (byte) ((gateway >> 16) & 0xff), (byte) ((gateway >> 24) & 0xff),
                };
                return cache = InetAddress.getByAddress(octets).getHostAddress();
            }
        } catch (IOException | RuntimeException ignored) {
            // Sin tabla de ruteo legible: el gateway por defecto del bridge.
        }
        return cache = "172.17.0.1";
    }

    /** JDBC del contenedor, con el host correcto para esta red. */
    static String jdbcUrl(PostgreSQLContainer<?> postgres) {
        return "jdbc:postgresql://" + host() + ":" + postgres.getMappedPort(5432)
                + "/" + postgres.getDatabaseName();
    }
}
