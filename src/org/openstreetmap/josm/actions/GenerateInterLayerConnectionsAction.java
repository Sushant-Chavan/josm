// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.actions;

import static org.openstreetmap.josm.gui.help.HelpUtil.ht;
import static org.openstreetmap.josm.tools.I18n.tr;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.Collections;
import java.util.Comparator;
import java.util.Collection;
import java.util.ArrayList;
import java.util.TreeSet;
import java.util.List;

import javax.swing.SwingUtilities;

import org.openstreetmap.josm.command.AddCommand;
import org.openstreetmap.josm.data.UndoRedoHandler;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.gui.MainApplication;
import org.openstreetmap.josm.gui.MapView;
import org.openstreetmap.josm.gui.dialogs.LatLonDialog;
import org.openstreetmap.josm.tools.Shortcut;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.data.osm.OsmPrimitiveComparator;
import org.openstreetmap.josm.data.osm.TagMap;
import org.openstreetmap.josm.data.osm.IPrimitive;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.tools.Geometry;

/**
 * This action displays a dialog where the user can enter a latitude and longitude,
 * and when ok is pressed, a new node is created at the specified position.
 */
public final class GenerateInterLayerConnectionsAction extends JosmAction {
    /**
     * Constructs a new {@code GenerateInterLayerConnectionsAction}.
     */
    public GenerateInterLayerConnectionsAction() {
        super(tr("Generate InterLayer Connections..."), "GenerateInterLayerConnectionsAction", tr("Associates each topological node to an area."),
                null, true);
        // setHelpId(ht("/Action/AddNode"));
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        if (!isEnabled())
            return;

        // #17682 - Run the action later in EDT to make sure the KeyEvent triggering it is consumed before the dialog is shown
        SwingUtilities.invokeLater(() -> {
            
            Collection<OsmPrimitive> primitives = getSortedOsmPrimitives();
            if (primitives != null && primitives.size() > 0)
            {
                Collection<Way> areaWays = getAreaWays();

                Logging.info("Generating inter-layer topological connections");
                int nAdditions = 0;
                Collection<Node> topologyNodes = getTopologicalNodes();
                for (Node node : topologyNodes) {
                    for (Way area : areaWays) {
                        if (areaContainsNode(area, node))
                        {
                            if (establishTopologicalConnection(area, node))
                                nAdditions++;
                            break;
                        }
                    }
                }
                Logging.info("Generated " + nAdditions + " new inter-layer topological connections.");
                nAdditions = 0;

                Logging.info("Generating inter-layer functional connections");
                Collection<IPrimitive> functionalWays = getFunctionalWays();
                for (Way area : areaWays) {
                    List<IPrimitive> zones = Geometry.filterInsideAnyPolygon(functionalWays, area);
                    if(zones.size() > 0)
                    {
                        for (IPrimitive zone : zones) {
                            if (establishZoneConnection(area, (Way)zone))
                                nAdditions++;
                        }
                    }
                }
                Logging.info("Generated " + nAdditions + " new inter-layer functional connections.");
            }
            else
            {
                Logging.warn("Could not generate inter-layer connections as no OSM primitives found!");
            }
        });
    }

    @Override
    protected boolean listenToSelectionChange() {
        return false;
    }

    @Override
    protected void updateEnabledState() {
        setEnabled(getLayerManager().getEditLayer() != null);
    }

    private DataSet getDataset() {
        DataSet ds = getLayerManager().getEditDataSet();
        if (ds == null)
        {
            Logging.warn("Invalid dataset!");
        }
        return ds;
    }

    private String getLayerName(OsmPrimitive p) {
        String layerName = null;
        if (p != null)
        {
            TagMap keys = p.getKeys();
            if (!keys.isEmpty() && keys.containsKey("layer"))
            {
                layerName = keys.get("layer");
            }
        }
        return layerName;
    }

    private Collection<OsmPrimitive> getSortedOsmPrimitives() {
        return getSortedOsmPrimitives(null);
    }

    private Collection<OsmPrimitive> getSortedOsmPrimitives(String layer) {
        DataSet ds = getDataset();
        if (ds != null)
        {
            Collection<OsmPrimitive> primitives = ds.allNonDeletedPrimitives();

            // Create a list from the collection so that we can sort it
            List<OsmPrimitive> primitiveList = new ArrayList<OsmPrimitive>();
            for (OsmPrimitive p : primitives) {
                String layerName = getLayerName(p);
                if (layer == null || (layerName != null && layerName.equals(layer)))
                    primitiveList.add(p);
            }
            // First sort the primitives in the order Nodes, Ways and Relations and within each primitive type sort them based on the Unique ID
            final Comparator<OsmPrimitive> orderingNodesWaysRelations = OsmPrimitiveComparator.orderingNodesWaysRelations();
            final Comparator<OsmPrimitive> byUniqueId = OsmPrimitiveComparator.comparingUniqueId();
            primitiveList.sort(orderingNodesWaysRelations.thenComparing(byUniqueId));
    
            return primitiveList;
        }
        return null;
    }

    private Collection<Node> getTopologicalNodes() {
        Collection<OsmPrimitive> topologyPrimitives = getSortedOsmPrimitives("topology");
        Collection<Node> topologyNodes = new TreeSet<Node>();
        for (OsmPrimitive p : topologyPrimitives) {
            if (p instanceof Way)
            {
                List<Node> nodes = ((Way)p).getNodes();
                for (Node n : nodes) {
                    topologyNodes.add(n);
                }
            }
        }
        return topologyNodes;
    }

