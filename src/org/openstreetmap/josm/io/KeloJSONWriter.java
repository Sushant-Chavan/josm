// License: GPL. For details, see LICENSE file.
package org.openstreetmap.josm.io;

import java.util.Collection;
import java.util.Map.Entry;

import javax.json.Json;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;

import org.openstreetmap.josm.data.Bounds;
import org.openstreetmap.josm.data.coor.EastNorth;
import org.openstreetmap.josm.data.coor.LatLon;
import org.openstreetmap.josm.data.osm.DataSet;
import org.openstreetmap.josm.data.osm.Node;
import org.openstreetmap.josm.data.osm.OsmPrimitive;
import org.openstreetmap.josm.data.osm.Relation;
import org.openstreetmap.josm.data.osm.RelationMember;
import org.openstreetmap.josm.data.osm.Way;
import org.openstreetmap.josm.data.osm.visitor.OsmPrimitiveVisitor;
import org.openstreetmap.josm.data.projection.Projections;
import org.openstreetmap.josm.io.GeoJSONWriter;
import org.openstreetmap.josm.tools.Logging;

/**
 * Writes OSM data as a customized GeoJSON string, using JSR 353: Java API for JSON Processing (JSON-P).
 * <p>
 * See <a href="https://tools.ietf.org/html/rfc7946">RFC7946: The GeoJSON Format</a>
 */
public class KeloJSONWriter extends GeoJSONWriter{

    private EastNorth origin;

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
     * Writes OSM data as a customized GeoJSON string (prettified or not).
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
}
