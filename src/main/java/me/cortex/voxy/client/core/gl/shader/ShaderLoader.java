package me.cortex.voxy.client.core.gl.shader;


import net.caffeinemc.mods.sodium.client.gl.shader.ShaderConstants;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderParser;
import org.apache.commons.io.IOUtils;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * NeoForge-compatible shader loader for Voxy.
 *
 * On Fabric, Sodium's ShaderLoader.getShaderSource() uses a flat classloader that can
 * access all mod resources. On NeoForge, each mod has an isolated classloader, so
 * Sodium's classloader cannot access Voxy's shader resources.
 *
 * This loader bypasses Sodium's resource loading and uses Voxy's own classloader.
 *
 * Upstream reference: https://github.com/MCRcortex/voxy
 * See: src/main/java/me/cortex/voxy/client/core/gl/shader/ShaderLoader.java
 */
public class ShaderLoader {
    private static final Pattern IMPORT_PATTERN = Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    /**
     * Parse and load a shader, matching upstream Voxy behavior.
     *
     * Upstream code:
     *   return "#version 460 core\n" + ShaderParser.parseShader(
     *       "\n#import <" + id + ">\n//beans", ShaderConstants.builder().build()
     *   ).src().replaceAll("\r\n", "\n").replaceFirst("\n#version .+\n", "\n");
     *
     * The key is the leading "\n" before #import - this ensures the regex
     * "\n#version .+\n" can match the #version directive in the loaded shader.
     */
    public static String parse(String id) {
        // Load shader source using Voxy's classloader (NeoForge classloader isolation fix)
        String shaderSource = getShaderSource(id);

        // Process any nested #import directives recursively
        shaderSource = processImports(shaderSource);

        // Match upstream format: "\n" + content + "\n//beans"
        // The leading \n is critical for the regex to work
        String processed = "\n" + shaderSource + "\n//beans";

        // Apply Sodium's shader constants processing (handles #define etc.)
        processed = invokeParseShader(processed);

        // Normalize line endings and strip original #version (upstream behavior)
        processed = processed.replaceAll("\r\n", "\n");
        processed = processed.replaceFirst("\n#version .+\n", "\n");

        // Prepend our target GLSL version
        return "#version 460 core\n" + processed;
    }

    /**
     * Calls Sodium's ShaderParser.parseShader reflectively so one jar works across
     * Sodium versions whose return type differs:
     *   Sodium 0.6.x : parseShader(String, ShaderConstants) -> String
     *   Sodium 0.8.x : parseShader(String, ShaderConstants) -> ShaderParser.ParsedShader (record, .src())
     * A direct call bakes the return type into the bytecode descriptor, which throws
     * NoSuchMethodError on the other version.
     */
    private static final Method PARSE_SHADER = resolveParseShader();

    private static Method resolveParseShader() {
        try {
            // getMethod matches on name + parameter types only, so it resolves on both versions
            return ShaderParser.class.getMethod("parseShader", String.class, ShaderConstants.class);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Incompatible Sodium: ShaderParser.parseShader(String, ShaderConstants) not found", e);
        }
    }

    private static String invokeParseShader(String source) {
        ShaderConstants constants = ShaderConstants.builder().build();
        Object result;
        try {
            result = PARSE_SHADER.invoke(null, source, constants);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Failed to invoke Sodium ShaderParser.parseShader", e);
        }
        if (result instanceof String s) {
            return s;// Sodium 0.6.x
        }
        if (result == null) {
            throw new RuntimeException("Sodium ShaderParser.parseShader returned null");
        }
        // Sodium 0.8.x: unwrap the ParsedShader record
        try {
            Method src = result.getClass().getMethod("src");
            Object unwrapped = src.invoke(result);
            if (unwrapped instanceof String s) {
                return s;
            }
            throw new RuntimeException("Sodium ParsedShader.src() did not return a String");
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException("Unsupported Sodium ShaderParser return type: " + result.getClass().getName(), e);
        }
    }

    /**
     * Load shader source using Voxy's classloader.
     * Path format: "namespace:path" -> "/assets/{namespace}/shaders/{path}"
     */
    private static String getShaderSource(String id) {
        String[] parts = id.split(":", 2);
        String namespace = parts.length > 1 ? parts[0] : "voxy";
        String path = parts.length > 1 ? parts[1] : parts[0];

        String resourcePath = String.format("/assets/%s/shaders/%s", namespace, path);

        try (InputStream in = ShaderLoader.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new RuntimeException("Shader not found: " + resourcePath + " (id=" + id + ")");
            }
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source: " + resourcePath, e);
        }
    }

    /**
     * Process #import directives recursively, loading from Voxy's resources.
     */
    private static String processImports(String source) {
        StringBuilder result = new StringBuilder();
        for (String line : source.split("\n")) {
            if (line.trim().startsWith("#import")) {
                Matcher matcher = IMPORT_PATTERN.matcher(line.trim());
                if (matcher.matches()) {
                    String namespace = matcher.group("namespace");
                    String path = matcher.group("path");
                    String importId = namespace + ":" + path;
                    String importedSource = getShaderSource(importId);
                    result.append(processImports(importedSource));
                } else {
                    result.append(line);
                }
            } else {
                result.append(line);
            }
            result.append("\n");
        }
        return result.toString();
    }
}
