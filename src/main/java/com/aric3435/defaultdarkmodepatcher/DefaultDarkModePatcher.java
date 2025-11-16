package com.aric3435.defaultdarkmodepatcher;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.zip.*;

public class DefaultDarkModePatcher implements ClientModInitializer {

    public static final String MOD_ID = "default_dark_mode_patcher";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    public static final String DEFAULT_PACK_FILENAME = "Default-Dark-Mode-1.21.6-2025.6.0.zip";
    public static final String DEFAULT_PACK_BAK_FILENAME = "Default-Dark-Mode-1.21.6-2025.6.0.zip.bak";
    public static final String PATCHED_PACK_FILENAME = "Default-Dark-Mode-25w46a-2025.11.0.zip";

    private static final String EXPECTED_SHA256 =
            "7DFF817B040D9942CBBE3228714075881736337E20713555ADCA62F546077741";

    private static final String UPDATED_PACK_MCMETA = """
        {
            "pack": {
                "pack_format": 63,
                "supported_formats": {
                    "min_inclusive": 63,
                    "max_inclusive": 74
                },
                "min_format": 63,
                "max_format": 74,
                "description": "Welcome to the dark side!\\n\\u00a78by nebulr \\u2022 1.21.11 \\u2022 2025.11.0"
            }
        }
        """;

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            Path gameDir = client.runDirectory.toPath();
            Path resourcePacksDir = gameDir.resolve("resourcepacks");
            Path zipPath = resourcePacksDir.resolve(DEFAULT_PACK_FILENAME);
            Path bakPath = resourcePacksDir.resolve(DEFAULT_PACK_BAK_FILENAME);
            Path patchedPath = resourcePacksDir.resolve(PATCHED_PACK_FILENAME);

            if (!Files.exists(zipPath)) {
                LOGGER.info("Default Dark Mode resource pack not found at {}. Skipping.", zipPath);
                return;
            }

