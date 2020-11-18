// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.io;

import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonValue;
import javax.json.JsonWriter;
import javax.json.stream.JsonGenerator;
import javax.json.stream.JsonParser;
import javax.json.stream.JsonParsingException;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.MultipolygonBuilder;
import org.openstreetmap.josm.data.osm.MultipolygonBuilder.JoinedPolygon;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.OsmPrimitiveVisitor;
import org.openstreetmap.josm.data.preferences.BooleanProperty;
import org.openstreetmap.josm.data.projection.Projection;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.gui.mappaint.ElemStyles;
import org.openstreetmap.josm.io.GeoJSONWriter;
import org.openstreetmap.josm.tools.Logging;
import org.openstreetmap.josm.tools.Pair;

/**
 * Writes OSM data as a customized GeoJSON string, using JSR 353: Java API for JSON Processing (JSON-P).
 * <p>
 * See <a href="https://tools.ietf.org/html/rfc7946">RFC7946: The GeoJSON Format</a>
 */
public class KeloJSONWriter extends GeoJSONWriter{

    // private final DataSet data;
    // private final Projection projection;
    private EastNorth origin;
    // private static final BooleanProperty SKIP_EMPTY_NODES = new BooleanProperty("geojson.export.skip-empty-nodes", true);
    // private static final BooleanProperty UNTAGGED_CLOSED_IS_POLYGON = new BooleanProperty("geojson.export.untagged-closed-is-polygon", false);
    // private static final Set<Way> processedMultipolygonWays = new HashSet<>();

    // /**
    //  * This is used to determine that a tag should be interpreted as a json
    //  * object or array. The tag should have both {@link #JSON_VALUE_START_MARKER}
    //  * and {@link #JSON_VALUE_END_MARKER}.
    //  */
    // static final String JSON_VALUE_START_MARKER = "{";
    // /**
    //  * This is used to determine that a tag should be interpreted as a json
    //  * object or array. The tag should have both {@link #JSON_VALUE_START_MARKER}
    //  * and {@link #JSON_VALUE_END_MARKER}.
    //  */
    // static final String JSON_VALUE_END_MARKER = "}";

    /**
     * Constructs a new {@code KeloJSONWriter}.
     * @param ds The OSM data set to save
     * @since 12806
     */
    public KeloJSONWriter(DataSet ds) {
        super(ds);
        this.projection = Projections.getProjectionByCode("EPSG:3857"); // Mercator
        this.origin = EastNorth.ZERO;
    }

    /**
     * Writes OSM data as a GeoJSON string (prettified).
     * @return The GeoJSON data
     */
    // public String write() {
    //     return write(true);
    // }

    /**
     * Writes OSM data as a GeoJSON string (prettified or not).
     * @param pretty {@code true} to have pretty output, {@code false} otherwise
     * @return The GeoJSON data
     * @since 6756
     */
    @Override
    public String write(boolean pretty) {
        findOrigin();
        return super.write(pretty);
    }

    private void findOrigin() {
        Collection<OsmPrimitive> primitives = data.allNonDeletedPrimitives();
        for (OsmPrimitive p : primitives) {
            if (p instanceof Node){
                for (Entry<String, String> t : p.getKeys().entrySet()) {
                    if (t.getKey().equals("name") && t.getValue().equals("origin")){
                        origin = projection.latlon2eastNorth(((Node)p).getCoor());
                        Logging.info("Setting custom origin at: " + origin.east() + "," + origin.north());
                        return;
                    }
                }
            }
        }
    }

    // private class GeometryPrimitiveVisitor implements OsmPrimitiveVisitor {

    //     private final JsonObjectBuilder geomObj;

    //     GeometryPrimitiveVisitor(JsonObjectBuilder geomObj) {
    //         this.geomObj = geomObj;
    //     }

    //     @Override
    //     public void visit(Node n) {
    //         geomObj.add("type", "Point");
    //         LatLon ll = n.getCoor();
    //         if (ll != null) {
    //             geomObj.add("coordinates", getCoorArray(null, n.getCoor()));
    //         }
    //     }

