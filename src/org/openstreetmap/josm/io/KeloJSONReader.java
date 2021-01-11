// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.io;

import static org.openstreetmap.josm.tools.I18n.tr;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonNumber;
import javax.json.JsonObject;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParser.Event;
import javax.json.stream.JsonParsingException;

import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.BBox;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.UploadPolicy;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.data.validation.TestError;
import org.openstreetmap.josm.data.validation.tests.DuplicateWay;
import org.openstreetmap.josm.gui.progress.NullProgressMonitor;
import org.openstreetmap.josm.gui.progress.ProgressMonitor;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Utils;

/**
 * Reader that reads KeloJSON files. 
 */
public class KeloJSONReader extends GeoJSONReader {

    private static final String NODEIDS = "nodeIds";
    private static final String MEMBERS = "members";
    private static final String RELATION = "relation";
    private static final int VERSION = 1;
    private List<JsonObject> relationsCache = new ArrayList<JsonObject>();

    private class NodeData {
        public long nodeId;
        public LatLon latLon;

        NodeData(LatLon ll, long id)
        {
            this.latLon = ll;
            this.nodeId = id;
        }
    }

    KeloJSONReader() {
        super();
        this.projection = Projections.getProjectionByCode("EPSG:3857"); // Mercator
    }

    @Override
    protected void parseFeatureCollection(final JsonArray features) {
        super.parseFeatureCollection(features);
        parseRelationCache();
    }

    private void parseRelationCache() {
        Logging.info("Parsing " + relationsCache.size() + " relations");
        for (JsonObject feature : relationsCache) {
            parseRelation(feature, feature.get(RELATION).asJsonObject());
        }
    }

    @Override
    protected void parseFeature(final JsonObject feature) {
        JsonValue geometry = feature.get(GEOMETRY);
        JsonValue relation = feature.get(RELATION);
        if (geometry != null && geometry.getValueType() == JsonValue.ValueType.OBJECT) {
            parseGeometry(feature, geometry.asJsonObject());
        } else if (relation != null && relation.getValueType() == JsonValue.ValueType.OBJECT){
            relationsCache.add(feature);
        } else {
            JsonValue properties = feature.get(PROPERTIES);
            if (properties != null && properties.getValueType() == JsonValue.ValueType.OBJECT) {
                parseNonGeometryFeature(feature, properties.asJsonObject());
            } else {
                Logging.warn(tr("Relation/non-geometry feature without properties found: {0}", feature));
            }
        }
    }

    private void parseRelation(final JsonObject feature, final JsonObject relation) {
        if (relation != null) {
            long id = ((JsonNumber)feature.get("id")).longValue();
            final Relation rel = new Relation();
            rel.setOsmId(id, VERSION, true);
            List<RelationMember> members = new ArrayList<RelationMember>();
            JsonArray relMembers = relation.getJsonArray(MEMBERS);
            for (JsonValue m : relMembers)
            {
                String type = ((JsonString)m.asJsonObject().get(TYPE)).getString();
                String role = ((JsonString)m.asJsonObject().get("role")).getString();
                long osmId = ((JsonNumber)m.asJsonObject().get("id")).longValue();

                OsmPrimitive prim = null;
                if (type.equals("Node"))
                    prim = new Node();
                else if (type.equals("Way"))
                    prim = new Way();
                else if (type.equals("Relation"))
                    prim = new Relation();
                else
                    Logging.error("Unknown member type!: " + type);

                if (prim != null)
                {
                    prim.setOsmId(osmId, VERSION, true); // Dummy primitive to create a PrimitiveID
                    OsmPrimitive primitive = getDataSet().getPrimitiveById(prim); // True primitive from dataset
                    if (primitive != null)
                        members.add(new RelationMember(role, primitive));
                }
            }

            rel.setMembers(members);
            fillTagsFromFeature(feature, rel);
            getDataSet().addPrimitive(rel);
        }
    }

    protected List<Long> getNodeIdList(final JsonObject feature)
    {
        JsonArray nodeIds = feature.get(GEOMETRY).asJsonObject().getJsonArray(NODEIDS).getJsonArray(0);
        List<Long> ids = new ArrayList<Long>();
        if (nodeIds != null) { 
            for (int i = 0; i < nodeIds.size(); i++){ 
                ids.add(((JsonNumber)nodeIds.get(i)).longValue());
            } 
        }
        return ids;
    }