            new Thread(() -> {
                boolean patched = patchWithVerification(zipPath, bakPath, patchedPath);
                client.execute(() -> {
                    if (client.player != null) {
                        if (patched) {
                            client.player.sendMessage(Text.literal("Default Dark Mode patched to 25w46a (1.21.11)."), false);
                        } else {
                            client.player.sendMessage(Text.literal("Default Dark Mode patch skipped. See logs for details."), false);
                        }
                    }
                });
            }).start();
        });
    }

    private boolean patchWithVerification(Path zipPath, Path bakPath, Path patchedPath) {
        try {
            boolean zipIsOriginal = verifySha256(zipPath, EXPECTED_SHA256);
            boolean bakExists = Files.exists(bakPath);
            boolean bakIsOriginal = bakExists && verifySha256(bakPath, EXPECTED_SHA256);

            if (zipIsOriginal) {
                if (!bakExists) {
                    Files.copy(zipPath, bakPath, StandardCopyOption.COPY_ATTRIBUTES);
                    LOGGER.info("Created backup .bak: {}", bakPath);
                }
                boolean ok = patchFromSourceToZip(zipPath, patchedPath);
                if (ok) Files.deleteIfExists(zipPath);
                return ok;
            }

            if (bakIsOriginal) {
                boolean ok = patchFromSourceToZip(bakPath, patchedPath);
                if (ok && Files.exists(zipPath)) Files.deleteIfExists(zipPath);
                return ok;
            }

            LOGGER.warn("Neither .zip nor .bak match expected SHA-256. Aborting patch.");
            return false;

        } catch (Exception e) {
            LOGGER.error("Patch verification failed: ", e);
            return false;
        }
    }

    private boolean patchFromSourceToZip(Path sourceZip, Path targetZip) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("ddm_patch_");
            unzip(sourceZip.toFile(), tempDir.toFile());

            Path packMcmeta = tempDir.resolve("pack.mcmeta");
            Files.writeString(packMcmeta, UPDATED_PACK_MCMETA,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);

            injectSlotTexture(tempDir, "nautilus_armor.png");
            injectSlotTexture(tempDir, "spear.png");
            injectGuiTexture(tempDir, "nautilus.png");

            zipDirectory(tempDir.toFile(), targetZip.toFile());
            LOGGER.info("Repackaged patched resource pack as: {}", targetZip);

            return true;
        } catch (Exception e) {
            LOGGER.error("Error during patching: ", e);
            return false;
        } finally {
            if (tempDir != null) {
                try {
                    deleteDirectoryRecursively(tempDir.toFile());
                } catch (IOException ignored) {}
            }
        }
    }

    private void injectSlotTexture(Path tempDir, String fileName) throws IOException {
        Path targetDir = tempDir.resolve("assets/minecraft/textures/gui/sprites/container/slot");
        Files.createDirectories(targetDir);
        try (InputStream in = DefaultDarkModePatcher.class.getResourceAsStream(
                "/assets/defaultdarkmodepatcher/slot/" + fileName)) {
            if (in != null) {
                Files.copy(in, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Injected slot texture: {}", fileName);
            } else {
                LOGGER.warn("Missing bundled texture: {}", fileName);
            }
        }
    }

    private void injectGuiTexture(Path tempDir, String fileName) throws IOException {
        Path targetDir = tempDir.resolve("assets/minecraft/textures/gui/container");
        Files.createDirectories(targetDir);
        try (InputStream in = DefaultDarkModePatcher.class.getResourceAsStream(
                "/assets/defaultdarkmodepatcher/gui/container/" + fileName)) {
            if (in != null) {
                Files.copy(in, targetDir.resolve(fileName), StandardCopyOption.REPLACE_EXISTING);
                LOGGER.info("Injected GUI texture: {}", fileName);
            } else {
                LOGGER.warn("Missing bundled GUI texture: {}", fileName);
            }
        }
    }

    private static boolean verifySha256(Path file, String expectedUpperHex) {
        try {
            String actual = computeSHA256(file);
            return expectedUpperHex.equalsIgnoreCase(actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static String computeSHA256(Path file) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream fis = new FileInputStream(file.toFile())) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) sb.append(String.format("%02X", b));
        return sb.toString();
    }

    private void unzip(File zipFile, File targetDir) throws IOException {
        byte[] buffer = new byte[4096];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = newFile(targetDir, entry);
                if (entry.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    newFile.getParentFile().mkdirs();
                    try (FileOutputStream fos = new FileOutputStream(newFile)) {
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            fos.write(buffer, 0, len);
                        }
                    }
                }
                zis.closeEntry();
            }
        }
    }

    private File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        File destFile = new File(destinationDir, zipEntry.getName());
        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();
        if (!destFilePath.startsWith(destDirPath + File.separator)) {
            throw new IOException("Entry is outside of the target directory: " + zipEntry.getName());
        }
        return destFile;
    }

    private void zipDirectory(File sourceDir, File zipFile) throws IOException {
        try (FileOutputStream fos = new FileOutputStream(zipFile);
             ZipOutputStream zos = new ZipOutputStream(fos)) {
            zipFileRecursive(sourceDir, sourceDir, zos);
        }
    }

    private void zipFileRecursive(File rootDir, File sourceFile, ZipOutputStream zos) throws IOException {
        if (sourceFile.isDirectory()) {
            File[] children = sourceFile.listFiles();
            if (children != null) {
                for (File file : children) {
                    zipFileRecursive(rootDir, file, zos);
                }
            }
        } else {
            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                String zipEntryName = rootDir.toURI().relativize(sourceFile.toURI()).getPath();
                ZipEntry zipEntry = new ZipEntry(zipEntryName);
                zos.putNextEntry(zipEntry);
                byte[] buffer = new byte[4096];
                int len;
                while ((len = fis.read(buffer)) > 0) {
                    zos.write(buffer, 0, len);
                }
                zos.closeEntry();
            }
        }
    }

    private void deleteDirectoryRecursively(File file) throws IOException {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirectoryRecursively(child);
                }
            }
        }
        if (!file.delete()) {
            LOGGER.warn("Unable to delete: {}", file.getAbsolutePath());
        }
    }
}