    //     @Override
    //     public void visit(Way w) {
    //         if (w != null) {
    //             if (!w.isTagged() && processedMultipolygonWays.contains(w)) {
    //                 // no need to write this object again
    //                 return;
    //             }
    //             final JsonArrayBuilder array = getCoorsArray(w.getNodes());
    //             boolean writeAsPolygon = w.isClosed() && ((!w.isTagged() && UNTAGGED_CLOSED_IS_POLYGON.get())
    //                     || ElemStyles.hasAreaElemStyle(w, false));
    //             if (writeAsPolygon) {
    //                 geomObj.add("type", "Polygon");
    //                 geomObj.add("coordinates", Json.createArrayBuilder().add(array));
    //             } else {
    //                 geomObj.add("type", "LineString");
    //                 geomObj.add("coordinates", array);
    //             }
    //         }
    //     }

    //     @Override
    //     public void visit(Relation r) {
    //         if (r == null || !r.isMultipolygon() || r.hasIncompleteMembers()) {
    //             return;
    //         }
    //         try {
    //             final Pair<List<JoinedPolygon>, List<JoinedPolygon>> mp = MultipolygonBuilder.joinWays(r);
    //             final JsonArrayBuilder polygon = Json.createArrayBuilder();
    //             Stream.concat(mp.a.stream(), mp.b.stream())
    //                     .map(p -> getCoorsArray(p.getNodes())
    //                             // since first node is not duplicated as last node
    //                             .add(getCoorArray(null, p.getNodes().get(0).getCoor())))
    //                     .forEach(polygon::add);
    //             geomObj.add("type", "MultiPolygon");
    //             final JsonArrayBuilder multiPolygon = Json.createArrayBuilder().add(polygon);
    //             geomObj.add("coordinates", multiPolygon);
    //             processedMultipolygonWays.addAll(r.getMemberPrimitives(Way.class));
    //         } catch (MultipolygonBuilder.JoinedPolygonCreationException ex) {
    //             Logging.warn("GeoJSON: Failed to export multipolygon {0}", r.getUniqueId());
    //             Logging.warn(ex);
    //         }
    //     }

    //     private JsonArrayBuilder getCoorsArray(Iterable<Node> nodes) {
    //         final JsonArrayBuilder builder = Json.createArrayBuilder();
    //         for (Node n : nodes) {
    //             LatLon ll = n.getCoor();
    //             if (ll != null) {
    //                 builder.add(getCoorArray(null, ll));
    //             }
    //         }
    //         return builder;
    //     }
    // }

    private class RelationPrimitiveVisitor implements OsmPrimitiveVisitor {

        private final JsonObjectBuilder relObj;

        RelationPrimitiveVisitor(JsonObjectBuilder relObj) {
            this.relObj = relObj;
        }

        @Override
        public void visit(Node n) {
        }

        @Override
        public void visit(Way w) {
        }

        @Override
        public void visit(Relation r) {
            if (r == null) {
                return;
            }
            relObj.add("members", getMembersArray(r.getMembers()));
        }

        private JsonArrayBuilder getMembersArray(Iterable<RelationMember> members) {
            final JsonArrayBuilder builder = Json.createArrayBuilder();
            for (RelationMember rm : members) {
                final JsonObjectBuilder memberObj = Json.createObjectBuilder();
                long id = rm.getMember().getUniqueId();
                String role = rm.getRole();
                String type = rm.isNode() ? "Node" : rm.isWay() ? "Way" : rm.isRelation() ? "Relation" : "Unknown";
                memberObj.add("id", Long.toString(id));
                memberObj.add("type", type);
                memberObj.add("role", role);

                builder.add(memberObj);
            }
            return builder;
        }
    }

    @Override
    protected JsonArrayBuilder getCoorArray(JsonArrayBuilder builder, LatLon c) {
        return GeoJSONWriter.getCoorArray(builder, projection.latlon2eastNorth(c).subtract(origin));
    }