    private Collection<Way> getAreaWays() {
        Collection<OsmPrimitive> areasPrimitives = getSortedOsmPrimitives("areas");
        Collection<Way> areaWays = new TreeSet<Way>();
        for (OsmPrimitive p : areasPrimitives) {
            if (p instanceof Way && ((Way)p).isClosed())
            {
                areaWays.add((Way)p);
            }
        }
        return areaWays;
    }

    private Collection<IPrimitive> getFunctionalWays() {
        Collection<OsmPrimitive> functionalPrimitives = getSortedOsmPrimitives("functional");
        Collection<IPrimitive> functionalWays = new TreeSet<IPrimitive>();
        for (OsmPrimitive p : functionalPrimitives) {
            if (p instanceof Way && ((Way)p).isClosed())
            {
                functionalWays.add((IPrimitive)p);
            }
        }
        return functionalWays;
    }

    private boolean areaContainsNode(Way area, Node node) {
        return Geometry.nodeInsidePolygon(node, area.getNodes());
    }

    private boolean establishTopologicalConnection(Way area, Node node) {
        DataSet ds = getDataset();
        if (ds == null)
            return false;

        String areaName = null;
        TagMap areaKeys = area.getKeys();
        boolean success = false;
        if (!areaKeys.isEmpty() && areaKeys.containsKey("name"))
        {
            areaName = areaKeys.get("name");

            Collection<OsmPrimitive> primitives = getSortedOsmPrimitives("topology");
            Relation relation = null;
            for (OsmPrimitive p : primitives) {
                TagMap keys = p.getKeys();
                if (p instanceof Relation && !keys.isEmpty() &&
                    keys.containsKey("type") && keys.get("type").equals("topologyConnection") &&
                    keys.containsKey("name") && keys.get("name").equals(areaName))
                {
                    relation = (Relation)p;
                }
            }

            if (relation == null) {
                //Logging.info("Topology connection relation not found for area " + areaName + ". Creating a new one...");
                relation = new Relation();

                TagMap keys = new TagMap();
                keys.put("layer", "topology");
                keys.put("type", "topologyConnection");
                keys.put("name", areaName);
                relation.setKeys(keys);
                relation.addMember(new RelationMember("parent", area));
                relation.addMember(new RelationMember("child", node));
                ds.beginUpdate();
                try { 
                    ds.addPrimitive(relation);
                    success = true;
                } 
                finally { 
                    ds.endUpdate(); 
                }
            }
            else
            {
                RelationMember newMember = new RelationMember("child", node);
                boolean isNew = true;
                for (RelationMember rm : relation.getMembers())
                {
                    if (rm.equals(newMember))
                    {
                        isNew = false;
                        break;
                    }
                }
                if (isNew)
                {
                    relation.addMember(newMember);
                    ds.beginUpdate();
                    try { 
                        ds.removePrimitive(relation);
                        ds.addPrimitive(relation);
                        success = true;
                    } 
                    finally { 
                        ds.endUpdate(); 
                    }
                }
            }
        }
        return success;
    }

    private boolean establishZoneConnection(Way area, Way zone) {
        DataSet ds = getDataset();
        if (ds == null)
            return false;

        String areaName = null;
        TagMap areaKeys = area.getKeys();
        boolean success = false;
        if (!areaKeys.isEmpty() && areaKeys.containsKey("name"))
        {
            areaName = areaKeys.get("name");

            Collection<OsmPrimitive> primitives = getSortedOsmPrimitives("functional");
            Relation relation = null;
            for (OsmPrimitive p : primitives) {
                TagMap keys = p.getKeys();
                if (p instanceof Relation && !keys.isEmpty() &&
                    keys.containsKey("type") && keys.get("type").equals("functionalConnection") &&
                    keys.containsKey("name") && keys.get("name").equals(areaName))
                {
                    relation = (Relation)p;
                }
            }

            if (relation == null) {
                //Logging.info("Functional connection relation not found for area " + areaName + ". Creating a new one...");
                relation = new Relation();

                TagMap keys = new TagMap();
                keys.put("layer", "functional");
                keys.put("type", "functionalConnection");
                keys.put("name", areaName);
                relation.setKeys(keys);
                relation.addMember(new RelationMember("parent", area));
                relation.addMember(new RelationMember("child", zone));
                ds.beginUpdate();
                try { 
                    ds.addPrimitive(relation);
                    success = true;
                } 
                finally { 
                    ds.endUpdate(); 
                }
            }
            else
            {
                RelationMember newMember = new RelationMember("child", zone);
                boolean isNew = true;
                for (RelationMember rm : relation.getMembers())
                {
                    if (rm.equals(newMember))
                    {
                        isNew = false;
                        break;
                    }
                }
                if (isNew)
                {
                    relation.addMember(newMember);
                    ds.beginUpdate();
                    try { 
                        ds.removePrimitive(relation);
                        ds.addPrimitive(relation);
                        success = true;
                    } 
                    finally { 
                        ds.endUpdate(); 
                    }
                }
            }
        }
        return success;
    }
}
