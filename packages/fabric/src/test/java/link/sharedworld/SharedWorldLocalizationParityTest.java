package link.sharedworld;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SharedWorldLocalizationParityTest {
    private static final Pattern KEY_PATTERN = Pattern.compile("\"((?:screen|menu)\\.sharedworld[^\"]*)\"");
    private static final Path LANG_DIR = Path.of("src/main/resources/assets/sharedworld/lang");
    private static final Path CANONICAL_LANG_FILE = LANG_DIR.resolve("en_us.json");

    @Test
    void enUsMatchesSharedWorldTranslationKeysReferencedFromSource() throws IOException {
        Set<String> referencedKeys = new TreeSet<>();
        collectKeys(Path.of("src/main/java"), referencedKeys);
        collectKeys(Path.of("src/test/java"), referencedKeys);

        Set<String> definedKeys = readSharedWorldKeys(CANONICAL_LANG_FILE);

        assertEquals(referencedKeys, definedKeys, () -> "Localization key mismatch."
                + "\nMissing from en_us: " + difference(referencedKeys, definedKeys)
                + "\nUnused in en_us: " + difference(definedKeys, referencedKeys));
    }

    @Test
    void everyLocaleMatchesEnUsTranslationKeys() throws IOException {
        Set<String> canonicalKeys = readSharedWorldKeys(CANONICAL_LANG_FILE);

        List<Path> localeFiles;
        try (var stream = Files.list(LANG_DIR)) {
            localeFiles = stream
                    .filter(path -> Files.isRegularFile(path) && path.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }

        for (Path localeFile : localeFiles) {
            if (localeFile.equals(CANONICAL_LANG_FILE)) {
                continue;
            }

            Set<String> localeKeys = readSharedWorldKeys(localeFile);
            String localeName = localeFile.getFileName().toString();
            assertEquals(canonicalKeys, localeKeys, () -> "Localization key mismatch for " + localeName + "."
                    + "\nMissing from " + localeName + ": " + difference(canonicalKeys, localeKeys)
                    + "\nUnused in " + localeName + ": " + difference(localeKeys, canonicalKeys));
        }
    }

    private static void collectKeys(Path root, Set<String> keys) throws IOException {
        try (var stream = Files.walk(root)) {
            for (Path path : stream.filter(file -> Files.isRegularFile(file) && file.toString().endsWith(".java")).toList()) {
                if (path.getFileName().toString().equals("SharedWorldLocalizationParityTest.java")) {
                    continue;
                }
                Matcher matcher = KEY_PATTERN.matcher(Files.readString(path));
                while (matcher.find()) {
                    keys.add(matcher.group(1));
                }
            }
        }
    }

    private static Set<String> readSharedWorldKeys(Path langFile) throws IOException {
        Set<String> definedKeys = new TreeSet<>();
        JsonObject root = JsonParser.parseString(Files.readString(langFile)).getAsJsonObject();
        for (String key : root.keySet()) {
            if (key.startsWith("screen.sharedworld") || key.startsWith("menu.sharedworld")) {
                definedKeys.add(key);
            }
        }
        return definedKeys;
    }

    private static Set<String> difference(Set<String> left, Set<String> right) {
        Set<String> diff = new TreeSet<>(left);
        diff.removeAll(right);
        return diff;
    }
}
