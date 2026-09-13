package link.sharedworld.devhelper.e2e;

import net.minecraft.client.CameraType;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.player.LocalPlayer;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The promo scenario's command set: choreography for footage capture rather
 * than assertions. One command per tick from the command file, plus per-tick
 * effects (smooth camera turns, held keys, button retries, screen waits).
 *
 * Commands:
 *   record:start:<path>:<fps>[:<speed>] | record:stop   speed = game seconds per wall second (0.25 with /tick rate 5)
 *   look:<yaw>:<pitch>:<ticks>            smooth turn to an absolute rotation
 *   move:<forward|back|left|right|jump|sneak|sprint|attack|use>:<ticks>
 *   perspective:<0|1|2>                   first person, third back, third front
 *   hide-hud:<true|false>
 *   press-button:<translation key>        retried for up to 10s on whatever screen is up
 *   await-screen:<SimpleName|none>[,...]  holds the queue until one of those screens is current
 *   open-multiplayer | title-screen | close-screen | pause
 *   resize:<w>:<h> | window-size | fps | gpu | screen | last-chat | pos
 *   server-cmd:<cmd> | chat:<text> | screenshot:<name> | press-key:<name> | gui-scale:<n>
 *   shutdown (returns true to the caller) | exit
 */
final class PromoOps {
    private static final int BUTTON_RETRY_TICKS = 200;
    private static final int AWAIT_SCREEN_TICKS = 1200;

    private final Map<KeyMapping, Integer> heldKeys = new HashMap<>();
    private float lookStartYaw;
    private float lookStartPitch;
    private float lookTargetYaw;
    private float lookTargetPitch;
    private int lookTicksTotal;
    private int lookTicksLeft;
    private String pendingButton;
    private int pendingButtonTicks;
    private String awaitedScreen;
    private int awaitedScreenTicks;

    /** Runs effects, then at most one command. Returns true when "shutdown" was read. */
    boolean tick(Minecraft minecraft, E2eCommands commands, E2eMarkers markers) {
        this.tickEffects(minecraft, markers);
        if (this.awaitedScreen != null) {
            return false;
        }
        String command = commands.poll();
        if (command == null) {
            return false;
        }
        if ("shutdown".equals(command)) {
            markers.emit("shutdown-received", null);
            return true;
        }
        this.handle(minecraft, command, markers);
        return false;
    }

    void tickEffects(Minecraft minecraft, E2eMarkers markers) {
        LocalPlayer player = minecraft.player;
        if (player != null && this.lookTicksLeft > 0) {
            this.lookTicksLeft--;
            float progress = 1.0f - (float) this.lookTicksLeft / this.lookTicksTotal;
            float eased = progress < 0.5f ? 2.0f * progress * progress : 1.0f - (float) Math.pow(-2.0f * progress + 2.0f, 2) / 2.0f;
            player.setYRot(this.lookStartYaw + (this.lookTargetYaw - this.lookStartYaw) * eased);
            player.setXRot(this.lookStartPitch + (this.lookTargetPitch - this.lookStartPitch) * eased);
        }
        if (!this.heldKeys.isEmpty()) {
            this.heldKeys.entrySet().removeIf(entry -> {
                int left = entry.getValue() - 1;
                if (left <= 0) {
                    entry.getKey().setDown(false);
                    return true;
                }
                entry.getKey().setDown(true);
                entry.setValue(left);
                return false;
            });
        }
        if (this.pendingButton != null) {
            if (minecraft.screen != null && WidgetAutomation.pressButton(minecraft.screen, this.pendingButton)) {
                markers.emit("button-pressed", this.pendingButton);
                this.pendingButton = null;
            } else if (--this.pendingButtonTicks <= 0) {
                markers.emit("button-missing", this.pendingButton + " screen=" + screenName(minecraft));
                this.pendingButton = null;
            }
        }
        if (this.awaitedScreen != null) {
            String current = screenName(minecraft);
            if (java.util.Arrays.asList(this.awaitedScreen.split(",")).contains(current)) {
                markers.emit("screen-seen", current);
                this.awaitedScreen = null;
            } else if (--this.awaitedScreenTicks <= 0) {
                markers.emit("screen-timeout", this.awaitedScreen + " screen=" + screenName(minecraft));
                this.awaitedScreen = null;
            }
        }
    }

