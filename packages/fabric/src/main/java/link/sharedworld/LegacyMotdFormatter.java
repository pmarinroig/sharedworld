package link.sharedworld;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

public final class LegacyMotdFormatter {
    // Classified by constant instead of ChatFormatting.isColor()/isFormat(),
    // which no longer exist on newer Minecraft versions.
    private static final Set<ChatFormatting> STYLE_FORMATS = EnumSet.of(
            ChatFormatting.OBFUSCATED,
            ChatFormatting.BOLD,
            ChatFormatting.STRIKETHROUGH,
            ChatFormatting.UNDERLINE,
            ChatFormatting.ITALIC
    );

    private LegacyMotdFormatter() {
    }

    public static List<Component> lines(String motd) {
        List<Component> components = new ArrayList<>();
        if (motd == null || motd.isBlank()) {
            return components;
        }
        for (String line : motd.split("\n", -1)) {
            components.add(parseLine(line));
        }
        return components;
    }

    private static Component parseLine(String line) {
        MutableComponent root = Component.empty();
        StringBuilder buffer = new StringBuilder();
        List<ChatFormatting> activeFormats = new ArrayList<>();
        ChatFormatting activeColor = null;

        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '§' && index + 1 < line.length()) {
                appendSegment(root, buffer, activeColor, activeFormats);
                ChatFormatting format = ChatFormatting.getByCode(line.charAt(++index));
                if (format == null) {
                    continue;
                }
                if (format == ChatFormatting.RESET) {
                    activeColor = null;
                    activeFormats.clear();
                } else if (STYLE_FORMATS.contains(format)) {
                    if (!activeFormats.contains(format)) {
                        activeFormats.add(format);
                    }
                } else {
                    activeColor = format;
                    activeFormats.clear();
                }
                continue;
            }
            buffer.append(current);
        }

        appendSegment(root, buffer, activeColor, activeFormats);
        return root;
    }

    private static void appendSegment(
            MutableComponent root,
            StringBuilder buffer,
            ChatFormatting activeColor,
            List<ChatFormatting> activeFormats
    ) {
        if (buffer.isEmpty()) {
            return;
        }
        MutableComponent segment = Component.literal(buffer.toString());
        if (activeColor != null && !activeFormats.isEmpty()) {
            ChatFormatting[] formats = new ChatFormatting[activeFormats.size() + 1];
            formats[0] = activeColor;
            for (int index = 0; index < activeFormats.size(); index++) {
                formats[index + 1] = activeFormats.get(index);
            }
            segment.withStyle(formats);
        } else if (activeColor != null) {
            segment.withStyle(activeColor);
        } else if (!activeFormats.isEmpty()) {
            segment.withStyle(activeFormats.toArray(ChatFormatting[]::new));
        }
        root.append(segment);
        buffer.setLength(0);
    }
}
