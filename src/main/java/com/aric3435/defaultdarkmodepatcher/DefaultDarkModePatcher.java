package com.aric3435.defaultdarkmodepatcher;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class DefaultDarkModePatcher implements ClientModInitializer {

    public static final String MOD_ID = "default_dark_mode_patcher";

    // Updated filenames for the new NebuIr pack and patched output
    public static final String DEFAULT_PACK_FILENAME = "Default-Dark-Mode-1.21.6-2025.6.0.zip";
    public static final String PATCHED_PACK_FILENAME = "Default-Dark-Mode-25w41a-2025.10.0.zip";

    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);

    // Updated pack.mcmeta for 25w41a (1.21.11 unofficial)
    private static final String UPDATED_PACK_MCMETA = """
        {
            "pack": {
                "pack_format": 63,
                "supported_formats": {
                    "min_inclusive": 63,
                    "max_inclusive": 70
                },
                "description": "Welcome to the dark side!\\n\\u00a78by nebulr \\u2022 1.21.11 \\u2022 2025.10.0"
            }
        }
        """;

    @Override
    public void onInitializeClient() {
        MinecraftClient client = MinecraftClient.getInstance();
        client.execute(() -> {
            Path gameDir = client.runDirectory.toPath();
            Path resourcePacksDir = gameDir.resolve("resourcepacks");
            Path defaultPackPath = resourcePacksDir.resolve(DEFAULT_PACK_FILENAME);
            if (Files.exists(defaultPackPath)) {
                LOGGER.info("Found Default Dark Mode resource pack at: {}", defaultPackPath);
                new Thread(() -> {
                    patchResourcePack(defaultPackPath);
                    client.execute(() -> {
                        if (client.player != null) {
                            client.player.sendMessage(Text.literal("Default Dark Mode has been patched to 25w41a (1.21.11)."), false);
                        }
                    });
                }).start();
            } else {
                LOGGER.info("Default Dark Mode resource pack not found, skipping patch.");
            }
        });
    }

    private void patchResourcePack(Path packPath) {
        LOGGER.info("Starting patch process for resource pack: {}", packPath);
        try {
            Path tempDir = Files.createTempDirectory("ddm_patch_");
            LOGGER.info("Created temporary directory: {}", tempDir);

            unzip(packPath.toFile(), tempDir.toFile());
            LOGGER.info("Extracted resource pack to temporary directory.");

            // Update pack.mcmeta
            Path packMcmeta = tempDir.resolve("pack.mcmeta");
            Files.writeString(packMcmeta, UPDATED_PACK_MCMETA,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            LOGGER.info("Updated pack.mcmeta with pack_format 63 and max_inclusive 70.");

            // Inject new slot textures
            injectSlotTexture(tempDir, "nautilus_armor.png");
            injectSlotTexture(tempDir, "spear.png");

            // Repackage
            Path patchedPackPath = packPath.getParent().resolve(PATCHED_PACK_FILENAME);
            File patchedZip = patchedPackPath.toFile();
            zipDirectory(tempDir.toFile(), patchedZip);
            LOGGER.info("Repackaged patched resource pack as: {}", patchedPackPath);

            // Delete the original pack after patching
            try {
                Files.deleteIfExists(packPath);
                LOGGER.info("Deleted original resource pack: {}", packPath);
            } catch (IOException e) {
                LOGGER.warn("Failed to delete original resource pack: {}", packPath, e);
            }

            deleteDirectoryRecursively(tempDir.toFile());
            LOGGER.info("Cleaned up temporary files.");
        } catch (Exception e) {
            LOGGER.error("Error during patching: ", e);
        }
    }

    private void injectSlotTexture(Path tempDir, String fileName) throws IOException {
        Path targetDir = tempDir.resolve("assets/minecraft/textures/gui/sprites/container/slot");
        Files.createDirectories(targetDir);

        try (InputStream in = DefaultDarkModePatcher.class.getResourceAsStream(
                "/assets/defaultdarkmodepatcher/slot/" + fileName)) {
            if (in == null) {
                LOGGER.warn("Missing bundled texture: {}", fileName);
                return;
            }
            Files.copy(in, targetDir.resolve(fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            LOGGER.info("Injected slot texture: {}", fileName);
        }
    }

    private void unzip(File zipFile, File targetDir) throws IOException {
        byte[] buffer = new byte[1024];
        try (ZipInputStream zis = new ZipInputStream(new FileInputStream(zipFile))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                File newFile = newFile(targetDir, entry);
                if (entry.isDirectory()) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }
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
            zipFile(sourceDir, sourceDir, zos);
        }
    }

    private void zipFile(File rootDir, File sourceFile, ZipOutputStream zos) throws IOException {
        if (sourceFile.isDirectory()) {
            for (File file : sourceFile.listFiles()) {
                zipFile(rootDir, file, zos);
            }
        } else {
            try (FileInputStream fis = new FileInputStream(sourceFile)) {
                String zipEntryName = rootDir.toURI().relativize(sourceFile.toURI()).getPath();
                ZipEntry zipEntry = new ZipEntry(zipEntryName);
                zos.putNextEntry(zipEntry);
                byte[] buffer = new byte[1024];
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
            for (File child : file.listFiles()) {
                deleteDirectoryRecursively(child);
            }
        }
        if (!file.delete()) {
            LOGGER.warn("Unable to delete: {}", file.getAbsolutePath());
        }
    }
}
