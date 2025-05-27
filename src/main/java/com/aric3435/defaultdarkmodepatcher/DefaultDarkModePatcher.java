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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class DefaultDarkModePatcher implements ClientModInitializer {

    public static final String MOD_ID = "default_dark_mode_patcher";
    public static final String DEFAULT_PACK_FILENAME = "Default-Dark-Mode-1.21.4+-2025.5.0.zip";
    public static final String PATCHED_PACK_FILENAME = "Default-Dark-Mode-1.21.6+-2025.5.0-unofficial.zip";
    private static final Logger LOGGER = LogManager.getLogger(MOD_ID);
    private static final String UPDATED_RENDTYPE_TEXT_FSH = """
        #version 150

        #moj_import <fog.glsl>
        #moj_import <minecraft:dynamictransforms.glsl>

        uniform sampler2D Sampler0;

        in float vertexDistance;
        in vec4 vertexColor;
        in vec2 texCoord0;

        out vec4 fragColor;

        void main() {
            vec4 color = texture(Sampler0, texCoord0) * vertexColor * ColorModulator;
            if (color.a < 0.1) {
                discard;
            }
            
            if (color.r > 0.2479 && color.r < 0.2481
                && color.g > 0.2479 && color.g < 0.2481
                && color.b > 0.2479 && color.b < 0.2481) {
                color = vec4(0.6667, 0.6667, 0.6667, 1.0);
            }
            
            vec3 pos = vec3(0.0, 0.0, vertexDistance);
            float sphericalDist = fog_spherical_distance(pos);
            float cylindricalDist = fog_cylindrical_distance(pos);
            
            fragColor = apply_fog(color, sphericalDist, cylindricalDist, 
                                FogEnvironmentalStart, FogEnvironmentalEnd,
                                FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
        }
        """;

    private static final String UPDATED_RENDTYPE_TEXT_INTENSITY_FSH = """
        #version 150

        #moj_import <fog.glsl>
        #moj_import <minecraft:dynamictransforms.glsl>

        uniform sampler2D Sampler0;

        in float vertexDistance;
        in vec4 vertexColor;
        in vec2 texCoord0;

        out vec4 fragColor;

        void main() {
            vec4 color = texture(Sampler0, texCoord0).rrrr * vertexColor * ColorModulator;
            if (color.a < 0.1) {
                discard;
            }
            
            if (color.r > 0.2479 && color.r < 0.2481
                && color.g > 0.2479 && color.g < 0.2481
                && color.b > 0.2479 && color.b < 0.2481) {
                color = vec4(0.6667, 0.6667, 0.6667, 1.0);
            }
            
            vec3 pos = vec3(0.0, 0.0, vertexDistance);
            float sphericalDist = fog_spherical_distance(pos);
            float cylindricalDist = fog_cylindrical_distance(pos);
            
            fragColor = apply_fog(color, sphericalDist, cylindricalDist, 
                                FogEnvironmentalStart, FogEnvironmentalEnd,
                                FogRenderDistanceStart, FogRenderDistanceEnd, FogColor);
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
                            client.player.sendMessage(Text.literal("Default Dark Mode has been patched automatically."), false);
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
                        
            Path shaderDir = tempDir.resolve("assets/minecraft/shaders/core");
            Files.writeString(shaderDir.resolve("rendertype_text.fsh"), UPDATED_RENDTYPE_TEXT_FSH, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            Files.writeString(shaderDir.resolve("rendertype_text_intensity.fsh"), UPDATED_RENDTYPE_TEXT_INTENSITY_FSH, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.CREATE);
            LOGGER.info("Updated shader files.");
            
            Path patchedPackPath = packPath.getParent().resolve(PATCHED_PACK_FILENAME);
            File patchedZip = patchedPackPath.toFile();
            zipDirectory(tempDir.toFile(), patchedZip);
            LOGGER.info("Repackaged patched resource pack as: {}", patchedPackPath);
            
            // Optionally, you could remove the original zip if desired. - Aric3435
            // Files.delete(packPath);
            // LOGGER.info("Deleted original resource pack.");
            
            deleteDirectoryRecursively(tempDir.toFile());
            LOGGER.info("Cleaned up temporary files.");
        } catch (Exception e) {
            LOGGER.error("Error during patching: ", e);
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
