package link.sharedworld.devhelper.e2e;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Records the game's own framebuffer to an H.264 file through ffmpeg, for
 * promo footage. Display-independent: works with a hidden window on a real
 * GPU display or under xvfb, which is what the WSLg desktop cannot give an
 * X11 screen grabber (its rootless X server grabs black).
 *
 * The GameRenderer mixin calls {@link #onFrame} at the end of every rendered
 * frame. Frames are read back only when a fixed-rate slot is due, copied off
 * the mapped GPU buffer into a pooled direct buffer, and written by one
 * background thread into a FIFO that ffmpeg reads. A frame that arrives late
 * fills every slot it missed, so the file always plays back in real time
 * whatever the game's frame rate did.
 */
public final class FrameRecorder {
    private static final Logger LOGGER = LoggerFactory.getLogger("sharedworld-frame-recorder");
    public static final FrameRecorder INSTANCE = new FrameRecorder();
    private static final int POOL_SIZE = 6;

    private volatile Session session;

    private FrameRecorder() {
    }

    /** Starts recording; returns a status line for the marker file. */
    public synchronized String start(Minecraft minecraft, Path output, int fps, double speed) {
        if (this.session != null) {
            return "already recording " + this.session.output;
        }
        try {
            RenderTarget target = minecraft.getMainRenderTarget();
            GpuTexture texture = target.getColorTexture();
            if (texture == null) {
                return "failed: incomplete framebuffer";
            }
            int pixelSize = texture.getFormat().pixelSize();
            if (pixelSize != 4) {
                return "failed: unsupported pixel size " + pixelSize;
            }
            Session started = new Session(output, fps, speed, target.width, target.height);
            started.open();
            this.session = started;
            return output + " " + target.width + "x" + target.height + "@" + fps + " speed=" + speed;
        } catch (IOException | RuntimeException exception) {
            LOGGER.error("frame recorder start failed", exception);
            return "failed: " + exception;
        }
    }

    /** Stops recording and waits for ffmpeg to finish the file. */
    public synchronized String stop() {
        Session stopping = this.session;
        if (stopping == null) {
            return "not recording";
        }
        this.session = null;
        return stopping.close();
    }

    public void onFrame(Minecraft minecraft) {
        Session current = this.session;
        if (current != null) {
            current.capture(minecraft);
        }
    }

    private record Frame(ByteBuffer pixels, long stampNanos) {
    }

    private static final class Session {
        private final Path output;
        private final int fps;
        /** Game seconds per wall second: 0.25 means the world runs 4x slow (/tick rate 5) and 4 wall seconds fill one clip second. */
        private final double speed;
        private long mapNanos;
        private long copyNanos;
        private final int width;
        private final int height;
        private final int frameBytes;
        private final ArrayBlockingQueue<ByteBuffer> pool = new ArrayBlockingQueue<>(POOL_SIZE);
        private final ArrayBlockingQueue<Frame> queue = new ArrayBlockingQueue<>(POOL_SIZE);
        /** GPU staging buffers, allocated once: creating one per frame is what made the D3D12 path crawl. */
        private final java.util.ArrayDeque<GpuBuffer> freeStaging = new java.util.ArrayDeque<>();
        private final List<GpuBuffer> allStaging = new ArrayList<>();
        private final List<String> problems = new ArrayList<>();
        private Path fifo;
        private Process ffmpeg;
        private Thread writer;
        private volatile boolean closing;
        /** Readbacks issued but not yet called back; their staging buffers are closed by the callback once closing. */
        private int inFlight;
        private long startNanos;
        private long nextCaptureSlot;
        private long captured;
        private long written;
        private long dropped;
        private long sizeMismatches;
        private volatile Throwable writerFailure;

        Session(Path output, int fps, double speed, int width, int height) {
            this.output = output;
            this.fps = fps;
            this.speed = speed;
            this.width = width;
            this.height = height;
            this.frameBytes = width * height * 4;
        }

        void open() throws IOException {
            Files.createDirectories(this.output.toAbsolutePath().getParent());
            this.fifo = this.output.resolveSibling(this.output.getFileName() + ".fifo");
            Files.deleteIfExists(this.fifo);
            try {
                int mkfifo = new ProcessBuilder("mkfifo", this.fifo.toString()).inheritIO().start().waitFor();
                if (mkfifo != 0) {
                    throw new IOException("mkfifo exited " + mkfifo);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted creating fifo", exception);
            }
            Path log = this.output.resolveSibling(this.output.getFileName() + ".ffmpeg.log");
            this.ffmpeg = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "warning", "-y",
                    "-f", "rawvideo", "-pixel_format", "rgba",
                    "-video_size", this.width + "x" + this.height, "-framerate", Integer.toString(this.fps),
                    "-i", this.fifo.toString(),
                    "-vf", "vflip",
                    "-c:v", "libx264", "-preset", this.width >= 3000 ? "ultrafast" : "veryfast", "-crf", this.width >= 3000 ? "14" : "12", "-pix_fmt", "yuv420p",
                    "-movflags", "+faststart",
                    this.output.toString()
            ).redirectErrorStream(true).redirectOutput(log.toFile()).start();
            for (int index = 0; index < POOL_SIZE; index++) {
                this.pool.add(ByteBuffer.allocateDirect(this.frameBytes));
            }
            this.startNanos = System.nanoTime();
            this.writer = new Thread(this::drain, "sharedworld-frame-writer");
            this.writer.setDaemon(true);
            this.writer.start();
        }

        /** Render thread: issue a readback when a slot is due. */
        void capture(Minecraft minecraft) {
            if (this.closing || this.writerFailure != null) {
                return;
            }
            long now = this.startNanos + (long) ((System.nanoTime() - this.startNanos) * this.speed);
            long slot = (now - this.startNanos) * this.fps / 1_000_000_000L;
            if (slot < this.nextCaptureSlot) {
                return;
            }
            this.nextCaptureSlot = slot + 1;
            RenderTarget target = minecraft.getMainRenderTarget();
            if (target.width != this.width || target.height != this.height) {
                this.sizeMismatches++;
                return;
            }
            GpuTexture texture = target.getColorTexture();
            GpuDevice device = RenderSystem.getDevice();
            GpuBuffer buffer = this.freeStaging.poll();
            if (buffer == null) {
                if (this.allStaging.size() < POOL_SIZE) {
                    buffer = device.createBuffer(() -> "SharedWorld promo frame", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, this.frameBytes);
                    this.allStaging.add(buffer);
                } else {
                    this.dropped++;
                    return;
                }
            }
            CommandEncoder encoder = device.createCommandEncoder();
            this.captured++;
            this.inFlight++;
            GpuBuffer staging = buffer;
            device.createCommandEncoder().copyTextureToBuffer(texture, staging, 0L, () -> {
                this.inFlight--;
                if (this.closing) {
                    staging.close();
                    return;
                }
                ByteBuffer destination = this.pool.poll();
                if (destination == null) {
                    this.dropped++;
                    this.freeStaging.add(staging);
                    return;
                }
                long mapStart = System.nanoTime();
                try (GpuBuffer.MappedView view = encoder.mapBuffer(staging, true, false)) {
                    long copyStart = System.nanoTime();
                    this.mapNanos += copyStart - mapStart;
                    ByteBuffer source = view.data().duplicate();
                    source.position(0).limit(this.frameBytes);
                    destination.clear();
                    destination.put(source);
                    destination.flip();
                    this.copyNanos += System.nanoTime() - copyStart;
                } finally {
                    this.freeStaging.add(staging);
                }
                if (!this.queue.offer(new Frame(destination, now))) {
                    this.dropped++;
                    this.pool.offer(destination);
                }
            }, 0);
        }

        /** Writer thread: fixed-rate output, repeating a frame for every slot it covers. */
        private void drain() {
            try (FileChannel channel = FileChannel.open(this.fifo, StandardOpenOption.WRITE)) {
                for (;;) {
                    Frame frame = this.queue.poll(200, TimeUnit.MILLISECONDS);
                    if (frame == null) {
                        if (this.closing) {
                            return;
                        }
                        continue;
                    }
                    long slotsDue = (frame.stampNanos() - this.startNanos) * this.fps / 1_000_000_000L + 1;
                    while (this.written < slotsDue) {
                        frame.pixels().rewind();
                        while (frame.pixels().hasRemaining()) {
                            channel.write(frame.pixels());
                        }
                        this.written++;
                    }
                    this.pool.offer(frame.pixels());
                }
            } catch (Throwable failure) {
                LOGGER.error("frame writer failed", failure);
                this.writerFailure = failure;
            }
        }

        String close() {
            this.closing = true;
            // Free buffers close now; in-flight ones close in their own callback.
            for (GpuBuffer staging : this.freeStaging) {
                staging.close();
            }
            this.freeStaging.clear();
            this.allStaging.clear();
            try {
                this.writer.join(10_000);
                boolean finished = this.ffmpeg.waitFor(60, TimeUnit.SECONDS);
                if (!finished) {
                    this.ffmpeg.destroyForcibly();
                    this.problems.add("ffmpeg did not finish in 60s");
                } else if (this.ffmpeg.exitValue() != 0) {
                    this.problems.add("ffmpeg exited " + this.ffmpeg.exitValue());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                this.problems.add("interrupted");
            } finally {
                try {
                    Files.deleteIfExists(this.fifo);
                } catch (IOException ignored) {
                    // Best effort.
                }
            }
            if (this.writerFailure != null) {
                this.problems.add("writer: " + this.writerFailure);
            }
            double seconds = (double) this.written / this.fps;
            long captures = Math.max(1, this.captured);
            return this.output + " written=" + this.written + " captured=" + this.captured + " dropped=" + this.dropped
                    + " mapMs=" + (this.mapNanos / captures / 1_000_000) + " copyMs=" + (this.copyNanos / captures / 1_000_000)
                    + " sizeMismatches=" + this.sizeMismatches + " seconds=" + String.format(java.util.Locale.ROOT, "%.2f", seconds)
                    + (this.problems.isEmpty() ? "" : " problems=" + String.join("; ", this.problems));
        }
    }
}
