// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.data.preferences.sources;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.openstreetmap.josm.spi.preferences.Config;

/**
 * Helper class for specialized extensions preferences.
 * @since 12649 (extracted from gui.preferences package)
 */
public abstract class SourcePrefHelper {

    private final String pref;
    protected final SourceType type;

    /**
     * Constructs a new {@code SourcePrefHelper} for the given preference key.
     * @param pref The preference key
     * @param type The source type
     * @since 12825
     */
    protected SourcePrefHelper(String pref, SourceType type) {
        this.pref = pref;
        this.type = type;
    }

    /**
     * Returns the default sources provided by JOSM core.
     * @return the default sources provided by JOSM core
     */
    public abstract Collection<ExtendedSourceEntry> getDefault();

    /**
     * Serializes the given source entry as a map.
     * @param entry source entry to serialize
     * @return map (key=value)
     */
    public Map<String, String> serialize(SourceEntry entry) {
        Map<String, String> res = new HashMap<>();
        res.put("url", entry.url == null ? "" : entry.url);
        res.put("title", entry.title == null ? "" : entry.title);
        return res;
    }

    /**
     * Deserializes the given map as a source entry.
     * @param entryStr map (key=value)
     * @return source entry
     */
    public SourceEntry deserialize(Map<String, String> entryStr) {
        return new SourceEntry(type,
                entryStr.get("url"),
                entryStr.get("name"),
                entryStr.get("title"),
                Optional.ofNullable(entryStr.get("active")).map(Boolean::parseBoolean).orElse(true));
    }

    /**
     * Returns the list of sources.
     * @return The list of sources
     */
    public List<SourceEntry> get() {
        List<Map<String, String>> src = Config.getPref().getListOfMaps(pref, null);
        if (src == null)
            return new ArrayList<>(getDefault());
        return src.stream()
                .map(sourcePref -> deserialize(new HashMap<>(sourcePref)))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Saves a list of sources to JOSM preferences.
     * @param entries list of sources
     * @return {@code true}, if something has changed (i.e. value is different than before)
     */
    public boolean put(Collection<? extends SourceEntry> entries) {
        List<Map<String, String>> setting = serializeList(entries);
        boolean unset = Config.getPref().getListOfMaps(pref, null) == null;
        if (unset) {
            Collection<Map<String, String>> def = serializeList(getDefault());
            if (setting.equals(def))
                return false;
        }
        return Config.getPref().putListOfMaps(pref, setting);
    }

    private List<Map<String, String>> serializeList(Collection<? extends SourceEntry> entries) {
        return new ArrayList<>(entries).stream().map(this::serialize).collect(Collectors.toList());
    }

    /**
     * Returns the set of active source URLs.
     * @return The set of active source URLs.
     */
    public final Set<String> getActiveUrls() {
        return get().stream()
                .filter(e -> e.active)
                .map(e -> e.url)
                .collect(Collectors.toCollection(LinkedHashSet::new)); // LinkedHashSet to retain order
    }
}