    // private static JsonArrayBuilder getCoorArray(JsonArrayBuilder builder, EastNorth c) {
    //     return (builder != null ? builder : Json.createArrayBuilder())
    //             .add(BigDecimal.valueOf(c.getX()).setScale(11, RoundingMode.HALF_UP))
    //             .add(BigDecimal.valueOf(c.getY()).setScale(11, RoundingMode.HALF_UP));
    // }

    @Override
    protected void appendPrimitive(OsmPrimitive p, JsonArrayBuilder array) {
        if (p.isIncomplete() ||
            (SKIP_EMPTY_NODES.get() && p instanceof Node && p.getKeys().isEmpty())) {
            return;
        }

        // Properties
        final JsonObjectBuilder propObj = Json.createObjectBuilder();
        for (Entry<String, String> t : p.getKeys().entrySet()) {
            String key = t.getKey();
            if(!key.equals("x") && !key.equals("y"))
                propObj.add(t.getKey(), GeoJSONWriter.convertValueToJson(t.getValue()));
        }
        final JsonObject prop = propObj.build();

        // Relation
        final JsonObjectBuilder relationObj = Json.createObjectBuilder();
        p.accept(new RelationPrimitiveVisitor(relationObj));
        final JsonObject rel = relationObj.build();

        // Geometry
        final JsonObjectBuilder geomObj = Json.createObjectBuilder();
        p.accept(new GeometryPrimitiveVisitor(geomObj));
        final JsonObject geom = geomObj.build();

        // Build primitive JSON object
        final JsonObjectBuilder primitiveObj = Json.createObjectBuilder();
        primitiveObj.add("type", "Feature");
        primitiveObj.add("id", Long.toString(p.getUniqueId()));
        if (!prop.isEmpty())
            primitiveObj.add("properties", prop);
        if (!geom.isEmpty())
            primitiveObj.add("geometry", geom);
        if (!rel.isEmpty())
            primitiveObj.add("relation", rel);
        array.add(primitiveObj);
    }

    // private static JsonValue convertValueToJson(String value) {
    //     if (value.startsWith(JSON_VALUE_START_MARKER) && value.endsWith(JSON_VALUE_END_MARKER)) {
    //         try (JsonParser parser = Json.createParser(new StringReader(value))) {
    //             if (parser.hasNext() && parser.next() != null) {
    //                 return parser.getValue();
    //             }
    //         } catch (JsonParsingException e) {
    //             Logging.warn(e);
    //         }
    //     }
    //     return Json.createValue(value);
    // }

    // protected void appendLayerBounds(DataSet ds, JsonObjectBuilder object) {
    //     if (ds != null) {
    //         Iterator<Bounds> it = ds.getDataSourceBounds().iterator();
    //         if (it.hasNext()) {
    //             Bounds b = new Bounds(it.next());
    //             while (it.hasNext()) {
    //                 b.extend(it.next());
    //             }
    //             appendBounds(b, object);
    //         }
    //     }
    // }

    // protected void appendBounds(Bounds b, JsonObjectBuilder object) {
    //     if (b != null) {
    //         JsonArrayBuilder builder = Json.createArrayBuilder();
    //         getCoorArray(builder, b.getMin());
    //         getCoorArray(builder, b.getMax());
    //         object.add("bbox", builder);
    //     }
    // }

    // protected void appendLayerFeatures(DataSet ds, JsonObjectBuilder object) {
    //     JsonArrayBuilder array = Json.createArrayBuilder();
    //     if (ds != null) {
    //         processedMultipolygonWays.clear();
    //         Collection<OsmPrimitive> primitives = ds.allNonDeletedPrimitives();
    //         // Relations first
    //         for (OsmPrimitive p : primitives) {
    //             if (p instanceof Relation)
    //                 appendPrimitive(p, array);
    //         }
    //         for (OsmPrimitive p : primitives) {
    //             if (!(p instanceof Relation))
    //                 appendPrimitive(p, array);
    //         }
    //         processedMultipolygonWays.clear();
    //     }
    //     object.add("features", array);
    // }
}