    @Override
    protected void parsePoint(final JsonObject feature, final JsonArray coordinates) {
        long id = ((JsonNumber)feature.get("id")).longValue();
        fillTagsFromFeature(feature, createNode(new NodeData(getLatLon(coordinates), id)));
    }

    @Override
    protected void parseLineString(final JsonObject feature, final JsonArray coordinates) {
        if (!coordinates.isEmpty()) {
            long wayId = ((JsonNumber)feature.get("id")).longValue();
            createWay(coordinates, wayId, getNodeIdList(feature), false)
                .ifPresent(way -> fillTagsFromFeature(feature, way));
        }
    }

    @Override
    protected void parsePolygon(final JsonObject feature, final JsonArray coordinates) {
        final int size = coordinates.size();
        long wayId = ((JsonNumber)feature.get("id")).longValue();
        if (size == 1) {
            createWay(coordinates.getJsonArray(0), wayId, getNodeIdList(feature), true)
                .ifPresent(way -> fillTagsFromFeature(feature, way));
        } else if (size > 1) {
            // create multipolygon
            final Relation multipolygon = new Relation();
            createWay(coordinates.getJsonArray(0), wayId, getNodeIdList(feature), true)
                .ifPresent(way -> multipolygon.addMember(new RelationMember("outer", way)));

            for (JsonValue interiorRing : coordinates.subList(1, size)) {
                createWay(interiorRing.asJsonArray(), wayId, getNodeIdList(feature), true)
                    .ifPresent(way -> multipolygon.addMember(new RelationMember("inner", way)));
            }

            fillTagsFromFeature(feature, multipolygon);
            multipolygon.put(TYPE, "multipolygon");
            getDataSet().addPrimitive(multipolygon);
        }
    }

    protected Node createNode(NodeData nodeData) {
        final List<Node> existingNodes = getDataSet().searchNodes(new BBox(nodeData.latLon, nodeData.latLon));
        if (!existingNodes.isEmpty()) {
            // reuse existing node, avoid multiple nodes on top of each other
            return existingNodes.get(0);
        }
        final Node node = new Node(nodeData.latLon);
        node.setOsmId(nodeData.nodeId, VERSION, true);
        getDataSet().addPrimitive(node);
        return node;
    }

    protected Optional<Way> createWay(final JsonArray coordinates, long wayId, final List<Long> nodeIds, final boolean autoClose) {
        if (coordinates.isEmpty()) {
            return Optional.empty();
        }

        final List<LatLon> latlons = coordinates.stream()
                .map(coordinate -> getLatLon(coordinate.asJsonArray()))
                .collect(Collectors.toList());

        List<NodeData> nodeDataList = new ArrayList<NodeData>();
        for (int i = 0; i < latlons.size(); i++)
        {
            nodeDataList.add(new NodeData(latlons.get(i), nodeIds.get(i)));
        }

        final int size = latlons.size();
        final boolean doAutoclose;
        if (size > 1) {
            if (latlons.get(0).equals(latlons.get(size - 1))) {
                doAutoclose = false; // already closed
            } else {
                doAutoclose = autoClose;
            }
        } else {
            doAutoclose = false;
        }

        final Way way = new Way();
        way.setOsmId(wayId, VERSION, true);
        getDataSet().addPrimitive(way);
        final List<Node> rawNodes = nodeDataList.stream().map(this::createNode).collect(Collectors.toList());
        if (doAutoclose) {
            rawNodes.add(rawNodes.get(0));
        }
        // see #19833: remove duplicated references to the same node
        final List<Node> wayNodes = new ArrayList<>(rawNodes.size());
        Node last = null;
        for (Node curr : rawNodes) {
            if (last != curr)
                wayNodes.add(curr);
            last = curr;
        }
        way.setNodes(wayNodes);

        return Optional.of(way);
    }

    /**
     * Parse the given input source and return the dataset.
     *
     * @param source          the source input stream. Must not be null.
     * @param progressMonitor the progress monitor. If null, {@link NullProgressMonitor#INSTANCE} is assumed
     * @return the dataset with the parsed data
     * @throws IllegalDataException     if an error was found while parsing the data from the source
     * @throws IllegalArgumentException if source is null
     */
    public static DataSet parseDataSet(InputStream source, ProgressMonitor progressMonitor) throws IllegalDataException {
        return new KeloJSONReader().doParseDataSet(source, progressMonitor);
    }
}