    void handle(Minecraft minecraft, String command, E2eMarkers markers) {
        try {
            if (command.startsWith("record:start:")) {
                String[] parts = command.substring("record:start:".length()).split(":");
                int fps = parts.length > 1 ? Integer.parseInt(parts[1]) : 60;
                double speed = parts.length > 2 ? Double.parseDouble(parts[2]) : 1.0;
                markers.emit("recording-started", FrameRecorder.INSTANCE.start(minecraft, Path.of(parts[0]), fps, speed));
            } else if ("record:stop".equals(command)) {
                markers.emit("recording-stopped", FrameRecorder.INSTANCE.stop());
            } else if (command.startsWith("look:")) {
                String[] parts = command.substring("look:".length()).split(":");
                LocalPlayer player = minecraft.player;
                if (player == null) {
                    markers.emit("look-failed", "no player");
                    return;
                }
                float yaw = Float.parseFloat(parts[0]);
                float pitch = Float.parseFloat(parts[1]);
                int ticks = parts.length > 2 ? Math.max(1, Integer.parseInt(parts[2])) : 1;
                this.lookStartYaw = player.getYRot();
                this.lookStartPitch = player.getXRot();
                float delta = yaw - this.lookStartYaw;
                while (delta > 180.0f) delta -= 360.0f;
                while (delta < -180.0f) delta += 360.0f;
                this.lookTargetYaw = this.lookStartYaw + delta;
                this.lookTargetPitch = pitch;
                this.lookTicksTotal = ticks;
                this.lookTicksLeft = ticks;
                markers.emit("look-started", command);
            } else if (command.startsWith("move:")) {
                String[] parts = command.substring("move:".length()).split(":");
                KeyMapping mapping = movementKey(minecraft, parts[0]);
                if (mapping == null) {
                    markers.emit("move-failed", "unknown key " + parts[0]);
                    return;
                }
                int ticks = parts.length > 1 ? Math.max(1, Integer.parseInt(parts[1])) : 1;
                mapping.setDown(true);
                this.heldKeys.put(mapping, ticks);
                markers.emit("move-started", command);
            } else if (command.startsWith("perspective:")) {
                int index = Integer.parseInt(command.substring("perspective:".length()));
                minecraft.options.setCameraType(CameraType.values()[index]);
                markers.emit("perspective-set", Integer.toString(index));
            } else if (command.startsWith("hide-hud:")) {
                minecraft.options.hideGui = Boolean.parseBoolean(command.substring("hide-hud:".length()));
                markers.emit("hud-hidden", Boolean.toString(minecraft.options.hideGui));
            } else if (command.startsWith("press-button:")) {
                this.pendingButton = command.substring("press-button:".length());
                this.pendingButtonTicks = BUTTON_RETRY_TICKS;
            } else if (command.startsWith("await-screen:")) {
                this.awaitedScreen = command.substring("await-screen:".length());
                this.awaitedScreenTicks = AWAIT_SCREEN_TICKS;
                String current = screenName(minecraft);
                if (java.util.Arrays.asList(this.awaitedScreen.split(",")).contains(current)) {
                    markers.emit("screen-seen", current);
                    this.awaitedScreen = null;
                }
            } else if ("title-screen".equals(command)) {
                minecraft.setScreen(new TitleScreen());
                markers.emit("title-screen", null);
            } else if ("open-multiplayer".equals(command)) {
                minecraft.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
                markers.emit("multiplayer-opened", null);
            } else if ("close-screen".equals(command)) {
                minecraft.setScreen(null);
                markers.emit("screen-closed", null);
            } else if ("pause".equals(command)) {
                if (minecraft.screen == null) {
                    minecraft.setScreen(new PauseScreen(true));
                }
                markers.emit("paused", screenName(minecraft));
            } else if (command.startsWith("resize:")) {
                String[] parts = command.substring("resize:".length()).split(":");
                GLFW.glfwSetWindowSize(minecraft.getWindow().handle(), Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                markers.emit("resize-requested", command);
            } else if ("window-size".equals(command)) {
                markers.emit("window-size", minecraft.getWindow().getWidth() + "x" + minecraft.getWindow().getHeight()
                        + " gui=" + minecraft.getWindow().getGuiScaledWidth() + "x" + minecraft.getWindow().getGuiScaledHeight());
            } else if ("pos".equals(command)) {
                LocalPlayer player = minecraft.player;
                markers.emit("pos", player == null ? "none" : String.format(Locale.ROOT, "%.2f %.2f %.2f %.1f %.1f",
                        player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
            } else if ("fps".equals(command)) {
                markers.emit("fps", Integer.toString(minecraft.getFps()));
            } else if ("gpu".equals(command)) {
                com.mojang.blaze3d.systems.GpuDevice device = com.mojang.blaze3d.systems.RenderSystem.getDevice();
                markers.emit("gpu", device.getBackendName() + " | " + device.getVendor() + " | " + device.getRenderer() + " | " + device.getVersion());
            } else if ("last-chat".equals(command)) {
                markers.emit("last-chat", lastChatLine(minecraft));
            } else if ("screen".equals(command)) {
                markers.emit("screen", screenName(minecraft));
            } else if (command.startsWith("server-cmd:")) {
                String serverCommand = command.substring("server-cmd:".length());
                if (minecraft.player == null) {
                    markers.emit("server-cmd-failed", "no player: " + serverCommand);
                    return;
                }
                minecraft.player.connection.sendCommand(serverCommand);
                markers.emit("server-cmd-sent", serverCommand);
            } else if (command.startsWith("chat:")) {
                String text = command.substring("chat:".length());
                if (minecraft.player == null) {
                    markers.emit("chat-failed", "no player: " + text);
                    return;
                }
                minecraft.player.connection.sendChat(text);
                markers.emit("chat-sent", text);
            } else if (command.startsWith("screenshot:")) {
                String name = command.substring("screenshot:".length());
                net.minecraft.client.Screenshot.takeScreenshot(minecraft.getMainRenderTarget(), image -> {
                    try (image) {
                        Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
                        java.nio.file.Files.createDirectories(directory);
                        image.writeToFile(directory.resolve(name + ".png"));
                        markers.emit("shot", name);
                    } catch (Exception exception) {
                        markers.emit("shot-failed", name + ": " + exception);
                    }
                });
            } else if (command.startsWith("press-key:")) {
                String name = command.substring("press-key:".length());
                markers.emit(pressKeyMapping(minecraft, name) ? "key-pressed" : "key-missing", name);
            } else if (command.startsWith("gui-scale:")) {
                minecraft.options.guiScale().set(Integer.parseInt(command.substring("gui-scale:".length())));
                minecraft.resizeDisplay();
                markers.emit("gui-scaled", command.substring("gui-scale:".length()));
            } else if ("exit".equals(command)) {
                markers.emit("exiting", null);
                minecraft.stop();
            } else {
                markers.emit("driver-exception", "promo: unknown command " + command);
            }
        } catch (RuntimeException exception) {
            markers.emit("driver-exception", "promo: " + command + ": " + exception);
        }
    }

    private static KeyMapping movementKey(Minecraft minecraft, String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "forward" -> minecraft.options.keyUp;
            case "back" -> minecraft.options.keyDown;
            case "left" -> minecraft.options.keyLeft;
            case "right" -> minecraft.options.keyRight;
            case "jump" -> minecraft.options.keyJump;
            case "sneak" -> minecraft.options.keyShift;
            case "sprint" -> minecraft.options.keySprint;
            case "attack" -> minecraft.options.keyAttack;
            case "use" -> minecraft.options.keyUse;
            default -> null;
        };
    }

    private static boolean pressKeyMapping(Minecraft minecraft, String name) {
        for (KeyMapping mapping : minecraft.options.keyMappings) {
            if (!name.equals(mapping.getName())) {
                continue;
            }
            try {
                java.lang.reflect.Field keyField = KeyMapping.class.getDeclaredField("key");
                keyField.setAccessible(true);
                KeyMapping.click((com.mojang.blaze3d.platform.InputConstants.Key) keyField.get(mapping));
                return true;
            } catch (ReflectiveOperationException | RuntimeException exception) {
                return false;
            }
        }
        return false;
    }

    /** Newest HUD chat line (reflection on ChatComponent.allMessages, newest first), or "". */
    private static String lastChatLine(Minecraft minecraft) {
        try {
            net.minecraft.client.gui.components.ChatComponent chat = minecraft.gui.getChat();
            java.lang.reflect.Field field = chat.getClass().getDeclaredField("allMessages");
            field.setAccessible(true);
            java.util.List<?> all = (java.util.List<?>) field.get(chat);
            if (all.isEmpty()) {
                return "";
            }
            Object message = all.get(0);
            Object content = message.getClass().getMethod("content").invoke(message);
            return ((net.minecraft.network.chat.Component) content).getString();
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return "error: " + exception;
        }
    }

    static String screenName(Minecraft minecraft) {
        return minecraft.screen == null ? "none" : minecraft.screen.getClass().getSimpleName();
    }
}
