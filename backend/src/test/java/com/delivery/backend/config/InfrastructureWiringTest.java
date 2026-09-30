package com.delivery.backend.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the wiring between the application and the containers it runs in.
 *
 * <p>Three separate defects got this far, and all three were the same mistake:
 * a service address that a developer could not tell was hardcoded, because on a
 * developer machine {@code localhost} is the right answer.
 *
 * <ul>
 *   <li>the JDBC URL pinned {@code localhost:5432}, so the backend container
 *       spent its startup retrying itself instead of the healthy Postgres beside
 *       it, and crashed on a loop;</li>
 *   <li>Redis had no host entry at all, so it stayed on Spring's own
 *       {@code localhost} default and auto-dispatch threw
 *       {@code RedisConnectionFailureException} on every paid order;</li>
 *   <li>both OSRM consumers read {@code app.osrm.base-url} while the property
 *       really lives at {@code application.osrm.base-url}. That one is the
 *       nastiest of the three, because a misspelled key that carries a default
 *       does not fail -- it silently uses the default. Every quote came from
 *       {@code localhost} while the environment said otherwise.</li>
 * </ul>
 *
 * <p>None of them would have been caught by a unit test of the service itself.
 * They are only visible when you read the configuration and the deployment
 * description against each other, which is what this class does.
 *
 * <p>What this cannot catch: a dependency added with no configuration entry at
 * all, because nothing in the config file hints that it should have one. The
 * redis case was exactly that. The net for it is running the real stack, which
 * is what {@code docker compose up} plus the verification scripts are for.
 */
class InfrastructureWiringTest {

