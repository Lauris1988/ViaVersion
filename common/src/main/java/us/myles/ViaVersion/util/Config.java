package us.myles.ViaVersion.util;

import org.yaml.snakeyaml.Yaml;
import us.myles.ViaVersion.api.configuration.ConfigurationProvider;

import java.io.*;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public abstract class Config implements ConfigurationProvider {
    private static ThreadLocal<Yaml> yaml = new ThreadLocal<Yaml>() {
        @Override
        protected Yaml initialValue() {
            return new Yaml();
        }
    };
    private CommentStore commentStore = new CommentStore('.', 2);
    private final File configFile;
    private Map<String, Object> config;

    public Config(File configFile) {
        this.configFile = configFile;
        reloadConfig();
    }

    public Map<String, Object> loadConfig(File location) {
        List<String> unsupported = getUnsupportedOptions();
        URL jarConfigFile = Config.class.getClassLoader().getResource("config.yml");
        try {
            commentStore.storeComments(jarConfigFile.openStream());
            for (String option : unsupported) {
                List<String> comments = commentStore.header(option);
                if (comments != null) {
                    comments.clear();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        Map<String, Object> config = null;
        if (location.exists()) {
            try (FileInputStream input = new FileInputStream(location)) {
                config = (Map<String, Object>) yaml.get().load(input);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (config == null) {
            config = new HashMap<>();
        }

        Map<String, Object> defaults = config;
        try (InputStream stream = jarConfigFile.openStream()) {
            defaults = (Map<String, Object>) yaml.get().load(stream);
            for (String option : unsupported) {
                defaults.remove(option);
            }
            // Merge with defaultLoader
            for (Object key : config.keySet()) {
                // Set option in new conf if exists
                if (defaults.containsKey(key) && !unsupported.contains(key.toString())) {
                    defaults.put((String) key, config.get(key));
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Save
        saveConfig(location, defaults);

        return defaults;
    }

    public void saveConfig(File location, Map<String, Object> config) {
        try {
            commentStore.writeComments(yaml.get().dump(config), location);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public abstract List<String> getUnsupportedOptions();

    @Override
    public void set(String path, Object value) {
        set(path, value);
    }

    @Override
    public void saveConfig() {
        this.configFile.getParentFile().mkdirs();
        saveConfig(this.configFile, this.config);
    }

    @Override
    public void reloadConfig() {
        this.configFile.getParentFile().mkdirs();
        this.config = loadConfig(this.configFile);
    }

    @Override
    public Map<String, Object> getValues() {
        return this.config;
    }

    public boolean getBoolean(String key, boolean def) {
        if (this.config.containsKey(key)) {
            return (boolean) this.config.get(key);
        } else {
            return def;
        }
    }

    public String getString(String key, String def) {
        if (this.config.containsKey(key)) {
            return (String) this.config.get(key);
        } else {
            return def;
        }
    }

    public int getInt(String key, int def) {
        if (this.config.containsKey(key)) {
            return (int) this.config.get(key);
        } else {
            return def;
        }
    }

    public double getDouble(String key, double def) {
        if (this.config.containsKey(key)) {
            return (double) this.config.get(key);
        } else {
            return def;
        }
    }

    public List<Integer> getIntegerList(String key) {
        if (this.config.containsKey(key)) {
            return (List<Integer>) this.config.get(key);
        } else {
            return new ArrayList<>();
        }
    }
}