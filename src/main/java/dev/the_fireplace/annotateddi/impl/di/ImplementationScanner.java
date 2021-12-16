package dev.the_fireplace.annotateddi.impl.di;

import com.google.gson.*;
import dev.the_fireplace.annotateddi.impl.AnnotatedDI;
import dev.the_fireplace.annotateddi.impl.UrlUtil;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.impl.util.FileSystemUtil;

import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipError;

@SuppressWarnings("rawtypes")
public class ImplementationScanner
{
    public static final String DI_CONFIG_FILE_NAME = "annotated-di.json";

    public Set<AnnotatedDIModule.ImplementationContainer> findImplementations() {
        try {
            Enumeration<URL> diConfigFileUrls = this.getClass().getClassLoader().getResources(DI_CONFIG_FILE_NAME);
            Set<Path> validConfigFilePaths = getConfigFilePaths(diConfigFileUrls);

            return getImplementationContainersFromConfigs(validConfigFilePaths);
        } catch (IOException e) {
            AnnotatedDI.getLogger().error("Exception when scanning for implementations!", e);
        }

        return Set.of();
    }

    private Set<Path> getConfigFilePaths(Enumeration<URL> mods) {
        Set<Path> modsList = new HashSet<>();

        while (mods.hasMoreElements()) {
            URL url;
            try {
                url = UrlUtil.getSource(DI_CONFIG_FILE_NAME, mods.nextElement());
            } catch (Exception e) {
                AnnotatedDI.getLogger().error("Error getting DI config's source!", e);
                continue;
            }
            Path normalizedPath, configJsonPath;
            try {
                normalizedPath = UrlUtil.asPath(url).normalize();
            } catch (URISyntaxException e) {
                throw new RuntimeException("Failed to convert URL " + url + "!", e);
            }

            if (Files.isDirectory(normalizedPath)) {
                configJsonPath = normalizedPath.resolve(DI_CONFIG_FILE_NAME);
            } else {
                // JAR file
                try {
                    FileSystemUtil.FileSystemDelegate jarFs = FileSystemUtil.getJarFileSystem(normalizedPath, false);
                    configJsonPath = jarFs.get().getPath(DI_CONFIG_FILE_NAME);
                } catch (IOException e) {
                    AnnotatedDI.getLogger().error("Failed to open JAR at " + normalizedPath + "!", e);
                    continue;
                } catch (ZipError e) {
                    AnnotatedDI.getLogger().error("Jar at " + normalizedPath + " is corrupted!", e);
                    continue;
                }
            }
            modsList.add(configJsonPath);
        }

        return modsList;
    }

    private Set<AnnotatedDIModule.ImplementationContainer> getImplementationContainersFromConfigs(Set<Path> configFilePaths) {
        Set<AnnotatedDIModule.ImplementationContainer> implementationContainers = new HashSet<>();

        for (Path path : configFilePaths) {
            AnnotatedDIModule.ImplementationContainer implementationContainer = readImplementationContainerFromPath(path);

            if (implementationContainer != null) {
                implementationContainers.add(implementationContainer);
            }
        }

        return implementationContainers;
    }

    @Nullable
    private AnnotatedDIModule.ImplementationContainer readImplementationContainerFromPath(Path path) {
        AnnotatedDIModule.ImplementationContainer implementationContainer = null;

        JsonParser jsonParser = new JsonParser();
        try (BufferedReader br = new BufferedReader(new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
            JsonElement jsonElement = jsonParser.parse(br);
            if (jsonElement instanceof JsonObject jsonObject) {
                implementationContainer = readImplementationContainerJson(jsonObject);
            }
        } catch (IOException | JsonParseException | ClassNotFoundException e) {
            AnnotatedDI.getLogger().error("Exception when reading implementation file!", e);
        }

        return implementationContainer;
    }

    private AnnotatedDIModule.ImplementationContainer readImplementationContainerJson(JsonObject jsonObject) throws ClassNotFoundException {
        JsonArray modImplementations = jsonObject.getAsJsonArray("implementations");
        Map<Class, List<AnnotatedDIModule.ImplementationData>> implementationDatas = new HashMap<>();
        for (JsonElement element : modImplementations) {
            JsonObject implementationObj = (JsonObject) element;
            if (isOnWrongEnvironment(implementationObj)) {
                continue;
            }

            JsonArray interfaceNames = implementationObj.getAsJsonArray("interfaces");
            List<Class> interfaces = new ArrayList<>();
            for (JsonElement interfaceName : interfaceNames) {
                interfaces.add(stringToClass(interfaceName.getAsString()));
            }
            Class implementationClass = stringToClass(implementationObj.get("class").getAsString());
            implementationDatas.computeIfAbsent(implementationClass, c -> new ArrayList<>()).add(new AnnotatedDIModule.ImplementationData(
                implementationClass,
                interfaces,
                implementationObj.has("namedImplementation")
                    ? implementationObj.get("namedImplementation").getAsString()
                    : "",
                implementationObj.has("useAllInterfaces") && implementationObj.get("useAllInterfaces").getAsBoolean(),
                implementationObj.has("environment") ? EnvType.valueOf(implementationObj.get("environment").getAsString()) : null
            ));
        }

        return new AnnotatedDIModule.ImplementationContainer(jsonObject.get("version").getAsString(), implementationDatas);
    }

    private boolean isOnWrongEnvironment(JsonObject implementationObj) {
        return implementationObj.has("environment")
            && !FabricLoader.getInstance().getEnvironmentType().equals(EnvType.valueOf(implementationObj.get("environment").getAsString()));
    }

    private Class stringToClass(String className) throws ClassNotFoundException {
        return Class.forName(className);
    }
}