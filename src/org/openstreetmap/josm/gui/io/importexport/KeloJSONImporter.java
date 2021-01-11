// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.gui.io.importexport;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

import javax.swing.JOptionPane;

import org.openstreetmap.josm.actions.ExtensionFileFilter;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.layer.OsmDataLayer;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.gui.util.GuiHelper;
import org.openstreetmap.josm.io.CachedFile;
import org.openstreetmap.josm.io.Compression;
import org.openstreetmap.josm.io.KeloJSONReader;
import org.openstreetmap.josm.io.IllegalDataException;
import org.openstreetmap.josm.tools.Logging;

/**
 * KeloJSON file importer.
 * @author Ian Dees &lt;ian.dees@gmail.com&gt;
 * @author matthieun &lt;https://github.com/matthieun&gt;
 * @since 15424
 */
public class KeloJSONImporter extends FileImporter {

    private static final ExtensionFileFilter FILE_FILTER = ExtensionFileFilter.newFilterWithArchiveExtensions(
        "kelojson", "kelojson", tr("KeloJSON file") + " *.kelojson",
        ExtensionFileFilter.AddArchiveExtension.NONE, Arrays.asList("gz", "bz", "bz2", "xz", "zip"));

    /**
     * Constructs a new KeloJSON File importer with an extension filter for .json and .kelojson
     */
    public KeloJSONImporter() {
        super(FILE_FILTER);
    }

    @Override
    public void importData(final File file, final ProgressMonitor progressMonitor) {
        progressMonitor.beginTask(tr("Loading json file…"));
        progressMonitor.setTicksCount(2);
        Logging.info("Parsing KeloJSON: {0}", file.getAbsolutePath());
        try (InputStream fileInputStream = Compression.getUncompressedFileInputStream(file)) {
            DataSet data = KeloJSONReader.parseDataSet(fileInputStream, progressMonitor);
            progressMonitor.worked(1);
            MainApplication.getLayerManager().addLayer(new OsmDataLayer(data, file.getName(), file));
        } catch (IOException | IllegalDataException e) {
            Logging.error("Error while reading json file!");
            Logging.error(e);
            GuiHelper.runInEDT(() -> JOptionPane.showMessageDialog(
                null, tr("Error loading kelojson file {0}", file.getAbsolutePath()), tr("Error"), JOptionPane.WARNING_MESSAGE));
        } finally {
            progressMonitor.finishTask();
        }
    }

    /**
     * Parse KeloJSON dataset.
     * @param source kelojson file
     * @return KeloJSON dataset
     * @throws IOException in case of I/O error
     * @throws IllegalDataException if an error was found while parsing the data from the source
     */
    public DataSet parseDataSet(final String source) throws IOException, IllegalDataException {
        try (CachedFile cf = new CachedFile(source)) {
            InputStream fileInputStream = Compression.getUncompressedFileInputStream(cf.getFile()); // NOPMD
            return KeloJSONReader.parseDataSet(fileInputStream, NullProgressMonitor.INSTANCE);
        }
    }
}
