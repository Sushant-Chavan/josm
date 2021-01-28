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
import java.util.Arrays;

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
    final String AREAS_LAYER_NAME = "areas";
    final String SUBAREAS_LAYER_NAME = "subareas";
    final String ZONES_LAYER_NAME = "zones";
    final String TOPOLOGY_LAYER_NAME = "topology";
    final String TRANSITION_RELATION_NAME = "transition";
    final String INTERLAYER_CORRESPONDENCE = "association";
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
                processAreasLayer();
                processSubAreasLayer();
                processZonesLayer();
                processTopologyLayer();
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
        Collection<OsmPrimitive> topologyPrimitives = getSortedOsmPrimitives(TOPOLOGY_LAYER_NAME);
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

    private Collection<Way> getClosedWays(String layer) {
        Collection<OsmPrimitive> primitives = getSortedOsmPrimitives(layer);
        Collection<Way> ways = new TreeSet<Way>();
        for (OsmPrimitive p : primitives) {
            if (p instanceof Way && ((Way)p).isClosed())
            {
                ways.add((Way)p);
            }
        }
        return ways;
    }

    private Collection<IPrimitive> getClosedWaysAsIPrimitives(String layer) {
        Collection<OsmPrimitive> primitives = getSortedOsmPrimitives(layer);
        Collection<IPrimitive> ways = new TreeSet<IPrimitive>();
        for (OsmPrimitive p : primitives) {
            if (p instanceof Way && ((Way)p).isClosed())
            {
                ways.add((IPrimitive)p);
            }
        }
        return ways;
    }

    private boolean areaContainsNode(Way area, Node node) {
        return Geometry.nodeInsidePolygon(node, area.getNodes());
    }


    private void processSubAreasLayer()
    {
        Logging.info("Generating inter-layer correspondences for SubAreas layer...");
        Collection<Way> subareas = getClosedWays(SUBAREAS_LAYER_NAME);

        // Establish connections with enclosing area
        Collection<Way> areas = getClosedWays(AREAS_LAYER_NAME);
        Collection<IPrimitive> subareaPrimitives = getClosedWaysAsIPrimitives(SUBAREAS_LAYER_NAME);
        for (Way area : areas) {
            List<IPrimitive> enclosedSubAreas = Geometry.filterInsideAnyPolygon(subareaPrimitives, area);
            if(enclosedSubAreas.size() > 0)
            {
                for (IPrimitive s : enclosedSubAreas) {
                    // TODO: check if subarea is overlapping with multiple areas and raise errors/warnings
                    Relation correspondence = establishCorrespondence((OsmPrimitive)s, area, 
                                                                      SUBAREAS_LAYER_NAME, 
                                                                      INTERLAYER_CORRESPONDENCE, 
                                                                      "parent", AREAS_LAYER_NAME);
                }
            }
        }

        // Establish connections with overlapping polygons from other layers
        List<String> layers = Arrays.asList(ZONES_LAYER_NAME);
        for (String layer : layers) {
            Collection<Way> polygons = getClosedWays(layer);
            for (Way subarea : subareas) {
                for (Way p : polygons) {
                    Geometry.PolygonIntersection result = Geometry.polygonIntersection(subarea.getNodes(), p.getNodes());
                    if (result != Geometry.PolygonIntersection.OUTSIDE) {
                        Relation correspondence = establishCorrespondence(subarea, p, 
                                                            SUBAREAS_LAYER_NAME, 
                                                            INTERLAYER_CORRESPONDENCE, 
                                                            "parent", layer);
                    }
                }
            }
        }

        // Establish connections with overlapping nodes from topology layer
        for (Node node : getTopologicalNodes()) {
            for (Way subarea : subareas) {
                if (areaContainsNode(subarea, node))
                {
                    Relation correspondence = establishCorrespondence(subarea, node,
                                                SUBAREAS_LAYER_NAME, 
                                                INTERLAYER_CORRESPONDENCE, 
                                                "parent", TOPOLOGY_LAYER_NAME);
                    break; // Since subareas are not allowed to overlap, a node can only belong to one subarea
                }
            }
        }

        Logging.info("Generated inter-layer correspondences in SubAreas layer.");
    }

    private void processZonesLayer()
    {
        Logging.info("Generating inter-layer correspondences for Zones layer...");
        Collection<Way> zones = getClosedWays(ZONES_LAYER_NAME);

        // Establish connections with overlapping polygons from other layers
        List<String> layers = Arrays.asList(AREAS_LAYER_NAME, SUBAREAS_LAYER_NAME);
        for (String layer : layers) {
            Collection<Way> polygons = getClosedWays(layer);
            for (Way zone : zones) {
                for (Way p : polygons) {
                    Geometry.PolygonIntersection result = Geometry.polygonIntersection(zone.getNodes(), p.getNodes());
                    if (result != Geometry.PolygonIntersection.OUTSIDE) {
                        Relation correspondence = establishCorrespondence(zone, p, 
                                                            ZONES_LAYER_NAME, 
                                                            INTERLAYER_CORRESPONDENCE, 
                                                            "parent", layer);
                    }
                }
            }
        }

        // Establish connections with overlapping nodes from topology layer
        for (Node node : getTopologicalNodes()) {
            for (Way zone : zones) {
                if (areaContainsNode(zone, node))
                {
                    Relation correspondence = establishCorrespondence(zone, node,
                                                ZONES_LAYER_NAME, 
                                                INTERLAYER_CORRESPONDENCE, 
                                                "parent", TOPOLOGY_LAYER_NAME);
                }
            }
        }
        Logging.info("Generated  inter-layer correspondences in Zones layer.");
    }

    private void processTopologyLayer()
    {
        Logging.info("Generating inter-layer correspondences for Topology layer...");
        for (Node node : getTopologicalNodes()) {
            // Establish correspondences with overlapping polygons
            List<String> layers = Arrays.asList(AREAS_LAYER_NAME, SUBAREAS_LAYER_NAME, ZONES_LAYER_NAME);
            for (String layer : layers) {
                for (Way polygon : getClosedWays(layer)) {
                    if (areaContainsNode(polygon, node))
                    {
                        Relation correspondence = establishCorrespondence(node, polygon, 
                                                    TOPOLOGY_LAYER_NAME, 
                                                    INTERLAYER_CORRESPONDENCE, 
                                                    "parent", layer);
                        break;
                    }
                }
            }
        }
        Logging.info("Generated inter-layer correspondences in Topology layer.");
    }

    private Relation establishCorrespondence(OsmPrimitive parent, OsmPrimitive child, String layer, String type, String parentRole, String childRole) {
        DataSet ds = getDataset();
        if (ds == null)
            return null;

        // Find if a relation already exists
        Collection<OsmPrimitive> primitives = getSortedOsmPrimitives(layer);
        Relation relation = null;
        for (OsmPrimitive p : primitives) {
            TagMap keys = p.getKeys();
            if (p instanceof Relation && !keys.isEmpty() &&
                keys.containsKey("type") && keys.get("type").equals(type) &&
                ((Relation)p).firstMember().getMember().equals(parent))
            {
                relation = (Relation)p;
            }
        }

        // No relation exists for this correspondence, create a new one
        if (relation == null) {
            //Logging.info("Creating a new correspondence..");
            relation = new Relation();

            TagMap keys = new TagMap();
            keys.put("layer", layer);
            keys.put("type", type);
            relation.setKeys(keys);
            relation.addMember(new RelationMember(parentRole, parent));
            relation.addMember(new RelationMember(childRole, child));
            AddNewPrimitiveToDataset(relation);
        }
        // Found a relation for ths correspondence, update it
        else {
            //Logging.info("Updating existing correspondence..");
            RelationMember newMember = new RelationMember(childRole, child);
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
                UpdatePrimitiveInDataset(relation);
            }
        }

        return relation;
    }

    private void AddNewPrimitiveToDataset(OsmPrimitive primitive) {
        DataSet ds = getDataset();
        ds.beginUpdate();
        try {
            ds.addPrimitive(primitive);
        } 
        finally { 
            ds.endUpdate(); 
        }
    }

    private void UpdatePrimitiveInDataset(OsmPrimitive primitive) {
        DataSet ds = getDataset();
        ds.beginUpdate();
        try { 
            ds.removePrimitive(primitive);
            ds.addPrimitive(primitive);
        } 
        finally { 
            ds.endUpdate(); 
        }
    }
}
