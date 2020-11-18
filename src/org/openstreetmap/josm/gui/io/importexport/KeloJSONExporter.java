// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.io.importexport;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.openstreetmap.josm.actions.ExtensionFileFilter;
import org.openstreetmap.josm.gui.layer.Layer;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.io.KeloJSONWriter;

/**
 * Exporter to write map data to a customized GeoJSON (KeloJSON) file.
 */
public class KeloJSONExporter extends FileExporter {

    /** File extension filter for .kelojson files */
    public static final ExtensionFileFilter FILE_FILTER = new ExtensionFileFilter(
            "kelojson", "kelojson", tr("KeloJSON Files") + " (*.kelojson)");

    /**
     * Constructs a new {@code KeloJSONExporter} with Mercator projection.
     */
    public KeloJSONExporter() {
        super(FILE_FILTER);
    }

    @Override
    public void exportData(File file, Layer layer) throws IOException {
        if (layer instanceof OsmDataLayer) {
            try (Writer out = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                out.write(new KeloJSONWriter(((OsmDataLayer) layer).data).write());
            }
        } else {
            throw new IllegalArgumentException(tr("Layer ''{0}'' not supported", layer.getClass().toString()));
        }
    }
}
