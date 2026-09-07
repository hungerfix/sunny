package com.hazbu.xcam.hooks;

import android.media.AudioRecord;
import com.hazbu.xcam.xposed.XCamModule;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hooks android.media.AudioRecord to replace the live microphone feed with
 * audio decoded from the virtual media source (mp4 / m3u8 / URL).
 *
 * Strategy:
 *   - chain.proceed() is called first on every read() so AudioRecord's internal
 *     ring-buffer and state machine remain consistent.
 *   - The buffer is then overwritten with virtual PCM before returning to the app.
 *   - If no media path is set, or the decoder is inactive, the real mic data
 *     is returned unchanged.
 */
public class AudioHook {

    private final XCamModule module;

    public AudioHook(XCamModule module) {
        this.module = module;
    }

    public void install(XposedModuleInterface.PackageReadyParam param) {
        try {
            module.logHook("[*] Initializing Audio Injection Hooks");
            hookConstructors();
            hookStartRecording();
            hookReadMethods();
            hookStopRelease();
            module.logHook("[+] Audio Injection Hooks installed successfully");
        } catch (Throwable t) {
            module.logHook("[!] Failed to install Audio hooks: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Constructor — capture format params for logging
    // ──────────────────────────────────────────────────────────────────────────

    private void hookConstructors() {
        try {
            for (Constructor<?> ctor : AudioRecord.class.getConstructors()) {
                module.hook(ctor).intercept(chain -> {
                    Object result = chain.proceed();
                    if (result instanceof AudioRecord) {
                        AudioRecord ar = (AudioRecord) result;
                        module.logHook("[*] AudioRecord created: SR=" + ar.getSampleRate()
                                + " CH=" + ar.getChannelCount()
                                + " fmt=" + ar.getAudioFormat());
                    }
                    return result;
                });
            }
            module.logHook("[+] Hooked: AudioRecord constructors");
        } catch (Throwable t) {
            module.logHook("[!] AudioRecord constructor hook failed: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // startRecording — kick off the audio decoder
    // ──────────────────────────────────────────────────────────────────────────

    private void hookStartRecording() {
        try {
            Method startRecording = AudioRecord.class.getDeclaredMethod("startRecording");
            module.hook(startRecording).intercept(chain -> {
                Object result = chain.proceed();
                String path = module.getMediaPath();
                if (path != null) {
                    AudioRecord ar = (AudioRecord) chain.getThisObject();
                    module.startAudio(path, ar.getSampleRate(), ar.getChannelCount());
                    module.logHook("[+] Audio injection started  SR=" + ar.getSampleRate()
                            + " CH=" + ar.getChannelCount());
                }
                return result;
            });
            module.logHook("[+] Hooked: AudioRecord#startRecording");
        } catch (Throwable t) {
            module.logHook("[!] startRecording hook failed: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // read() overloads — inject virtual PCM into every variant
    // ──────────────────────────────────────────────────────────────────────────

    private void hookReadMethods() {
        for (Method method : AudioRecord.class.getDeclaredMethods()) {
            if (!method.getName().equals("read")) continue;

            Class<?>[] types = method.getParameterTypes();

            // ── read(byte[], int, int) ────────────────────────────────────────
            if (types.length >= 3
                    && types[0] == byte[].class
                    && types[1] == int.class
                    && types[2] == int.class) {
                hookReadByteArray(method);
            }

            // ── read(short[], int, int) ───────────────────────────────────────
            else if (types.length >= 3
                    && types[0] == short[].class
                    && types[1] == int.class
                    && types[2] == int.class) {
                hookReadShortArray(method);
            }

            // ── read(ByteBuffer, int) or read(ByteBuffer, int, int) ───────────
            else if (types.length >= 2 && types[0] == ByteBuffer.class && types[1] == int.class) {
                hookReadByteBuffer(method);
            }
        }
    }

    private void hookReadByteArray(Method method) {
        try {
            module.hook(method).intercept(chain -> {
                // Let AudioRecord read from the real mic first (keeps state healthy)
                Object result = chain.proceed();
                if (module.getMediaPath() == null || !module.isAudioActive()) return result;

                byte[] buf           = (byte[]) chain.getArgs().get(0);
                int    offsetInBytes = (int)    chain.getArgs().get(1);
                int    sizeInBytes   = (int)    chain.getArgs().get(2);
                int written = module.readAudioData(buf, offsetInBytes, sizeInBytes);
                module.logHook("[*] AudioRecord.read(byte[]) injected " + written + " B");
                return written;
            });
            module.logHook("[+] Hooked: AudioRecord#read(byte[], int, int)");
        } catch (Throwable t) {
            module.logHook("[!] read(byte[]) hook failed: " + t.getMessage());
        }
    }

    private void hookReadShortArray(Method method) {
        try {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (module.getMediaPath() == null || !module.isAudioActive()) return result;

                short[] buf            = (short[]) chain.getArgs().get(0);
                int     offsetInShorts = (int)     chain.getArgs().get(1);
                int     sizeInShorts   = (int)     chain.getArgs().get(2);

                // Read virtual PCM as bytes, then unpack as little-endian PCM16
                byte[] tmp = new byte[sizeInShorts * 2];
                module.readAudioData(tmp, 0, tmp.length);
                for (int i = 0; i < sizeInShorts; i++) {
                    buf[offsetInShorts + i] =
                            (short) ((tmp[i * 2] & 0xFF) | (tmp[i * 2 + 1] << 8));
                }
                module.logHook("[*] AudioRecord.read(short[]) injected " + sizeInShorts + " samples");
                return sizeInShorts;
            });
            module.logHook("[+] Hooked: AudioRecord#read(short[], int, int)");
        } catch (Throwable t) {
            module.logHook("[!] read(short[]) hook failed: " + t.getMessage());
        }
    }

    private void hookReadByteBuffer(Method method) {
        try {
            module.hook(method).intercept(chain -> {
                Object result = chain.proceed();
                if (module.getMediaPath() == null || !module.isAudioActive()) return result;

                ByteBuffer buf         = (ByteBuffer) chain.getArgs().get(0);
                int        sizeInBytes = (int)        chain.getArgs().get(1);

                byte[] tmp = new byte[sizeInBytes];
                int written = module.readAudioData(tmp, 0, sizeInBytes);
                buf.rewind();
                buf.put(tmp, 0, written);
                module.logHook("[*] AudioRecord.read(ByteBuffer) injected " + written + " B");
                return written;
            });
            module.logHook("[+] Hooked: AudioRecord#read(ByteBuffer, int)");
        } catch (Throwable t) {
            module.logHook("[!] read(ByteBuffer) hook failed: " + t.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // stop / release — shut down the audio decoder
    // ──────────────────────────────────────────────────────────────────────────

    private void hookStopRelease() {
        try {
            Method stop = AudioRecord.class.getDeclaredMethod("stop");
            module.hook(stop).intercept(chain -> {
                module.logHook("[*] AudioRecord#stop — stopping audio injection");
                module.stopAudio();
                return chain.proceed();
            });
            module.logHook("[+] Hooked: AudioRecord#stop");
        } catch (Throwable t) {
            module.logHook("[!] stop hook failed: " + t.getMessage());
        }

        try {
            Method release = AudioRecord.class.getDeclaredMethod("release");
            module.hook(release).intercept(chain -> {
                module.logHook("[*] AudioRecord#release — stopping audio injection");
                module.stopAudio();
                return chain.proceed();
            });
            module.logHook("[+] Hooked: AudioRecord#release");
        } catch (Throwable t) {
            module.logHook("[!] release hook failed: " + t.getMessage());
        }
    }
}