    /** {@code ${NAME}} or {@code ${NAME:default}}. */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)(?::([^}]*))?}");
    private static final Pattern AT_VALUE = Pattern.compile("@Value\\(\\s*\"\\$\\{([A-Za-z_][A-Za-z0-9_.-]*)(?::([^}]*))?}\"");

    private static final Path REPO_ROOT = repoRoot();
    private static final Path APPLICATION_YML = REPO_ROOT.resolve("backend/src/main/resources/application.yml");
    private static final Path COMPOSE_YML = REPO_ROOT.resolve("docker-compose.yml");

    // ------------------------------------------------------------------ tests

    @Test
    @DisplayName("no infrastructure address is buried in an @Value default")
    void noInfraAddressBuriedInAnAtValueDefault() throws IOException {
        Set<String> declared = flattenedKeys(yamlTree(APPLICATION_YML));
        List<String> offences = new ArrayList<>();
        int seen = 0;

        for (Path file : javaSources()) {
            String source = Files.readString(file, StandardCharsets.UTF_8);
            Matcher m = AT_VALUE.matcher(source);
            while (m.find()) {
                seen++;
                String key = m.group(1);
                String fallback = m.group(2);
                if (fallback == null || !isLocalAddress(fallback)) {
                    continue;
                }
                if (!declared.contains(key)) {
                    offences.add(file.getFileName() + " reads the key '" + key + "' with the default "
                            + fallback + ", and no such key exists in application.yml -- "
                            + "so the environment variable meant to override it is read by nobody");
                }
            }
        }

        // A regex that quietly matches nothing would make the check above pass
        // forever while proving nothing. Assert the scan actually scanned.
        assertTrue(seen >= 7, "expected to find the @Value placeholders in main, found " + seen
                + " -- the regex has probably stopped matching and this test is now vacuous");

        assertTrue(offences.isEmpty(), "\n" + String.join("\n", offences));
    }

    @Test
    @DisplayName("application.yml contains no hardcoded local address")
    void applicationYmlHasNoHardcodedLocalAddress() throws IOException {
        String raw = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
        // Comments are stripped before the search: the file explains this exact
        // failure in prose, and a test that reads its own documentation as
        // evidence of the bug is useless.
        String code = stripComments(raw).replaceAll("\\$\\{[^}]*}", " ");

        Matcher m = Pattern.compile("localhost|127\\.0\\.0\\.1").matcher(code);
        // The offset is only read when there is a match to point at; asking a
        // Matcher for start() after a failed find() throws, which would turn a
        // passing check into an error.
        boolean found = m.find();
        assertFalse(found, "application.yml addresses a local host literally"
                + (found ? ", at offset " + m.start() : "")
                + ". Inside a container 'localhost' is the container itself, so every such"
                + " address has to be a ${VARIABLE:default} instead");
    }

    @Test
    @DisplayName("compose overrides every variable that defaults to a local address")
    void composeOverridesEveryVariableThatDefaultsToALocalAddress() throws IOException {
        String raw = Files.readString(APPLICATION_YML, StandardCharsets.UTF_8);
        Set<String> composeEnv = backendServiceEnvironment();

        List<String> missing = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(raw);
        while (m.find()) {
            String name = m.group(1);
            String fallback = m.group(2);
            if (fallback == null || !isLocalAddress(fallback)) {
                continue;
            }
            if (!composeEnv.contains(name)) {
                missing.add(name + " (defaults to " + fallback + " but docker-compose.yml does not set it"
                        + " for the backend, so the container keeps the local-only default)");
            }
        }

        assertTrue(missing.isEmpty(), "\n" + String.join("\n", missing));

        // Sanity guard, last: it should never be the thing that fires, because
        // the check above already derives the list from the config file. If it
        // does fire, the parser stopped seeing the environment block and the
        // check above is passing for the wrong reason.
        assertTrue(composeEnv.contains("DB_HOST") && composeEnv.contains("REDIS_HOST")
                        && composeEnv.contains("OSRM_BASE_URL"),
                "the backend service should set the three infrastructure hosts; found " + composeEnv);
    }

    // ----------------------------------------------------------------- helpers

    private static boolean isLocalAddress(String value) {
        return value.contains("localhost") || value.contains("127.0.0.1");
    }

    /** The directory holding both {@code backend/} and {@code docker-compose.yml}. */
    private static Path repoRoot() {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isRegularFile(p.resolve("docker-compose.yml"))
                    && Files.isDirectory(p.resolve("backend/src/main/resources"))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "no ancestor of " + dir + " holds both docker-compose.yml and backend/src/main/resources;"
                        + " the module layout changed and this test is looking in the wrong place");
    }

    private static List<Path> javaSources() throws IOException {
        Path main = REPO_ROOT.resolve("backend/src/main/java");
        try (Stream<Path> walk = Files.walk(main)) {
            return walk.filter(p -> p.toString().endsWith(".java")).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlTree(Path file) throws IOException {
        try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = new Yaml().load(reader);
            assertTrue(root instanceof Map, file + " should parse to a mapping, got " + root);
            return (Map<String, Object>) root;
        }
    }

    /** Dotted leaf paths: {@code spring.data.redis.host}. */
    private static Set<String> flattenedKeys(Map<String, Object> tree) {
        Set<String> keys = new LinkedHashSet<>();
        flatten(tree, "", keys);
        return keys;
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> node, String prefix, Set<String> out) {
        for (Map.Entry<String, Object> e : node.entrySet()) {
            String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
            Object value = e.getValue();
            if (value instanceof Map) {
                flatten((Map<String, Object>) value, path, out);
            } else {
                out.add(path);
            }
        }
    }

    /**
     * The keys of the backend service's {@code environment:} block, read as text.
     *
     * <p>Not parsed as YAML on purpose: the compose file quotes its
     * {@code ${JWT_SECRET_KEY:?...}} gate, and a test that breaks the moment
     * someone adds an unquoted colon is a test about quoting, not about wiring.
     */
    private static Set<String> backendServiceEnvironment() throws IOException {
        List<String> lines = Files.readAllLines(COMPOSE_YML, StandardCharsets.UTF_8);
        Set<String> keys = new LinkedHashSet<>();

        int start = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).matches("^ {2}backend:\\s*$")) {
                start = i;
                break;
            }
        }
        assertTrue(start >= 0, "docker-compose.yml has no 'backend:' service");

        boolean inEnvironment = false;
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.matches("^ {2}\\S")) {
                break; // the next service: the backend block is over
            }
            if (line.matches("^ {4}environment:\\s*$")) {
                inEnvironment = true;
                continue;
            }
            if (inEnvironment) {
                Matcher m = Pattern.compile("^ {6}([A-Za-z_][A-Za-z0-9_]*):").matcher(line);
                if (m.find()) {
                    keys.add(m.group(1));
                } else if (!line.matches("^ {6,}.*") || line.isBlank()) {
                    inEnvironment = false; // dedented out of the block
                }
            }
        }
        return keys;
    }

    private static String stripComments(String yaml) {
        StringBuilder sb = new StringBuilder();
        for (String line : yaml.split("\n", -1)) {
            if (line.stripLeading().startsWith("#")) {
                continue;
            }
            int hash = line.indexOf(" #");
            sb.append(hash >= 0 ? line.substring(0, hash) : line).append('\n');
        }
        return sb.toString();
    }
}
